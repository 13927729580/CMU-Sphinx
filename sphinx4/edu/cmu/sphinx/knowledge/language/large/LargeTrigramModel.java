/*
 * Copyright 1999-2002 Carnegie Mellon University.  
 * Portions Copyright 2002 Sun Microsystems, Inc.  
 * Portions Copyright 2002 Mitsubishi Electronic Research Laboratories.
 * All Rights Reserved.  Use is subject to license terms.
 * 
 * See the file "license.terms" for information on usage and
 * redistribution of this file, and for a DISCLAIMER OF ALL 
 * WARRANTIES.
 *
 */

package edu.cmu.sphinx.knowledge.language.large;

import edu.cmu.sphinx.knowledge.dictionary.Dictionary;

import edu.cmu.sphinx.knowledge.language.LanguageModel;
import edu.cmu.sphinx.knowledge.language.WordSequence;

import edu.cmu.sphinx.util.LogMath;
import edu.cmu.sphinx.util.SphinxProperties;
import edu.cmu.sphinx.util.Utilities;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Reader;

import java.net.URL;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;

import java.nio.channels.FileChannel;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Collections;
import java.util.StringTokenizer;

// for testing
import java.io.*;
import edu.cmu.sphinx.util.Timer;


/**
 * Reads a binary language model file generated by the 
 * CMU-Cambridge Statistical Language Modelling Toolkit.
 * 
 * Note that all probabilites in the grammar are stored in LogMath log
 * base format. Language Probabilties in the language model file are
 * stored in log 10  base. They are converted to the LogMath logbase.
 */
public class LargeTrigramModel implements LanguageModel {

    private static final String DARPA_LM_HEADER = "Darpa Trigram LM";

    private static final int LOG2_BIGRAM_SEGMENT_SIZE_DEFAULT = 9;

    private static final float MIN_PROBABILITY = -99.0f;

    private static final int BYTES_PER_BIGRAM = 8;

    private static final int BYTES_PER_TRIGRAM = 4;


    private SphinxProperties props;
    private LogMath logMath;
    private Set vocabulary;
    private int maxNGram = 3;

    private int bytesRead = 0;

    private Map unigramIDMap;
    private Map loadedBigramFollowers;

    private UnigramProbability[] unigrams;
    private String[] words;
    private int startWordID;
    private int endWordID;

    private int bigramOffset;
    private int trigramOffset;

    private int numberUnigrams;
    private int numberBigrams;
    private int numberTrigrams;

    private float[] bigramProbTable;
    private float[] trigramBackoffTable;
    private float[] trigramProbTable;
    private float[] trigramSegmentTable;

    private FileInputStream is;
    private FileChannel fileChannel;
    private boolean bigEndian = true;

    
    /**
     * Creates a simple ngram model from the data at the URL. The
     * data should be an ARPA format
     *
     * @param context the context for this model
     *
     * @throws IOException if there is trouble loading the data
     */
    public LargeTrigramModel(String context) 
        throws IOException, FileNotFoundException {
	initialize(context);
    }
    

    /**
     * Raw constructor
     */
    public LargeTrigramModel() {
    }

    
    /**
     * Initializes this LanguageModel
     *
     * @param context the context to associate this linguist with
     */
    public void initialize(String context) throws IOException {
        this.props = SphinxProperties.getSphinxProperties(context);
        
        String format = props.getString
            (LanguageModel.PROP_FORMAT, LanguageModel.PROP_FORMAT_DEFAULT);
        String location = props.getString
            (LanguageModel.PROP_LOCATION, LanguageModel.PROP_LOCATION_DEFAULT);
        
        vocabulary = new HashSet();
        unigramIDMap = new HashMap();
        loadedBigramFollowers = new HashMap();
        logMath = LogMath.getLogMath(context);
        loadBinary(location);
    }
    
    
    /**
     * Called before a recognition
     */
    public void start() {
    }
    
