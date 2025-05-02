/*
 * Copyright 2000-2023 JetBrains s.r.o.
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

import com.jetbrains.JBR;
import com.jetbrains.WindowDecorations;
import test.jb.testhelpers.screenshot.ScreenShotHelpers;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.IOException;

/*
 * @test
 * @summary Verify modifying of title bar height
 * @requires (os.family == "windows" | os.family == "mac")
 * @library ../../../testhelpers/screenshot ../../../testhelpers/TitleBar ../../../testhelpers/utils
 * @build TestUtils TaskResult Task CommonAPISuite MouseUtils ScreenShotHelpers Rect RectCoordinates MouseUtils
 * @run main/othervm CheckFullScreen
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0 CheckFullScreen
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.25 CheckFullScreen
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.5 CheckFullScreen
 */
public class CheckFullScreen {

    public static void main(String... args) throws AWTException, IOException {
        Frame f = new Frame(){
            @Override
            public void paint(Graphics g) {
                Rectangle r = g.getClipBounds();
                g.setColor(Color.BLUE);
                g.fillRect(r.x, r.y, r.width, 100);
                super.paint(g);
            }
        };

        WindowDecorations.CustomTitleBar titleBar = JBR.getWindowDecorations().createCustomTitleBar();
        titleBar.setHeight(100);
        JBR.getWindowDecorations().setCustomTitleBar(f, titleBar);

        f.setVisible(true);
        GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().setFullScreenWindow(f);

        Robot robot = new Robot();
        robot.delay(5000);

        BufferedImage image = ScreenShotHelpers.takeScreenshot(f);
        ScreenShotHelpers.storeScreenshot("fullscreen", image);
    }

}