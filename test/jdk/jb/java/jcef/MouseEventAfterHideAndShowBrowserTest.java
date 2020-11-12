// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

import java.awt.*;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @test
 * @key headful
 * @summary Regression test for JBR-2412. The test checks that mouse actions are handled on jcef browser after hide and show it.
 * @run main/othervm MouseEventAfterHideAndShowBrowserTest
 */

public class MouseEventAfterHideAndShowBrowserTest {

    public static void main(String[] args) throws InvocationTargetException, InterruptedException {

        MouseEventScenario scenario = new MouseEventScenario();
        try {

            scenario.initUI();
            scenario.mouseMove(scenario.getBrowserFrame().getFrameCenter());

            MouseEventScenario.latch = new CountDownLatch(1);
            scenario.getBrowserFrame().hideAndShowBrowser();
            MouseEventScenario.latch.await(2, TimeUnit.SECONDS);

            //mouseEntered and mouseExited events work unstable. These actions are not tested.
            scenario.doMouseActions();

            System.out.println("Test PASSED");
        } catch (AWTException e) {
            e.printStackTrace();
        } finally {
            scenario.disposeBrowserFrame();
        }
    }
}