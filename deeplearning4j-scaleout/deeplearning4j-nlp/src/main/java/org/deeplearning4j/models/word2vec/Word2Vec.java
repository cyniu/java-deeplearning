package org.deeplearning4j.models.word2vec;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.util.concurrent.AtomicDouble;

import it.unimi.dsi.util.XorShift1024StarRandomGenerator;
import org.apache.commons.math3.random.RandomGenerator;
import org.deeplearning4j.bagofwords.vectorizer.TextVectorizer;
import org.deeplearning4j.bagofwords.vectorizer.TfidfVectorizer;
import org.deeplearning4j.berkeley.Counter;
import org.deeplearning4j.models.word2vec.wordstore.inmemory.InMemoryLookupCache;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.ops.transforms.Transforms;
import org.deeplearning4j.nn.api.Persistable;
import org.deeplearning4j.text.documentiterator.DocumentIterator;
import org.deeplearning4j.text.stopwords.StopWords;
import org.deeplearning4j.text.tokenization.tokenizerfactory.UimaTokenizerFactory;
import org.deeplearning4j.util.MathUtils;
import org.deeplearning4j.text.sentenceiterator.SentenceIterator;
import org.deeplearning4j.text.tokenization.tokenizerfactory.DefaultTokenizerFactory;
import org.deeplearning4j.text.tokenization.tokenizer.Tokenizer;
import org.deeplearning4j.text.tokenization.tokenizerfactory.TokenizerFactory;
import org.deeplearning4j.models.word2vec.wordstore.VocabCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



/**
 * Leveraging a 3 layer neural net with a softmax approach as output,
 * converts a word based on its context and the training examples in to a
 * numeric vector
 * @author Adam Gibson
 *
 */
public class Word2Vec implements Persistable {


    private static final long serialVersionUID = -2367495638286018038L;

    private transient TokenizerFactory tokenizerFactory = new DefaultTokenizerFactory();
    private transient SentenceIterator sentenceIter;
    private transient DocumentIterator docIter;
    private transient VocabCache cache;

    private int topNSize = 40;
    private int sample = 1;
    //learning rate
    private AtomicDouble alpha = new AtomicDouble(0.025);
    //number of times the word must occur in the vocab to appear in the calculations, otherwise treat as unknown
    private int minWordFrequency = 5;
    //context to use for gathering word frequencies
    private int window = 5;
    //number of neurons per layer
    private int layerSize = 50;
    private transient  RandomGenerator g;
    private static Logger log = LoggerFactory.getLogger(Word2Vec.class);
    private AtomicInteger numSentencesProcessed = new AtomicInteger(0);
    private List<String> stopWords;
    private boolean shouldReset = true;
    //number of iterations to run
    private int numIterations = 1;
    public final static String UNK = "UNK";
    private long seed = 123;
    private boolean saveVocab = false;
    private double minLearningRate = 0.01;
    private AtomicInteger numWordsSoFar = new AtomicInteger(0);
    private TextVectorizer vectorizer;


    public Word2Vec() {}


    /**
     * Find all words with a similar characters
     * in the vocab
     * @param word the word to compare
     * @param accuracy the accuracy: 0 to 1
     * @return the list of words that are similar in the vocab
     */
    public List<String> similarWordsInVocabTo(String word,double accuracy) {
        List<String> ret = new ArrayList<>();
        for(String s : cache.words()) {
            if(MathUtils.stringSimilarity(word,s) >= accuracy)
                ret.add(s);
        }
        return ret;
    }




    public int indexOf(String word) {
        return cache.indexOf(word);
    }


    /**
     * Get the word vector for a given matrix
     * @param word the word to get the matrix for
     * @return the ndarray for this word
     */
    public double[] getWordVector(String word) {
        int i = this.cache.indexOf(word);
        if(i < 0)
            return cache.vector(UNK).ravel().data().asDouble();
        return cache.vector(word).ravel().data().asDouble();
    }

    /**
     * Get the word vector for a given matrix
     * @param word the word to get the matrix for
     * @return the ndarray for this word
     */
    public INDArray getWordVectorMatrix(String word) {
        int i = this.cache.indexOf(word);
        if(i < 0)
            return cache.vector(UNK);
        return cache.vector(word);
    }

