import java.awt.AWTException;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.io.File;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Drives the ten IDE scenes of the text quality plan (instrument 3b) with java.awt.Robot against
 * an IDEA that is already up (run-ide.sh with --seed, project open, frame maximized) and captures
 * each at device resolution.
 * <pre>
 *   java IdeScenes --out <dir> [--scenes editor,selection,tree,menu,tooltip,settings,search,terminal,markdown,presentation]
 * </pre>
 * Scenes are driven by keyboard shortcuts only (the mouse is used for the tooltip scene), so they
 * work at any scale as long as the seed configuration holds; each capture is preceded by a settle
 * time long enough for the frame to repaint under every arm.
 */
public class IdeScenes {

    static Robot robot;
    static File out;
    static int settleMs = 2500;
    static int idePid = 0;   // the isolated IDE's java process: keys go nowhere else
    static File fgScript;

    public static void main(String[] args) throws Exception {
        List<String> scenes = List.of("editor", "selection", "tree", "menu", "tooltip", "settings", "search", "terminal",
                                      "markdown", "presentation");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> out = new File(args[++i]);
                case "--scenes" -> scenes = List.of(args[++i].split(","));
                case "--settle" -> settleMs = Integer.parseInt(args[++i]);
                case "--pid" -> idePid = Integer.parseInt(args[++i].replaceAll("[^0-9]", ""));   // shells leave CR or BOM bytes
                case "--fg" -> fgScript = new File(args[++i]);
                default -> throw new IllegalArgumentException(args[i]);
            }
        }
        out.mkdirs();
        if (idePid == 0 || fgScript == null) {
            throw new IllegalStateException("--pid and --fg are required: keys must only go to the isolated IDE");
        }
        robot = new Robot();
        robot.setAutoDelay(40);
        requireIdeInFront();
        // leave whatever popup the start left behind, drop the start-up notifications,
        // and open the file once before the scenes: the first navigation after start
        // can be swallowed while the editor is still restoring
        key(KeyEvent.VK_ESCAPE); key(KeyEvent.VK_ESCAPE);
        sleep(500);
        action("Clear All Notifications");
        sleep(800);
        openFile("Hello.java");
        sleep(1500);
        openFile("Hello.java");
        for (String scene : scenes) {
            requireIdeInFront();
            // notifications arrive at their own pace during the first minute: drop them before every scene
            action("Clear All Notifications");
            sleep(600);
            key(KeyEvent.VK_ESCAPE);
            switch (scene) {
                case "editor" -> {
                    openFile("Hello.java");
                    chord(KeyEvent.VK_CONTROL, KeyEvent.VK_HOME);
                    capture("editor");
                }
                case "selection" -> {
                    openFile("Hello.java");
                    chord(KeyEvent.VK_CONTROL, KeyEvent.VK_HOME);
                    for (int i = 0; i < 23; i++) key(KeyEvent.VK_DOWN);
                    chord(KeyEvent.VK_SHIFT, KeyEvent.VK_DOWN); chord(KeyEvent.VK_SHIFT, KeyEvent.VK_DOWN);
                    chord(KeyEvent.VK_SHIFT, KeyEvent.VK_DOWN); chord(KeyEvent.VK_SHIFT, KeyEvent.VK_END);
                    capture("selection");
                    key(KeyEvent.VK_ESCAPE);
                }
                case "tree" -> {
                    openFile("Hello.java");
                    chord(KeyEvent.VK_ALT, KeyEvent.VK_1);   // focus the project tree
                    sleep(400);
                    capture("tree");
                    key(KeyEvent.VK_ESCAPE);
                }
                case "menu" -> {
                    openFile("Hello.java");
                    key(KeyEvent.VK_ALT);
                    sleep(300);
                    key(KeyEvent.VK_ENTER);   // the hamburger / first menu
                    sleep(400);
                    capture("menu");
                    key(KeyEvent.VK_ESCAPE); key(KeyEvent.VK_ESCAPE);
                }
                case "tooltip" -> {
                    openFile("Hello.java");
                    chord(KeyEvent.VK_CONTROL, KeyEvent.VK_HOME);
                    // the documentation popup over a searched symbol stands in for a tooltip: deterministic text
                    chord(KeyEvent.VK_CONTROL, KeyEvent.VK_F);
                    sleep(600);
                    type("Collectors.joining");
                    sleep(800);
                    key(KeyEvent.VK_ESCAPE);   // closes the search bar, the caret stays on the match
                    sleep(400);
                    chord(KeyEvent.VK_CONTROL, KeyEvent.VK_Q);
                    sleep(1500);
                    capture("tooltip");
                    key(KeyEvent.VK_ESCAPE);
                }
                case "settings" -> {
                    chord(KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_S);
                    sleep(1500);
                    type("font");
                    sleep(800);
                    capture("settings");
                    key(KeyEvent.VK_ESCAPE); sleep(300); key(KeyEvent.VK_ESCAPE);
                }
                case "search" -> {
                    // the class tab of Search Everywhere: its list is deterministic, the
                    // all tab ranks by history and model and differs between runs
                    chord(KeyEvent.VK_CONTROL, KeyEvent.VK_N);
                    sleep(800);
                    type("Point");
                    sleep(1500);
                    capture("search");
                    key(KeyEvent.VK_ESCAPE);
                }
                case "terminal" -> {
                    chord(KeyEvent.VK_ALT, KeyEvent.VK_F12);
                    sleep(2500);
                    type("echo text rendering scene 0123456789 ffi fl AVfi");   // fixed output: dir prints timestamps
                    key(KeyEvent.VK_ENTER);
                    sleep(1200);
                    capture("terminal");
                    chord(KeyEvent.VK_ALT, KeyEvent.VK_F12);   // hide
                    sleep(300);
                    key(KeyEvent.VK_ESCAPE);
                }
                case "markdown" -> {
                    openFile("README.md");
                    sleep(1500);
                    capture("markdown");
                }
                case "presentation" -> {
                    openFile("Hello.java");
                    chord(KeyEvent.VK_CONTROL, KeyEvent.VK_HOME);
                    action("Enter Presentation Mode");
                    sleep(2500);
                    capture("presentation");
                    action("Exit Presentation Mode");
                    sleep(2000);
                }
                default -> System.err.println("unknown scene " + scene);
            }
        }
        System.out.println("scenes done: " + out);
    }

    /** Brings the isolated IDE to the front and aborts if any other window owns the keyboard. */
    static void requireIdeInFront() throws Exception {
        String fg = foreground(true);
        if (!fg.startsWith(idePid + "|")) {
            throw new IllegalStateException("the foreground window is not the isolated IDE (pid " + idePid + "): " + fg);
        }
    }

    static String foreground(boolean activate) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                                                             "-File", fgScript.getAbsolutePath()));
        if (activate) { cmd.add("-Activate"); cmd.add(Integer.toString(idePid)); }
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String outText = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        String[] lines = outText.split("\\R");
        return lines[lines.length - 1];
    }

    /** Focus the project tree: if a popup fails to open, typed text goes to its speed search, never into a file. */
    static void parkFocus() {
        key(KeyEvent.VK_ESCAPE);
        sleep(200);
        chord(KeyEvent.VK_ALT, KeyEvent.VK_1);
        sleep(500);
    }

    static void openFile(String name) throws Exception {
        popup(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_N);
        type(name);
        sleep(900);
        key(KeyEvent.VK_ENTER);
        sleep(1200);
        key(KeyEvent.VK_ESCAPE);   // any popup the navigation left
        sleep(200);
    }

    /** Runs an action by name through Search Everywhere's action tab. */
    static void action(String name) throws Exception {
        popup(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_A);
        type(name);
        sleep(1200);
        key(KeyEvent.VK_ENTER);
    }

    /**
     * Opens a Search Everywhere style popup and returns only once it is visibly up: the popup
     * appears asynchronously, and while the IDE is busy (a start-up freeze of a few seconds is
     * normal on the VM) a fixed wait let the typed text reach the editor and modify the source.
     * The popup is centred near the top of the frame; a change in that band is the signal.
     */
    static void popup(int... chord) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            parkFocus();
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            Rectangle band = new Rectangle(screen.width * 3 / 10, screen.height / 12, screen.width * 4 / 10, screen.height / 4);
            BufferedImage before = robot.createScreenCapture(band);
            requireIdeInFront();   // the user's IDE took the foreground back between the start check and a chord once (2026-10-21)
            chord(chord);
            for (int waited = 0; waited < 8000; waited += 200) {
                sleep(200);
                if (changed(before, robot.createScreenCapture(band))) {
                    sleep(600);   // the field and the list settle
                    requireIdeInFront();   // and again before anything is typed into the popup
                    return;
                }
            }
            System.err.println("popup did not open within 8 s (attempt " + (attempt + 1) + ")");
            try {   // what was on the screen instead: kept next to the scenes for diagnosis
                ImageIO.write(robot.createScreenCapture(new Rectangle(screen)), "png", new File(out, "popup-miss-" + (attempt + 1) + ".png"));
            } catch (Exception e) {
                System.err.println("could not save the miss: " + e);
            }
            key(KeyEvent.VK_ESCAPE);
            sleep(1000);
        }
        throw new IllegalStateException("popup did not open: no key is typed into an editor");
    }

    /** More than a sliver of the band changed (a caret blink or a status widget would not count). */
    static boolean changed(BufferedImage a, BufferedImage b) {
        int n = 0;
        for (int y = 0; y < a.getHeight(); y += 2) {
            for (int x = 0; x < a.getWidth(); x += 2) {
                if (a.getRGB(x, y) != b.getRGB(x, y) && ++n > 2000) return true;
            }
        }
        return false;
    }

    static void capture(String scene) throws Exception {
        sleep(settleMs);
        String fg = foreground(false);
        if (!fg.startsWith(idePid + "|")) {
            throw new IllegalStateException("lost the isolated IDE before capturing " + scene + ": " + fg);
        }
        Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        MultiResolutionImage mri = robot.createMultiResolutionScreenCapture(screen);
        BufferedImage best = null;
        for (Image v : mri.getResolutionVariants()) {
            BufferedImage b = toBuffered(v);
            if (best == null || b.getWidth() > best.getWidth()) best = b;
        }
        ImageIO.write(best, "png", new File(out, scene + ".png"));
        System.out.println(scene + ": " + best.getWidth() + "x" + best.getHeight());
    }

    static BufferedImage toBuffered(Image img) {
        if (img instanceof BufferedImage b) return b;
        BufferedImage b = new BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_RGB);
        b.getGraphics().drawImage(img, 0, 0, null);
        return b;
    }

    static void type(String s) {
        for (char c : s.toCharArray()) {
            int code = KeyEvent.getExtendedKeyCodeForChar(c);
            boolean upper = Character.isUpperCase(c);
            if (upper) robot.keyPress(KeyEvent.VK_SHIFT);
            robot.keyPress(code); robot.keyRelease(code);
            if (upper) robot.keyRelease(KeyEvent.VK_SHIFT);
        }
    }

    static void key(int code) { robot.keyPress(code); robot.keyRelease(code); }

    static void chord(int... codes) {
        for (int c : codes) robot.keyPress(c);
        for (int i = codes.length - 1; i >= 0; i--) robot.keyRelease(codes[i]);
        sleep(150);
    }

    static void sleep(int ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }
}
