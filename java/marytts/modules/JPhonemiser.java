/**
 * Copyright 2002-2008 DFKI GmbH.
 * All Rights Reserved.  Use is subject to license terms.
 * 
 * Permission is hereby granted, free of charge, to use and distribute
 * this software and its documentation without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of this work, and to
 * permit persons to whom this work is furnished to do so, subject to
 * the following conditions:
 * 
 * 1. The code must retain the above copyright notice, this list of
 *    conditions and the following disclaimer.
 * 2. Any modifications must be clearly marked as such.
 * 3. Original authors' names are not deleted.
 * 4. The authors' names are not used to endorse or promote products
 *    derived from this software without specific prior written
 *    permission.
 *
 * DFKI GMBH AND THE CONTRIBUTORS TO THIS WORK DISCLAIM ALL WARRANTIES WITH
 * REGARD TO THIS SOFTWARE, INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS, IN NO EVENT SHALL DFKI GMBH NOR THE
 * CONTRIBUTORS BE LIABLE FOR ANY SPECIAL, INDIRECT OR CONSEQUENTIAL
 * DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR
 * PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS
 * ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF
 * THIS SOFTWARE.
 */
package marytts.modules;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.StringTokenizer;
import java.util.TreeMap;

import marytts.datatypes.MaryData;
import marytts.datatypes.MaryDataType;
import marytts.datatypes.MaryXML;
import marytts.fst.FSTLookup;
import marytts.modules.InternalModule;
import marytts.modules.phonemiser.AllophoneSet;
import marytts.modules.phonemiser.TrainedLTS;
import marytts.server.MaryProperties;
import marytts.util.MaryUtils;
import marytts.util.dom.MaryDomUtils;
import marytts.util.dom.NameNodeFilter;

import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.traversal.DocumentTraversal;
import org.w3c.dom.traversal.NodeFilter;
import org.w3c.dom.traversal.NodeIterator;


/**
 * The phonemiser module -- java implementation.
 *
 * @author Marc Schr&ouml;der, Sathish
 */

public class JPhonemiser extends InternalModule
{

    public Map<String, List<String>> userdict;
    public FSTLookup lexicon;
    protected TrainedLTS lts;

    protected AllophoneSet allophoneSet;

    public JPhonemiser(String propertyPrefix)
    throws Exception
    {
        this(MaryProperties.needFilename(propertyPrefix+"allophoneset"),
                MaryProperties.getFilename(propertyPrefix+"userdict"),
                MaryProperties.needFilename(propertyPrefix+"lexicon"),
                MaryProperties.needFilename(propertyPrefix+"lettertosound"));
    }
    
    
    /**
     * Constructor providing the individual filenames of files that are required.
     * @param allophonesFilename
     * @param userdictFilename
     * @param lexiconFilename
     * @param ltsFilename
     * @throws Exception
     */
    public JPhonemiser(String allophonesFilename, String userdictFilename, String lexiconFilename, String ltsFilename)
    throws Exception
    {
        super("JPhonemiser", MaryDataType.PARTSOFSPEECH, MaryDataType.PHONEMES,
                AllophoneSet.getAllophoneSet(allophonesFilename).getLocale());
        allophoneSet = AllophoneSet.getAllophoneSet(allophonesFilename);
        // userdict is optional
        if (userdictFilename != null)
            userdict = readLexicon(userdictFilename);
        lexicon = new FSTLookup(lexiconFilename);
        lts = new TrainedLTS(allophoneSet, ltsFilename);
    }
    
    
    public MaryData process(MaryData d)
        throws Exception
    {
        Document doc = d.getDocument();
        NodeIterator it = MaryDomUtils.createNodeIterator(doc, doc, MaryXML.TOKEN);
        Element t = null;
        while ((t = (Element) it.nextNode()) != null) {
                String text;
                
                // Do not touch tokens for which a transcription is already
                // given (exception: transcription contains a '*' character:
                if (t.hasAttribute("ph") &&
                    !t.getAttribute("ph").contains("*")) {
                    continue;
                }
                if (t.hasAttribute("sounds_like"))
                    text = t.getAttribute("sounds_like");
                else
                    text = MaryDomUtils.tokenText(t);
                
                String pos = null;
                // use part-of-speech if available
                if (t.hasAttribute("pos")){
                    pos = t.getAttribute("pos");
                }
                
                if (text != null && !text.equals("")) {
                    // If text consists of several parts (e.g., because that was
                    // inserted into the sounds_like attribute), each part
                    // is transcribed separately.
                    StringBuilder ph = new StringBuilder();
                    String g2pMethod = null;
                    StringTokenizer st = new StringTokenizer(text, " -");
                    while (st.hasMoreTokens()) {
                        String graph = st.nextToken();
                        StringBuffer helper = new StringBuffer();
                        String phon = phonemise(graph, pos, helper);
                        if (ph.length() == 0) { // first part
                            // The g2pMethod of the combined beast is
                            // the g2pMethod of the first constituant.
                            g2pMethod = helper.toString();
                            ph.append(phon);
                        } else { // following parts
                            ph.append(" - ");
                            // Reduce primary to secondary stress:
                            ph.append(phon.replace('\'', ','));
                       }
                    }
                    
                    if (ph != null && ph.length() > 0) {
                        setPh(t, ph.toString());
                        t.setAttribute("g2p_method", g2pMethod.toString());
                    }
            }
        }
        MaryData result = new MaryData(outputType(), d.getLocale());
        result.setDocument(doc);
        return result;
    }

