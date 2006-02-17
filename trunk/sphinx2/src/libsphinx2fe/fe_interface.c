/* ====================================================================
 * Copyright (c) 1996-2004 Carnegie Mellon University.  All rights 
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
#include <stdio.h>
#include <string.h>
#include <math.h>
#include <stdlib.h>
#include <assert.h>

#include "s2types.h"
#include "fe.h"
#include "fe_internal.h"

/* 
 *   HISTORY
 *
 *   12-Aug-99 Created by M Seltzer for opensource SPHINX III system
 *   Based in part on past implementations by R Singh, M Siegler, M
 *   Ravishankar, and others
 *             
 *
 *    7-Feb-00 M. Seltzer - changed fe_process_utt usage. Function now
 *    allocated 2d feature array internally and assigns the passed
 *    pointer to it. This was done to allow for varying numbers of
 *    frames to be written when block i/o processing
 *      
 *    17-Apr-01 RAH, upgraded all floats to float32, it was causing
 *    some conflicts with external functions that were using float32.
 *    I know that it doesn't matter for the most part because floats
 *    are normally float32, however it makes things cleaner.
 *    
 */  


/*********************************************************************
   FUNCTION:   fe_init
   PARAMETERS: param_t *P
   RETURNS:    pointer to a new front end or NULL if failure.
   DESCRIPTION: builds a front end instance FE according to user
   parameters in P, and completes FE structure accordingly,
   i.e. builds appropriate filters, buffers, etc. If a param in P is
   0 then the FE parameter is set to its default value as defined
   in fe.h 
   Note: if default PRE_EMPHASIS_ALPHA is changed from 0, this will be
   problematic for init of this parameter...
**********************************************************************/

fe_t *fe_init(param_t const *P)
{
    fe_t  *FE = (fe_t *) calloc(1,sizeof(fe_t));

    if (FE==NULL){
      E_WARN("memory alloc failed in fe_init()\n");
	return(NULL);
    }
    
    /* transfer params to front end */
    fe_parse_general_params(P,FE);

    /* compute remaining FE parameters */

    FE->FRAME_SHIFT        = (int32)(FE->SAMPLING_RATE/FE->FRAME_RATE + 0.5);
    FE->FRAME_SIZE         = (int32)(FE->WINDOW_LENGTH*FE->SAMPLING_RATE + 0.5);
    FE->PRIOR              = 0;
    FE->FRAME_COUNTER 	   = 0;	 	

    /* establish buffers for overflow samps and hamming window */
    FE->OVERFLOW_SAMPS = (int16 *)calloc(FE->FRAME_SIZE,sizeof(int16));
    FE->HAMMING_WINDOW = (float64 *) calloc(FE->FRAME_SIZE,sizeof(float64));
    
    if (FE->OVERFLOW_SAMPS==NULL || FE->HAMMING_WINDOW==NULL){
	E_WARN("memory alloc failed in fe_init()\n");
	return(NULL);
    }

    /* create hamming window */    
    fe_create_hamming(FE->HAMMING_WINDOW, FE->FRAME_SIZE);
    
    /* init and fill appropriate filter structure */
    if (FE->FB_TYPE==MEL_SCALE) {   
	if ((FE->MEL_FB = (melfb_t *) calloc(1,sizeof(melfb_t)))==NULL){
	    E_WARN("memory alloc failed in fe_init()\n");
	    return(NULL);
	}
	/* transfer params to mel fb */
	fe_parse_melfb_params(P, FE->MEL_FB);

	fe_build_melfilters(FE->MEL_FB);
	fe_compute_melcosine(FE->MEL_FB);
    } else {
	E_WARN("MEL SCALE IS CURRENTLY THE ONLY IMPLEMENTATION!\n");
	return(NULL);
    }

    /*** Z.A.B. ***/	
    /*** Initialize the overflow buffers ***/		
    fe_start_utt(FE);

    return(FE);
}


/*********************************************************************
   FUNCTION: fe_start_utt
   PARAMETERS: fe_t *FE
   RETURNS: 0 if successful
   DESCRIPTION: called at the start of an utterance. resets the
   overflow buffer and activates the start flag of the front end
**********************************************************************/
int32 fe_start_utt(fe_t *FE)
{
    FE->NUM_OVERFLOW_SAMPS = 0;
    memset(FE->OVERFLOW_SAMPS,0,FE->FRAME_SIZE*sizeof(int16));
    FE->START_FLAG=1;
    FE->PRIOR = 0;
    return 0;
}


