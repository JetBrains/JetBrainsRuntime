import sun.awt.AppContext;
import sun.java2d.d3d.D3DSurfaceData;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.*;

import static javax.swing.RepaintManager.currentManager;


public class RectangleTest{
    static Graphics g2d;
    static int tmp = 0;

    public static void main(String[] args) {
        JFrame jf = new JFrame("Demo");
        Container cp = jf.getContentPane();
        MyCanvas tl = new MyCanvas();
        cp.add(tl);
        jf.setSize(3800, 2000);
        jf.setVisible(true);
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    jf.repaint();
                }
            }
        });
//        thread.start();
    }
}


class MyCanvas extends JComponent {
    static private AtomicInteger fps = new AtomicInteger(0);
    static Timer timer = null;

    @Override
    public void paintComponent(Graphics g) {
        if(g instanceof Graphics2D)
        {
            Graphics2D g2 = (Graphics2D)g;
            g2.setColor(new Color((float)Math.random(), (float)Math.random(), (float)Math.random()));
            g2.fillRect(0, 0, 3800, 2000);

            if (timer == null) {
                timer = new Timer(1000, new ActionListener() {

                    @Override
                    public void actionPerformed(ActionEvent e) {
                        System.err.println("FPS 2 = " + fps.get());
                        fps.set(0);
                    }
                });
                timer.setRepeats(true);
                timer.start();
            }
            fps.addAndGet(1);
        }
    }
}