    /**
     * Phonemise the word text. This starts with a simple lexicon lookup,
     * followed by some heuristics, and finally applies letter-to-sound rules
     * if nothing else was successful. 
     * 
     * @param text the textual (graphemic) form of a word.
     * @param pos the part-of-speech of the word
     * @param g2pMethod This is an awkward way to return a second
     * String parameter via a StringBuffer. If a phonemisation of the text is
     * found, this parameter will be filled with the method of phonemisation
     * ("lexicon", ... "rules"). 
     * @return a phonemisation of the text if one can be generated, or
     * null if no phonemisation method was successful.
     */
    public String phonemise(String text, String pos, StringBuffer g2pMethod)
    {
        // First, try a simple userdict and lexicon lookup:

        String result = userdictLookup(text, pos);
        if (result != null) {
            g2pMethod.append("userdict");
            return result;
        }
        
        result = lexiconLookup(text, pos);
        if (result != null) {
            g2pMethod.append("lexicon");
            return result;
        }

        // Lookup attempts failed. Try normalising exotic letters
        // (diacritics on vowels, etc.), look up again:
        String normalised = MaryUtils.normaliseUnicodeLetters(text, getLocale());
        if (!normalised.equals(text)) {
            result = userdictLookup(normalised, pos);
            if (result != null) {
                g2pMethod.append("userdict");
                return result;
            }
            result = lexiconLookup(normalised, pos);
            if (result != null) {
                g2pMethod.append("lexicon");
                return result;
            }
        }
           
        // Cannot find it in the lexicon -- apply letter-to-sound rules
        // to the normalised form

        String phonemes = lts.predictPronunciation(text);
        result = lts.syllabify(phonemes);
        if (result != null) {
            g2pMethod.append("rules");
            return result;
        }

        return null;
    }
        
    
    
    /**
     * Look a given text up in the (standard) lexicon. part-of-speech is used 
     * in case of ambiguity.
     * 
     * @param text
     * @param pos
     * @return
     */
    protected String lexiconLookup(String text, String pos)
    {
        if (text == null || text.length() == 0) return null;
        String[] entries;
        entries = lexiconLookupPrimitive(text, pos);
        // If entry is not found directly, try the following changes:
        // - lowercase the word
        // - all lowercase but first uppercase
        if (entries.length  == 0) {
            text = text.toLowerCase(getLocale());
            entries = lexiconLookupPrimitive(text, pos);
        }
        if (entries.length  == 0) {
            text = text.substring(0,1).toUpperCase(getLocale()) + text.substring(1);
            entries = lexiconLookupPrimitive(text, pos);
         }
         
         if (entries.length  == 0) return null;
         return entries[0];
    }

