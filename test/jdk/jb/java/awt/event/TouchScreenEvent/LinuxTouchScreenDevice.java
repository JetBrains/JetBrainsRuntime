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

import java.awt.Toolkit;
import java.io.Closeable;
import java.io.IOException;

public class LinuxTouchScreenDevice implements Closeable {
    // TODO add product id
    private int width;
    private int height;
    private int fileDescriptor;

    static {
        System.loadLibrary("touchscreen_device");
    }

    public LinuxTouchScreenDevice() throws IOException {
        this(Toolkit.getDefaultToolkit().getScreenSize().width, Toolkit.getDefaultToolkit().getScreenSize().height);
    }

    public LinuxTouchScreenDevice(int width, int height) throws IOException {
        this.width = width;
        this.height = height;
        fileDescriptor = create(getWidth(), getHeight());
        checkCompletion(fileDescriptor,
                "Failed to create virtual touchscreen device");
    }

    @Override
    public void close() throws IOException {
        checkCompletion(destroy(fileDescriptor),
                "Failed to close touchscreen device");
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public void click(int trackingId, int x, int y) throws IOException {
        checkCompletion(clickImpl(fileDescriptor, trackingId, x, y),
                "Failed to click on touchscreen device");

    }

    public void move(int trackingId, int fromX, int fromY, int toX, int toY) throws IOException {
        checkCompletion(moveImpl(fileDescriptor, trackingId, fromX, fromY, toX, toY),
                "Failed to move on virtual touchscreen device");
    }

    private void checkCompletion(int code, String errorMessage) throws IOException {
        if (code < 0) {
            throw new IOException(errorMessage);
        }
    }

    private native int create(int width, int height);

    private native int destroy(int fd);

    private native int clickImpl(int fd, int trackingId, int x, int y);

    private native int moveImpl(int fd, int trackingId, int fromX, int fromY, int toX, int toY);
}


