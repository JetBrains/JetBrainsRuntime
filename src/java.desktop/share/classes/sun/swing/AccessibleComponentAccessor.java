package sun.swing;

import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import java.util.List;
import java.util.function.Function;
import java.util.concurrent.CopyOnWriteArrayList;

public class AccessibleComponentAccessor {
    private static final List<Function<AccessibleContext, Object>> accessors = new CopyOnWriteArrayList<>();

    public static void addAccessor(Function<AccessibleContext, Object> accessor) {
        accessors.add(accessor);
    }

    public static Accessible getAccessible(AccessibleContext context) {
        for (Function<AccessibleContext, Object> accessor : accessors) {
            Object o = accessor.apply(context);
            if (o instanceof Accessible accessible) {
                return accessible;
            }
        }
        return null;
    }
}
