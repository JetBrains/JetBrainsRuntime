/*
 * Copyright 2000-2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sun.java2d.loops;

import sun.font.GlyphList;
import sun.java2d.SunGraphics2D;
import sun.java2d.SurfaceData;
import sun.java2d.pipe.Region;

import java.awt.*;

public class DrawGlyphListColor extends GraphicsPrimitive {

    public final static String methodSignature = "DrawGlyphListColor(...)".toString();

    public final static int primTypeID = makePrimTypeID();

    public static DrawGlyphListColor locate(SurfaceType srctype,
                                            CompositeType comptype,
                                            SurfaceType dsttype)
    {
        return (DrawGlyphListColor)
            GraphicsPrimitiveMgr.locate(primTypeID,
                                        srctype, comptype, dsttype);
    }

    protected DrawGlyphListColor(SurfaceType srctype,
                                 CompositeType comptype,
                                 SurfaceType dsttype)
    {
        super(methodSignature, primTypeID, srctype, comptype, dsttype);
    }


    public void DrawGlyphListColor(SunGraphics2D sg2d, SurfaceData dest,
                                     GlyphList srcData, int fromGlyph, int toGlyph) {
        // actual implementation is in the subclass
    }

    // This instance is used only for lookup.
    static {
        GraphicsPrimitiveMgr.registerGeneral(
                                new DrawGlyphListColor(null, null, null));
    }

    public GraphicsPrimitive makePrimitive(SurfaceType srctype,
                                           CompositeType comptype,
                                           SurfaceType dsttype) {
        return new General(srctype, comptype, dsttype);
    }

    private static class General extends DrawGlyphListColor {
        private final Blit blit;

        public General(SurfaceType srctype,
                       CompositeType comptype,
                       SurfaceType dsttype)
        {
            super(srctype, comptype, dsttype);
            blit = Blit.locate(SurfaceType.IntArgbPre, CompositeType.SrcOverNoEa, dsttype);
        }

        public void DrawGlyphListColor(SunGraphics2D sg2d, SurfaceData dest,
                                  GlyphList gl, int fromGlyph, int toGlyph) {

            Region clip = sg2d.getCompClip();
            int cx1 = clip.getLoX();
            int cy1 = clip.getLoY();
            int cx2 = clip.getHiX();
            int cy2 = clip.getHiY();
            for (int i = fromGlyph; i < toGlyph; i++) {
                gl.setGlyphIndex(i);
                int metrics[] = gl.getMetrics();
                int x = metrics[0];
                int y = metrics[1];
                int w = metrics[2];
                int h = metrics[3];
                int gx1 = x;
                int gy1 = y;
                int gx2 = x + w;
                int gy2 = y + h;
                if (gx1 < cx1) gx1 = cx1;
                if (gy1 < cy1) gy1 = cy1;
                if (gx2 > cx2) gx2 = cx2;
                if (gy2 > cy2) gy2 = cy2;
                if (gx2 > gx1 && gy2 > gy1) {
                    blit.Blit(gl.getColorGlyphData(), dest, AlphaComposite.SrcOver, clip,
                            gx1 - x, gy1 - y, gx1, gy1, gx2 - gx1, gy2 - gy1);
                }
            }
        }
    }

    public GraphicsPrimitive traceWrap() {
        return new TraceDrawGlyphListColor(this);
    }

    private static class TraceDrawGlyphListColor extends DrawGlyphListColor {
        DrawGlyphListColor target;

        public TraceDrawGlyphListColor(DrawGlyphListColor target) {
            super(target.getSourceType(),
                  target.getCompositeType(),
                  target.getDestType());
            this.target = target;
        }

        public GraphicsPrimitive traceWrap() {
            return this;
        }

        public void DrawGlyphListColor(SunGraphics2D sg2d, SurfaceData dest,
                                  GlyphList glyphs, int fromGlyph, int toGlyph)
        {
            tracePrimitive(target);
            target.DrawGlyphListColor(sg2d, dest, glyphs, fromGlyph, toGlyph);
        }
    }
}
