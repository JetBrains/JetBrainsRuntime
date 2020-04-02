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

import javax.swing.*;
import java.awt.*;

/**
 * @test
 * @summary Regression test for JBR-2210 (JVM crashes on Windows with Japanese locale and UTF-8 enconding)
 * @run main/othervm -Duser.language=ja -Duser.region=JP -Dfile.encoding=UTF-8 JapaneseLocaleTest
 */

/*
 * Description:
 * Test checks that the JVM does not crash on with Japanese locale and UTF-8 enconding by JFrame creation.
 */

public class JapaneseLocaleTest {
    private static JFrame frame;
    public static void main(String[] args) throws Exception{
        try {
            EventQueue.invokeAndWait(JapaneseLocaleTest::initFrame);
        }
        finally {
            EventQueue.invokeAndWait(() ->frame.dispose());
        }
    }

    private static void initFrame(){
        frame = new JFrame();
        frame.setVisible(true);
    }
}