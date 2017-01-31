/*
 * Copyright 2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import sun.awt.AWTAccessor;
import javax.swing.*;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.ColorModel;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/*
 * @test
 * bug       JRE-210
 * @summary  JEditorPane's font metrics to honour switching to different GC scale
 * @author   anton.tarasov
 * @requires (os.family == "windows")
 * @run      main/othervm -Dsun.font.layoutengine=icu -Di18n=true JEditorPaneGCSwitchTest_i18n
 */
// -Dsun.font.layoutengine=icu is used while Harfbuzz crashes with i18n
public class JEditorPaneGCSwitchTest_i18n extends JPanel {
    public static void main(String[] args) throws InterruptedException {
        JEditorPaneGCSwitchTest.main(null);
    }
}