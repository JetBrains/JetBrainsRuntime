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

package sun.font;

import sun.java2d.SurfaceData;

import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.image.Raster;

class ColorGlyphSurfaceData extends SurfaceData {
    ColorGlyphSurfaceData() {
        super(State.UNTRACKABLE);
        initOps();
    }

    private native void initOps();

    native void setCurrentGlyph(long imgPtr);

    @Override
    public SurfaceData getReplacement() {
        throw new UnsupportedOperationException();
    }

    @Override
    public GraphicsConfiguration getDeviceConfiguration() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Raster getRaster(int x, int y, int w, int h) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Rectangle getBounds() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object getDestination() {
        throw new UnsupportedOperationException();
    }
}