    /**
     * Called after a recognition
     */
    public void stop() {
    }

    
    /**
     * Gets the ngram probability of the word sequence represented by
     * the word list 
     *
     * @param wordSequence the word sequence
     *
     * @return the probability of the word sequence.
     * Probability is in logMath log base
     *
     */
    public float getProbability(WordSequence wordSequence) {
        if (wordSequence.size() == 1) {
            return getUnigramProbability(wordSequence);
        } else if (wordSequence.size() == 2) {
            return getBigramProbability(wordSequence);
            /*
              } else if (wordSequence.size() == 3) {
              return getTrigramProbability(wordSequence);
              */
        } else {
            throw new Error("Unsupported N-gram: " + wordSequence.size());
        }
    }


    /**
     * Returns the unigram probability of the given unigram.
     *
     * @param wordSequence the unigram word sequence
     *
     * @return the unigram probability
     */
    private float getUnigramProbability(WordSequence wordSequence) {
        String unigram = wordSequence.getWord(0);
        Integer unigramID = (Integer) unigramIDMap.get(unigram);
        if (unigramID == null) {
            throw new Error("Unigram not in LM: " + unigram);
        } else {
            UnigramProbability probability = unigrams[unigramID.intValue()];
            return probability.getLogProbability();
        }
    }


    /**
     * Returns true if this language model has the given unigram.
     *
     * @param unigram the unigram to find
     *
     * @return true if this LM has this unigram, false otherwise
     */
    private boolean hasUnigram(String unigram) {
        return (unigramIDMap.get(unigram) != null);
    }


    /**
     * Returns the ID of the given word.
     *
     * @param word the word to find the ID
     *
     * @return the ID of the word
     */
    private final int getWordID(String word) {
        Integer integer = (Integer) unigramIDMap.get(word);
        if (integer == null) {
            throw new IllegalArgumentException("No word ID: " + word);
        } else {
            return integer.intValue();
        }
    }


    /**
     * Returns the unigram probability of the given unigram.
     *
     * @param wordSequence the unigram word sequence
     *
     * @return the unigram probability
     */
    private float getBigramProbability(WordSequence wordSequence) {

        String firstWord = wordSequence.getWord(0).toLowerCase();
        String secondWord = wordSequence.getWord(1).toLowerCase();

        if (numberBigrams == 0 || firstWord == null) {
            return getUnigramProbability(wordSequence.getNewest());
        }
        
        if (!hasUnigram(secondWord)) {
            throw new Error("Bad word2: " + secondWord);
        }

        int firstWordID = getWordID(firstWord);

        int numberBigramFollowers = 
            unigrams[firstWordID+1].getFirstBigramEntry() -
            unigrams[firstWordID].getFirstBigramEntry();

        int secondWordID = getWordID(secondWord);

        BigramProbability bigram = null;

        if (numberBigramFollowers > 0) {
            // load all the bigram followers of firstWord
            // and then find the bigram with the secondWord
            BigramFollowers bigramFollowers = 
                loadBigramFollowers(firstWordID, numberBigramFollowers);
            bigram = bigramFollowers.findBigram(secondWordID);
        }

        if (bigram != null) {
            assert (words[bigram.getWordID()].equals(secondWord));
            return bigramProbTable[bigram.getProbabilityID()];
        } else {
            return (unigrams[firstWordID].getLogBackoff() + 
                    unigrams[secondWordID].getLogProbability());
        }
    }


    /**
     * Returns the backoff probability for the give sequence of words
     *
     * @param wordSequence the sequence of words
     *
     * @return the backoff probability in LogMath log base
     */
    public float getBackoff(WordSequence wordSequence) {
        float logBackoff = 0.0f;           // log of 1.0
        UnigramProbability prob = null; //getProb(wordSequence);
        if (prob != null) {
            logBackoff = prob.getLogBackoff();
        }
        return logBackoff;
    }
    

    /**
     * Returns the maximum depth of the language model
     *
     * @return the maximum depth of the language mdoel
     */
    public int getMaxDepth() {
        return maxNGram;
    }


    /**
     * Returns the set of words in the lanaguage model. The set is
     * unmodifiable.
     *
     * @return the unmodifiable set of words
     */
    public Set getVocabulary() {
        return Collections.unmodifiableSet(vocabulary);
    }
    
    
    /**
     * Provides the log base that controls the range of probabilities
     * returned by this N-Gram
     */
    public void setLogMath(LogMath logMath) {
        this.logMath = logMath;
    }


