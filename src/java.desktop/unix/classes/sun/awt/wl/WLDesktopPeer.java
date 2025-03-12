/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2025, JetBrains s.r.o.. All rights reserved.
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

import sun.awt.SunToolkit;
import sun.awt.UNIXToolkit;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;

import java.awt.Desktop.Action;
import java.awt.peer.DesktopPeer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class WLDesktopPeer implements DesktopPeer {

    // supportedActions may be changed from native within the init() call
    private static final List<Desktop.Action> supportedActions
            = new ArrayList<>(Arrays.asList(Action.OPEN, Action.MAIL, Action.BROWSE));

    private static boolean nativeLibraryLoaded = false;
    private static boolean initExecuted = false;

    private static void initWithLock(){
        SunToolkit.awtLock();
        try {
            if (!initExecuted) {
                nativeLibraryLoaded = init(
                        UNIXToolkit.getEnabledGtkVersion().getNumber(),
                        UNIXToolkit.isGtkVerbose());
            }
        } finally {
            initExecuted = true;
            SunToolkit.awtUnlock();
        }
    }

    WLDesktopPeer(){
        initWithLock();
    }

    static boolean isDesktopSupported() {
        initWithLock();
        return nativeLibraryLoaded && !supportedActions.isEmpty();
    }

    public boolean isSupported(Action type) {
        return supportedActions.contains(type);
    }

    public void open(File file) throws IOException {
        try {
            launch(file.toURI());
        } catch (MalformedURLException e) {
            throw new IOException(file.toString());
        }
    }

    public void edit(File file) throws IOException {
        throw new UnsupportedOperationException("The current platform " +
                "doesn't support the EDIT action.");
    }

    public void print(File file) throws IOException {
        throw new UnsupportedOperationException("The current platform " +
                "doesn't support the PRINT action.");
    }

    public void mail(URI uri) throws IOException {
        launch(uri);
    }

    public void browse(URI uri) throws IOException {
        launch(uri);
    }

    private void launch(URI uri) throws IOException {
        byte[] uriByteArray = ( uri.toString() + '\0' ).getBytes();
        boolean result = false;
        SunToolkit.awtLock();
        try {
            if (!nativeLibraryLoaded) {
                throw new IOException("Failed to load native libraries.");
            }
            result = gnome_url_show(uriByteArray);
        } finally {
            SunToolkit.awtUnlock();
        }
        if (!result) {
            throw new IOException("Failed to show URI:" + uri);
        }
    }

    private native boolean gnome_url_show(byte[] url);
    private static native boolean init(int gtkVersion, boolean verbose);
}
