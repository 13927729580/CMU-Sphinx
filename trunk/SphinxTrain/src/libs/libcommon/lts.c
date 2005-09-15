/*************************************************************************/
/*                                                                       */
/*                  Language Technologies Institute                      */
/*                     Carnegie Mellon University                        */
/*                        Copyright (c) 1999                             */
/*                        All Rights Reserved.                           */
/*                                                                       */
/*  Permission is hereby granted, free of charge, to use and distribute  */
/*  this software and its documentation without restriction, including   */
/*  without limitation the rights to use, copy, modify, merge, publish,  */
/*  distribute, sublicense, and/or sell copies of this work, and to      */
/*  permit persons to whom this work is furnished to do so, subject to   */
/*  the following conditions:                                            */
/*   1. The code must retain the above copyright notice, this list of    */
/*      conditions and the following disclaimer.                         */
/*   2. Any modifications must be clearly marked as such.                */
/*   3. Original authors' names are not deleted.                         */
/*   4. The authors' names are not used to endorse or promote products   */
/*      derived from this software without specific prior written        */
/*      permission.                                                      */
/*                                                                       */
/*  CARNEGIE MELLON UNIVERSITY AND THE CONTRIBUTORS TO THIS WORK         */
/*  DISCLAIM ALL WARRANTIES WITH REGARD TO THIS SOFTWARE, INCLUDING      */
/*  ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS, IN NO EVENT   */
/*  SHALL CARNEGIE MELLON UNIVERSITY NOR THE CONTRIBUTORS BE LIABLE      */
/*  FOR ANY SPECIAL, INDIRECT OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES    */
/*  WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN   */
/*  AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION,          */
/*  ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF       */
/*  THIS SOFTWARE.                                                       */
/*                                                                       */
/*************************************************************************/
/*             Author:  Alan W Black (awb@cs.cmu.edu)                    */
/*               Date:  December 1999                                    */
/*************************************************************************/
/*                                                                       */
/*  Letter to sound rule support                                         */
/*                                                                       */
/*************************************************************************/
/* Modified for SphinxTrain by David Huggins-Daines <dhuggins@cs.cmu.edu> */

#include <stdio.h>
#include <string.h>
#include <ctype.h>
#include "s3/s3.h"
#include "s3/common.h"
#include "s3/lts.h"
#include "s3/lexicon.h"
#include "s2/byteorder.h"

static cst_lts_phone apply_model(cst_lts_letter *vals,
				 cst_lts_addr start,
				 const cst_lts_model *model);

static char *cst_substr(const char *str,int start, int length)
{
    char *nstr = NULL;

    if (str)
    {
	nstr = ckd_malloc(length+1);
	strncpy(nstr,str+start,length);
	nstr[length] = '\0';
    }
    return nstr;
}

