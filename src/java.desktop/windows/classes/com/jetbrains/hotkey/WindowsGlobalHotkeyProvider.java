package com.jetbrains.hotkey;

import java.util.*;

/**
 * Windows-specific global hotkey provider.
 *
 * <p>
 * Normally, only a single instance of this class should be alive (the one loaded by GlobalHotkeyService), but
 * nothing will break if more are created.
 * </p>
 *
 * <p>
 * Upon the first hotkey registration, an instance of this class creates a thread that will handle events regarding
 * the hotkeys. Hotkey bindings are identified using numeric IDs.
 * </p>
 */
public class WindowsGlobalHotkeyProvider implements GlobalHotkeyProvider, AutoCloseable {
    static {
        System.loadLibrary("globalhotkeys");
    }

    public WindowsGlobalHotkeyProvider() {}

    /**
     * Converts a modifier to bitmask for use inside the RegisterHotKey WinAPI call.
     */
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
    private final Set<Integer> freeIds = new HashSet<>();

    /**
     * Finds the next ID that isn't in use.
     */
    private int nextFreeId() {
        var id = freeIds.stream().findFirst();
        id.ifPresent(freeIds::remove);
        return id.orElse(hotkeys.size() + 1);
    }

    private boolean isHotkeyRegistered(Hotkey hotkey) {
        return hotkeys.containsKey(hotkey);
    }

    /**
     * Reserves the ID for the specified hotkey.
     */
    private int assignHotkeyId(Hotkey hotkey) {
        int id = nextFreeId();
        hotkeys.put(hotkey, id);
        return id;
    }

    /**
     * Releases the ID for the specified hotkey.
     */
    private void freeHotkey(Hotkey hotkey) {
        if (!isHotkeyRegistered(hotkey)) {
            return;
        }
        int id = hotkeys.get(hotkey);
        freeIds.add(id);
        hotkeys.remove(hotkey);
    }

    /**
     * A thread that calls GetMessage in a loop to check for incoming WM_HOTKEY events.
     * If this thread receives such an event, it calls an appropriate callback.
     */
    private Thread pollThread;

    /**
     * Opaque pointer to a C++ struct, containing data required by native code.
     */
    private long ctx;

    /**
     * Calls RegisterHotKey WinAPI function. If the calling thread is not pollThread, then this method
     * posts a message to pollThread, asking it to register the hotkey. This is because only the thread that
     * registers a hotkey can receive events regarding it.
     *
     * @param ctx Pointer to the context
     * @param id ID of the hotkey
     * @param keyCode Virtual key code
     * @param modifiers Modifier bitset as described in RegisterHotKey docs
     * @return boolean indicating the success of registering the hotkey
     */
    private native static boolean nativeRegisterHotkey(long ctx, int id, int keyCode, int modifiers);

    /**
     * Calls UnregisterHotKey WinAPI function. Just like the nativeRegisterHotkey method, ensures that
     * this function is called inside the pollThread.
     *
     * @param ctx Pointer to the context
     * @param id ID of the hotkey
     */
    private native static void nativeUnregisterHotkey(long ctx, int id);

    /**
     * Waits until a registered hotkey is triggered. Should only be called inside the pollThread.
     *
     * @param ctx Pointer to the context
     * @return ID of the triggered hotkey or 0 if interrupted.
     */
    private native static int nativePollHotkey(long ctx);

    /**
     * Creates the aforementioned opaque C++ context.
     *
     * @return Pointer to the context as a long integer
     */
    private native static long nativeCreateContext();

    /**
     * Destroys the context.
     *
     * @param ctx Pointer to the context
     */
    private native static void nativeDestroyContext(long ctx);

    /**
     * Initializes the context. Should only be called inside the pollThread.
     * @param ctx Pointer to the context
     */
    private native static void nativeInitContext(long ctx);


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
