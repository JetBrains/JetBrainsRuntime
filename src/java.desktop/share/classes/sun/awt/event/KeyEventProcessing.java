package sun.awt.event;

import java.lang.annotation.Native;
import java.security.PrivilegedAction;

public class KeyEventProcessing {
    @Native
    public final static boolean useNationalLayoutsNonNull;

    // This property is used to emulate old behavior
    // when user should turn off national keycodes processing
    // "com.jetbrains.use.old.keyevent.processing"
    @Native
    public final static boolean useNationalLayouts;

    public final static String VMOptionString = "com.sun.awt.use.national.layouts";

    static {
        String propertyValue = getProperty();
        useNationalLayoutsNonNull = "true".equals(propertyValue);
        useNationalLayouts = useNationalLayoutsNonNull || propertyValue == null;
    }

    static String getProperty() {
        return java.security.AccessController.doPrivileged(
                (PrivilegedAction<String>)() -> System.getProperty(VMOptionString)
        );
    }
}
