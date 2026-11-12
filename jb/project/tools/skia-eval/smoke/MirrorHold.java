import java.awt.*; import java.awt.geom.Ellipse2D; import java.awt.image.BufferedImage; import java.util.concurrent.*;
public class MirrorHold {
    static long rssMB() throws Exception {
        Process p = new ProcessBuilder("ps", "-o", "rss=", "-p", Long.toString(ProcessHandle.current().pid())).start();
        return Long.parseLong(new String(p.getInputStream().readAllBytes()).trim()) / 1024;
    }
    public static void main(String[] a) throws Exception {
        int threads = 8, images = 4, w = 2400, h = 1600;
        CountDownLatch drawn = new CountDownLatch(threads), done = new CountDownLatch(1);
        System.gc(); Thread.sleep(300);
        long before = rssMB();
        for (int t = 0; t < threads; t++) {
            Thread th = new Thread(() -> {
                for (int i = 0; i < images; i++) {
                    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = img.createGraphics(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(Color.BLUE); for (int k = 0; k < 50; k++) g.fill(new Ellipse2D.Double(k * 40, k * 25, 60, 40)); g.dispose();
                }
                drawn.countDown();
                try { done.await(); } catch (InterruptedException e) { }   // the thread stays alive, idle
            });
            th.setDaemon(true);
            th.start();
        }
        drawn.await();
        for (int i = 0; i < 5; i++) { System.gc(); Thread.sleep(200); }
        System.out.printf("%s: resident %d MB before, %d MB after %d threads drew into %d dropped %dx%d INT_ARGB images each (images collected, threads alive)%n",
                System.getProperty("sun.java2d.skia.raster") != null ? "Skia raster" : "loops", before, rssMB(), threads, images, w, h);
        {
            try {
                Object bytes = Class.forName("sun.java2d.skia.SKEnvironment").getMethod("rasterMirrorBytes").invoke(null);
                System.out.printf("mirror bytes held: %.1f MB%n", ((Long) bytes) / 1e6);
            } catch (NoSuchMethodException e) {
                System.out.println("mirror bytes held: not counted by this build");
            }
            Process p = new ProcessBuilder("perl", "-e", "alarm 60; exec @ARGV", "vmmap", "--summary", Long.toString(ProcessHandle.current().pid())).redirectErrorStream(true).start();
            for (String line : new String(p.getInputStream().readAllBytes()).split("\n")) if (line.startsWith("MALLOC") || line.contains("Physical footprint:") || line.startsWith("Java") || line.startsWith("VM_ALLOCATE") || line.startsWith("TOTAL")) System.out.println(line.trim());
        }
        done.countDown();
    }
}
