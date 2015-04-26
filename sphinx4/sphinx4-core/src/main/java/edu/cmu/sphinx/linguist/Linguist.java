/*
 * Copyright 1999-2002 Carnegie Mellon University.  
 * Portions Copyright 2002 Sun Microsystems, Inc.  
 * Portions Copyright 2002 Mitsubishi Electric Research Laboratories.
 * All Rights Reserved.  Use is subject to license terms.
 * 
 * See the file "license.terms" for information on usage and
 * redistribution of this file, and for a DISCLAIMER OF ALL 
 * WARRANTIES.
 *
 */

package edu.cmu.sphinx.linguist;

import edu.cmu.sphinx.util.props.Configurable;
import edu.cmu.sphinx.util.props.S4Double;

import java.io.IOException;

/**
 * The linguist is responsible for representing and managing the search space for the decoder.  The role of the linguist
 * is to provide, upon request, the search graph that is to be used by the decoder.  The linguist is a generic interface
 * that provides language model services.
 * <p>
 * The main role of any linguist is to represent the search space for the decoder. The search space can be retrieved by
 * a SearchManager via a call to <code> getSearchGraph</code>. This method returns a SearchGraph. The initial state in
 * the search graph can be retrieved via a call to <code>getInitialState</code> Successor states can be retrieved via
 * calls to <code>SearchState.getSuccessors().</code>. There are a number of search state subinterfaces that are used to
 * indicate different types of states in the search space:
 * <ul> <li><b>WordSearchState </b>- represents a word in the search space. <li><b>UnitSearchState </b>- represents a
 * unit in the search space <li><b>HMMSearchState </b> represents an HMM state in the search space 
 * </ul>
 * A linguist has a great deal of latitude about the order in which it returns states. For instance a 'flat' linguist
 * may return a WordState at the beginning of a word, while a 'tree' linguist may return WordStates at the ending of a
 * word. Likewise, a linguist may omit certain state types completely (such as a unit state). Some Search Managers may
 * want to know a priori the order in which different state types will be generated by the linguist. The method
 * <code>SearchGraph.getNumStateOrder()</code> can be used to retrieve the number of state types that will be returned
 * by the linguist. The method <code>SearchState.getOrder()</code> returns the ranking for a particular state.
 * <p>
 * Depending on the vocabulary size and topology, the search space represented by the linguist may include a very large
 * number of states. Some linguists will generate the search states dynamically, that is, the object representing a
 * particular state in the search space is not created until it is needed by the SearchManager. SearchManagers often
 * need to be able to determine if a particular state has been entered before by comparing states. Because SearchStates
 * may be generated dynamically, the <code>SearchState.equals()</code> call (as opposed to the reference equals '=='
 * method) should be used to determine if states are equal. The states returned by the linguist will generally provide
 * very efficient implementations of <code>equals</code> and <code>hashCode</code>. This will allow a SearchManager to
 * maintain collections of states in HashMaps efficiently.
 * <p>
 * The lifecycle of a linguist is as follows: 
 * <ul>
 * <li> The linguist is created by the configuration manager
 * <li> The linguist is given an opportunity to register its properties via a call to its <code>register</code> method.
 * <li>  The linguist is given a new set of properties via the <code>newProperties</code> call.  A well written linguist
 * should be prepared to respond to <code>newProperties</code> call at any time.
 * <li> The <code>allocate</code> method is called. During this call the linguist generally allocates resources such as
 * acoustic and language models. This can often take a significant amount of time. A well-written linguist will be able
 * to deal with multiple calls to <code>allocate</code>. This can happen if a linguist is shared by multiple search
 * managers.
 * <li> The <code>getSearchGraph</code> method is called by the search to retrieve the search graph that is used to
 * guide the decoding/search.  This method is typically called at the beginning of each recognition. The linguist should
 * endeavor to return the search graph as quickly as possible to reduce any recognition latency.  Some linguists will
 * pre-generate the search graph in the <code>allocate</code> method, and only need to return a reference to the search
 * graph, while other linguists may dynamically generate the search graph on each call.  Also note that some linguists
 * may change the search graph between calls so a search manager should always get a new search graph before the start
 * of each recognition.
 * <li> The <code>startRecognition</code> method is called just before recognition starts. This gives the linguist the
 * opportunity to prepare for the recognition task.  Some linguists may keep caches of search states that need to be
 * primed or flushed. Note however that if a linguist depends on <code>startRecognition</code> or
 * <code>stopRecognition</code> it is likely to not be a reentrant linguist which could limit its usefulness in some
 * multi-threaded environments.
 * <li> The <code>stopRecognition</code> method is called just after recognition completes. This gives the linguist the
 * opportunity to cleanup after the recognition task.  Some linguists may keep caches of search states that need to be
 * primed or flushed. Note however that if a linguist depends on <code>startRecognition</code> or
 * <code>stopRecognition</code> it is likely to not be a reentrant linguist which could limit its usefulness in some
 * multi-threaded environments.
 * </ul>
 */
