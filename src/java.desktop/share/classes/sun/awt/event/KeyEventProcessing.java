package sun.awt.event;

import sun.awt.util.SystemInfo;

import java.lang.annotation.Native;
import java.security.PrivilegedAction;

public class KeyEventProcessing {
    // This property is used to emulate old behavior
    // when user should turn off national keycodes processing
    // "com.jetbrains.use.old.keyevent.processing"
    public final static String useNationalLayoutsOption = "com.sun.awt.use.national.layouts";
    @Native
    public final static boolean useNationalLayouts = "true".equals(
            getProperty(useNationalLayoutsOption, SystemInfo.isMac ? "true" : "false"));

    // Used on windows to emulate latin OEM keys on cyrillic keyboards
    public final static String useLatinNonAlphaNumKeycodesOption = "com.sun.awt.useLatinNonAlphaNumKeycodes";
    @Native
    public final static boolean useLatinNonAlphaNumKeycodes = "true".equals(
            getProperty(useLatinNonAlphaNumKeycodesOption, "false"));

    private static String getProperty(String option, String dflt) {
        return java.security.AccessController.doPrivileged(
                (PrivilegedAction<String>) () -> System.getProperty(option, dflt)
        );
    }
}
