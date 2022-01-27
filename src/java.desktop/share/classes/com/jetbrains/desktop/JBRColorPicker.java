package com.jetbrains.desktop;

import com.jetbrains.desktop.JBRColorPickerListener;
import com.jetbrains.desktop.JBRLowLevelMouse;
import com.jetbrains.desktop.JBRLowLevelMouseListener;

import java.awt.Color;
import java.util.HashSet;

public class JBRColorPicker {
    private static HashSet<JBRColorPickerListener> listeners = new HashSet<JBRColorPickerListener>();
    private static Color last_color;
    private static JBRColorPicker.LowLeveMouseListener lowLeveMouseListener = new JBRColorPicker.LowLeveMouseListener();

    public static void start() {
        last_color = getPixelColorUnderCursor();
        JBRLowLevelMouse.registerListener(lowLeveMouseListener);
        JBRLowLevelMouse.startListening();
    }

    public static boolean registerListener(JBRColorPickerListener listener) {
        return listeners.add(listener);
    }

    public static boolean unregisterListener(JBRColorPickerListener listener) {
        return listeners.remove(listener);
    }

    private static void close() {
        JBRLowLevelMouse.unregisterListener(lowLeveMouseListener);
        JBRLowLevelMouse.stopListening();
        notifyClosed(last_color);
    }

    private static native Color getPixelColor(int x, int y);

    private static native Color getPixelColorUnderCursor();

    private static void notifyColorChanged(Color color) {
        if (color == last_color) {
            return;
        }
        last_color = color;
        for (JBRColorPickerListener listener : listeners) {
            listener.colorChanged(color);
        }
    }

    private static void notifyClosed(Color color) {
        for (JBRColorPickerListener listener : listeners) {
            listener.closed(color);
        }
    }

    private static class LowLeveMouseListener implements JBRLowLevelMouseListener {
        LowLeveMouseListener() {}

        @Override
        public void mouseClicked(int x, int y) {
            JBRColorPicker.last_color = JBRColorPicker.getPixelColor(x, y);
            JBRColorPicker.close();
        }

        @Override
        public void mouseMoved(int x, int y) {
            Color color = JBRColorPicker.getPixelColor(x, y);
            if (!JBRColorPicker.last_color.equals(color)) {
                JBRColorPicker.notifyColorChanged(color);
                JBRColorPicker.last_color = color;
            }
        }
    }
}
