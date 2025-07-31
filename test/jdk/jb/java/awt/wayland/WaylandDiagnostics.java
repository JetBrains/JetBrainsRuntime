import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * @test
 * @requires os.family == "linux"
 * @run main/othervm -Dawt.toolkit.name=WLToolkit WaylandDiagnostics
 * @key headful
 */
public class WaylandDiagnostics {

    /* Helper: run a command and dump its output (or a fallback message) */
    private static void runAndPrint(String title, String... cmd) {
        System.out.println("== " + title + " ==");
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.waitFor();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                boolean any = false;
                while ((line = br.readLine()) != null) {
                    System.out.println(line);
                    any = true;
                }
                if (!any) System.out.println("(no output)");
            }
        } catch (Exception ex) {
            System.out.println("Command failed: " + String.join(" ", cmd) + " → " + ex);
        }
        System.out.println();
    }

    public static void main(String[] args) throws Exception {

        /* 1. Java & OS info */
        System.out.println("== Java / OS ==");
        System.out.printf("java.version  : %s%n", System.getProperty("java.version"));
        System.out.printf("java.vendor   : %s%n", System.getProperty("java.vendor"));
        System.out.printf("os.name       : %s %s%n%n",
                System.getProperty("os.name"), System.getProperty("os.version"));

        /* 2. Key environment variables */
        System.out.println("== Environment variables ==");
        String[] vars = { "XDG_SESSION_TYPE", "DISPLAY", "WAYLAND_DISPLAY",
                "_JAVA_AWT_WM_NONREPARENTING" };
        for (String v : vars)
            System.out.printf("%s=%s%n", v, System.getenv(v));
        System.out.println();

        /* 3. Xwayland binary presence */
        System.out.println("== /usr/bin/Xwayland exists? ==");
        System.out.println(Files.exists(Paths.get("/usr/bin/Xwayland")));
        System.out.println();

        /* 4. Xwayland process running? */
        runAndPrint("Xwayland process list", "pgrep", "-a", "Xwayland");

        /* 5. pacman package check (Arch only; harmless elsewhere) */
        runAndPrint("pacman -Q xorg-xwayland", "pacman", "-Q", "xorg-xwayland");

        /* 6. Basic Swing/AWT test */
        System.out.println("== Swing test ==");
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            System.out.println("Headless mode: Java sees no GUI environment.");
        } else {
            try {
                javax.swing.JFrame frame = new javax.swing.JFrame("Ping");
                frame.setSize(100, 50);      // don’t actually show it
                frame.getGraphicsConfiguration();   // forces X connection
                System.out.println("Swing frame created successfully – GUI available.");
            } catch (Throwable t) {
                System.out.println("Swing failed:");
                t.printStackTrace(System.out);
            }
        }
        System.out.println();
    }
}