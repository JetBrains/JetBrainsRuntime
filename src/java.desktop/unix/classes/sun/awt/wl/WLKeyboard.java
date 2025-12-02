/*
 * Copyright 2023 JetBrains s.r.o.
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

import java.awt.*;
import java.lang.reflect.InvocationTargetException;
import java.util.Timer;
import java.awt.event.InputEvent;
import java.util.TimerTask;

class WLKeyboard {
    WLKeyboard() {
        keyRepeatManager = new KeyRepeatManager();
        initialize(keyRepeatManager);
    }

    private class KeyRepeatManager {
        // Methods and fields should only be accessed from the EDT
        private final Timer timer = new Timer("WLKeyboard.KeyRepeatManager", true);
        private TimerTask currentRepeatTask;
        private int currentRepeatKeycode;
        private int delayBeforeRepeatMillis;
        private int delayBetweenRepeatMillis;

        // called from native code
        void setRepeatInfo(int charsPerSecond, int delayMillis) {
            // this function receives (0, 0) when key repeat is disabled
            assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";
            this.delayBeforeRepeatMillis = delayMillis;
            if (charsPerSecond > 0) {
                this.delayBetweenRepeatMillis = (int) (1000.0 / charsPerSecond);
            } else {
                this.delayBetweenRepeatMillis = 0;
            }

            cancelRepeat();
        }

        boolean isRepeatEnabled() {
            return this.delayBeforeRepeatMillis > 0 || this.delayBetweenRepeatMillis > 0;
        }

        void cancelRepeat() {
            assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";
            if (currentRepeatTask != null) {
                currentRepeatTask.cancel();
                currentRepeatTask = null;
                currentRepeatKeycode = 0;
            }
        }

        // called from native code
        void stopRepeat(int keycode) {
            assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";
            if (currentRepeatKeycode == keycode) {
                cancelRepeat();
            }
        }

        // called from native code
        void startRepeat(long serial, long timestamp, int keycode) {
            assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";
            cancelRepeat();
            if (keycode == 0 || !isRepeatEnabled()) {
                return;
            }

            currentRepeatKeycode = keycode;

            long delta = timestamp - System.currentTimeMillis();

            currentRepeatTask = new TimerTask() {
                @Override
                public void run() {
                    try {
                        EventQueue.invokeAndWait(() -> {
                            if (this == currentRepeatTask) {
                                handleKeyRepeat(serial, delta + System.currentTimeMillis(), keycode);
                            }
                        });
                    } catch (InterruptedException ignored) {
                    } catch (InvocationTargetException e) {
                        throw new RuntimeException(e);
                    }
                }
            };

            timer.scheduleAtFixedRate(currentRepeatTask, delayBeforeRepeatMillis, delayBetweenRepeatMillis);
        }
    }

    private final KeyRepeatManager keyRepeatManager;

    private native void initialize(KeyRepeatManager keyRepeatManager);

    public native int getModifiers();

    public native boolean isCapsLockPressed();

    public native boolean isNumLockPressed();

    public void onLostFocus() {
        assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";
        keyRepeatManager.cancelRepeat();
        cancelCompose();
    }

    private native void handleKeyRepeat(long serial, long timestamp, int keycode);

    private native void cancelCompose();

    private native int getXKBModifiersMask();
}
