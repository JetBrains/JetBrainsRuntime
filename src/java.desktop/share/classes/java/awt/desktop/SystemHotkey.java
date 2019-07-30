package java.awt.desktop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import sun.util.logging.PlatformLogger;

import java.awt.event.InputEvent;

/**
 * Provides info about system hotkeys
 */
public class SystemHotkey {
    private static final PlatformLogger ourLog = PlatformLogger.getLogger(SystemHotkey.class.getName());
    private static final Map<Integer, String> ourCache = new HashMap<>();

    private final boolean myEnabled;
    private final int myKeyCode;
    private final int myModifiers;
    private final String myChar;
    private final String mySource;
    private final String myDescription;

    SystemHotkey(boolean enabled, int keyCode, int modifiers, String keyChar, String source, String description) {
        this.myEnabled = enabled;
        this.myKeyCode = keyCode;
        this.myModifiers = modifiers;
        this.myChar = keyChar;
        this.mySource = source;
        this.myDescription = description;
    }

    public String toString() {
        return String.format("enable=%s vkey=0x%X ['%s'] mod='%s' char=%s src=%s desc='%s'",
                String.valueOf(myEnabled),
                myKeyCode, getVirtualCodeDescription(myKeyCode), InputEvent.getModifiersExText(myModifiers),
                String.valueOf(myChar), String.valueOf(mySource), String.valueOf(myDescription));
    }

    /**
     * Checks whether hotkey is enabled
     * @return true when hotkey is active
     */
    public boolean isEnabled() {
        return myEnabled;
    }

    /**
     * Gets virtual keycode of hotkey
     * @return virtual keycode
     */
    public int getKeyCode() {
        return myKeyCode;
    }

    /**
     * Gets modifiers of hotkey
     * @return modifiers of hotkey
     */
    public int getModifiers() {
        return myModifiers;
    }

    /**
     * Gets char (or string description) of hotkey
     * @return char (or string description) of hotkey
     */
    public String getChar() {
        return myChar;
    }

    /**
     * Gets source description
     * @return source description
     */
    public String getSource() {
        return mySource;
    }

    /**
     * Gets hotkey description
     * @return hotkey description
     */
    public String getDescription() {
        return myDescription;
    }

    /**
     * Reads all registered hotkeys
     * @return list of all registered hotkeys
     */
    public static List<SystemHotkey> readSystemHotkeys() {
        final SystemHotkeyReader reader = new SystemHotkeyReader();
        reader.readSystemHotkeys();
        return reader.getResult();
    }

    /**
     * Gets virtual code description
     * @param code (virtual keycode)
     * @return virtual keycode description
     */
    public static String getVirtualCodeDescription(int code) {
        return ourCache.computeIfAbsent(code, (c)->{
            final String desc = virtualCodeDescription(c);
            return desc == null || desc.isEmpty() ? String.format("Unknown_key_code_0x%X", c) : desc;
        });
    }

    private static native String virtualCodeDescription(int code);
}

class SystemHotkeyReader {
    private final List<SystemHotkey> myResult = new ArrayList<>();

    void add(boolean enabled, int keyCode, String keyChar, int modifiers, String source, String desc) {
        myResult.add(new SystemHotkey(enabled, keyCode, modifiers, keyChar, source, desc));
    }

    List<SystemHotkey> getResult() { return myResult; }

    native void readSystemHotkeys();
}