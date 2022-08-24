package com.jetbrains.hotkey;

import java.util.ServiceLoader;

public class GlobalHotkeyService {
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

    private GlobalHotkeyProvider getLoader() {
        return loader.findFirst().orElseThrow(() ->
            new UnsupportedOperationException("Global hotkeys are not supported on this platform"));
    }

    public boolean tryRegisterHotkey(Hotkey hotkey, HotkeyListener listener) {
        return getLoader().tryRegisterHotkey(hotkey, listener);
    }

    public void unregisterHotkey(Hotkey hotkey) {
        getLoader().unregisterHotkey(hotkey);
    }
}
