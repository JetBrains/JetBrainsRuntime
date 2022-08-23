package java.awt.desktop;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Collection;

import sun.util.logging.PlatformLogger;

import javax.swing.*;
import java.awt.event.KeyEvent;
import java.awt.event.InputEvent;

/**
 * Provides info about system hotkeys
 */
public class SystemHotkey extends AWTKeyStroke {
    private static final long serialVersionUID = -6593119259058157651L;
    private static final PlatformLogger ourLog = PlatformLogger.getLogger(java.awt.desktop.SystemHotkey.class.getName());
    private static final Map<Integer, String> ourCodeDescriptionCache = new HashMap<>();

    private final int myNativeKeyCode;
    private final String myDescription;
    
    SystemHotkey(char keyChar, int javaKeyCode, int javaModifiers, String description, int nativeKeyCode) {
      super(keyChar, javaKeyCode, javaModifiers, true);
      this.myNativeKeyCode = nativeKeyCode;
      this.myDescription = description;
    }
    
    public String toString() {
      return String.format("desc='%s' char=%s mod='%s' nativeKeyCode=0x%X ['%s'] javaKeyCode=0x%X",
              String.valueOf(myDescription), String.valueOf(getKeyChar()), InputEvent.getModifiersExText(getModifiers()),
              myNativeKeyCode, getOsxKeyCodeDescription(myNativeKeyCode), getKeyCode());
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
    private static String getOsxKeyCodeDescription(int code) {
        return ourCodeDescriptionCache.computeIfAbsent(code, (c)->{
            final String desc = osxKeyCodeDescription(c);
            return desc == null || desc.isEmpty() ? String.format("Unknown_key_code_0x%X", c) : desc;
        });
    }

    private static native String osxKeyCodeDescription(int osxCode);
}

class SystemHotkeyReader {
    private final List<SystemHotkey> myResult = new ArrayList<>();

    void add(int keyCode, String keyChar, int modifiers, String desc) {
        myResult.add(new SystemHotkey(
                keyChar == null || keyChar.isEmpty() ? KeyEvent.CHAR_UNDEFINED : keyChar.charAt(0),
                keyCode == -1 ? KeyEvent.VK_UNDEFINED : osx2java(keyCode),
                modifiers, desc, keyCode
        ));
    }

    List<SystemHotkey> getResult() { return myResult; }

    native void readSystemHotkeys();

    private static native int osx2java(int osxCode);
}