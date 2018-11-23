package quality.util;

public class EnvUtil {
    public static native int setenv(String name, String value, int overwrite);
    public static native String getenv(String name);

    static {
        System.loadLibrary("env");
    }
}