    /**
     * Returns the log math the controls the log base for the range of
     * probabilities used by this n-gram
     */
    public LogMath getLogMath() {
        return this.logMath;
    }


    /**
     * Loads the bigram followers of the given first word in a bigram from
     * disk to memory.
     *
     * @param firstWordID ID of the first word
     * @param numberFollowers the number of bigram followers this word has
     *
     * @return the bigram followers of the given word
     */
    private BigramFollowers loadBigramFollowers(int firstWordID, 
                                                int numberFollowers) {
        BigramFollowers followers = null;

        if ((followers = isBigramLoaded(firstWordID)) == null) {

            System.out.println("Loading BigramFollowers from disk");

            int firstBigramEntry = unigrams[firstWordID].getFirstBigramEntry();
            
            long position = (long) (bigramOffset + 
                                    (firstBigramEntry * BYTES_PER_BIGRAM));
            int size = (numberFollowers + 1) * BYTES_PER_BIGRAM;
            
            try {
                assert ((position + size) <= fileChannel.size());
                ByteBuffer buffer = loadBigramBuffer(position, size);
                if (!bigEndian) {
                    buffer.order(ByteOrder.LITTLE_ENDIAN);
                }
                followers = new BigramFollowers(buffer, numberFollowers);
            } catch (IOException ioe) {
                ioe.printStackTrace();
                throw new Error("Error loading bigram followers");
            }
            loadedBigramFollowers.put(new Integer(firstWordID), followers);
        }

        return followers;
    }


    /**
     * Loads the contents of the memory-mapped file starting at the 
     * given position and for the given size, into a byte buffer.
     * This method is implemented because MappedByteBuffer.load()
     * does not work properly.
     *
     * @param position the starting position in the file
     * @param size the number of bytes to load
     *
     * @return the laoded ByteBuffer
     */
    private ByteBuffer loadBigramBuffer(long position, int size) throws
    IOException {
        ByteBuffer bb = ByteBuffer.allocate(size);
        fileChannel.position(position);
        int bytesRead = fileChannel.read(bb);
        if (bytesRead != size) {
            throw new IOException("Insufficient bytes read.");
        }
        return bb;
    }


