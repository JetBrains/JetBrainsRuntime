/*
 * Copyright 2000-2023 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.jetbrains;

import java.awt.*;
import java.io.Serial;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

public interface FontExtensions {
    public static final int FEATURE_ON = 1;
    public static final int FEATURE_OFF = 0;

    /**
     * The list of all supported features. For feature's description look at
     * <a href=https://learn.microsoft.com/en-us/typography/opentype/spec/featurelist>documentation</a> <br>
     * The following features: KERN, LIGA, CALT are missing intentionally. These features will be added automatically
     * by adding {@link java.awt.font.TextAttribute} to {@link java.awt.Font}:
     * <ul>
     * <li>Attribute {@link java.awt.font.TextAttribute#KERNING} manages KERN feature</li>
     * <li>Attribute {@link java.awt.font.TextAttribute#LIGATURES} manages LIGA and CALT features</li>
     * </ul>
     */
    enum FeatureTag {
        AALT, ABVF, ABVM, ABVS, AFRC, AKHN, BLWF, BLWM, BLWS, CASE, CCMP, CFAR, CHWS, CJCT, CLIG, CPCT, CPSP, CSWH, CURS,
        CV01, CV02, CV03, CV04, CV05, CV06, CV07, CV08, CV09, CV10, CV11, CV12, CV13, CV14, CV15, CV16, CV17, CV18, CV19,
        CV20, CV21, CV22, CV23, CV24, CV25, CV26, CV27, CV28, CV29, CV30, CV31, CV32, CV33, CV34, CV35, CV36, CV37, CV38,
        CV39, CV40, CV41, CV42, CV43, CV44, CV45, CV46, CV47, CV48, CV49, CV50, CV51, CV52, CV53, CV54, CV55, CV56, CV57,
        CV58, CV59, CV60, CV61, CV62, CV63, CV64, CV65, CV66, CV67, CV68, CV69, CV70, CV71, CV72, CV73, CV74, CV75, CV76,
        CV77, CV78, CV79, CV80, CV81, CV82, CV83, CV84, CV85, CV86, CV87, CV88, CV89, CV90, CV91, CV92, CV93, CV94, CV95,
        CV96, CV97, CV98, CV99, C2PC, C2SC, DIST, DLIG, DNOM, DTLS, EXPT, FALT, FIN2, FIN3, FINA, FLAC, FRAC, FWID, HALF,
        HALN, HALT, HIST, HKNA, HLIG, HNGL, HOJO, HWID, INIT, ISOL, ITAL, JALT, JP78, JP83, JP90, JP04, LFBD, LJMO, LNUM,
        LOCL, LTRA, LTRM, MARK, MED2, MEDI, MGRK, MKMK, MSET, NALT, NLCK, NUKT, NUMR, ONUM, OPBD, ORDN, ORNM, PALT, PCAP,
        PKNA, PNUM, PREF, PRES, PSTF, PSTS, PWID, QWID, RAND, RCLT, RKRF, RLIG, RPHF, RTBD, RTLA, RTLM, RUBY, RVRN, SALT,
        SINF, SIZE, SMCP, SMPL, SS01, SS02, SS03, SS04, SS05, SS06, SS07, SS08, SS09, SS10, SS11, SS12, SS13, SS14, SS15,
        SS16, SS17, SS18, SS19, SS20, SSTY, STCH, SUBS, SUPS, SWSH, TITL, TJMO, TNAM, TNUM, TRAD, TWID, UNIC, VALT, VATU,
        VCHW, VERT, VHAL, VJMO, VKNA, VKRN, VPAL, VRT2, VRTR, ZERO;

        public String getName() {
            return toString().toLowerCase();
        }

        public static Optional<FeatureTag> getFeatureTag(String str) {
            try {
                return Optional.of(FeatureTag.valueOf(str.toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                return Optional.empty();
            }
        }
    }

    final class Features extends TreeMap<FeatureTag, Integer> {
        @Serial
        private static final long serialVersionUID = 1L;

        public Features(Map<FontExtensions.FeatureTag, Integer> map) {
            super(map);
        }

        public Features(FontExtensions.FeatureTag... features) {
            Arrays.stream(features).forEach(tag -> put(tag, FontExtensions.FEATURE_ON));
        }

        private TreeMap<String, Integer> getAsTreeMap() {
            TreeMap<String, Integer> res = new TreeMap<>();
            forEach((tag, value) -> res.put(tag.getName(), value));
            return res;
        }
    }

    /**
     * This method derives a new {@link java.awt.Font} object with certain set of {@link FeatureTag}
     * and correspoinding values
     *
     * @param font       basic font
     * @param features   set of OpenType's features wrapped inside {@link Features}
     */
    Font deriveFontWithFeatures(Font font, Features features);

    Dimension getSubpixelResolution();
}