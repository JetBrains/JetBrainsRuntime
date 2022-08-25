package com.jetbrains.hotkey;

import java.util.ServiceLoader;

/**
 * This service loads platform-specific service providers for global hotkeys using ServiceLoader.
 */
public final class GlobalHotkeyService {
    private static GlobalHotkeyService service;
    private final ServiceLoader<GlobalHotkeyProvider> loader;

    private GlobalHotkeyService() {
        loader = ServiceLoader.load(GlobalHotkeyProvider.class);
    }

    public synchronized static GlobalHotkeyService getInstance() {
        if (service == null) {
            service = new GlobalHotkeyService();
        }
        return service;
    }

    private GlobalHotkeyProvider getProvider() {
        return loader.findFirst().orElseThrow(() ->
            new UnsupportedOperationException("Global hotkeys are not supported on this platform"));
    }

    public boolean tryRegisterHotkey(Hotkey hotkey, HotkeyListener listener) {
        if (hotkey == null) {
            throw new IllegalArgumentException("hotkey");
        }

        if (listener == null) {
            throw new IllegalArgumentException("listener");
        }

        return getProvider().tryRegisterHotkey(hotkey, listener);
    }

    public void unregisterHotkey(Hotkey hotkey) {
        if (hotkey == null) {
            throw new IllegalArgumentException("hotkey");
        }

        getProvider().unregisterHotkey(hotkey);
    }
}
