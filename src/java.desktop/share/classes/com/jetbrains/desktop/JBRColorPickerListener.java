package com.jetbrains.desktop;

import java.awt.Color;

public interface JBRColorPickerListener {
    void colorChanged(Color color);
    void closed(Color color);
}
