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
import java.awt.event.InputEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@JBRApi.Service
@JBRApi.Provides("SystemShortcuts")
public class JBRSystemShortcutsMacOS {
    @JBRApi.Provides("SystemShortcuts.Shortcut")
    public static class Shortcut {
        private final int keyCode;
        private final char keyChar;
        private final int modifiers;
        private final String id;
        private final String description;

        public Shortcut(int keyCode, char keyChar, int modifiers, String id, String description) {
            this.keyCode = keyCode;
            this.keyChar = keyChar;
            this.modifiers = modifiers;
            this.id = id;
            this.description = description;
        }

        public int getKeyCode() {
            return keyCode;
        }

        public char getKeyChar() {
            return keyChar;
        }

        public int getModifiers() {
            return modifiers;
        }

        public String getId() {
            return id;
        }

        public String getDescription() {
            return description;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Shortcut shortcut)) return false;
            return keyCode == shortcut.keyCode && keyChar == shortcut.keyChar && modifiers == shortcut.modifiers && Objects.equals(id, shortcut.id) && Objects.equals(description, shortcut.description);
        }

        @Override
        public int hashCode() {
            return Objects.hash(keyCode, keyChar, modifiers, id, description);
        }

        @Override
        public String toString() {
            return "Shortcut{" +
                    "keyCode=" + keyCode +
                    ", keyChar=" + (int)keyChar +
                    ", modifiers=" + modifiers +
                    ", id='" + id + '\'' +
                    ", description='" + description + '\'' +
                    '}';
        }
    }

    @JBRApi.Provided("SystemShortcuts.ChangeEventListener")
    public interface ChangeEventListener {
        void handleSystemShortcutsChangeEvent();
    }

    public Shortcut[] querySystemShortcuts() {
        var hotkeys = SystemHotkey.readSystemHotkeys();
        var result = new Shortcut[hotkeys.size()];
        for (int i = 0; i < hotkeys.size(); ++i) {
            var hotkey = hotkeys.get(i);
            result[i] = new Shortcut(
                    hotkey.getKeyCode(),
                    hotkey.getKeyChar(),
                    hotkey.getModifiers(),
                    hotkey.getId(),
                    hotkey.getDescription()
            );
        }

        return result;
    }

    public void setChangeListener(ChangeEventListener listener) {
        SystemHotkey.setChangeEventHandler(listener::handleSystemShortcutsChangeEvent);
    }
}
