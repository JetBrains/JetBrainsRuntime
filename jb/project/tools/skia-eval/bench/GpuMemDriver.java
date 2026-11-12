import java.io.PrintWriter;
import java.io.FileWriter;
import java.lang.reflect.Method;

/**
 * RenderPerfTest with the process' memory sampled: every 5 s the physical footprint (footprint(1)) and, on the Skia
 * pipe, the head of SKEnvironment.gpuMemoryReport; every 30 s and at exit the whole report, into gpumem-<arm>.txt.
 *   java <arm's flags> --add-exports java.desktop/sun.java2d.skia=ALL-UNNAMED -cp <rpt classes>:. GpuMemDriver <arm> <RenderPerfTest args>
 */
public class GpuMemDriver {
    static PrintWriter out;
    static Method report;

    static String footprint() {
        try {
            Process p = new ProcessBuilder("footprint", Long.toString(ProcessHandle.current().pid())).redirectErrorStream(true).start();
            for (String line : new String(p.getInputStream().readAllBytes()).split("\n")) {
                if (line.contains("Footprint:")) return line.replaceAll(".*Footprint: *", "").trim();
            }
        } catch (Exception e) { }
        return "?";
    }

    static String report() {
        try { return report == null ? null : (String) report.invoke(null); } catch (Exception e) { return "report failed: " + e.getCause(); }
    }

    static synchronized void sample(long start, boolean full) {
        String r = report();
        out.printf("%6.1f s  footprint %s%n", (System.nanoTime() - start) / 1e9, footprint());
        if (r != null) {
            for (String line : r.split("\n")) if (full || line.startsWith("context")) out.println(line);
        }
        out.flush();
    }

    public static void main(String[] args) throws Exception {
        out = new PrintWriter(new FileWriter("gpumem-" + args[0] + ".txt"));
        if (Boolean.getBoolean("sun.java2d.skia")) report = Class.forName("sun.java2d.skia.SKEnvironment").getMethod("gpuMemoryReport");
        long start = System.nanoTime();
        Thread sampler = new Thread(() -> {
            for (int i = 1; ; i++) {
                try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                sample(start, i % 6 == 0);
            }
        });
        sampler.setDaemon(true);
        sampler.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            sample(start, true);
            Runtime rt = Runtime.getRuntime();
            out.printf("java heap committed %d MB, used %d MB%n", rt.totalMemory() >> 20, (rt.totalMemory() - rt.freeMemory()) >> 20);
            try {
                Process p = new ProcessBuilder("vmmap", "--summary", Long.toString(ProcessHandle.current().pid())).redirectErrorStream(true).start();
                out.print(new String(p.getInputStream().readAllBytes()));
            } catch (Exception e) { out.println("vmmap failed: " + e); }
            out.flush();
        }));
        String[] rest = new String[args.length - 1];
        System.arraycopy(args, 1, rest, 0, rest.length);
        Class.forName("renderperf.RenderPerfTest").getMethod("main", String[].class).invoke(null, (Object) rest);
    }
}
