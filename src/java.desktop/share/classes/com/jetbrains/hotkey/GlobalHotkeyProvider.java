package com.jetbrains.hotkey;

/**
 * This interface is implemented by platform-specific service providers.
 */
public interface GlobalHotkeyProvider {
    /**
     * Registers a callback handler for a hotkey. Upon error or if a hotkey is already registered, returns false.
     * Otherwise, returns true.
     */
    boolean tryRegisterHotkey(Hotkey hotkey, HotkeyListener listener);

    /**
     * Unregisters the specified hotkey. Does nothing if a hotkey is not registered.
     */
    void unregisterHotkey(Hotkey hotkey);
}
