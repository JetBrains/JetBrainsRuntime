package com.jetbrains.impl;

import com.jetbrains.ExtendedGlyphCache;

import java.awt.*;
import sun.font.FontUtilities;

public class ExtendedGlyphCacheImpl implements ExtendedGlyphCache.V1 {

    @Override
    public Dimension getSubpixelResolution() {
        return FontUtilities.subpixelResolution;
    }

}