/*********************************************************************
   FUNCTION: fe_process_utt
   PARAMETERS: fe_t *FE, int16 *spch, int32 nsamps, float32 **cep, int32 nframes
   RETURNS: status, successful or not
   DESCRIPTION: processes the given speech data and returns
   features. will prepend overflow data from last call and store new
   overflow data within the FE
**********************************************************************/
int32 fe_process_utt(fe_t *FE, int16 const *spch, int32 nsamps, float32 **cep, int32 *nframes)
{
    int32 frame_start, frame_count=0, whichframe=0;
    int32 i, spbuf_len, offset=0;  
    float64 *spbuf, *fr_data, *fr_fea;
    int16 const *allspch = spch;
    int16 * tmp_spch = NULL;
    int32 return_value = FE_SUCCESS;
    int32 frame_return_value;
    
    /* are there enough samples to make at least 1 frame? */
    if (nsamps+FE->NUM_OVERFLOW_SAMPS >= FE->FRAME_SIZE){
      
      /* if there are previous samples, pre-pend them to input speech samps */
      if ((FE->NUM_OVERFLOW_SAMPS > 0)) {

	if ((tmp_spch = (int16 *) malloc (sizeof(int16)*(FE->NUM_OVERFLOW_SAMPS +nsamps)))==NULL){
	    E_WARN("memory alloc failed in fe_process_utt()\n");
	    return FE_MEM_ALLOC_ERROR;
	}
	/* RAH */
	memcpy (tmp_spch,FE->OVERFLOW_SAMPS,FE->NUM_OVERFLOW_SAMPS*(sizeof(int16))); /* RAH */
	memcpy(tmp_spch+FE->NUM_OVERFLOW_SAMPS, spch, nsamps*(sizeof(int16))); /* RAH */
	nsamps += FE->NUM_OVERFLOW_SAMPS;
	allspch = tmp_spch;
	FE->NUM_OVERFLOW_SAMPS = 0; /*reset overflow samps count */
      }
      /* compute how many complete frames  can be processed and which samples correspond to those samps */
      frame_count=0;
      for (frame_start=0; frame_start+FE->FRAME_SIZE <= nsamps; frame_start += FE->FRAME_SHIFT)
	frame_count++;
      
      spbuf_len = (frame_count-1)*FE->FRAME_SHIFT + FE->FRAME_SIZE;    
      assert(spbuf_len <= nsamps); // why this assertion?
      
      if ((spbuf=(float64 *)calloc(spbuf_len, sizeof(float64)))==NULL){
	  E_WARN("memory alloc failed in fe_process_utt()\n");
	  return(FE_MEM_ALLOC_ERROR);
      }
      
      /* pre-emphasis if needed,convert from int16 to float64 */
      if (FE->PRE_EMPHASIS_ALPHA != 0.0){
	fe_pre_emphasis(allspch, spbuf, spbuf_len, FE->PRE_EMPHASIS_ALPHA, FE->PRIOR);
      } else{
	fe_short_to_double(allspch, spbuf, spbuf_len);
      }
      
      /* frame based processing - let's make some cepstra... */    
      fr_data = (float64 *)calloc(FE->FRAME_SIZE, sizeof(float64));
      fr_fea = (float64 *)calloc(FE->FEATURE_DIMENSION, sizeof(float64));
      
      if (fr_data==NULL || fr_fea==NULL){
	  E_WARN("memory alloc failed in fe_process_utt()\n");
	  return(FE_MEM_ALLOC_ERROR);
      }

      for (whichframe=0;whichframe<frame_count;whichframe++){

	for (i=0;i<FE->FRAME_SIZE;i++)
	  fr_data[i] = spbuf[whichframe*FE->FRAME_SHIFT + i];
	
	fe_hamming_window(fr_data, FE->HAMMING_WINDOW, FE->FRAME_SIZE);
	
	frame_return_value = fe_frame_to_fea(FE, fr_data, fr_fea);
	
	if (FE_SUCCESS != frame_return_value) {
	  return_value = frame_return_value;
	}

	for (i=0;i<FE->FEATURE_DIMENSION;i++)
	  cep[whichframe][i] = (float32)fr_fea[i];
      }
      /* done making cepstra */
      
      
      /* assign samples which don't fill an entire frame to FE overflow buffer for use on next pass */
      if (spbuf_len < nsamps)	{
	offset = ((frame_count)*FE->FRAME_SHIFT);
	memcpy(FE->OVERFLOW_SAMPS,allspch+offset,(nsamps-offset)*sizeof(int16));
	FE->NUM_OVERFLOW_SAMPS = nsamps-offset;
	FE->PRIOR = allspch[offset-1];
	assert(FE->NUM_OVERFLOW_SAMPS<FE->FRAME_SIZE);
      }
      
      if (tmp_spch)
	free(tmp_spch);
      free(spbuf);
      free(fr_data);
      free(fr_fea);
    }
    
    /* if not enough total samps for a single frame, append new samps to
       previously stored overlap samples */
    else { 
      memcpy(FE->OVERFLOW_SAMPS+FE->NUM_OVERFLOW_SAMPS,allspch, nsamps*(sizeof(int16)));
      FE->NUM_OVERFLOW_SAMPS += nsamps;
      assert(FE->NUM_OVERFLOW_SAMPS < FE->FRAME_SIZE);
      frame_count=0;
    }
    *nframes = frame_count;
    return return_value;
}


