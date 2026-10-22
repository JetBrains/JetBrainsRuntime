import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Text rendering scene for the Skia backend comparison: keywords (bold), comments (italic),
 * strings, numbers, annotations, generics, lambdas and ligature sequences (->, !=, >=, ::, //),
 * a line with CJK (日本語 中文 한국어) and an emoji 🚀, long enough lines to soft-wrap, and a
 * method that code vision annotates with usages. The caret stays on line 20, selection on 24-27.
 */
public class Hello {
    private static final Map<String, Integer> WEIGHTS = Map.of("thin", 100, "regular", 400, "bold", 700);

    /** A point; the record keyword and its components are highlighted differently. */
    record Point(int x, int y) implements Comparable<Point> {
        @Override
        public int compareTo(Point other) {
            return Integer.compare(x * x + y * y, other.x * other.x + other.y * other.y);
        }
    }

    @SuppressWarnings("unused")
    public static void main(String[] args) {
        List<Point> points = List.of(new Point(1, 2), new Point(3, 4), new Point(5, 6), new Point(-7, 8));
        String joined = points.stream().map(p -> p.x() + "/" + p.y()).collect(Collectors.joining(", "));
        System.out.println("Hello from the Skia backend: " + joined + " — ffi fl Wq AVfi Tj 0123 {}[]()");
        for (int i = 0; i < 10; i++) {
            if (i != 3 && i >= 1 && i <= 8) {
                System.out.printf("%d squared is %d%n", i, i * i);   // -> ligatures: != >= <= -> => :: //
            }
        }
        Function<String, Integer> weight = name -> WEIGHTS.getOrDefault(Objects.requireNonNull(name), 400);
        double ratio = 1.0 / 3.0, scale = 1.25f, big = 1_000_000L * 2.5e-3;
        char ch = 'ä'; boolean ok = ratio < scale || !Double.isNaN(big);
        String text = "日本語のテキスト 中文文本 한국어 텍스트 Ünïcödé 🚀 ∑∂∆ → ⇒ ≠ ≤ ≥";
        List<String> words = new ArrayList<>(List.of(text.split(" ")));
        words.removeIf(String::isBlank);
        System.out.println(weight.apply("bold") + " " + words.size() + " " + ch + " " + ok + " " + describe(points));
    }

    /** Called from main and from the test; code vision shows "1 usage" above it. */
    static String describe(List<? extends Comparable<?>> items) {
        StringBuilder sb = new StringBuilder("[");
        for (Object item : items) {
            sb.append(item).append(item == items.get(items.size() - 1) ? "" : ", ");
        }
        return sb.append(']').toString();
    }
}
