/*
 * @test
 * @summary JBR-10183 reproducer — Turkish locale bootstrap failure
 *
 * Demonstrates that JBR-8664's locale-sensitive String.toUpperCase() in
 * WindowsPath.getUppercasePath() causes a circular ICU initialization
 * failure when the system locale is Turkish.
 *
 * REQUIREMENTS:
 *   - JBR 25 build containing JBR-8664 with IoOverNioFileSystem enabled
 *     (i.e. built via mkimage_x64.sh)
 *   - Windows OS
 *   - Turkish locale (or -Duser.language=tr -Duser.region=TR)
 *
 * HOW IT WORKS:
 *   The test uses a custom DefaultFileSystemProvider (DelegatingProvider)
 *   to force FileSystems.getDefault() initialization early, *before*
 *   SystemModuleFinders$SystemImage.<clinit> is triggered.
 *
 *   It then forces SystemImage.<clinit> by looking up a resource from a
 *   boot module class.  Inside SystemImage.<clinit>, the JVM loads the
 *   native "jimage" library, which calls File.getCanonicalPath().  On
 *   JBR 25, IoOverNioFileSystem routes this through NIO:
 *
 *     WindowsPath.equals → getUppercasePath → String.toUpperCase()
 *       → Turkish ConditionalSpecialCasing ('i' → 'İ')
 *         → ICU NormalizerImpl.load → ICUBinary.getRequiredData("nfc.nrm")
 *           → Class.getResourceAsStream → SystemImage (STILL IN <clinit>!)
 *             → returns null → NullPointerException
 *
 * @requires (os.family == "windows")
 * @library /test/lib
 * @build TurkishBootstrapTest DelegatingProvider
 * @run main/othervm -Djava.nio.file.spi.DefaultFileSystemProvider=DelegatingProvider TurkishBootstrapTest
 * @run main/othervm -Djava.nio.file.spi.DefaultFileSystemProvider=DelegatingProvider -Djbr.java.io.use.nio=true TurkishBootstrapTest
 * @run main/othervm -Djava.nio.file.spi.DefaultFileSystemProvider=DelegatingProvider -Djbr.java.io.use.nio=false TurkishBootstrapTest
 */

import jdk.test.lib.process.OutputAnalyzer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public class TurkishBootstrapTest {

    static class Child {

        public static void main(String[] args) {
            System.err.println("Child: locale=" + java.util.Locale.getDefault());
            System.err.println("Child: java.home=" + System.getProperty("java.home"));

            // ---------------------------------------------------------------
            // Step 2 — trigger SystemModuleFinders$SystemImage.<clinit>.
            //
            // Class.getResource() on a boot-module class goes through
            // BootLoader → SystemModuleFinders$SystemModuleReader → SystemImage.
            // If SystemImage has not been initialised yet (typical with CDS),
            // its <clinit> runs NOW — inside which File.getCanonicalPath is
            // called.  On JBR 25 with Turkish locale, this triggers the
            // circular ICU dependency → NullPointerException.
            // ---------------------------------------------------------------
            System.err.println("[step 2] Triggering SystemImage.<clinit> via Class.getResource …");
            try {
                java.net.URL url = Object.class.getResource("Object.class");
                System.err.println("[step 2] Resource lookup succeeded: " + url);
                System.out.println("SUCCESS");
            } catch (Throwable t) {
                t.printStackTrace(System.err);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        System.err.println("Java:   " + System.getProperty("java.runtime.version"));
        System.err.println("Locale: " + java.util.Locale.getDefault());
        System.err.println("OS:     " + System.getProperty("os.name"));
        System.err.println("FS provider property: "
                + System.getProperty("java.nio.file.spi.DefaultFileSystemProvider", "(not set)"));
        boolean isNioEnabled = Boolean.parseBoolean(System.getProperty("jbr.java.io.use.nio"));
        System.err.println("IoOverNio enabled:    " + isNioEnabled);
        System.err.println();

        // ---------------------------------------------------------------
        // Step 1 — force FileSystems.getDefault() to initialise NOW.
        //
        // This loads DelegatingProvider (from the classpath, via the
        // system class loader — no SystemImage involvement) and creates
        // the default FileSystem.  After this call, IoOverNioFileSystem's
        // acquireNioFs() can return the default FS without re-entrancy.
        // ---------------------------------------------------------------
        System.err.println("[step 1] Initialising FileSystems.getDefault() …");
        java.nio.file.FileSystem defaultFs = java.nio.file.FileSystems.getDefault();
        System.err.println("[step 1] Default FS: " + defaultFs
                + " (provider: " + defaultFs.provider().getClass().getName() + ")");

        Path javaHome = Path.of(System.getProperty("java.home"));
        Path scratchDir = Path.of(".");
        // ideaJbr name contains capital 'I' — this is the trigger
        Path ideaJbr = Files.createDirectory(scratchDir.resolve("IDEA_JBR"));
        System.out.println("Creating jbr directory: " + ideaJbr);

        try {
            // Create directory ideaJbr: xcopy <java.home> <ideaJbr> /E /H /C /I
            ProcessBuilder xcopy = new ProcessBuilder(
                    "xcopy", "/E", "/H", "/C", "/I", "/Q", javaHome.toString(), ideaJbr.toString());
            xcopy.inheritIO();
            int rc = xcopy.start().waitFor();
            if (rc != 0) {
                throw new RuntimeException("xcopy failed with exit code " + rc);
            }

            // Verify <ideaJbr> exists and contains java executable
            Path javaExe = ideaJbr.resolve("bin").resolve("java.exe");
            if (!Files.exists(javaExe)) {
                throw new RuntimeException("java.exe not found at " + javaExe);
            }

            // Build subprocess command using the ideaJbr'd java
            String testClasses = System.getProperty("test.classes", ".");
            List<String> cmd = Arrays.asList(javaExe.toString(),
                    "-Duser.language=tr", "-Duser.region=TR", "-Djbr.java.io.use.nio=" + isNioEnabled,
                    "-cp", testClasses, TurkishBootstrapTest.Child.class.getName());

            System.out.println("Launching subprocess: " + String.join(" ", cmd));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            OutputAnalyzer output = new OutputAnalyzer(pb.start());

            // The subprocess must not crash with NPE in ICUBinary
            output.shouldNotContain("Cannot invoke \"java.io.InputStream.available()\" because \"is\" is null");
            output.shouldNotContain("ICUBinary.getRequiredData");
            output.shouldNotContain("NullPointerException");
            output.shouldNotContain("ExceptionInInitializerError");
            output.shouldContain("SUCCESS");

            System.out.println("PASSED: subprocess started successfully with Turkish locale");

        } finally {
            // Remove the ideaJbr (rmdir removes the junction, not the target)
            if (Files.exists(ideaJbr)) {
                new ProcessBuilder("cmd.exe", "/C", "rmdir", "/S", "/Q", ideaJbr.toString())
                                  .inheritIO().start().waitFor();
            }
        }
    }
}
