/*
 * Copyright 2024 JetBrains s.r.o.
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

package sun.lwawt.macosx;

import com.jetbrains.exported.JBRApi;

import java.awt.*;
import java.awt.desktop.SystemHotkey;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

@JBRApi.Service
@JBRApi.Provides("SystemShortcuts")
public class JBRSystemShortcutsMacOS {
    @JBRApi.Provides("SystemShortcuts.Shortcut")
    public static class Shortcut {
        private final AWTKeyStroke keyStroke;
        private final String id;
        private final String description;
        private final int nativeKeyCode;

        public Shortcut(AWTKeyStroke keyStroke, String id, String description, int nativeKeyCode) {
            this.keyStroke = keyStroke;
            this.id = id;
            this.description = description;
            this.nativeKeyCode = nativeKeyCode;
        }

        public AWTKeyStroke getKeyStroke() {
            return keyStroke;
        }

        public String getId() {
            return id;
        }

        public String getDescription() {
            return description;
        }

        public int getNativeKeyCode() {
            return nativeKeyCode;
        }

        @Override
        public String toString() {
            return "JBR.SystemShortcuts.Shortcut[id = " + id + ", description = " + description + ", keyStroke = " + keyStroke + ", nativeKeyCode = " + nativeKeyCode + "]";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Shortcut shortcut = (Shortcut) o;
            return nativeKeyCode == shortcut.nativeKeyCode && Objects.equals(keyStroke, shortcut.keyStroke) && Objects.equals(id, shortcut.id) && Objects.equals(description, shortcut.description);
        }

        @Override
        public int hashCode() {
            return Objects.hash(keyStroke, id, description, nativeKeyCode);
        }
    }

    @JBRApi.Provided("SystemShortcuts.ChangeEventListener")
    public interface ChangeEventListener {
        void handleSystemShortcutsChangeEvent();
    }

    public List<Shortcut> querySystemShortcuts() {
        List<Shortcut> result = new ArrayList<>();
        for (var hotkey : SystemHotkey.readSystemHotkeys()) {
            result.add(new Shortcut(hotkey, hotkey.getId(), hotkey.getDescription(), hotkey.getNativeKeyCode()));
        }
        return result;
    }

    void setChangeListener(ChangeEventListener listener) {
        SystemHotkey.setChangeEventHandler(listener::handleSystemShortcutsChangeEvent);
    }
}