/*********************************************************************
   FUNCTION: fe_end_utt
   PARAMETERS: fe_t *FE, float32 *cepvector, int32 nframes
   RETURNS: status, successful or not
   DESCRIPTION: if there are overflow samples remaining, it will pad
   with zeros to make a complete frame and then process to
   cepstra. also deactivates start flag of FE, and resets overflow
   buffer count. 
**********************************************************************/
int32 fe_end_utt(fe_t *FE, float32 *cepvector, int32 *nframes)
{
  int32 pad_len=0, frame_count=0;
  int32 i;
  float64 *spbuf, *fr_fea = NULL;
  int32 return_value = FE_SUCCESS;
  
  /* if there are any samples left in overflow buffer, pad zeros to
     make a frame and then process that frame */
  
  if ((FE->NUM_OVERFLOW_SAMPS > 0)) { 
    pad_len = FE->FRAME_SIZE - FE->NUM_OVERFLOW_SAMPS;
    memset(FE->OVERFLOW_SAMPS+(FE->NUM_OVERFLOW_SAMPS),0,pad_len*sizeof(int16));
    FE->NUM_OVERFLOW_SAMPS += pad_len;
    assert(FE->NUM_OVERFLOW_SAMPS==FE->FRAME_SIZE);
    
    if ((spbuf=(float64 *)calloc(FE->FRAME_SIZE,sizeof(float64)))==NULL){
	E_WARN("memory alloc failed in fe_end_utt()\n");
	return(FE_MEM_ALLOC_ERROR);
    }
 
    if (FE->PRE_EMPHASIS_ALPHA != 0.0){
      fe_pre_emphasis(FE->OVERFLOW_SAMPS, spbuf, FE->FRAME_SIZE,FE->PRE_EMPHASIS_ALPHA, FE->PRIOR);
    } else {
      fe_short_to_double(FE->OVERFLOW_SAMPS, spbuf, FE->FRAME_SIZE);
    }
    
    /* again, who should implement cep vector? this can be implemented
       easily from outside or easily from in here */
    if ((fr_fea = (float64 *)calloc(FE->FEATURE_DIMENSION, sizeof(float64)))==NULL){
	E_WARN("memory alloc failed in fe_end_utt()\n");
	return(FE_MEM_ALLOC_ERROR);
    }

    fe_hamming_window(spbuf, FE->HAMMING_WINDOW, FE->FRAME_SIZE);
    return_value = fe_frame_to_fea(FE, spbuf, fr_fea);	
    for (i=0;i<FE->FEATURE_DIMENSION;i++)
      cepvector[i] = (float32)fr_fea[i];
    frame_count=1;
    free(fr_fea);		/* RAH - moved up */
    free (spbuf);		/* RAH */
  } else {
    frame_count=0;
    /* FIXME: This statement has no effect whatsoever! */
    cepvector = NULL;
  }
  
  /* reset overflow buffers... */
  FE->NUM_OVERFLOW_SAMPS = 0;
  FE->START_FLAG=0;
  
  *nframes = frame_count;
  return return_value;
}

/*********************************************************************
   FUNCTION: fe_close
   PARAMETERS: fe_t *FE
   RETURNS: 
   DESCRIPTION: free all allocated memory within FE and destroy FE 
**********************************************************************/

int32 fe_close(fe_t *FE)
{
  /* kill FE instance - free everything... */
  if (FE->FB_TYPE==MEL_SCALE) {
    fe_free_2d(FE->MEL_FB->filter_coeffs);
    fe_free_2d(FE->MEL_FB->mel_cosine);
    free(FE->MEL_FB->left_apex);
    free(FE->MEL_FB->width);
    free(FE->MEL_FB);
  } else {
    /* We won't end up here, since this was already checked up when we
     * started. But just in case, let's break, if we're debugging.
     */
    assert(0);
  }
    
  free(FE->OVERFLOW_SAMPS);
  free(FE->HAMMING_WINDOW);
  free(FE);
  return(0);
}
