/*
 * Copyright 2000-2022 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
  @test
  @key headful
  @summary a regression test for JBR-4303.
  @run main GetPointerInfoTest
*/

import java.awt.*;

/**
 *  The test checks <code>MouseInfo.getPointerInfo()</code> for all locations with the steps <code>X_STEP</code> and
 *  <code>Y_STEP</code> on all graphic devices.
 *  It moves mouse to the current location via <code>Robot.mouseMove()</code> and checks that
 *  <code>MouseInfo.getPointerInfo()</code> is not NULL.
 *  It also checks <code>MouseInfo.getPointerInfo().getLocation()</code> returns expected values.
 */
public class GetPointerInfoTest {

    public static int X_STEP = 100;
    public static int Y_STEP = 100;

    static boolean isPassed = true;

    public static void main(String[] args) throws Exception {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice[] graphicsDevices = ge.getScreenDevices();

        for (GraphicsDevice gd : graphicsDevices) {
            String name = gd.getIDstring();
            int width = gd.getDisplayMode().getWidth();
            int height = gd.getDisplayMode().getHeight();
            System.out.println("Check for device: " + name + " " + width + "x" + height);

            Robot robot = new Robot(gd);

            robot.setAutoDelay(0);
            robot.setAutoWaitForIdle(true);
            robot.delay(10);
            robot.waitForIdle();

            GraphicsConfiguration gc = gd.getDefaultConfiguration();

            Rectangle bounds = gc.getBounds();

            for (double y = bounds.getY(); y < bounds.getY() + bounds.getHeight(); y += Y_STEP) {

                for (double x = bounds.getX(); x < bounds.getX() + bounds.getWidth(); x += X_STEP) {

                    Point p = new Point((int)x, (int)y);

                    System.out.println("\tmouse move to x=" + p.x + " y=" + p.y);
                    robot.mouseMove(p.x, p.y);

                    System.out.print("\t\tMouseInfo.getPointerInfo.getLocation");
                    PointerInfo pi = MouseInfo.getPointerInfo();
                    if (pi == null) {
                        throw new RuntimeException("Test failed. getPointerInfo() returned null value.");
                    }

                    Point piLocation = pi.getLocation();
                    if (piLocation.x != p.x || piLocation.y != p.y) {
                        System.out.println(" - ***FAILED*** x=" + piLocation.x + " y=" + piLocation.y);
                        isPassed = false;
                    } else {
                        System.out.println(" x=" + p.x + ", y=" + p.y + " - passed");
                    }
                }
            }
        }
        if ( !isPassed )
            throw new RuntimeException("PointerInfo.getLocation() returns unexpected value(s).");

        System.out.println("Test PASSED.");
    }
}
