import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

import java.util.concurrent.TimeUnit;

/**
 * @test
 * @bug 8354460
 * @summary <code>JStackHangTest</code> verifies the streaming output of the attach API. It launches
 *       <code>JStackLauncher</code> in a child VM and ensures <code>jstack</code> does not hang.
 * @library /test/lib
 * @compile JStackHangTest.java
 * @run main/othervm -Djdk.attach.vm.streaming=true JStackHangTest
 */

public class JStackHangTest {

    static final int JSTACK_WAIT_TIME = JStackLauncher.JSTACK_WAIT_TIME * 2;

    public static final int CODE_NOT_RETURNED = 100;

    public static void main(String[] args) throws Exception {

        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(
                "-Djdk.attach.vm.streaming=" + System.getProperty("jdk.attach.vm.streaming"),
                JStackLauncher.class.getName());

        Process process = ProcessTools.startProcess("Child", pb);
        OutputAnalyzer outputAnalyzer = new OutputAnalyzer(process);

        int returnCode;

        if (!process.waitFor(JSTACK_WAIT_TIME, TimeUnit.MILLISECONDS)) {
            System.out.println("jstack did not completed in " + JSTACK_WAIT_TIME + " ms.");

            System.out.println("killing all the jstack processes");
            ProcessTools.executeCommand("killall", "-9", "jstack");
            returnCode = CODE_NOT_RETURNED;

        } else {
            returnCode = outputAnalyzer.getExitValue();
            System.out.println("returnCode = " + returnCode);
        }

        if (returnCode == CODE_NOT_RETURNED)
            throw new RuntimeException("jstack: failed to start or hanged");
        else
            System.out.println("jstack: completed successfully");
    }
}
