package java.awt.desktop;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

import java.awt.event.InputEvent;

/**
 * Provides info about system hotkeys
 */
public class SystemHotkey {
    private static final Map<Integer, String> ourCache = new HashMap<>();

    private boolean myEnabled;
    private int myKeyCode;
    private int myModifiers;
    private String myChar;
    private String mySource;
    private String myDescription;

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
    public static native List<SystemHotkey> readSystemHotkeys();

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
