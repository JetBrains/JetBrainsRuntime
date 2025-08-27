import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * @test
 * @bug 8354460
 * @summary checking streaming output for attach API
 * @run main/othervm JStackHangTest
 */

//-Djdk.attach.vm.streaming=false
public class JStackHangTest {

    public static final String JAVA_HOME = System.getProperty("java.home");
    public static final String JAVA_BIN = JAVA_HOME + File.separator + "bin";
    public static final String JSTACK_PATH = JAVA_BIN + File.separator + "jstack";

    static final int JSTACK_WAIT_TIME = 5000;

    static final int THREAD_COUNT = 10;
    static final int STACK_DEPTH = 100;

    static Process process;
    static CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

    public static void main(String[] args) throws IOException, InterruptedException {
        // Create threads with deep stacks
        List<Thread> threads = createManyThreadsWithDeepStacks();

        // Run jstack against current process
        System.out.println("jstack starting");
        process = new ProcessBuilder(JSTACK_PATH, String.valueOf(ProcessHandle.current().pid())).start();

        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        Stream<String> output = reader.lines();
        output.forEach(System.out::println);

        process.waitFor(JSTACK_WAIT_TIME, TimeUnit.MILLISECONDS);
        System.out.println("wait for process = " + process);

        threads.forEach(Thread::interrupt);
    }

    public static List<Thread> createManyThreadsWithDeepStacks() throws InterruptedException {
        List<Thread> threads = new ArrayList<>();

        for (int threadIndex = 0; threadIndex < THREAD_COUNT; threadIndex++) {
            System.out.println("Creating thread " + threadIndex);

            Thread t = new DeepStackThread(threadIndex);

            t.start();
            threads.add(t);
        }

        latch.await();

        return threads;
    }

    static class DeepStackThread extends Thread {
        DeepStackThread(int index) {
            super.setName("DeepStackThread-" + index);
        }

        @Override
        public synchronized void run() {
            try {
                createDeepStack(STACK_DEPTH);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.out.println("Thread " + getName() + " exiting");
        }

        void createDeepStack(int depth) throws InterruptedException {
            if (depth > 0) {
                createDeepStack(depth - 1);
            } else {
                latch.countDown();
                Thread.sleep(Long.MAX_VALUE);
            }
        }
    }
}