    /**
     * Returns the word vector divided by the norm2 of the array
     * @param word the word to get the matrix for
     * @return the looked up matrix
     */
    public INDArray getWordVectorMatrixNormalized(String word) {
        int i = this.cache.indexOf(word);

        if(i < 0)
            return cache.vector(UNK);
        INDArray r =  cache.vector(word);
        return r.div(Nd4j.getBlasWrapper().nrm2(r));
    }


    /**
     * Get the top n words most similar to the given word
     * @param word the word to compare
     * @param n the n to get
     * @return the top n words
     */
    public Collection<String> wordsNearest(String word,int n) {
        INDArray vec = Transforms.unitVec(this.getWordVectorMatrix(word));


       if(cache instanceof  InMemoryLookupCache) {
           InMemoryLookupCache l = (InMemoryLookupCache) cache;
           INDArray syn0 = l.getSyn0();
           INDArray weights = syn0.norm2(0).rdivi(1).muli(vec);
           INDArray distances = syn0.mulRowVector(weights).sum(1);
           INDArray[] sorted = Nd4j.sortWithIndices(distances,0,false);
           INDArray sort = sorted[0];
           List<String> ret = new ArrayList<>();
           if(n > sort.length())
               n = sort.length();

           for(int i = 0; i < n; i++)
               ret.add(cache.wordAtIndex(sort.getInt(i)));



           return ret;
       }

        if(vec == null)
            return new ArrayList<>();
        Counter<String> distances = new Counter<>();

        for(String s : cache.words()) {
            if(s.equals(word))
                continue;
            INDArray otherVec = getWordVectorMatrix(s);
            double sim = Transforms.cosineSim(vec,otherVec);
            distances.incrementCount(s, sim);
        }


        distances.keepTopNKeys(n);
        return distances.keySet();

    }

    /**
     * Brings back a list of words that are analagous to the 3 words
     * presented in vector space
     * @param w1
     * @param w2
     * @param w3
     * @return a list of words that are an analogy for the given 3 words
     */
    public List<String> analogyWords(String w1,String w2,String w3) {
        TreeSet<VocabWord> analogies = analogy(w1, w2, w3);
        List<String> ret = new ArrayList<>();
        for(VocabWord w : analogies) {
            String w4 = cache.wordAtIndex(w.getIndex());
            ret.add(w4);
        }
        return ret;
    }




    private void insertTopN(String name, double score, List<VocabWord> wordsEntrys) {
        if (wordsEntrys.size() < topNSize) {
            VocabWord v = new VocabWord(score,name);
            v.setIndex(cache.indexOf(name));
            wordsEntrys.add(v);
            return;
        }
        double min = Double.MAX_VALUE;
        int minOffe = 0;
        int minIndex = -1;
        for (int i = 0; i < topNSize; i++) {
            VocabWord wordEntry = wordsEntrys.get(i);
            if (min > wordEntry.getWordFrequency()) {
                min =  wordEntry.getWordFrequency();
                minOffe = i;
                minIndex = wordEntry.getIndex();
            }
        }

        if (score > min) {
            VocabWord w = new VocabWord(score, VocabWord.PARENT_NODE);
            w.setIndex(minIndex);
            wordsEntrys.set(minOffe,w);
        }

    }

    /**
     * Returns true if the model has this word in the vocab
     * @param word the word to test for
     * @return true if the model has the word in the vocab
     */
    public boolean hasWord(String word) {
        return cache.indexOf(word) >= 0;
    }

    /**
     * Train the model
     */
    public void fit(){
        boolean loaded =  buildVocab();
        //save vocab after building
        if(!loaded && saveVocab)
            cache.saveVocab();
        if(stopWords == null)
            readStopWords();


        log.info("Training word2vec multithreaded");

        if(sentenceIter != null)
            sentenceIter.reset();
        if(docIter != null)
            docIter.reset();


        final AtomicLong latch = new AtomicLong(0);
        //final List<List<VocabWord>> docs = new CopyOnWriteArrayList<>();
        //for(int i = 0; i < vectorizer.index().numDocuments(); i++)
         //   docs.add(vectorizer.index().document(i));

        ExecutorService service = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);
        log.info("Processing sentences...");
        for(int i = 0; i < numIterations; i++) {
            log.info("Iteration " + (i + 1));
            int numDocs = vectorizer.index().numDocuments();
            log.info("Training on " + numDocs);
            for(int j = 0; j < numDocs;j++) {
                final int k = j;
                service.execute(new Runnable() {

                    /**
                     * When an object implementing interface <code>Runnable</code> is used
                     * to create a thread, starting the thread causes the object's
                     * <code>run</code> method to be called in that separately executing
                     * thread.
                     * <p/>
                     * The general contract of the method <code>run</code> is that it may
                     * take any action whatsoever.
                     *
                     * @see Thread#run()
                     */
                    @Override
                    public void run() {
                        trainSentence(vectorizer.index().document(k),k);
                    }
                });

                numSentencesProcessed.incrementAndGet();

                if(numSentencesProcessed.get() % 100 == 0)
                    log.info("Num sentences processed " + numSentencesProcessed.get());
            }

            //reset for the iteration
            numSentencesProcessed.set(0);
        }