    /**
     * Returns the BigramFollower of the given word, if it is loaded.
     *
     * @param firstWordID the ID of the word whose bigrams we want to check
     *
     * @return the BigramFollower of the given word, if it is loaded,
     *         or null if it is not loaded
     */
    private BigramFollowers isBigramLoaded(int firstWordID) {
        return (BigramFollowers) loadedBigramFollowers.get
            (new Integer(firstWordID));
    }

    
    /**
     * Loads the language model from the given location. 
     *
     * @param location the location of the language model
     */
    private void loadBinary(String location) throws IOException {

        FileInputStream fis = new FileInputStream(location);
	DataInputStream stream = new DataInputStream
            (new BufferedInputStream(fis));
	
	// read standard header string-size; set bigEndian flag
	
	int headerLength = readInt(stream, bigEndian);

	if (headerLength != (DARPA_LM_HEADER.length() + 1)) { // not big-endian
	    headerLength = Utilities.swapInteger(headerLength);
	    if (headerLength == (DARPA_LM_HEADER.length() + 1)) {
		bigEndian = false;
	    } else {
		throw new Error
		    ("Bad binary LM file magic number: " + headerLength +
		     ", not an LM dumpfile?");
	    }
	}

	// read and verify standard header string

	String header = readString(stream, headerLength - 1);
	readByte(stream); // read the '\0'
        
	if (!header.equals(DARPA_LM_HEADER)) {
	    throw new Error("Bad binary LM file header: " + header);
	}

	// read LM filename string size and string
	
	int fileNameLength = readInt(stream, bigEndian);
	for (int i = 0; i < fileNameLength; i++) {
	    readByte(stream);
	}

	numberUnigrams = 0;
	int logBigramSegmentSize = LOG2_BIGRAM_SEGMENT_SIZE_DEFAULT;
	
	// read version number, if present. it must be <= 0.

	int version = readInt(stream, bigEndian);
	System.out.println("Version: " + version);
	if (version <= 0) { // yes, its the version number
	    readInt(stream, bigEndian); // read and skip timestamp
	    
	    // read and skip format description
	    int formatLength;
	    for (;;) {
		if ((formatLength = readInt(stream, bigEndian)) == 0) {
		    break;
		}
		for (int i = 0; i < formatLength; i++) {
		    readByte(stream);
                }
	    }

	    // read log bigram segment size if present
	    if (version <= -2) {
		logBigramSegmentSize = readInt(stream, bigEndian);
		if (logBigramSegmentSize < 1 || logBigramSegmentSize > 15) {
		    throw new Error("log2(bg_seg_sz) outside range 1..15");
		}
	    }

	    numberUnigrams = readInt(stream, bigEndian);
	} else {
	    numberUnigrams = version;
	}

        int bigramSegmentSize = 1 << logBigramSegmentSize;

	if (numberUnigrams <= 0) {
	    throw new Error("Bad number of unigrams: " + numberUnigrams +
			    ", must be > 0.");
	}
	System.out.println("# of unigrams: " + numberUnigrams);

	numberBigrams = readInt(stream, bigEndian);
	if (numberBigrams < 0) {
	    throw new Error("Bad number of bigrams: " + numberBigrams);
	}
	System.out.println("# of bigrams: " + numberBigrams);

	numberTrigrams = readInt(stream, bigEndian);
	if (numberTrigrams < 0) {
	    throw new Error("Bad number of trigrams: " + numberTrigrams);
	}
	System.out.println("# of trigrams: " + numberTrigrams);

	unigrams = readUnigrams(stream, numberUnigrams+1, bigEndian);


	// skip all the bigram entries, the +1 is the sentinel at the end
	if (numberBigrams > 0) {
            bigramOffset = bytesRead;
            System.out.println("bigramOffset: " + bigramOffset);
            int bytesToSkip = (numberBigrams + 1) * BYTES_PER_BIGRAM;
	    stream.skipBytes(bytesToSkip);
            bytesRead += bytesToSkip;
	}

	// skip all the trigram entries
	if (numberTrigrams > 0) {
            trigramOffset = bytesRead;
            int bytesToSkip = numberTrigrams * BYTES_PER_TRIGRAM;
	    stream.skipBytes(bytesToSkip);
            bytesRead += bytesToSkip;
	}

	// read the bigram probabilities table
	if (numberBigrams > 0) {
            this.bigramProbTable = readProbabilitiesTable(stream, bigEndian);
	}

	// read the trigram backoff weight table and trigram prob table
	if (numberTrigrams > 0) {
	    trigramBackoffTable = readProbabilitiesTable(stream, bigEndian);
	    trigramProbTable = readProbabilitiesTable(stream, bigEndian);
            int trigramSegTableSize = ((numberBigrams+1)/bigramSegmentSize)+1;
            trigramSegmentTable = readTrigramSegmentTable(stream, bigEndian, 
                                                          trigramSegTableSize);
        }

	// read word string names
        int wordsStringLength = readInt(stream, bigEndian);
        if (wordsStringLength <= 0) {
            throw new Error("Bad word string size: " + wordsStringLength);
        }

        // read the string of all words
        String wordsString = readString(stream, wordsStringLength);

        // first make sure string just read contains ucount words
        int numberWords = 0;
        for (int i = 0; i < wordsStringLength; i++) {
            if (wordsString.charAt(i) == '\0') {
                numberWords++;
            }
        }
        if (numberWords != numberUnigrams) {
            throw new Error("Bad # of words: " + numberWords);
        }

        // break up string just read into words
        this.words = wordsString.split("\0");

        buildUnigramIDMap();
        
        // applyUnigramWeight();

        fis.close();
        stream.close();

        is = new FileInputStream(location);
        fileChannel = is.getChannel();
    }
    
    
    /**
     * Builds the map from unigram to unigramID.
     * Also finds the startWordID and endWordID.
     */
    private void buildUnigramIDMap() {
        for (int i = 0; i < words.length; i++) {
            if (words[i].equals(Dictionary.SENTENCE_START_SPELLING)) {
                this.startWordID = i;
            } else if (words[i].equals(Dictionary.SENTENCE_END_SPELLING)) {
                this.endWordID = i;
            }
            unigramIDMap.put(words[i].toLowerCase(), (new Integer(i)));
        }
    }
    

