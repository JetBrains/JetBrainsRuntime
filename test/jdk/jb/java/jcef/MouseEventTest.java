// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

import java.awt.*;
import java.lang.reflect.InvocationTargetException;

/**
 * @test
 * @key headful
 * @requires (os.arch != "x86" & os.arch != "i386" & os.arch != "aarch64")
 * @summary Regression test for JBR-2639. The test checks that mouse actions are handled on jcef browser.
 * @run main/othervm MouseEventTest
 */


public class MouseEventTest {

    public static void main(String[] args) throws InvocationTargetException, InterruptedException {

        MouseEventScenario scenario = new MouseEventScenario();
        try {
            scenario.initUI();

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