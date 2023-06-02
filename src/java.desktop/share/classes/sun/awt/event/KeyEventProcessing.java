package sun.awt.event;

import sun.font.FontUtilities;

import java.lang.annotation.Native;

public class KeyEventProcessing {
    // This property is used to emulate old behavior
    // when user should turn off national keycodes processing
    // "com.jetbrains.use.old.keyevent.processing"
    public final static String useNationalLayoutsOption = "com.sun.awt.use.national.layouts";
    @Native
    public final static boolean useNationalLayouts = "true".equals(
            System.getProperty(useNationalLayoutsOption,
                FontUtilities.isMacOSX ? "true" : "false"));

    // Report dead keys (such as VK_DEAD_GRAVE) as normal keys (such as VK_BACK_QUOTE)
    public final static String reportDeadKeysAsNormalOption = "com.sun.awt.reportDeadKeysAsNormal";
    @Native
    public final static boolean reportDeadKeysAsNormal = "true".equals(
            System.getProperty(reportDeadKeysAsNormalOption,
                FontUtilities.isMacOSX ? "true" : "false"));

    // Used on windows to emulate latin OEM keys on cyrillic keyboards
    public final static String useLatinNonAlphaNumKeycodesOption = "com.sun.awt.useLatinNonAlphaNumKeycodes";
    @Native
    public final static boolean useLatinNonAlphaNumKeycodes = "true".equals(
            System.getProperty(useLatinNonAlphaNumKeycodesOption, "false"));
}