        try {
            service.shutdown();
            service.awaitTermination(1, TimeUnit.DAYS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }


        while(latch.get() > 0) {
            log.info("Waiting on sentences...Num processed so far " + numSentencesProcessed.get() + " with latch count at " + latch.get());
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }



    }




    /**
     * Train on the given sentence returning a list of vocab words
     * @param is the sentence to fit on
     * @return
     */
    public List<VocabWord> trainSentence(InputStream is) {
        Tokenizer tokenizer = tokenizerFactory.create(is);
        List<VocabWord> sentence2 = new ArrayList<>();
        while(tokenizer.hasMoreTokens()) {
            String next = tokenizer.nextToken();
            if(stopWords.contains(next))
                next = UNK;
            VocabWord word = cache.wordFor(next);
            if(word == null)
                continue;


        }


        trainSentence(sentence2,0);
        return sentence2;
    }


    /**
     * Train on the given sentence returning a list of vocab words
     * @param sentence the sentence to fit on
     * @return
     */
    public List<VocabWord> trainSentence(String sentence) {
        if(sentence == null || sentence.isEmpty())
            return new ArrayList<>();

        Tokenizer tokenizer = tokenizerFactory.create(sentence);
        List<VocabWord> sentence2 = new ArrayList<>();

        while(tokenizer.hasMoreTokens()) {
            String next = tokenizer.nextToken();
            if(stopWords.contains(next))
                next = UNK;
            VocabWord word = cache.wordFor(next);
            if(word == null)
                continue;

            sentence2.add(word);

        }


        trainSentence(sentence2,0);
        return sentence2;
    }


    /**
     *
     *
     * @param word
     * @return
     */
    public Set<VocabWord> distance(String word) {
        INDArray wordVector = getWordVectorMatrix(word);
        if (wordVector == null) {
            return null;
        }

        INDArray tempVector;
        List<VocabWord> wordEntrys = new ArrayList<>(topNSize);
        for (String name : cache.words()) {
            if (name.equals(word)) {
                continue;
            }

            tempVector = cache.vector(name);
            insertTopN(name, Nd4j.getBlasWrapper().dot(wordVector,tempVector), wordEntrys);
        }
        return new TreeSet<>(wordEntrys);
    }

    /**
     *
     * @return
     */
    public TreeSet<VocabWord> analogy(String word0, String word1, String word2) {
        INDArray wv0 = getWordVectorMatrix(word0);
        INDArray wv1 = getWordVectorMatrix(word1);
        INDArray wv2 = getWordVectorMatrix(word2);


        INDArray wordVector = wv1.sub(wv0).add(wv2);

        if (wv1 == null || wv2 == null || wv0 == null)
            return null;

        INDArray tempVector;
        String name;
        List<VocabWord> wordEntrys = new ArrayList<>(topNSize);
        for (int i = 0; i < cache.numWords(); i++) {
            name = cache.wordAtIndex(i);

            if (name.equals(word0) || name.equals(word1) || name.equals(word2)) {
                continue;
            }


            tempVector = cache.vector(cache.wordAtIndex(i));
            double dist = Nd4j.getBlasWrapper().dot(wordVector,tempVector);
            insertTopN(name, dist, wordEntrys);
        }
        return new TreeSet<>(wordEntrys);
    }


    public void setup() {

        log.info("Building binary tree");
        buildBinaryTree();
        log.info("Resetting weights");
        if(shouldReset)
            resetWeights();

    }


