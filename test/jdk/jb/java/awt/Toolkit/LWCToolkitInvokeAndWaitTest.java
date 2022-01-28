import java.awt.*;
import javax.swing.*;
import java.awt.event.InvocationEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import sun.lwawt.macosx.CThreading;
import sun.lwawt.macosx.LWCToolkit;
import sun.awt.AWTThreading;

/*
 * @test
 * @summary Tests that LWCToolkit.invokeAndWait can survive losing the invocation event.
 * @requires (os.family == "mac")
 * @compile --add-exports=java.desktop/sun.lwawt.macosx=ALL-UNNAMED --add-exports=java.desktop/sun.awt=ALL-UNNAMED LWCToolkitInvokeAndWaitTest.java
 * @run main/othervm --add-exports=java.desktop/sun.lwawt.macosx=ALL-UNNAMED --add-exports=java.desktop/sun.awt=ALL-UNNAMED LWCToolkitInvokeAndWaitTest
 * @author Anton Tarasov
 */
public class LWCToolkitInvokeAndWaitTest {
    static final CountDownLatch LATCH = new CountDownLatch(1);
    static final JFrame FRAME = new JFrame(LWCToolkitInvokeAndWaitTest.class.getSimpleName());

    static final AtomicBoolean PASSED = new AtomicBoolean(true);

    public static void main(String[] args) throws InterruptedException {
        EventQueue.invokeLater(LWCToolkitInvokeAndWaitTest::runOnEdt);
        if (!LATCH.await(5, TimeUnit.SECONDS)) {
            System.out.println("Invocation timed out!");
            PASSED.set(false);
        }
        FRAME.dispose();
        if (!PASSED.get()) {
            throw new RuntimeException("Test FAILED!");
        }
        System.out.println("Test PASSED");
    }

    static void runOnEdt() {
        FRAME.getContentPane().setBackground(Color.green);
        FRAME.setLocationRelativeTo(null);
        FRAME.setSize(200, 200);
        FRAME.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        FRAME.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                test();
            }
        });
        FRAME.setVisible(true);
    }

    static void test() {
        Toolkit.getDefaultToolkit().getSystemEventQueue().push(new EventQueue() {
            @Override
            protected void dispatchEvent(AWTEvent event) {
                if (event instanceof InvocationEvent && event.toString().contains(",runnable=sun.lwawt.macosx.LWCToolkit")) {
                    System.out.println("Not dispatching: " + event);
                    return;
                }
                super.dispatchEvent(event);
            }
        });
        CThreading.executeOnAppKit(() -> {
            long startTime = System.currentTimeMillis();
            try {
                LWCToolkit.invokeAndWait(() -> System.out.println("Am I dispatched?"), FRAME);
            } catch (InvocationTargetException e) {
                PASSED.set(false);
                e.printStackTrace();
            }
            System.out.println("Invocation took " + (System.currentTimeMillis() - startTime) + " ms");
            LATCH.countDown();
        });
    }
}