    /**
     * Apply the unigram weight to the set of unigrams
     */
    private void applyUnigramWeight() {

        float unigramWeight = props.getFloat
            (LanguageModel.PROP_UNIGRAM_WEIGHT, 
	     LanguageModel.PROP_UNIGRAM_WEIGHT_DEFAULT);

        float logUnigramWeight = logMath.linearToLog(unigramWeight);
        float logNotUnigramWeight = logMath.linearToLog(1.0f - unigramWeight);
        float logUniform = logMath.linearToLog(1.0f/(numberUnigrams));

        float p2 = logUniform + logNotUnigramWeight;

        for (int i = 0; i < numberUnigrams; i++) {
            if (!words[i].equals(Dictionary.SENTENCE_START_SPELLING)) {
                float p1 = unigrams[i].getLogProbability() + logUnigramWeight;
                unigrams[i].setLogProbability(logMath.addAsLinear(p1, p2));
            }
        }
    }


    /**
     * Reads the probability table from the given DataInputStream.
     *
     * @param stream the DataInputStream from which to read the table
     * @param bigEndian true if the given stream is bigEndian, false otherwise
     */
    private float[] readProbabilitiesTable(DataInputStream stream,
                                           boolean bigEndian) throws
    IOException {
        
	int numProbs = readInt(stream, bigEndian);
	if (numProbs <= 0 || numProbs > 65536) {
	    throw new Error("Bad probabilities table size: " + numProbs);
	}
	float[] probTable = new float[numProbs];
	for (int i = 0; i < numProbs; i++) {
	    probTable[i] = logMath.log10ToLog(readFloat(stream, bigEndian));
	}
        
	return probTable;
    }


    /**
     * Reads the probability table from the given DataInputStream.
     *
     * @param stream the DataInputStream from which to read the table
     * @param bigEndian true if the given stream is bigEndian, false otherwise
     */
    private float[] readTrigramSegmentTable(DataInputStream stream, 
                                            boolean bigEndian, int tableSize) 
	throws IOException {
	int numProbs = readInt(stream, bigEndian);
	if (numProbs != tableSize) {
	    throw new Error("Bad trigram seg table size: " + numProbs);
	}
	float[] segmentTable = new float[numProbs];
	for (int i = 0; i < numProbs; i++) {
	    segmentTable[i] = readFloat(stream, bigEndian);
	}
	return segmentTable;
    }


    /**
     * Read in the unigrams in the given DataInputStream.
     *
     * @param stream the DataInputStream to read from
     * @param numberUnigrams the number of unigrams to read
     * @param bigEndian true if the DataInputStream is big-endian,
     *                  false otherwise
     *
     * @return an array of UnigramProbability index by the unigram ID
     */
    private UnigramProbability[] readUnigrams(DataInputStream stream, 
                                              int numberUnigrams, 
                                              boolean bigEndian)
    throws IOException {

        UnigramProbability[] unigrams = new UnigramProbability[numberUnigrams];
                                  
	for (int i = 0; i < numberUnigrams; i++) {

	    // read unigram ID, unigram probability, unigram backoff weight
	    int unigramID = readInt(stream, bigEndian);

            // if we're not reading the sentinel unigram at the end,
            // make sure that the unigram IDs are consecutive
            if (i != (numberUnigrams - 1)) {
                assert (unigramID == i);
            }
            
            float unigramProbability = readFloat(stream, bigEndian);
	    float unigramBackoff = readFloat(stream, bigEndian);
	    int firstBigramEntry = readInt(stream, bigEndian);

            float logProbability = logMath.log10ToLog(unigramProbability);
            float logBackoff = logMath.log10ToLog(unigramBackoff);

            unigrams[i] = new UnigramProbability
                (logProbability, logBackoff, firstBigramEntry);
	}

        return unigrams;
    }


