package com.jetbrains.desktop;

import com.jetbrains.desktop.JBRLowLevelMouseListener;

import java.util.HashSet;

public class JBRLowLevelMouse {
    static HashSet<JBRLowLevelMouseListener> listeners = new HashSet<JBRLowLevelMouseListener>();

    public static native void startListening();
    public static native void stopListening();

    public static boolean registerListener(JBRLowLevelMouseListener listener) {
        return listeners.add(listener);
    }

    public static boolean unregisterListener(JBRLowLevelMouseListener listener) {
        return listeners.remove(listener);
    }

    private static void notifyMouseClicked(int x, int y) {
        for (JBRLowLevelMouseListener listener : listeners) {
            listener.mouseClicked(x, y);
        }
    }

    private static void notifyMouseMoved(int x, int y) {
        for (JBRLowLevelMouseListener listener : listeners) {
            listener.mouseMoved(x, y);
        }
    }
}
