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
 * Provides info about system hotkeys.
 * Deprecated, use JBR SystemShortcuts API instead.
 */
public class SystemHotkey extends AWTKeyStroke {
    private static final long serialVersionUID = 4340163519946285280L;
    private static final PlatformLogger ourLog = PlatformLogger.getLogger(java.awt.desktop.SystemHotkey.class.getName());
    private static final Map<Integer, String> ourCodeDescriptionCache = new HashMap<>();
    private static volatile Runnable changeEventHandler = null;

    private final int myNativeKeyCode;
    private final String myId;
    private final String myDescription;
    
    SystemHotkey(char keyChar, int javaKeyCode, int javaModifiers, String description, int nativeKeyCode) {
        this(keyChar, javaKeyCode, javaModifiers, description, nativeKeyCode, description);
    }

    SystemHotkey(char keyChar, int javaKeyCode, int javaModifiers, String description, int nativeKeyCode, String id) {
        super(keyChar, javaKeyCode, javaModifiers, true);
        this.myNativeKeyCode = nativeKeyCode;
        this.myId = id;
        this.myDescription = description;
    }
    
    public String toString() {
      return String.format("id='%s' desc='%s' char=%s mod='%s' nativeKeyCode=0x%X ['%s'] javaKeyCode=0x%X",
              String.valueOf(myId), String.valueOf(myDescription), String.valueOf(getKeyChar()), InputEvent.getModifiersExText(getModifiers()),
              myNativeKeyCode, getOsxKeyCodeDescription(myNativeKeyCode), getKeyCode());
    }

    /**
     * Gets unique hotkey identifier
     * @return hotkey identifier
     */
    public String getId() {
        return myId;
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

    /**
     * Set the event handler, which is fired when the user changes a system shortcut.
     * @param changeEventHandler event handler
     */
    public static void setChangeEventHandler(Runnable changeEventHandler) {
        SystemHotkey.changeEventHandler = changeEventHandler; // store to volatile
    }

    private static void onChange() {
        // called from native code from any thread
        var handler = changeEventHandler; // load from volatile
        if (handler != null) {
            EventQueue.invokeLater(handler);
        }
    }
}

class SystemHotkeyReader {
    private final List<SystemHotkey> myResult = new ArrayList<>();

    void add(int keyCode, String keyChar, int modifiers, String desc, String id) {
        myResult.add(new SystemHotkey(
                keyChar == null || keyChar.isEmpty() ? KeyEvent.CHAR_UNDEFINED : keyChar.charAt(0),
                keyCode == -1 ? KeyEvent.VK_UNDEFINED : osx2java(keyCode),
                modifiers, desc, keyCode, (id == null) ? desc : id
        ));
    }

    List<SystemHotkey> getResult() { return myResult; }

    native void readSystemHotkeys();

    private static native int osx2java(int osxCode);
}