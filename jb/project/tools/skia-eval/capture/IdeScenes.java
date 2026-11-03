import java.awt.AWTException;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.io.File;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Drives the IDE scenes of the text quality plan (instrument 3b) against an IDEA that is already up
 * (run-ide.sh with --seed, project open) and captures each at device resolution. The steps are
 * commands to the scene-driver plugin in the isolated IDE (scene-driver/SceneService.java, over the
 * built-in server: open a file, set a selection, invoke an action by id, open a settings page, Search
 * Everywhere with a query, a terminal command, a key to the focus owner) — no keystrokes typed into
 * popups, no focus guessing (2026-11-02; before, the Robot typed action names into Search Everywhere
 * and a scene was lost to the terminal keeping Escape). The Robot only captures the screen. Five scenes since 2026-11-02 (user: most of the former ten showed the same frame,
 * some were unstable): {@code workspace} — the project tree, the editor with a four-line selection
 * and the documentation popup, the terminal with a line of output — then {@code menu},
 * {@code settings}, {@code search} and {@code markdown}; presentation mode is gone.
 * <pre>
 *   java IdeScenes --out <dir> --pid <ide pid> --fg <fg script> [--helper <fgfast>] --port <built-in server port>
 *                  [--scenes workspace,menu,settings,search,markdown] [--themes light,dark] [--window 1728x980] [--scale 2]
 *                  [--quiesce <ms>] [--settle <ms>] [--exit]
 * </pre>
 * The frame is placed at the usable area's origin at a fixed logical size (default 1728x980: the
 * common ground of a 16-inch MacBook panel's default 2x mode — 1728 wide, 980 left under the menu
 * bar and a dock — and a 1080p screen under its taskbar; the same on every platform and scale),
 * and every capture is the frame's own bounds with its native decorations — no taskbar, menu bar or
 * desktop. The scale is the display's (the wrapper asks the user to set it in the OS, ScreenBounds).
 * Scenes are driven by keyboard shortcuts only, so they work at any scale as long as the seed
 * configuration holds. Waits are screen-driven (2026-11-02; a scene took 15 s of fixed sleeps
 * before): a popup is typed into once the screen has changed and stopped changing after the key
 * that opens it, asynchronous content (search results, the documentation popup, the terminal) is
 * awaited the same way, and each capture is preceded by a settle that waits for the screen to
 * stop changing. The old fixed sleeps remain as upper bounds. {@code --quiesce <ms>} waits at the
 * start until the screen has been unchanged for that long (the IDE's post-indexing work: the
 * inspection pass, the widgets), at most 150 s.
 */
public class IdeScenes {

    static Robot robot;
    static File out;
    static int settleMs = 2500;
    static int quiesceMs = 0;    // --quiesce: required unchanged span at the start, 0 = none
    static int windowW = 1728, windowH = 980;   // --window: the frame's logical size
    static double requiredScale = 2;             // --scale: the display scale the scenes are for (picks the display)
    static Rectangle frame;      // the frame's bounds in logical pixels, as placed; every capture and sample is this
    static int idePid = 0;   // the isolated IDE's java process: keys go nowhere else
    static File fgScript;
    static File helperBinary;    // --helper: the built fgfast (macOS); default: next to the fg script
    static int port = 0;         // --port: the isolated IDE's built-in server (idea.log: "built-in server started, port N")
    static List<String> themes = List.of();   // --themes light,dark: captured in one session, the UI theme switched between them
    static String scaleTag = "2";             // --scale, for the per-theme directory names (<out>/<theme>-<scale>x[-<placement>])
    static String placement = "";             // --placement fractional: the IDE's glyph placement setting the captures were taken with (Windows; a set dimension of the viewer)
    static boolean exitAtEnd = false;         // --exit: a clean shutdown of the IDE after the scenes (for the system snapshot)
    static final java.util.Map<String, long[]> profileTotals = new java.util.TreeMap<>();   // primitive → {nanos, calls}, printed at the end
    static void profile(String what, long startNanos) {
        long[] t = profileTotals.computeIfAbsent(what, k -> new long[2]);
        t[0] += System.nanoTime() - startNanos; t[1]++;
    }
    static final java.util.concurrent.ExecutorService writer = java.util.concurrent.Executors.newSingleThreadExecutor();   // PNG encoding off the scene thread
    static final boolean MAC = System.getProperty("os.name").toLowerCase().contains("mac");

