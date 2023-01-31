package sun.awt.event;

import java.lang.annotation.Native;

public class TouchEvent {
    @Native public static final int TOUCH_BEGIN = 2;
    @Native public static final int TOUCH_UPDATE = 3;
    @Native public static final int TOUCH_END = 4;

    /**
     * The allowable difference between coordinates of touch update events
     * in pixels
     */
    @Native public static final int CLICK_RADIUS = 10;

    /**
     * Stop owning touch processing after timeout
     * in ms
     */
    public static final long NO_UPDATE_TIMEOUT = 1000L;

    public final static String defaultTouchHandlingOption = "com.jetbrains.default.touchscreen.mode";

    @Native
    public final static boolean defaultTouchHandling = "true".equalsIgnoreCase(
            System.getProperty(defaultTouchHandlingOption, "false"));
}
