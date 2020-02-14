package sun.awt;

import java.util.concurrent.Callable;

/**
 * TODO: remove it in JBR 202
 *
 * @deprecated
 * @see AWTThreading
 */
public class InvokeOnToolkitHelper {
    public static <T> T invokeAndBlock(Callable<T> callable) {
        return AWTThreading.executeWaitToolkit(callable);
    }
}
