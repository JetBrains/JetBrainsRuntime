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

import sun.java2d.SunGraphics2D;
import sun.java2d.pipe.PixelFillPipe;
import sun.java2d.pipe.PixelToParallelogramConverter;

/**
 * The raster surface's non-antialiased fill pipe: an integer {@code fillRect}
 * under an integer translation goes straight to the colour rectangle entry, as
 * the loops' {@code FillRect} does (the converter's double transforms and
 * normalization cost as much as the whole loops' call for the IDE's most
 * frequent operation); everything else is the converter's.
 */
final class SKRasterFillPipe implements PixelFillPipe {
    private final PixelToParallelogramConverter converter;

    SKRasterFillPipe(PixelToParallelogramConverter converter) {
        this.converter = converter;
    }

    @Override
    public void fillRect(SunGraphics2D sg2d, int x, int y, int width, int height) {
        if (width > 0 && height > 0 && sg2d.transformState <= SunGraphics2D.TRANSFORM_INT_TRANSLATE
                && SKRasterShapePipe.fillIntegerRect(sg2d, x + sg2d.transX, y + sg2d.transY, width, height)) {
            return;
        }
        converter.fillRect(sg2d, x, y, width, height);
    }

    @Override
    public void fillRoundRect(SunGraphics2D sg2d, int x, int y, int width, int height, int arcWidth, int arcHeight) {
        converter.fillRoundRect(sg2d, x, y, width, height, arcWidth, arcHeight);
    }

    @Override
    public void fillOval(SunGraphics2D sg2d, int x, int y, int width, int height) {
        converter.fillOval(sg2d, x, y, width, height);
    }

    @Override
    public void fillArc(SunGraphics2D sg2d, int x, int y, int width, int height, int startAngle, int arcAngle) {
        converter.fillArc(sg2d, x, y, width, height, startAngle, arcAngle);
    }

    @Override
    public void fillPolygon(SunGraphics2D sg2d, int[] xPoints, int[] yPoints, int nPoints) {
        converter.fillPolygon(sg2d, xPoints, yPoints, nPoints);
    }
}
