package sun.awt.event;

import java.lang.annotation.Native;
import java.security.PrivilegedAction;

public class KeyEventProcessing {
    @Native
    public final static boolean useNationalLayouts = java.security.AccessController.doPrivileged((PrivilegedAction<Boolean>)()-> "true".equals(System.getProperty("com.sun.awt.use.national.layouts")));
}
