package com.jetbrains.hotkey;

/**
 * Type of global hotkey callbacks
 */
@FunctionalInterface
public interface HotkeyListener {
    void onHotkeyPressed();
}
