

/**
 * @test
 * @requires os.family == "linux"
 * @run main/othervm WDTest
 * @key headful
 */
public class WDTest {

    public static void main(String[] args) throws Exception {

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