    /**
     * Reads a byte from the given DataInputStream.
     *
     * @param stream the DataInputStream to read from
     *
     * @return the byte read
     */
    private final byte readByte(DataInputStream stream) throws IOException {
        bytesRead++;
        return stream.readByte();
    }


    /**
     * Reads an integer from the given DataInputStream.
     *
     * @param stream the DataInputStream to read from
     * @param bigEndian true if the DataInputStream is in bigEndian,
     *                  false otherwise
     *
     * @return the integer read
     */
    private final int readInt(DataInputStream stream, boolean bigEndian) 
    throws IOException {
        bytesRead += 4;
        if (bigEndian) {
            return stream.readInt();
        } else {
            return Utilities.readLittleEndianInt(stream);
	}
    }


    /**
     * Reads a float from the given DataInputStream.
     *
     * @param stream the DataInputStream to read from
     * @param bigEndian true if the DataInputStream is in bigEndian,
     *                  false otherwise
     *
     * @return the float read
     */
    private final float readFloat(DataInputStream stream, boolean bigEndian)
    throws IOException {
        bytesRead += 4;
        if (bigEndian) {
            return stream.readFloat();
        } else {
            return Utilities.readLittleEndianFloat(stream);
	}
    }


    /**
     * Reads a string of the given length from the given DataInputStream.
     * It is assumed that the DataInputStream contains 8-bit chars.
     *
     * @param stream the DataInputStream to read from
     * @param length the number of characters in the returned string
     *
     * @return a string of the given length from the given DataInputStream
     */
    private final String readString(DataInputStream stream, int length)
        throws IOException {
        StringBuffer buffer = new StringBuffer();
        for (int i = 0; i < length; i++) {
            buffer.append((char)readByte(stream));
	}
        return buffer.toString();
    }

    
    
    /**
     * A test routine
     *
     */
    public static void main(String[] args) throws Exception {
        String propsPath; 
        if (args.length == 0) {
            propsPath = "file:./binary.props";
        } else {
            propsPath = args[0];
        }

        Timer.start("LM Load");
        SphinxProperties.initContext("test", new URL(propsPath));
        LargeTrigramModel sm = new LargeTrigramModel("test");
        Timer.stop("LM Load");

        Timer.dumpAll();

        LogMath logMath = LogMath.getLogMath("test");
        
        BufferedReader reader = new BufferedReader
            (new InputStreamReader(System.in));
        
        String input;
        
        System.out.println("Max depth is " + sm.getMaxDepth());
        System.out.print("Enter words: ");

        while ((input = reader.readLine()) != null) {

            StringTokenizer st = new StringTokenizer(input);
            List list = new ArrayList();
            while (st.hasMoreTokens()) {
                String tok = (String) st.nextToken();
                list.add(tok);
            }
            WordSequence wordSequence = new WordSequence(list);
            float logProbability = sm.getProbability(wordSequence);
            
            System.out.println
                ("Probability of " + wordSequence + " is: " +
                 logProbability + "(log), " + 
                 LogMath.logToLog(logProbability, 
                                  logMath.getLogBase(),
                                  10.0f) + 
                 "(log10)");

            long usedMemory = Runtime.getRuntime().totalMemory() - 
                Runtime.getRuntime().freeMemory();
            
            System.out.println("Used memory: " + usedMemory + " bytes");

            System.out.print("Enter words: ");
        }
        
        
        Timer timer = Timer.getTimer("test", "lookup trigram");
        
        List list1 = new ArrayList();
        WordSequence ws1 = new WordSequence("t", "h", "e");
        WordSequence ws2 = new WordSequence("a", "l", "q");
        
        for (int i = 0; i < 1000000; i++) {
            timer.start();
            sm.getProbability(ws1);
            timer.stop();
            timer.start();
            sm.getProbability(ws2);
            timer.stop();
        }
        
        Timer.dumpAll("test");
    }
}

