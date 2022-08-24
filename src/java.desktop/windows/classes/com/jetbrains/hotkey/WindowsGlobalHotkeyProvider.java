package com.jetbrains.hotkey;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class WindowsGlobalHotkeyProvider implements GlobalHotkeyProvider, AutoCloseable {
    static {
        System.loadLibrary("globalhotkeys");
    }

    public WindowsGlobalHotkeyProvider() {}

    private static int modifierToBitmask(Hotkey.Modifier modifier) {
        return switch (modifier) {
            case ALT     -> 0x0001; // MOD_ALT
            case CONTROL -> 0x0002; // MOD_CONTROL
            case SHIFT   -> 0x0004; // MOD_SHIFT
            case SUPER   -> 0x0008; // MOD_WIN
        };
    }

    private final Map<Hotkey, Integer> hotkeys = new HashMap<>();
    private final Map<Integer, HotkeyListener> listeners = new HashMap<>();
    private final Set<Integer> freeIds = new TreeSet<>();

    private int nextFreeId() {
        var id = freeIds.stream().findFirst();
        id.ifPresent(freeIds::remove);
        return id.orElse(hotkeys.size() + 1);
    }

    private boolean isHotkeyRegistered(Hotkey hotkey) {
        return hotkeys.containsKey(hotkey);
    }

    private int assignHotkeyId(Hotkey hotkey) {
        int id = nextFreeId();
        hotkeys.put(hotkey, id);
        return id;
    }

    private void freeHotkey(Hotkey hotkey) {
        if (!isHotkeyRegistered(hotkey)) {
            return;
        }
        int id = hotkeys.get(hotkey);
        freeIds.add(id);
        hotkeys.remove(hotkey);
    }

    private native static boolean nativeRegisterHotkey(long ctx, int id, int keyCode, int modifiers);
    private native static void nativeUnregisterHotkey(long ctx, int id);
    private native static int nativePollHotkey(long ctx);
    private native static long nativeCreateContext();
    private native static long nativeDestroyContext(long ctx);
    private native static void nativeInitContext(long ctx);

    private Thread pollThread;
    private long ctx;

    private void startPollThread() {
        ctx = nativeCreateContext();
        pollThread = new Thread(() -> {
            nativeInitContext(ctx);
            while (!Thread.currentThread().isInterrupted()) {
                int id = nativePollHotkey(ctx);
                if (id <= 0) {
                    continue;
                }

                HotkeyListener listener;

                synchronized (listeners) {
                    listener = listeners.get(id);
                }

                if (listener != null) {
                    listener.onHotkeyPressed();
                }
            }
        });
        pollThread.start();
    }

    @Override
    public synchronized boolean tryRegisterHotkey(Hotkey hotkey, HotkeyListener listener) {
        if (isHotkeyRegistered(hotkey)) {
            return false;
        }

        if (pollThread == null) {
            startPollThread();
        }

        int modifiers = 0;
        for (var modifier : hotkey.modifiers()) {
            modifiers |= modifierToBitmask(modifier);
        }

        int id = assignHotkeyId(hotkey);
        boolean result = nativeRegisterHotkey(ctx, id, hotkey.keyCode(), modifiers);

        if (result) {
            synchronized (listeners) {
                listeners.put(id, listener);
            }
        } else {
            freeHotkey(hotkey);
        }

        return result;
    }

    @Override
    public synchronized void unregisterHotkey(Hotkey hotkey) {
        if (!isHotkeyRegistered(hotkey)) {
            return;
        }

        nativeUnregisterHotkey(ctx, hotkeys.get(hotkey));
        freeHotkey(hotkey);
    }

    @Override
    public synchronized void close() {
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }

        if (ctx != 0) {
            nativeDestroyContext(ctx);
            ctx = 0;
        }
    }
}