    public static void main(String[] args) throws Exception {
        List<String> scenes = List.of("workspace", "settings", "markdown");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> out = new File(args[++i]);
                case "--scenes" -> scenes = List.of(args[++i].split(","));
                case "--settle" -> settleMs = Integer.parseInt(args[++i]);
                case "--quiesce" -> quiesceMs = Integer.parseInt(args[++i]);
                case "--scale" -> { scaleTag = args[++i]; requiredScale = Double.parseDouble(scaleTag); }
                case "--placement" -> placement = args[++i];
                case "--window" -> { String[] wh = args[++i].toLowerCase().split("x"); windowW = Integer.parseInt(wh[0]); windowH = Integer.parseInt(wh[1]); }
                case "--pid" -> idePid = Integer.parseInt(args[++i].replaceAll("[^0-9]", ""));   // shells leave CR or BOM bytes
                case "--fg" -> fgScript = new File(args[++i]);
                case "--helper" -> helperBinary = new File(args[++i]);
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--themes" -> themes = List.of(args[++i].split(","));
                case "--exit" -> exitAtEnd = true;
                default -> throw new IllegalArgumentException(args[i]);
            }
        }
        out.mkdirs();
        if (idePid == 0 || fgScript == null || port == 0) {
            throw new IllegalStateException("--pid, --fg and --port are required: the commands must go to the isolated IDE");
        }
        robot = new Robot(device());
        requireIdeInFront();
        ide("cmd=front");
        placeFrame();
        final long t0 = System.nanoTime();
        if (quiesceMs > 0) {
            quiesce(quiesceMs, 150_000);
            System.out.printf("quiet after %.1f s%n", (System.nanoTime() - t0) / 1e9);
        }
        // the start-up notifications dropped once (balloons are off in the seed; the bell's badge would
        // differ between arms otherwise), the file shown
        ide("cmd=action&id=ClearAllNotifications");
        expect(() -> ide("cmd=open&file=src/Hello.java"), 1500);
        final File armDir = out;
        List<String> themeList = themes.isEmpty() ? java.util.Collections.singletonList(null) : themes;
        for (int t = 0; t < themeList.size(); t++) {
          String theme = themeList.get(t);
          if (theme != null) {
            out = new File(armDir, theme + "-" + scaleTag + "x" + (placement.isEmpty() ? "" : "-" + placement));
            out.mkdirs();
            if (t > 0) {
                // the UI theme and colour scheme as the seed of that theme sets them; the frame repaints in steps
                String laf = theme.equals("dark") ? "ExperimentalDark" : "ExperimentalLight", scheme = theme.equals("dark") ? "Dark" : "Light";
                expect(() -> ide("cmd=theme&laf=" + laf + "&scheme=" + scheme), 3000);
                awaitQuiet(300, 3000);
                expect(() -> ide("cmd=open&file=src/Hello.java"), 1500);
            }
          }
          // the project tree: pinned at one width (the full Skia route laid it out wider than the others,
          // 2026-11-03) and fully expanded, so every scene shows the same panel of file icons and names
          retry(() -> ide("cmd=toolwindow&id=Project&show=true&width=" + TREE_WIDTH + "&expand=true"), 10, 400);
          awaitQuiet(150, 1000);
          for (String scene : scenes) {
            final long ts = System.nanoTime();
            requireIdeInFront();
            switch (scene) {
                case "workspace" -> {
                    ide("cmd=open&file=src/Hello.java");
                    awaitQuiet(100, 800);   // the editor opens asynchronously: the first selection found no editor on three of twelve arms (2026-11-03)
                    // the editor's find-and-replace panel with the selected word, Comparable (line 18), as its
                    // search text (its occurrences highlighted); then a four-line selection, lines 15-18, ending at
                    // the caret on Comparable, whose documentation the quick documentation action shows; the
                    // file's first lines — the comment with the CJK, emoji and ligature samples — stay in view
                    // the word selected: the find panel takes it as its text. Retried with the IDE re-fronted and the file
                    // re-opened: the markdown preview's cef_server helper starts about now and can take the activation,
                    // after which the editor opens but cannot be focused or selected ("Cannot focus editor"; F5 at 1x, 2026-11-03)
                    retry(() -> { requireIdeInFront(); ide("cmd=open&file=src/Hello.java"); return ide("cmd=select&from=18:%40Comparable&to=18:52&caret=to"); }, 20, 400);   // the word selected: the find panel takes it as its text
                    expect(() -> ide("cmd=action&id=Replace"), 1500);
                    awaitQuiet(150, 1000);
                    // the focus back from the search field to the editor, then the selection (retried: the editor is
                    // not the selected one for an instant after the find panel took the focus)
                    retry(() -> { ide("cmd=open&file=src/Hello.java"); return ide("cmd=select&from=15:-1&to=18:%40Comparable&caret=to"); }, 10, 300);
                    expect(() -> ide("cmd=action&id=QuickJavaDoc"), 1500);
                    capture("workspace");
                    ide("cmd=key&code=ESCAPE");   // the popup
                    awaitQuiet(100, 400);
                    ide("cmd=select&clear=true");
                    ide("cmd=key&code=ESCAPE");   // the find panel
                    awaitQuiet(100, 400);
                }
                case "menu" -> {
                    expect(() -> ide("cmd=menu"), 800);   // the main menu as the hamburger button's popup
                    capture("menu");
                    ide("cmd=key&code=ESCAPE");
                    awaitQuiet(100, 400);
                }
                case "settings" -> {
                    expect(() -> ide("cmd=settings&class=com.intellij.ide.ui.AppearanceConfigurable"), 1500);   // Appearance & Behavior > Appearance (by class: its id is not registered)
                    awaitQuiet(150, 1000);
                    capture("settings");
                    ide("cmd=key&code=ESCAPE");   // closes the dialog
                    awaitQuiet(100, 800);
                }
                case "search" -> {
                    // the class tab of Search Everywhere: its list is deterministic, the
                    // all tab ranks by history and model and differs between runs
                    expect(() -> ide("cmd=search&tab=ClassSearchEverywhereContributor&text=Point"), 1500);
                    awaitQuiet(150, 1000);
                    capture("search");
                    ide("cmd=key&code=ESCAPE");
                    awaitQuiet(100, 400);
                }
                case "markdown" -> {
                    // the terminal at the bottom with a line of fixed output: the shell first, the command once
                    // the prompt is up; then the README with its preview
                    expect(() -> ide("cmd=terminal"), 2500);
                    awaitQuiet(150, 1500);
                    expect(() -> ide("cmd=terminalExec&line=" + (MAC ? "ls" : "echo text rendering scene 0123456789 ffi fl AVfi")), 1200);
                    expect(() -> ide("cmd=open&file=README.md"), 1500);
                    awaitQuiet(250, 1500);   // the preview renders after the editor
                    capture("markdown");
                    expect(() -> ide("cmd=toolwindow&id=Terminal&show=false&close=true"), 800);   // the shell too: the next theme opens a fresh one
                }
                default -> System.err.println("unknown scene " + scene);
            }
            System.out.printf("  %s%s took %.1f s%n", theme != null ? theme + " " : "", scene, (System.nanoTime() - ts) / 1e9);
          }
        }
        long tw = System.nanoTime();
        writer.shutdown();
        writer.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS);
        profile("png-flush", tw);
        System.out.printf("scenes done in %.1f s: %s%n", (System.nanoTime() - t0) / 1e9, armDir);
        StringBuilder sb = new StringBuilder("profile (s / calls):");
        for (var e : profileTotals.entrySet()) sb.append(String.format(" %s %.1f/%d", e.getKey(), e.getValue()[0] / 1e9, e.getValue()[1]));
        System.out.println(sb);
        if (exitAtEnd) {
            System.out.println("exit requested: " + ide("cmd=exit"));
        }
    }

    static final java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();

    /** One command to the scene-driver plugin in the isolated IDE; its answer, "ok" or the error (which aborts the run). */
    /** A command retried while the IDE is not ready for it (an editor still opening): up to attempts, delay ms apart. */
    static final int TREE_WIDTH = 330;   // the project tool window's width in logical pixels

    static String retry(java.util.concurrent.Callable<String> command, int attempts, long delay) throws Exception {
        for (int i = 1; ; i++) {
            try {
                return command.call();
            } catch (IllegalStateException e) {
                if (i >= attempts) throw e;
                System.err.println("  retrying after: " + e.getMessage());
                try { Thread.sleep(delay); } catch (InterruptedException ie) { throw new IllegalStateException(ie); }
            }
        }
    }

    static String ide(String query) {
        try {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/api/scene?" + query.replace(" ", "%20")))   // a terminal line with spaces (the Windows one) must be a valid URI
                    .timeout(java.time.Duration.ofSeconds(20)).GET().build();
            long t = System.nanoTime();
            java.net.http.HttpResponse<String> response = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            profile("ide", t);
            String body = response.body().trim();
            if (response.statusCode() != 200) throw new IllegalStateException(query + " -> HTTP " + response.statusCode() + " " + body.replaceAll("<[^>]*>", " ").trim());
            if (body.startsWith("error")) throw new IllegalStateException(query + " -> " + body);
            return body;
        } catch (java.io.IOException | InterruptedException e) {
            throw new IllegalStateException(query + " failed: " + e, e);
        }
    }

    /**
     * Brings the isolated IDE to the front and aborts if any other application owns the keyboard
     * (the activation from a background process worked in every probe on macOS; the focus losses
     * seen on 2026-10-22 came from the Shift+Cmd+A Service, see action()).
     */
    static void requireIdeInFront() throws Exception {
        String fg = foreground(false);
        if (fg.startsWith(idePid + "|")) return;   // already in front: the activation's 300 ms wait is saved
        fg = foreground(true);
        if (!fg.startsWith(idePid + "|")) {
            throw new IllegalStateException("the foreground window is not the isolated IDE (pid " + idePid + "): " + fg);
        }
    }

    // One long-lived helper answers every foreground query in milliseconds: on macOS fgfast --serve
    // (next to fg.sh; the osascript script took 0.6 s per call, 2026-10-26), on Windows fg.ps1 -Serve
    // (a PowerShell start-up took 0.5-1 s per call, 2026-11-02). A scene makes a dozen queries.
    static Process fgServer;
    static java.io.BufferedReader fgIn;
    static java.io.Writer fgOut;

    /** One request line to the long-lived helper, its one answer line; null without a helper. */
    static synchronized String helper(String request) throws Exception {
        if (!startHelper()) return null;
        fgOut.write(request + "\n");
        fgOut.flush();
        String line = fgIn.readLine();
        if (line == null) { fgServer.destroy(); fgServer = null; return null; }
        return line.trim();
    }

    static boolean startHelper() throws Exception {
        if (fgServer != null && fgServer.isAlive()) return true;
        List<String> cmd;
        if (MAC) {
            File helper = helperBinary != null ? helperBinary : new File(fgScript.getParentFile(), "fgfast");
            if (!helper.canExecute()) return false;
            cmd = List.of(helper.getAbsolutePath(), "--serve");
        } else {
            cmd = List.of("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", fgScript.getAbsolutePath(), "-Serve");
        }
        fgServer = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        fgIn = new java.io.BufferedReader(new java.io.InputStreamReader(fgServer.getInputStream()));
        fgOut = new java.io.OutputStreamWriter(fgServer.getOutputStream());
        return true;
    }

    static String foregroundFast(boolean activate) throws Exception {
        return helper(activate ? "activate " + idePid : "query");
    }

    static String foreground(boolean activate) throws Exception {
        String fast = foregroundFast(activate);
        if (fast != null) return fast;
        List<String> cmd = MAC
                ? new java.util.ArrayList<>(List.of("bash", fgScript.getAbsolutePath()))
                : new java.util.ArrayList<>(List.of("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                                                    "-File", fgScript.getAbsolutePath()));
        if (activate) { cmd.add(MAC ? "--activate" : "-Activate"); cmd.add(Integer.toString(idePid)); }
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String outText = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        String[] lines = outText.split("\\R");
        return lines[lines.length - 1];
    }

    /**
     * The display the scenes run on: the first one at the requested scale with room for the frame
     * (ScreenBounds.suitable, the wrapper waited for it), else the default one. The Robot is made
     * for it, so the captures carry its scale.
     */
    static java.awt.GraphicsDevice device;

    static java.awt.GraphicsDevice device() {
        if (device == null) {
            java.awt.GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            device = ge.getDefaultScreenDevice();
            for (java.awt.GraphicsDevice d : ge.getScreenDevices()) {
                java.awt.GraphicsConfiguration gc = d.getDefaultConfiguration();
                Rectangle usable = ScreenBounds.usable(gc);
                if (Math.abs(gc.getDefaultTransform().getScaleX() - requiredScale) < 0.01 && usable.width >= windowW && usable.height >= windowH) { device = d; break; }
            }
        }
        return device;
    }

    static Rectangle usableArea() {
        return ScreenBounds.usable(device().getDefaultConfiguration());
    }

    /** The scene display's scale (device pixels per logical pixel). */
    static double displayScale() {
        return device().getDefaultConfiguration().getDefaultTransform().getScaleX();
    }

    /**
     * Puts the frame at the usable area's origin at the requested logical size and measures it:
     * one correction for the difference between the placed and the measured bounds (Windows'
     * invisible resize borders), then the measured bounds must match within 2 px, or the run
     * stops — a smaller screen, a visible dock or a stuck maximized state would otherwise be
     * captured as the arm's rendering.
     */
    static void placeFrame() throws Exception {
        Rectangle usable = usableArea();
        Rectangle want = new Rectangle(usable.x, usable.y, windowW, windowH);
        // the frame may not be in the window list yet right after indexing ended (2026-11-02: a run 13 s
        // after launch got none): up to 10 s of retries
        Rectangle got = null;
        String answer = null;
        for (int attempt = 0; attempt < 20 && got == null; attempt++) {
            if (attempt > 0) sleep(500);
            answer = helperPlaceRaw(want);
            got = parseBounds(answer);
        }
        if (got != null && (Math.abs(got.width - want.width) > 2 || Math.abs(got.height - want.height) > 2 || Math.abs(got.x - want.x) > 2 || Math.abs(got.y - want.y) > 2)) {
            // the helper placed the outer rectangle; the visible one differs by the borders
            Rectangle corrected = new Rectangle(want.x + (want.x - got.x), want.y + (want.y - got.y), want.width + (want.width - got.width), want.height + (want.height - got.height));
            got = helperPlace(corrected);
        }
        if (got == null) {
            throw new IllegalStateException("could not place the frame through the helper (answer: " + answer + "; the accessibility permission for the terminal?)");
        }
        if (Math.abs(got.width - want.width) > 2 || Math.abs(got.height - want.height) > 2) {
            throw new IllegalStateException("the frame is " + got.width + "x" + got.height + " at " + got.x + "," + got.y + " instead of " + want.width + "x" + want.height
                                            + " at " + want.x + "," + want.y + ": the usable area is " + usable.width + "x" + usable.height
                                            + " (a smaller display, a visible dock or taskbar in the way?)");
        }
        frame = got;
        System.out.println("frame " + frame.width + "x" + frame.height + " at " + frame.x + "," + frame.y + ", scale " + displayScale());
        // the pointer out of the frame: the screen capture includes it (seen in every capture of 2026-11-02)
        Rectangle screenBounds = device().getDefaultConfiguration().getBounds();
        int px = frame.x + 12, py = frame.y + frame.height + 20;
        if (py >= screenBounds.y + screenBounds.height) { px = frame.x + frame.width - 1; py = frame.y + frame.height - 1; }   // no room below: the frame's corner pixel
        robot.mouseMove(px, py);
    }

    /** Asks the helper to place the frame (logical pixels) and returns the measured bounds, null if it could not. */
    static Rectangle helperPlace(Rectangle r) throws Exception {
        return parseBounds(helperPlaceRaw(r));
    }

    static String helperPlaceRaw(Rectangle r) throws Exception {
        double s = MAC ? 1 : displayScale();   // the Windows helper speaks device pixels (it is DPI-aware)
        return helper(String.format("place %d %d %d %d %d", idePid, Math.round(r.x * s), Math.round(r.y * s), Math.round(r.width * s), Math.round(r.height * s)));
    }

    /** The frame's current bounds in logical pixels, from the helper; the placed ones if it cannot tell. */
    static Rectangle frameBounds() throws Exception {
        Rectangle r = parseBounds(helper("bounds " + idePid));
        return r != null ? r : frame;
    }

    static Rectangle parseBounds(String line) {
        if (line == null) return null;
        String[] f = line.trim().split("\\s+");
        double s = 1;
        int o = 0;
        if (f.length == 5 && f[0].equals("phys")) { s = displayScale(); o = 1; }   // Windows: device pixels
        if (f.length - o != 4) return null;
        try {
            return new Rectangle((int) Math.round(Integer.parseInt(f[o]) / s), (int) Math.round(Integer.parseInt(f[o + 1]) / s),
                                 (int) Math.round(Integer.parseInt(f[o + 2]) / s), (int) Math.round(Integer.parseInt(f[o + 3]) / s));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The area the waits sample and the captures take: the frame once placed, the usable area before. */
    static Rectangle sceneArea() {
        return frame != null ? frame : usableArea();
    }

    /**
     * Performs the keys and waits until the screen has changed (the popup, the result, the new
     * state is painted) and then stopped changing. {@code maxMs} is the former fixed sleep: if
     * nothing changes within it, the wait ends there as before.
     */
    static void expect(Runnable keys, int maxMs) {
        long t = System.nanoTime();
        try {
            expect0(keys, maxMs);
        } finally {
            profile("expect", t);
        }
    }

    static void expect0(Runnable keys, int maxMs) {
        Rectangle screen = sceneArea();
        int[] before = sample(screen);
        keys.run();
        final long start = System.nanoTime();
        while (true) {
            sleep(50);
            int[] now = sample(screen);
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            if (differing(now, before) > QUIET_CELLS || elapsed >= maxMs) break;
        }
        awaitQuiet(0, Math.max(400, maxMs / 2));   // two equal samples 60 ms apart are the check; no floor
    }

    /**
     * Grid cells that may differ between two samples of a quiet screen: a blinking caret or a
     * spinner touches one or two cells, a popup, a result list or a tool window many more.
     */
    static final int QUIET_CELLS = 2;

    static int differing(int[] a, int[] b) {
        int n = 0;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) n++;
        return n;
    }

    /**
     * Waits until two samples 60 ms apart are equal, at least {@code minMs} and at most
     * {@code maxMs} (the former fixed sleep) after the call.
     */
    static void awaitQuiet(int minMs, int maxMs) {
        long t = System.nanoTime();
        try {
            awaitQuiet0(minMs, maxMs);
        } finally {
            profile("awaitQuiet", t);
        }
    }

    static void awaitQuiet0(int minMs, int maxMs) {
        Rectangle screen = sceneArea();
        final long start = System.nanoTime();
        int[] last = sample(screen);
        while (true) {
            sleep(60);
            int[] now = sample(screen);
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            if ((differing(now, last) <= QUIET_CELLS && elapsed >= minMs) || elapsed >= maxMs) return;
            last = now;
        }
    }

    /**
     * Waits until the screen has been unchanged for {@code stableMs} (samples every 500 ms), at
     * most {@code maxMs}: the IDE's work after indexing — the inspection pass, the run and
     * inspection widgets, the error stripe — repaints in bursts that a short settle misses
     * (the Windows kit slept a fixed 120 s for it).
     */
    static void quiesce(int stableMs, int maxMs) {
        Rectangle screen = sceneArea();
        final long start = System.nanoTime();
        long stableSince = start;
        int[] last = sample(screen);
        int[] changes = new int[last.length];   // per cell, for the report
        boolean reported = false;
        while (true) {
            sleep(500);
            int[] now = sample(screen);
            long t = System.nanoTime();
            if (differing(now, last) > QUIET_CELLS) { stableSince = t; }
            for (int i = 0; i < now.length; i++) if (now[i] != last[i]) changes[i]++;
            last = now;
            if ((t - stableSince) / 1_000_000 >= stableMs) return;
            if (!reported && (t - start) / 1_000_000 >= 20_000) { reported = true; reportChanges(changes, screen); }
            if ((t - start) / 1_000_000 >= maxMs) { System.err.println("warning: the screen kept changing for " + maxMs / 1000 + " s"); reportChanges(changes, screen); return; }
        }
    }

    /** The grid cells that changed most often so far (column, row and the screen position), for a long quiesce. */
    static void reportChanges(int[] changes, Rectangle screen) {
        int cols = 48, rows = 30;
        StringBuilder sb = new StringBuilder("still changing after 20 s; busiest cells (col,row → x,y: changes):");
        Integer[] idx = new Integer[changes.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Integer.compare(changes[b], changes[a]));
        for (int k = 0; k < 6 && changes[idx[k]] > 0; k++) {
            int i = idx[k], c = i % cols, r = i / cols;
            sb.append(String.format(" (%d,%d → %d,%d: %d)", c, r, screen.x + c * (screen.width - 1) / (cols - 1), screen.y + r * (screen.height - 1) / (rows - 1), changes[i]));
        }
        System.out.println(sb);
    }

    /**
     * Waits until the frame stops changing before a capture: two samples 150 ms apart equal on the
     * coarse grid, at least 300 ms and at most 2 x settleMs after the call (2026-10-26: replaced a
     * fixed 2.5 s; 2026-11-02: 300/600 ms → 150/300, the waits before it have already seen the frame
     * quiet, this is the last check — 8.6 s of a 34 s run went into it).
     */
    static void settle() throws Exception {
        Rectangle screen = sceneArea();
        final long start = System.nanoTime();
        sleep(150);
        int[] last = sample(screen);
        while (true) {
            sleep(150);
            int[] now = sample(screen);
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            if ((differing(now, last) <= QUIET_CELLS && elapsed >= 300) || elapsed >= 2L * settleMs) return;
            last = now;
        }
    }

    static int[] sample(Rectangle screen) {
        long t = System.nanoTime();
        try {
            return sample0(screen);
        } finally {
            profile("sample", t);
        }
    }

    static boolean helperSampling = true;   // macOS: the helper captures and samples in one call (≈ 15 ms against the Robot's 82)

    static int[] sample0(Rectangle screen) {
        int cols = 48, rows = 30;
        if (MAC && helperSampling) {
            try {
                String line = helper(String.format("sample %d %d %d %d %d %d", screen.x, screen.y, screen.width, screen.height, cols, rows));
                if (line != null && !line.equals("none")) {
                    String[] f = line.trim().split(" ");
                    if (f.length == cols * rows) {
                        int[] out = new int[f.length];
                        for (int i = 0; i < f.length; i++) out[i] = Integer.parseInt(f[i]);
                        return out;
                    }
                }
            } catch (Exception e) {
                System.err.println("helper sampling failed, the Robot from here on: " + e);
            }
            helperSampling = false;
        }
        BufferedImage shot = robot.createScreenCapture(screen);   // the 1x variant suffices for change detection
        int[] out = new int[cols * rows];
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
            out[r * cols + c] = shot.getRGB(c * (shot.getWidth() - 1) / (cols - 1), r * (shot.getHeight() - 1) / (rows - 1));
        }
        return out;
    }

    static void capture(String scene) throws Exception {
        // sticky balloons arrive at their own pace ("Automatic error reporting is now on" sat in every
        // capture of 2026-11-02 after a single clear at the start): dropped right before each capture
        ide("cmd=action&id=ClearAllNotifications");
        long t = System.nanoTime();
        settle();
        profile("settle", t);
        t = System.nanoTime();
        String fg = foreground(false);
        if (!fg.startsWith(idePid + "|")) {
            throw new IllegalStateException("lost the isolated IDE before capturing " + scene + ": " + fg);
        }
        // the frame's own bounds with its native decorations: no menu bar, taskbar or desktop
        Rectangle screen = frameBounds();
        MultiResolutionImage mri = robot.createMultiResolutionScreenCapture(screen);
        BufferedImage best = null;
        for (Image v : mri.getResolutionVariants()) {
            BufferedImage b = toBuffered(v);
            if (best == null || b.getWidth() > best.getWidth()) best = b;
        }
        final BufferedImage image = best;
        final File target = new File(out, scene + ".png");
        writer.submit(() -> {
            try {
                ImageIO.write(image, "png", target);
            } catch (java.io.IOException e) {
                System.err.println("could not write " + target + ": " + e);
            }
        });
        profile("capture", t);
        System.out.println(scene + ": " + best.getWidth() + "x" + best.getHeight());
    }

    static BufferedImage toBuffered(Image img) {
        if (img instanceof BufferedImage b) return b;
        BufferedImage b = new BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_RGB);
        b.getGraphics().drawImage(img, 0, 0, null);
        return b;
    }

    static void sleep(int ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }
}
