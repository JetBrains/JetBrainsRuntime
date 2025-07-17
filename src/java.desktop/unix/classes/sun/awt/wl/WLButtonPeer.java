/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2021, JetBrains s.r.o.. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package sun.awt.wl;

import java.awt.Button;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.FocusEvent;
import java.awt.peer.ButtonPeer;
import sun.util.logging.PlatformLogger;

public class WLButtonPeer extends WLComponentPeer implements ButtonPeer {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLButtonPeer");
    String label;


    public WLButtonPeer(Button target) {
        super(target);
        label = target.getLabel();
    }

    @Override
    protected void wlSetVisible(boolean v) {
        // TODO: unimplemented
    }

    public boolean isFocusable() {
        return true;
    }

    @Override
    public void setLabel(String label) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLButtonPeer.setLabel(String)");
        }
    }

    public void setBackground(Color c) {
        super.setBackground(c);
    }

    public void focusGained(FocusEvent e) {
        super.focusGained(e);
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLButtonPeer.focusGained(FocusEvent)");
        }
    }

    public void focusLost(FocusEvent e) {
        super.focusLost(e);
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLButtonPeer.focusLost(FocusEvent)");
        }
    }

    @Override
    void paintPeer(Graphics g) {
        super.paintPeer(g);
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLButtonPeer.paintPeer(Graphics)");
        }
    }

    public Dimension getMinimumSize() {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLButtonPeer.getMinimumSize()");
        }
        return new Dimension(0, 0);
    }
}
