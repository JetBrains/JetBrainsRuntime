package sun.awt.event;

import sun.awt.util.SystemInfo;

import java.lang.annotation.Native;
import java.security.PrivilegedAction;

public class KeyEventProcessing {
    // This property is used to emulate old behavior
    // when user should turn off national keycodes processing
    // "com.jetbrains.use.old.keyevent.processing"
    @Native
    public final static boolean useNationalLayouts = "true".equals(getProperty());

    public final static String VMOptionString = "com.sun.awt.use.national.layouts";

    private static String getProperty() {
        return java.security.AccessController.doPrivileged(
                (PrivilegedAction<String>)() -> System.getProperty(VMOptionString, getDefaultValue())
        );
    }

    private static String getDefaultValue() {
        if (SystemInfo.isWindows || SystemInfo.isMac) {
            return "true";
        } else {
            return "false";
        }
    }
}
