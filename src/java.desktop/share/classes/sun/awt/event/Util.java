package sun.awt.event;

import java.security.PrivilegedAction;

@SuppressWarnings("removal")
class Util {
    public static String getProperty(String option, String dflt) {
        return java.security.AccessController.doPrivileged(
                (PrivilegedAction<String>) () -> System.getProperty(option, dflt)
        );
    }
}