public interface Linguist extends Configurable {

    /** Word insertion probability property */
    @S4Double(defaultValue = 1.0)
    public final static String PROP_WORD_INSERTION_PROBABILITY = "wordInsertionProbability";

    /** Unit insertion probability property */
    @S4Double(defaultValue = 1.0)
    public final static String PROP_UNIT_INSERTION_PROBABILITY = "unitInsertionProbability";

    /** Silence insertion probability property */
    @S4Double(defaultValue = 1.0)
    public final static String PROP_SILENCE_INSERTION_PROBABILITY = "silenceInsertionProbability";

    /** Filler insertion probability property */
    @S4Double(defaultValue = 1.0)
    public final static String PROP_FILLER_INSERTION_PROBABILITY = "fillerInsertionProbability";

    /** The property that defines the language weight for the search */
    @S4Double(defaultValue = 1.0)
    public final static String PROP_LANGUAGE_WEIGHT = "languageWeight";


    /**
     * Retrieves search graph.  The search graph represents the search space to be used to guide the search.
     * <p>
     * Implementor's note: This method is typically called at the beginning of each recognition and therefore should be
     *
     * @return the search graph
     */
    public SearchGraph getSearchGraph();


    /**
     * Called before a recognition. This method gives a linguist the opportunity to prepare itself before a recognition
     * begins.
     * <p>
     * Implementor's Note - Some linguists (or underlying lanaguge or acoustic models) may keep caches or pools that
     * need to be initialzed before a recognition. A linguist may implement this method to perform such initialization.
     * Note however, that an ideal linguist will, once allocated, be state-less. This will allow the linguist to be
     * shared by multiple simulataneous searches. Reliance on a 'startRecognition' may prevent a linguist from being
     * used in a multi-threaded search.
     */
    public void startRecognition();


    /**
     * Called after a recognition. This method gives a linguist the opportunity to clean up after a recognition has been
     * completed.
     * <p>
     * Implementor's Note - Some linguists (or underlying lanaguge or acoustic models) may keep caches or pools that
     * need to be flushed after a recognition. A linguist may implement this method to perform such flushing. Note
     * however, that an ideal linguist will once allocated, be state-less. This will allow the linguist to be shared by
     * multiple simulataneous searches. Reliance on a 'stopRecognition' may prevent a linguist from being used in a
     * multi-threaded search.
     */
    public void stopRecognition();


    /**
     * Allocates the linguist. Resources allocated by the linguist are allocated here. This method may take many seconds
     * to complete depending upon the linguist.
     * <p>
     * Implementor's Note - A well written linguist will allow allocate to be called multiple times without harm. This
     * will allow a linguist to be shared by multiple search managers.
     *
     * @throws IOException if an IO error occurs
     */
    public void allocate() throws IOException;


    /**
     * Deallocates the linguist. Any resources allocated by this linguist are released.
     * <p>
     * Implementor's Note - if the linguist is being shared by multiple searches, the deallocate should only actually
     * deallocate things when the last call to deallocate is made. Two approaches for dealing with this:
     * <p>
     * (1) Keep an allocation counter that is incremented during allocate and decremented during deallocate. Only when
     * the counter reaches zero should the actually deallocation be performed.
     * <p>
     * (2) Do nothing in dellocate - just the the GC take care of things
     */
    public void deallocate() throws IOException;
}