    /**
     * Builds the vocabulary for training
     */
    public boolean buildVocab() {
        readStopWords();

        if(cache.vocabExists()) {
            log.info("Loading vocab...");
            cache.loadVocab();
            cache.resetWeights();
            return true;
        }

        //vectorizer will handle setting up vocab meta data
        vectorizer = new TfidfVectorizer.Builder()
                .cache(cache).iterate(docIter).iterate(sentenceIter)
                .minWords(minWordFrequency).stopWords(stopWords)
                .tokenize(tokenizerFactory).build();
        vectorizer.fit();

        setup();

        return false;
    }


    /**
     * Create a tsne plot
     */
    public void plotTsne() {
        cache.plotVocab();
    }


    /**
     * Train on a list of vocab words
     * @param sentence the list of vocab words to train on
     */
    public void trainSentence(final List<VocabWord> sentence,int doc) {
        if(g == null)
            g = new XorShift1024StarRandomGenerator(seed);
        if(sentence == null)
            return;
        numWordsSoFar.getAndAdd(sentence.size());
        if(doc % 1000 == 0) {
            alpha.set(Math.max(minLearningRate,alpha.get() * (1 - (1.0 * (double) doc / (double) vectorizer.index().numDocuments()))));
            log.info("Num words so far " + numWordsSoFar.get() + " alpha is " + alpha.get());
        }

        if(sentence.isEmpty())
            return;



        for(int i = 0; i < sentence.size(); i++)
            skipGram(i, sentence, (int) g.nextDouble() % window);
    }


    /**
     * Train via skip gram
     * @param i
     * @param sentence
     */
    public void skipGram(int i,List<VocabWord> sentence, int b) {
        final VocabWord word = sentence.get(i);
        if(word == null || sentence.isEmpty())
            return;

        int end =  window * 2 + 1 - b;

        for(int a = b; a < end; a++) {
            if(a != window) {
                int c = i - window + a;
                if(c >= 0 && c < sentence.size()) {
                    VocabWord lastWord = sentence.get(c);
                    int numTries = 0;
                    while(lastWord == null) {
                        lastWord = sentence.get(c);
                        numTries++;
                    }
                    if(numTries >= 3)
                        throw new IllegalStateException("Unable to get word from sentence");

                    iterate(word,lastWord);
                }
            }
        }

    }

    public Map<String,INDArray> toVocabFloat() {
        Map<String,INDArray> ret = new HashMap<>();
        for(int i = 0; i < cache.numWords(); i++) {
            String word = cache.wordAtIndex(i);
            ret.put(word,getWordVectorMatrix(word));
        }

        return ret;

    }





    /**
     * Train the word vector
     * on the given words
     * @param w1 the first word to fit
     */
    public void  iterate(VocabWord w1, VocabWord w2) {
        cache.iterate(w1,w2);
    }




    /* Builds the binary tree for the word relationships */
    private void buildBinaryTree() {
        log.info("Constructing priority queue");
        Huffman huffman = new Huffman(cache.vocabWords());
        huffman.build();

        log.info("Built tree");

    }



    /* reinit weights */
    private void resetWeights() {
        cache.resetWeights();
    }


    /**
     * Returns the similarity of 2 words
     * @param word the first word
     * @param word2 the second word
     * @return a normalized similarity (cosine similarity)
     */
    public double similarity(String word,String word2) {
        if(word.equals(word2))
            return 1.0;

        INDArray vector = Transforms.unitVec(getWordVectorMatrix(word));
        INDArray vector2 = Transforms.unitVec(getWordVectorMatrix(word2));
        if(vector == null || vector2 == null)
            return -1;
        return  Nd4j.getBlasWrapper().dot(vector,vector2);
    }




    @SuppressWarnings("unchecked")
    private void readStopWords() {
        if(this.stopWords != null)
            return;
        this.stopWords = StopWords.getStopWords();


    }




