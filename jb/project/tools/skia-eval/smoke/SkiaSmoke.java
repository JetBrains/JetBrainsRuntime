import java.lang.reflect.Method;

/** Smoke test: load libawt_skia through SKEnvironment and print the context description. */
public class SkiaSmoke {
    public static void main(String[] args) throws Exception {
        Class<?> env = Class.forName("sun.java2d.skia.SKEnvironment");
        Method enabled = env.getMethod("isSkiaEnabled");
        Method desc = env.getMethod("getDescription");
        System.out.println("isSkiaEnabled = " + enabled.invoke(null));
        System.out.println("description   = " + desc.invoke(null));
    }
}
