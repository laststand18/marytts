/**
 * Copyright 2000-2006 DFKI GmbH.
 * All Rights Reserved.  Use is subject to license terms.
 *
 * This file is part of MARY TTS.
 *
 * MARY TTS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package marytts.language.en;

import java.util.Locale;

import marytts.datatypes.MaryDataType;
import marytts.language.en_US.datatypes.USEnglishDataTypes;
import marytts.modules.Utt2XMLBase;

import org.w3c.dom.Element;

import com.sun.speech.freetts.Item;
import com.sun.speech.freetts.Relation;
import com.sun.speech.freetts.Utterance;



/**
 * Convert FreeTTS utterances into MaryXML format
 * (Tokens, English).
 *
 * @author Marc Schr&ouml;der
 */

public class Utt2XMLTokensEn extends Utt2XMLBase
{
    public Utt2XMLTokensEn()
    {
        super("Utt2XML TokensEn",
              USEnglishDataTypes.FREETTS_TOKENS,
              MaryDataType.TOKENS,
              Locale.ENGLISH);
    }

    /**
     * Depending on the data type, find the right information in the utterance
     * and insert it into the sentence.
     */
    protected void fillSentence(Element sentence, Utterance utterance)
    {
        Relation tokenRelation = utterance.getRelation(Relation.TOKEN);
        if (tokenRelation == null) return;
        Item tokenItem = tokenRelation.getHead();
        while (tokenItem != null) {
            insertToken(tokenItem, sentence);
            tokenItem = tokenItem.getNext();
        }
    }




}

