/*
 * Copyright 2000-2020 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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


