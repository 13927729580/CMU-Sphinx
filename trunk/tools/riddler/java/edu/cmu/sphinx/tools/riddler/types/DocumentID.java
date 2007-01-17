/**
 * Copyright 1999-2007 Carnegie Mellon University.
 * Portions Copyright 2002 Sun Microsystems, Inc.
 * All Rights Reserved.  Use is subject to license terms.
 * <p/>
 * See the file "license.terms" for information on usage and
 * redistribution of this file, and for a DISCLAIMER OF ALL
 * WARRANTIES.
 * <p/>
 * <p/>
 * User: Garrett Weinberg
 * Date: Jan 13, 2007
 * Time: 8:49:25 PM
 */

package edu.cmu.sphinx.tools.riddler.types;

/**
 * wrapper around a transcript's unique identifier
 */
public class DocumentID {
    int id;

    public DocumentID(int id) {
        this.id = id;
    }
}
