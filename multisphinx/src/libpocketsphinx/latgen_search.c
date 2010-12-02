/* -*- c-basic-offset: 4; indent-tabs-mode: nil -*- */
/* ====================================================================
 * Copyright (c) 2010 Carnegie Mellon University.  All rights
 * reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer. 
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * This work was supported in part by funding from the Defense Advanced 
 * Research Projects Agency and the National Science Foundation of the 
 * United States of America, and the CMU Sphinx Speech Consortium.
 *
 * THIS SOFTWARE IS PROVIDED BY CARNEGIE MELLON UNIVERSITY ``AS IS'' AND 
 * ANY EXPRESSED OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, 
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL CARNEGIE MELLON UNIVERSITY
 * NOR ITS EMPLOYEES BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT 
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, 
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY 
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT 
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE 
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * ====================================================================
 *
 */

/**
 * @file latgen_search.c Lattice generation (as a search pass).
 */

#include "latgen_search.h"

static int latgen_search_decode(ps_search_t *base);
static int latgen_search_free(ps_search_t *base);

static ps_searchfuncs_t latgen_funcs = {
    /* name: */   "latgen",
    /* free: */   latgen_search_free,
    /* decode: */ latgen_search_decode,
};

ps_search_t *
latgen_init(cmd_ln_t *config,
	    dict2pid_t *d2p,
	    arc_buffer_t *input_arcs)
{
	latgen_t *latgen;

	latgen = ckd_calloc(1, sizeof(*latgen));
	ps_search_init(&latgen->base, &latgen_funcs,
		       config, NULL, d2p->dict, d2p);
        latgen->input_arcs = input_arcs;
	
	return &latgen->base;
}

static int
latgen_search_decode(ps_search_t *base)
{
    latgen_t *latgen = (latgen_t *)base;

    E_INFO("Latgen search starting\n");
    while (arc_buffer_wait(latgen->input_arcs, -1) >= 0) {
        E_INFO("Got some arcs (end frame %d)\n",
               latgen->input_arcs->next_sf);
        if (latgen->input_arcs->final)
            break;
    }
    E_INFO("Latgen search stopping\n");
    return 0;
}

static int
latgen_search_free(ps_search_t *base)
{
	return 0;
}
