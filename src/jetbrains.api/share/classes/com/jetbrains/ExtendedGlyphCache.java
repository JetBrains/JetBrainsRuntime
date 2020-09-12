package com.jetbrains;

import java.awt.*;

public interface ExtendedGlyphCache extends JBRService {


    interface V1 extends ExtendedGlyphCache {

        Dimension getSubpixelResolution();

    }


}