int lts_apply(const char *in_word,const char *feats,
	      const cst_lts_rules *r, lex_entry_t *out_phones)
{
    int pos, index, i, maxphones;
    cst_lts_letter *fval_buff;
    cst_lts_letter *full_buff;
    cst_lts_phone phone;
    char *left, *right, *p;
    char hash;
    char zeros[8];
    char *word;

    /* Downcase the incoming word unless we are a non-roman alphabet. */
    word = ckd_salloc((char *)in_word);
    if (!r->letter_table)
	for (i = 0; i < strlen(word); ++i)
	    word[i] = tolower(word[i]);

    /* Fill in out_phones structure as best we can. */
    maxphones = strlen(word) + 10;
    out_phones->phone = ckd_malloc(maxphones * sizeof(char *));
    out_phones->ci_acmod_id = ckd_malloc(maxphones * sizeof(acmod_id_t));
    out_phones->phone_cnt = 0;
    
    /* For feature vals for each letter */
    fval_buff = ckd_calloc((r->context_window_size*2)+
			   r->context_extra_feats, sizeof(cst_lts_letter));
    /* Buffer with added contexts */
    full_buff = ckd_calloc((r->context_window_size*2)+ /* TBD assumes single POS feat */
			  strlen(word)+1, sizeof(cst_lts_letter));
    if (r->letter_table)
    {
	for (i=0; i<8; i++) zeros[i] = 2;
	sprintf(full_buff,"%.*s%c%s%c%.*s",
		r->context_window_size-1, zeros,
		1,
		word,
		1,
		r->context_window_size-1, zeros);
	hash = 1;
    }
    else
    {
	/* Assumes l_letter is a char and context < 8 */
	sprintf(full_buff,"%.*s#%s#%.*s",
		r->context_window_size-1, "00000000",
		word,
		r->context_window_size-1, "00000000");
	hash = '#';
    }

    /* Do the prediction backwards so we don't need to reverse the answer */
    for (pos = r->context_window_size + strlen(word) - 1;
	 full_buff[pos] != hash;
	 pos--)
    {
	/* Fill the features buffer for the predictor */
	sprintf(fval_buff,"%.*s%.*s%s",
		r->context_window_size,
		full_buff+pos-r->context_window_size,
		r->context_window_size,
		full_buff+pos+1,
		feats);
	if ((!r->letter_table
	     && ((full_buff[pos] < 'a') || (full_buff[pos] > 'z'))))
	{   
#ifdef EXCESSIVELY_CHATTY
	    E_WARN("lts:skipping unknown char \"%c\"\n",
		   full_buff[pos]);
#endif
	    continue;
	}
	if (r->letter_table)
	    index = full_buff[pos] - 3;
	else
	    index = (full_buff[pos]-'a')%26;
	phone = apply_model(fval_buff,
			    r->letter_index[index],
			    r->models);
	/* delete epsilons and split dual-phones */
	if (0 == strcmp("epsilon",r->phone_table[phone]))
	    continue;
	/* dynamically grow out_phones if necessary. */
	if (out_phones->phone_cnt + 2 > maxphones) {
	    maxphones += 10;
	    out_phones->phone = ckd_realloc(out_phones->phone,
					    maxphones*sizeof(char *));
	    out_phones->ci_acmod_id = ckd_realloc(out_phones->ci_acmod_id,
						  maxphones * sizeof(acmod_id_t));
	}
	if ((p=strchr(r->phone_table[phone],'-')) != NULL)
	{
	    left = cst_substr(r->phone_table[phone],0,
			      strlen(r->phone_table[phone])-strlen(p));
	    right = cst_substr(r->phone_table[phone],
			       (strlen(r->phone_table[phone])-strlen(p))+1,
			       (strlen(p)-1));
	    out_phones->phone[out_phones->phone_cnt++] = left;
	    out_phones->phone[out_phones->phone_cnt++] = right;
	    ckd_free(left);
	    ckd_free(right);
	}
	else
	    out_phones->phone[out_phones->phone_cnt++] =
		ckd_salloc((char *)r->phone_table[phone]);
    }


    ckd_free(full_buff);
    ckd_free(fval_buff);
    ckd_free(word);
    return S3_SUCCESS;
}

static cst_lts_phone apply_model(cst_lts_letter *vals,cst_lts_addr start, 
				 const cst_lts_model *model)
{
    /* because some machines (ipaq/mips) can't deal with addrs not on     */
    /* word boundaries we use a static and copy the rule values each time */
    /* so we know its properly aligned                                    */
    /* Hmm this still might be wrong on some machines that align the      */
    /* structure cst_lts_rules differently                                */
    cst_lts_rule state;
    unsigned short nstate;
    static const int sizeof_cst_lts_rule = 6;

    memmove(&state,&model[start*sizeof_cst_lts_rule],sizeof_cst_lts_rule);
    for ( ;
	 state.feat != CST_LTS_EOR;
	)
    {
	if (vals[state.feat] == state.val)
	    nstate = state.qtrue;
	else
	    nstate = state.qfalse;
	/* This should really happen at compilation time */
	/* if (WORDS_BIGENDIAN) */ /* byteswap macros are slightly bogus */
	SWAP_W(nstate);

	memmove(&state,&model[nstate*sizeof_cst_lts_rule],sizeof_cst_lts_rule);
    }

    return (cst_lts_phone)state.val;
}
