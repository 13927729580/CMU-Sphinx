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


package edu.cmu.sphinx.frontend;

import edu.cmu.sphinx.model.acoustic.AcousticModel;

import java.io.IOException;


/**
 * A frontend where FeatureFrames can be obtained.
 */
public interface FrontEnd {

    /**
     * The prefix for all Frontend SphinxProperties names.
     * Its value is currently <code>"edu.cmu.sphinx.frontend."</code>.
     */
    public static final String PROP_PREFIX = "edu.cmu.sphinx.frontend.";


    /**
     * The SphinxProperty name for sample rate in Hertz (i.e.,
     * number of times per second), which has a default value of 8000.
     */
    public static final String PROP_SAMPLE_RATE = "sampleRate";


    /**
     * The SphinxProperty name that specifies whether the
     * FrontEnd should use the properties from the AcousticModel.
     */
    public static final String PROP_USE_ACOUSTIC_MODEL_PROPS =
	"useAcousticModelProperties";

    
    /**
     * The SphinxProperty name for the number of bits per sample.
     */
    public static final String PROP_BITS_PER_SAMPLE = "bitsPerSample";


    /**
     * The SphinxProperty name for the number of bytes per frame.
     */
    public static final String PROP_BYTES_PER_AUDIO_FRAME = 
	"bytesPerAudioFrame";


    /**
     * The SphinxProperty name for window size in milliseconds.
     */
    public static final String PROP_WINDOW_SIZE_MS = "windowSizeInMs";


    /**
     * The SphinxProperty name for window shift in milliseconds,
     * which has a default value of 10F.
     */
    public static final String PROP_WINDOW_SHIFT_MS = "windowShiftInMs";

    
    /**
     * The SphinxProperty name for the size of a cepstrum, which is
     * 13 by default.
     */
    public static final String PROP_CEPSTRUM_SIZE = "cepstrumSize";


    /**
     * The SphinxProperty name that indicates whether Features
     * should retain a reference to the original raw audio bytes. The
     * default value is true.
     */
    public static final String PROP_KEEP_AUDIO_REFERENCE ="keepAudioReference";

    
    /**
     * The SphinxProperty name that specifies the Filterbank class.
     */
    public static final String PROP_FILTERBANK = "filterbank";


    /**
     * The SphinxProperty name that specifies the CepstrumProducer class.
     */
    public static final String PROP_CEPSTRUM_PRODUCER = "cepstrumProducer";


    /**
     * The SphinxProperty name that specifies the Endpointer class.
     */
    public static final String PROP_ENDPOINTER = "endpointer";


    /**
     * The SphinxProperty name that specifies the CMN class.
     */
    public static final String PROP_CMN = "cmn";


    /**
     * The SphinxProperty name that specifies the FeatureExtractor class.
     */
    public static final String PROP_FEATURE_EXTRACTOR = "featureExtractor";


    /**
     * Returns the prefix for acoustic model properties.
     *
     * @return the prefix for acoustic model properties
     */
    public static final String ACOUSTIC_PROP_PREFIX = AcousticModel.PROP_PREFIX;


    /**
     * Returns the next N feature (of the given acoustic model) 
     * produced by this FrontEnd, in a FeatureFrame object.
     * The number of Features return maybe less than N, in which
     * case the last Feature will contain a Signal.UTTERANCE_END signal.
     *
     * @param numberFeatures the number of FeatureFrames to return
     *
     * @return N number of FeatureFrames, or null
     *    if no more FeatureFrames available
     *
     * @see FeatureFrame
     *
     * @throw java.io.IOException if an I/O error occurred
     */
    public FeatureFrame getFeatureFrame(int numberFeatures, 
					String acousticModelName) 
	throws IOException;

}