    private String[] lexiconLookupPrimitive(String text, String pos) {
        String[] entries;
        if (pos != null) { // look for pos-specific version first
            entries = lexicon.lookup(text+pos);
            if (entries.length == 0) {  // not found -- lookup without pos
                entries = lexicon.lookup(text);
            }
        } else {
            entries = lexicon.lookup(text);
        }
        return entries;
    }
    
    /**
     * look a given text up in the userdict. part-of-speech is used 
     * in case of ambiguity.
     * 
     * @param text
     * @param pos
     * @return
     */
    protected String userdictLookup(String text, String pos)
    {
        if (userdict == null || text == null || text.length() == 0) return null;
        List<String> entries = userdict.get(text);
        // If entry is not found directly, try the following changes:
        // - lowercase the word
        // - all lowercase but first uppercase
        if (entries  == null) {
            text = text.toLowerCase(getLocale());
            entries = userdict.get(text);
         }
         if (entries == null) {
             text = text.substring(0,1).toUpperCase(getLocale()) + text.substring(1);
             entries = userdict.get(text);
         }
         
         if (entries == null) return null;

         String transcr = null;
         for (String entry : entries) {
             String[] parts = entry.split("\\|");
             transcr = parts[0];
             if (parts.length > 1) {
                 StringTokenizer tokenizer = new StringTokenizer(entry);
                 while (tokenizer.hasMoreTokens()) {
                     String onePos = tokenizer.nextToken();
                     if (pos.equals(onePos)) return transcr; // found
                 }
             }
         }
         // no match of POS: return last entry
         return transcr;
    }    
    

    
    /**
     * Read a lexicon. Lines must have the format
     * 
     * graphemestring | phonemestring | optional-parts-of-speech
     * 
     * The pos-item is optional. Different pos's belonging to one grapheme
     * chain may be separated by whitespace
     * 
     * 
     * @param lexiconFilename
     * @return
     */
    protected Map<String, List<String>> readLexicon(String lexiconFilename)
    throws IOException
    {
        String line;
        Map<String,List<String>> fLexicon = new HashMap<String,List<String>>();

        BufferedReader lexiconFile = new BufferedReader(new InputStreamReader(new FileInputStream(lexiconFilename), "UTF-8"));
        while ((line = lexiconFile.readLine()) != null) {
            // Ignore empty lines and comments:
            if (line.trim().equals("") || line.startsWith("#"))
                continue;

            String[] lineParts = line.split("\\s*\\|\\s*");
            String graphStr = lineParts[0];
            String phonStr = lineParts[1];
            try {
                allophoneSet.splitIntoAllophones(phonStr);
            } catch (RuntimeException re) {
                logger.warn("Lexicon '"+lexiconFilename+"': invalid entry for '"+graphStr+"'", re);
            }
            String phonPosStr = phonStr;
            if (lineParts.length > 2){
                String pos = lineParts[2];
                if (!pos.trim().equals(""))
                    phonPosStr += "|" + pos;
            }

            List<String> transcriptions = fLexicon.get(graphStr);
            if  (null == transcriptions) {
                transcriptions = new ArrayList<String>();
                fLexicon.put(graphStr, transcriptions);
            }
            transcriptions.add(phonPosStr);
        }
        lexiconFile.close();
        return fLexicon; 
    }

    
    protected void setPh(Element t, String ph)
    {
        if (!t.getTagName().equals(MaryXML.TOKEN))
            throw new DOMException(DOMException.INVALID_ACCESS_ERR,
                                   "Only t elements allowed, received " +
                                   t.getTagName() + ".");
        if (t.hasAttribute("ph")) {
            String prevPh = t.getAttribute("ph");
            // In previous sampa, replace star with sampa:
            String newPh = prevPh.replaceFirst("\\*", ph);
            t.setAttribute("ph", newPh);
        } else {
            t.setAttribute("ph", ph);
        }
    }

}
