/*
 * Copyright 2026 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sun.java2d.skia;

import java.awt.font.GlyphVector;
import java.text.AttributedCharacterIterator;
import java.util.concurrent.ConcurrentHashMap;

import sun.java2d.SunGraphics2D;
import sun.java2d.pipe.AttributedTextPipe;
import sun.java2d.pipe.TextPipe;

/**
 * Text on the Skia GPU pipe under a gradient or texture paint or an AlphaComposite rule other than SrcOver (states
 * whose glyph images the GPU text pipe does not composite): text in strikes Skia's text path draws goes to it — the
 * text path, the shaper and the paragraph engine draw with the native paint, its shader and blend — and everything
 * else to the loops' choice for the state, with the whole call. One instance per wrapped pipe.
 */
final class SKPaintTextPipe implements TextPipe, AttributedTextPipe {

    private static final ConcurrentHashMap<TextPipe, SKPaintTextPipe> instances = new ConcurrentHashMap<>();

    private final TextPipe skia, loops;

    private SKPaintTextPipe(TextPipe skia, TextPipe loops) {
        this.skia = skia;
        this.loops = loops;
    }

    static SKPaintTextPipe of(TextPipe skia, TextPipe loops) {
        return loops instanceof SKPaintTextPipe p ? p : instances.computeIfAbsent(loops, l -> new SKPaintTextPipe(skia, l));
    }

    @Override
    public void drawString(SunGraphics2D sg2d, String s, double x, double y) {
        (SKGlyphContexts.mayDraw(sg2d.getFontInfo().fontStrike) ? skia : loops).drawString(sg2d, s, x, y);
    }

    @Override
    public void drawChars(SunGraphics2D sg2d, char[] data, int offset, int length, int x, int y) {
        (SKGlyphContexts.mayDraw(sg2d.getFontInfo().fontStrike) ? skia : loops).drawChars(sg2d, data, offset, length, x, y);
    }

    @Override
    public void drawGlyphVector(SunGraphics2D sg2d, GlyphVector gv, float x, float y) {
        boolean bySkia = SKGlyphContexts.mayDraw(sg2d.getGVFontInfo(gv.getFont(), gv.getFontRenderContext()).fontStrike);
        (bySkia ? skia : loops).drawGlyphVector(sg2d, gv, x, y);
    }

    /** The paragraph route decides itself (every run's strike on the Skia scaler); false leaves it to TextLayout. */
    @Override
    public boolean drawString(SunGraphics2D sg2d, AttributedCharacterIterator it, float x, float y) {
        return skia instanceof AttributedTextPipe p && p.drawString(sg2d, it, x, y);
    }
}
