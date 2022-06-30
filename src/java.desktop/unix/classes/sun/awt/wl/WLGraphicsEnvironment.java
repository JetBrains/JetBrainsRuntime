/*
 * Copyright (c) 2021, 2022, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2021, 2022, JetBrains s.r.o.. All rights reserved.
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

import java.awt.GraphicsDevice;
import sun.java2d.SunGraphicsEnvironment;
import sun.java2d.SurfaceManagerFactory;
import sun.java2d.UnixSurfaceManagerFactory;
import sun.util.logging.PlatformLogger;

public class WLGraphicsEnvironment extends SunGraphicsEnvironment {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLComponentPeer");
    static {
        SurfaceManagerFactory.setInstance(new UnixSurfaceManagerFactory());
    }

    @Override
    protected int getNumScreens() {
        log.info("Not implemented: WLGraphicsEnvironment.getNumScreens()");
        return 1;
    }

    @Override
    protected GraphicsDevice makeScreenDevice(int screennum) {
        log.info("Not implemented: WLGraphicsEnvironment.makeScreenDevice(int)");
        return new WLGraphicsDevice();
    }

    @Override
    public boolean isDisplayLocal() {
        return true;
    }
}
