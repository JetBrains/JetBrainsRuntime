/*
 * Copyright 2017-2023 JetBrains s.r.o.
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