    @Override
    public void write(OutputStream os) {
        try {
            ObjectOutputStream dos = new ObjectOutputStream(os);

            dos.writeObject(this);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public void load(InputStream is) {
        try {
            ObjectInputStream ois = new ObjectInputStream(is);
            Word2Vec vec = (Word2Vec) ois.readObject();
            this.alpha = vec.alpha;
            this.minWordFrequency = vec.minWordFrequency;
            this.numSentencesProcessed = vec.numSentencesProcessed;
            this.sample = vec.sample;
            this.stopWords = vec.stopWords;
            this.topNSize = vec.topNSize;
            this.window = vec.window;

        }catch(Exception e) {
            throw new RuntimeException(e);
        }



    }




    public int getLayerSize() {
        return layerSize;
    }
    public void setLayerSize(int layerSize) {
        this.layerSize = layerSize;
    }

    public int getWindow() {
        return window;
    }






    public List<String> getStopWords() {
        return stopWords;
    }

    public  synchronized SentenceIterator getSentenceIter() {
        return sentenceIter;
    }



    public  TokenizerFactory getTokenizerFactory() {
        return tokenizerFactory;
    }




    public  void setTokenizerFactory(TokenizerFactory tokenizerFactory) {
        this.tokenizerFactory = tokenizerFactory;
    }

    public VocabCache getCache() {
        return cache;
    }

    public void setCache(VocabCache cache) {
        this.cache = cache;
    }

    /**
     * Note that calling a setter on this
     * means assumes that this is a training continuation
     * and therefore weights should not be reset.
     * @param sentenceIter
     */
    public void setSentenceIter(SentenceIterator sentenceIter) {
        this.sentenceIter = sentenceIter;
        this.shouldReset = false;
    }



    public static class Builder {
        private int minWordFrequency = 1;
        private int layerSize = 50;
        private SentenceIterator iter;
        private List<String> stopWords = StopWords.getStopWords();
        private int window = 5;
        private TokenizerFactory tokenizerFactory;
        private VocabCache vocabCache;
        private DocumentIterator docIter;
        private double lr = 2.5e-1;
        private int iterations = 5;
        private long seed = 123;
        private boolean saveVocab = false;

        public Builder saveVocab(boolean saveVocab){
            this.saveVocab = saveVocab;
            return this;
        }

        public Builder seed(long seed) {
            this.seed = seed;
            return this;
        }

        public Builder iterations(int iterations) {
            this.iterations = iterations;
            return this;
        }


        public Builder learningRate(double lr) {
            this.lr = lr;
            return this;
        }


        public Builder iterate(DocumentIterator iter) {
            this.docIter = iter;
            return this;
        }

        public Builder vocabCache(VocabCache cache) {
            this.vocabCache = cache;
            return this;
        }

        public Builder minWordFrequency(int minWordFrequency) {
            this.minWordFrequency = minWordFrequency;
            return this;
        }

        public Builder tokenizerFactory(TokenizerFactory tokenizerFactory) {
            this.tokenizerFactory = tokenizerFactory;
            return this;
        }



        public Builder layerSize(int layerSize) {
            this.layerSize = layerSize;
            return this;
        }

        public Builder stopWords(List<String> stopWords) {
            this.stopWords = stopWords;
            return this;
        }

        public Builder windowSize(int window) {
            this.window = window;
            return this;
        }

        public Builder iterate(SentenceIterator iter) {
            this.iter = iter;
            return this;
        }




        public Word2Vec build() {

            if(iter == null) {
                Word2Vec ret = new Word2Vec();
                ret.layerSize = layerSize;
                ret.window = window;
                ret.alpha.set(lr);
                ret.stopWords = stopWords;
                ret.setCache(vocabCache);
                ret.numIterations = iterations;
                ret.minWordFrequency = minWordFrequency;
                ret.seed = seed;
                ret.saveVocab = saveVocab;

                try {
                    if (tokenizerFactory == null)
                        tokenizerFactory = new UimaTokenizerFactory();
                }catch(Exception e) {
                    throw new RuntimeException(e);
                }

                if(vocabCache == null)
                    vocabCache = new InMemoryLookupCache(layerSize);

                ret.docIter = docIter;
                ret.tokenizerFactory = tokenizerFactory;

                return ret;
            }

            else {
                Word2Vec ret = new Word2Vec();
                ret.alpha.set(lr);
                ret.layerSize = layerSize;
                ret.sentenceIter = iter;
                ret.window = window;
                ret.stopWords = stopWords;
                ret.minWordFrequency = minWordFrequency;
                ret.setCache(vocabCache);
                ret.docIter = docIter;
                ret.minWordFrequency = minWordFrequency;
                ret.numIterations = iterations;
                ret.seed = seed;
                ret.numIterations = iterations;
                ret.saveVocab = saveVocab;

                try {
                    if (tokenizerFactory == null)
                        tokenizerFactory = new UimaTokenizerFactory();
                }catch(Exception e) {
                    throw new RuntimeException(e);
                }

                if(vocabCache == null)
                    vocabCache = new InMemoryLookupCache(layerSize);

                ret.tokenizerFactory = tokenizerFactory;
                return ret;
            }



        }
    }




}