/*
 * Copyright 2026 JetBrains s.r.o.
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

package sun.font;

import java.awt.FontFormatException;

import sun.java2d.Disposer;
import sun.java2d.DisposerRecord;

/**
 * A TrueType font of Font.createFont on macOS: characters it lacks fall back by CoreText's cascade from the font, as
 * the platform's text falls back from a font (CTFontCreateForString with a CTFont of the same file; CTLine and
 * TextKit 2 draw so), through its composite (CCascadeCompositeFont) on every route — glyph vectors, layouts, drawString
 * and the string metrics alike. The JDK's TrueType fonts otherwise draw the missing glyph.
 */
public final class CCascadeTrueTypeFont extends TrueTypeFont implements FontSubstitution {
    private CompositeFont composite;
    private long cascadeBase;
    private boolean cascadeBaseMade;

    public CCascadeTrueTypeFont(String path, int index) throws FontFormatException {
        super(path, null, index, true);
    }

    @Override
    public synchronized CompositeFont getCompositeFont2D() {
        if (composite == null) {
            composite = new CCascadeCompositeFont(this);
        }
        return composite;
    }

    @Override
    public boolean substitutesWhenDrawn() {
        return true;
    }

    /**
     * The CTFont of this font's file and index that CoreText's cascade starts from, made on first use and released
     * when the font is collected; 0 when CoreText cannot read the file.
     */
    synchronized long cascadeBase() {
        if (!cascadeBaseMade) {
            cascadeBaseMade = true;
            cascadeBase = CCascadeGlyphMapper.nativeCreateBase(platName, fontIndex);
            if (cascadeBase != 0) {
                Disposer.addObjectRecord(this, new BaseDisposer(cascadeBase));
            }
        }
        return cascadeBase;
    }

    private record BaseDisposer(long base) implements DisposerRecord {
        @Override
        public void dispose() {
            CCascadeGlyphMapper.nativeReleaseBase(base);
        }
    }
}
