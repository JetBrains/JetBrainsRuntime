/*
 * Copyright 2024 JetBrains s.r.o.
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
 * @summary BoxLayout doesn't ignore invisible components
 * @key headful
 * @run main NPECheckRequests
 */

import java.awt.BorderLayout;
import java.lang.reflect.InvocationTargetException;
import java.awt.Dimension;
import java.util.logging.Logger;
import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

public class NPECheckRequests {
    private static final Logger LOG = Logger.getLogger(NPECheckRequests.class.getName());
    JFrame frame;
    JPanel p;
    BrokenComponent foo;

    public void init() {
        frame = new JFrame() {
            @Override
            public void validate() {
                try {
                    super.validate();
                } catch (BrokenComponentException e) {
                    if (foo.broken) {
                        LOG.info("Caught BrokenComponentException in JFrame.validate() - expected, ignored");
                    } else {
                        throw e;
                    }
                }
            }
        };
        p = new JPanel();
        BoxLayout boxLayout = new BoxLayout(p, BoxLayout.X_AXIS);
        p.setLayout(boxLayout);
        foo = new BrokenComponent();
        p.add(foo);
        frame.setLayout(new BorderLayout());
        frame.add(p, BorderLayout.CENTER);
        try {
            frame.pack();
        } catch (BrokenComponentException ignored) {
            LOG.info("Caught BrokenComponentException in JFrame.pack() - expected, ignored");
        }
    }

    public void start() {
        foo.broken = false;
        // check that the layout isn't in a broken state because of that exception
        frame.pack();
    }

    public static void main(String[] args) throws InterruptedException,
            InvocationTargetException {
        NPECheckRequests test = new NPECheckRequests();
        SwingUtilities.invokeAndWait(test::init);
        SwingUtilities.invokeAndWait(test::start);
    }

    private class BrokenComponent extends JPanel {

        boolean broken = true;

        @Override
        public Dimension getPreferredSize() {
            if (broken) {
                throw new BrokenComponentException();
            }
            return super.getPreferredSize();
        }
    }

    private static class BrokenComponentException extends RuntimeException {
        BrokenComponentException() {
            super("Broken component");
        }
    }

}
