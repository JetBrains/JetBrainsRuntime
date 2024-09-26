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
        private final Timer timer = new Timer("WLKeyboard.KeyRepeatManager", true);
        private TimerTask currentRepeatTask;
        private int delayBeforeRepeatMillis;
        private int delayBetweenRepeatMillis;

        void setRepeatInfo(int charsPerSecond, int delayMillis) {
            // this function receives (0, 0) when key repeat is disabled
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
            if (currentRepeatTask != null) {
                currentRepeatTask.cancel();
                currentRepeatTask = null;
            }
        }

        void startRepeat(long timestamp, int keycode) {
            cancelRepeat();
            if (keycode == 0 || !isRepeatEnabled()) {
                return;
            }

            long delta = timestamp - System.currentTimeMillis();
            currentRepeatTask = new TimerTask() {
                @Override
                public void run() {
                    try {
                        EventQueue.invokeAndWait(() -> {
                            handleKeyPress(delta + System.currentTimeMillis(), keycode, true);
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

    public static final int XKB_SHIFT_MASK = 1 << 0;
    public static final int XKB_CAPS_LOCK_MASK = 1 << 1;
    public static final int XKB_CTRL_MASK = 1 << 2;
    public static final int XKB_ALT_MASK = 1 << 3;
    public static final int XKB_NUM_LOCK_MASK = 1 << 4;
    public static final int XKB_MOD3_MASK = 1 << 5;
    public static final int XKB_META_MASK = 1 << 6;
    public static final int XKB_MOD5_MASK = 1 << 7;

    private final KeyRepeatManager keyRepeatManager;

    private native void initialize(KeyRepeatManager keyRepeatManager);

    public int getModifiers() {
        int result = 0;
        int mask = getXKBModifiersMask();

        if ((mask & XKB_SHIFT_MASK) != 0) {
            result |= InputEvent.SHIFT_DOWN_MASK;
        }

        if ((mask & XKB_CTRL_MASK) != 0) {
            result |= InputEvent.CTRL_DOWN_MASK;
        }

        if ((mask & XKB_ALT_MASK) != 0) {
            result |= InputEvent.ALT_DOWN_MASK;
        }

        if ((mask & XKB_META_MASK) != 0) {
            result |= InputEvent.META_DOWN_MASK;
        }

        return result;
    }

    public boolean isCapsLockPressed() {
        return (getXKBModifiersMask() & XKB_CAPS_LOCK_MASK) != 0;
    }

    public boolean isNumLockPressed() {
        return (getXKBModifiersMask() & XKB_NUM_LOCK_MASK) != 0;
    }

    public void onLostFocus() {
        keyRepeatManager.cancelRepeat();
        cancelCompose();
    }

    private native void handleKeyPress(long timestamp, int keycode, boolean isRepeat);

    private native void cancelCompose();

    private native int getXKBModifiersMask();
}
