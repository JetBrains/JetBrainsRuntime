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

import jdk.internal.misc.InnocuousThread;
import java.awt.FileDialog;
import java.awt.peer.FileDialogPeer;
import java.io.File;
import java.io.FilenameFilter;
import sun.awt.AWTAccessor;
import sun.awt.SunToolkit;


final class GtkFileDialogPeer extends WLDialogPeer implements FileDialogPeer {

    // A pointer to the native GTK FileChooser widget
    private volatile long widget;
    private volatile boolean quit;

    GtkFileDialogPeer(FileDialog fd) {
        super(fd);
    }

    private static native void initIDs();
    private native void run(String title, int mode, String dir, String file,
                            FilenameFilter filter, boolean isMultipleMode);
    private native void quit();
    private native void toFrontImpl(long timestamp);

    static {
        initIDs();
    }

    @Override
    public void toFront() {
        long timestamp = WLToolkit.getInputState().getTimestamp();
        toFrontImpl(timestamp);
    }

    @Override
    public native void setBounds(int x, int y, int width, int height, int op);

    /**
     * Called exclusively by the native C code.
     */
    private void setFileInternal(String directory, String[] filenames) {
        AWTAccessor.FileDialogAccessor accessor = AWTAccessor.getFileDialogAccessor();
        FileDialog fd = (FileDialog) target;
        if (filenames == null) {
            accessor.setDirectory(fd, null);
            accessor.setFile(fd, null);
            accessor.setFiles(fd, null);
        } else {
            // Fix 6987233: add the trailing slash if it's absent
            String withSeparator = directory;
            if (directory != null) {
                withSeparator = directory.endsWith(File.separator) ?
                        directory : (directory + File.separator);
            }
            accessor.setDirectory(fd, withSeparator);
            accessor.setFile(fd, filenames[0]);

            File[] files = new File[filenames.length];
            for (int i = 0; i < filenames.length; i++) {
                files[i] = new File(directory, filenames[i]);
            }
            accessor.setFiles(fd, files);
        }
    }

    /**
     * Called exclusively by the native C code.
     */
    private boolean filenameFilterCallback(String fullname) {
        FileDialog fd = (FileDialog) target;

        if (fd.getFilenameFilter() == null) {
            // no filter, accept all.
            return true;
        }

        File file = new File(fullname);
        return fd.getFilenameFilter().accept(new File(file.getParent()), file.getName());
    }

    @Override
    public void setVisible(boolean b) {
        SunToolkit.awtLock();
        try {
            quit = !b;
            if (b) {
                InnocuousThread.newThread("ShowGtkFileDialog", this::showNativeDialog).start();
            } else {
                quit();
                FileDialog fd = (FileDialog) target;
                fd.setVisible(false);
            }
        } finally {
            SunToolkit.awtUnlock();
        }
    }

    @Override
    public void dispose() {
        SunToolkit.awtLock();
        try {
            quit = true;
            quit();
        }
        finally {
            SunToolkit.awtUnlock();
        }
        super.dispose();
    }

    @Override
    public void setDirectory(String dir) {
        // We do not implement this method because we
        // have delegated to FileDialog#setDirectory
    }

    @Override
    public void setFile(String file) {
        // We do not implement this method because we
        // have delegated to FileDialog#setFile
    }

    @Override
    public void setFilenameFilter(FilenameFilter filter) {
        // We do not implement this method because we
        // have delegated to FileDialog#setFilenameFilter
    }

    private void showNativeDialog() {
        FileDialog fd = (FileDialog) target;
        String dirname = fd.getDirectory();
        // File path has a priority over the directory path.
        String filename = fd.getFile();
        if (filename != null) {
            final File file = new File(filename);
            if (fd.getMode() == FileDialog.LOAD
                    && dirname != null
                    && file.getParent() == null) {
                filename = dirname + (dirname.endsWith(File.separator) ? "" :
                        File.separator) + filename;
            }
            if (fd.getMode() == FileDialog.SAVE && file.getParent() != null) {
                filename = file.getName();
                dirname = file.getParent();
            }
        }
        if (!quit) {
            run(fd.getTitle(), fd.getMode(), dirname, filename,
                    fd.getFilenameFilter(), fd.isMultipleMode());
        }
    }

    /**
     * Called by native code when GTK dialog is created.
     */
    boolean setWindow() {
        return !quit && widget != 0;
    }

    /**
     * Called by native code when GTK dialog is closing.
     */
    private void onClose() {
        widget = 0;
        FileDialog fd = (FileDialog) target;
        fd.setVisible(false);
    }
}
