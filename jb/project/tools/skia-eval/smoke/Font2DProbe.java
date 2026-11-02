import java.awt.Font; import java.lang.reflect.Method;
public class Font2DProbe { public static void main(String[] a) throws Exception {
  Method m = Class.forName("sun.font.FontUtilities").getMethod("getFont2D", Font.class);
  for (String fam : new String[]{"Inter", "JetBrains Mono", "Fira Code", "Helvetica Neue", "Monaco", "Dialog"}) {
    Font f = new Font(fam, Font.PLAIN, 13); Object f2d = m.invoke(null, f);
    System.out.printf("%-16s -> %-28s %s%n", fam, f2d.getClass().getSimpleName(), f.getFontName());
  } } }
