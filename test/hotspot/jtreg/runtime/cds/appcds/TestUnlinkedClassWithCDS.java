/*
 * @test
 * @summary bootclasspath mismatch test.
 * @requires vm.cds
 * @library /test/lib
 * @compile test-classes/CustomClassLoaderApp.java
 * @run driver TestUnlinkedClassWithCDS
 */

import jdk.test.lib.helpers.ClassFileInstaller;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.lib.cds.CDSTestUtils;

public class TestUnlinkedClassWithCDS {
    public static void main(String[] args) throws Exception {
        String libJar = ClassFileInstaller.writeJar("lib.jar", "Outer", "Outer$Inner");
        String appJar = ClassFileInstaller.writeJar("app.jar", "CustomClassLoaderApp", "CustomClassLoader");
        System.setProperty("test.noclasspath", "true");
        TestCommon.startNewArchiveName();
        String archive = TestCommon.getCurrentArchiveName();

        run("dump",
            "-cp", appJar,
            "-XX:ArchiveClassesAtExit=" + archive,
            "CustomClassLoaderApp",
            libJar)
            .shouldHaveExitValue(0)
            .shouldContain("Declaring class: class Outer");

        run("exec",
            "-cp", appJar,
            "-XX:SharedArchiveFile=" + archive,
            "CustomClassLoaderApp",
            libJar)
            .shouldHaveExitValue(0)
            .shouldContain("Declaring class: class Outer");
    }

    private static OutputAnalyzer run(String log, String... args) throws Exception {
        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(args);
        return CDSTestUtils.executeAndLog(pb.start(), log);
    }
}
