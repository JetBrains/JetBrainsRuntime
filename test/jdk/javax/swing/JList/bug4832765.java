/*
 * Copyright (c) 2003, 2023, Oracle and/or its affiliates. All rights reserved.
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
 * @test
 * @bug 4832765
 * @summary JList vertical scrolling doesn't work properly.
 * @run main bug4832765
 */

import java.awt.Dimension;
import java.awt.Rectangle;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

public class bug4832765 {

    public static void main(String[] argv) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String[] data = {"One", "Two", "Three", "Four",
                    "Five", "Six ", "Seven", "Eight",
                    "Nine", "Ten", "Eleven", "Twelv"};
            JList<String> list = new JList<>(data);
            list.setLayoutOrientation(JList.HORIZONTAL_WRAP);

            JScrollPane jsp = new JScrollPane(list);
            Rectangle rect = list.getCellBounds(5, 5);
            Dimension d = new Dimension(200, rect.height);
            jsp.setPreferredSize(d);
            jsp.setMinimumSize(d);

            list.scrollRectToVisible(rect);

            int unit = list.getScrollableUnitIncrement(rect,
                    SwingConstants.VERTICAL,
                    -1);
            if (unit <= 0) {
                throw new RuntimeException("JList scrollable unit increment" +
                        " should be greate than 0.");
            }
        });
    }
}
