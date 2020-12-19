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

import java.awt.AWTException;
import java.awt.GraphicsDevice;
import java.io.IOException;

public class LinuxTouchRobot extends TouchRobot {
    private LinuxTouchScreenDevice device;

    public LinuxTouchRobot() throws AWTException, IOException {
        super();
        device = new LinuxTouchScreenDevice();
    }

    public LinuxTouchRobot(GraphicsDevice screen) throws AWTException, IOException {
        super(screen);
        device = new LinuxTouchScreenDevice();
    }

    public void touchClick(int fingerId, int x, int y) throws IOException {
        device.click(fingerId, x, y);
    }

    public void touchMove(int fingerId, int fromX, int fromY, int toX, int toY) throws IOException {
        device.move(fingerId, fromX, fromY, toX, toY);
    }
}
