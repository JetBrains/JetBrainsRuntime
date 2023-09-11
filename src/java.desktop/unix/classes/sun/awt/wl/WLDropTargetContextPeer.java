/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2022, JetBrains s.r.o.. All rights reserved.
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

import sun.awt.AppContext;
import sun.awt.dnd.SunDropTargetContextPeer;
import sun.awt.dnd.SunDropTargetEvent;

import java.io.IOException;

public class WLDropTargetContextPeer extends SunDropTargetContextPeer {

    private static final Object DTCP_KEY = "DropTargetContextPeer";

    private WLDropTargetContextPeer() {}

    static WLDropTargetContextPeer getPeer(AppContext appContext) {
        synchronized (_globalLock) {
            WLDropTargetContextPeer peer =
                    (WLDropTargetContextPeer)appContext.get(DTCP_KEY);
            if (peer == null) {
                peer = new WLDropTargetContextPeer();
                appContext.put(DTCP_KEY, peer);
            }

            return peer;
        }
    }

    @Override
    protected Object getNativeData(long format) throws IOException {
        return null;
    }

    @Override
    protected void doDropDone(boolean success, int dropAction, boolean isLocal) {

    }

    /*
     * @param returnValue the drop action selected by the Java drop target.
     */
    @Override
    protected void eventProcessed(SunDropTargetEvent e, int returnValue,
                                  boolean dispatcherDone) {
        /* The native context is the pointer to the XClientMessageEvent
           structure. */
        long ctxt = getNativeDragContext();
        /* If the event was not consumed, send a response to the source. */
        try {
            if (ctxt != 0 && !e.isConsumed()) {
                // ...
            }
        } finally {

        }
    }
}
