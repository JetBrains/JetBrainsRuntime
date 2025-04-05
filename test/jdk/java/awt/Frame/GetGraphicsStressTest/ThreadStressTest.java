import java.util.concurrent.TimeUnit;

/**
 * @test
 * @key headful
 */
public final class ThreadStressTest {

    static volatile Throwable failed;
    static volatile long endtime;

    public static void main(final String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> failed = e);

        for (int i = 0; i < 4; i++) {
            endtime = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            test();
        }

        Thread.sleep(10000);
    }

    private static void test() throws Exception {
        Object lock = new Object();

        Thread thread1 = new Thread(() -> {
            while (!isComplete()) {
                synchronized (lock) {
                    // Simulate resource teardown/setup
                    lock.notifyAll();
                }
            }
        });
        Thread thread2 = new Thread(() -> {
            while (!isComplete()) {
                synchronized (lock) {
                    // Simulate resource access
                    try {
                        lock.wait(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        });
        Thread thread3 = new Thread(() -> {
            while (!isComplete()) {
                synchronized (lock) {
                    // Simulate resource access
                    try {
                        lock.wait(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        });
        Thread thread4 = new Thread(() -> {
            while (!isComplete()) {
                synchronized (lock) {
                    // Simulate additional activity
                    lock.notifyAll();
                }
            }
        });

        thread1.start();
        thread2.start();
        thread3.start();
        thread4.start();

        thread1.join();
        thread2.join();
        thread3.join();
        thread4.join();

        if (failed != null) {
            System.err.println("Test failed");
            failed.printStackTrace();
            throw new RuntimeException(failed);
        }
    }

    private static boolean isComplete() {
        return endtime - System.nanoTime() < 0 || failed != null;
    }
}
