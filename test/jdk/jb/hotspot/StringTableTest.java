/**
 * @test
 * @run main/othervm --add-opens java.base/java.lang=ALL-UNNAMED StringTableTest
 */

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public class StringTableTest {
    public static void main(String[] args) throws Exception {
        Field f = String.class.getDeclaredField("value");
        f.setAccessible(true);
        f.set("s1".intern(), f.get("s2"));
        for (int i = 0; i < 4_000_000; i++) {
            ("s_" + i).intern();
        }
    }
}

