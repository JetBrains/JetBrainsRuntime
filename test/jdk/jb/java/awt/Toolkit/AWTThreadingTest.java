import java.awt.*;
import javax.swing.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import sun.lwawt.macosx.CThreading;
import sun.lwawt.macosx.LWCToolkit;
import sun.awt.AWTThreading;

/*
 * @test
 * @summary tests that AWTThreading can manage a stream of cross EDT/AppKit invocation requests
 * @requires (os.family == "mac")
 * @compile --add-exports=java.desktop/sun.lwawt.macosx=ALL-UNNAMED --add-exports=java.desktop/sun.awt=ALL-UNNAMED AWTThreadingTest.java
 * @run main/othervm --add-exports=java.desktop/sun.lwawt.macosx=ALL-UNNAMED --add-exports=java.desktop/sun.awt=ALL-UNNAMED AWTThreadingTest
 * @author Anton Tarasov
 */
public class AWTThreadingTest {
    static final ReentrantLock LOCK = new ReentrantLock();
    static final Condition COND = LOCK.newCondition();
    static final CountDownLatch LATCH = new CountDownLatch(1);

    static JFrame frame;
    static Thread thread;

    final static AtomicBoolean passed = new AtomicBoolean(true);
    final static AtomicInteger counter = new AtomicInteger(0);

    public static void main(String[] args) throws InterruptedException {
        EventQueue.invokeLater(AWTThreadingTest::runGui);

        LATCH.await(5, TimeUnit.SECONDS);

        frame.dispose();
        thread.interrupt();

        if (!passed.get()) {
            throw new RuntimeException("Test FAILED!");
        }
        System.out.println("Test PASSED");
    }

    static void runGui() {
        frame = new JFrame("frame");
        frame.getContentPane().setBackground(Color.green);
        frame.setLocationRelativeTo(null);
        frame.setSize(200, 200);
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                startThread();
            }
        });
        frame.setVisible(true);
    }

    static void startThread() {
        thread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                }

                //
                // 1. Execute invokeAndWait() from AppKit to EDT
                //
                CThreading.executeOnAppKit(() -> {
                    try {
                        LWCToolkit.invokeAndWait(counter::incrementAndGet, Window.getWindows()[0]);
                    } catch (Exception e) {
                        fail(e);
                    }
                });

                //
                // 2. Execute invokeAndBlock() from EDT to AppKit
                //
                EventQueue.invokeLater(() -> {
                    passed.set(false);

                    Boolean success = AWTThreading.executeWaitToolkit(() -> {
                        try {
                            return CThreading.executeOnAppKit(() -> Boolean.TRUE);
                        } catch (Throwable e) {
                            fail(e);
                        }
                        return null;
                    });
                    System.out.println("Success: " + counter.get() + ": " + success);

                    passed.set(Boolean.TRUE.equals(success));

                    if (passed.get()) {
                        lock(COND::signal);
                    }
                    else {
                        fail(null);
                    }
                });

                lock(COND::await);
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    static void lock(MyRunnable runnable) {
        LOCK.lock();
        try {
            try {
                runnable.run();
            } catch (Exception e) {
                e.printStackTrace();
            }
        } finally {
            LOCK.unlock();
        }
    }

    interface MyRunnable {
        void run() throws Exception;
    }

    static void fail(Throwable e) {
        if (e != null) e.printStackTrace();
        passed.set(false);
        LATCH.countDown();
    }
}