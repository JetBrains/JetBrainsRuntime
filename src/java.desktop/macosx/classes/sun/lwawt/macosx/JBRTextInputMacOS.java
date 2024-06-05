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

@JBRApi.Service
@JBRApi.Provides("TextInput")
public class JBRTextInputMacOS {
    private EventListener listener;

    JBRTextInputMacOS() {
        var desc = (CInputMethodDescriptor) LWCToolkit.getLWCToolkit().getInputMethodAdapterDescriptor();
        desc.textInputEventListener = new EventListener() {
            public void handleSelectTextRangeEvent(SelectTextRangeEvent event) {
                // This listener is called on the EDT
                synchronized (JBRTextInputMacOS.this) {
                    if (listener != null) {
                        listener.handleSelectTextRangeEvent(event);
                    }
                }
            }
        };
    }

    @JBRApi.Provides("TextInput.SelectTextRangeEvent")
    public static class SelectTextRangeEvent {
        private final Object source;
        private final int begin;
        private final int length;

        public SelectTextRangeEvent(Object source, int begin, int length) {
            this.source = source;
            this.begin = begin;
            this.length = length;
        }

        public Object getSource() {
            return source;
        }

        public int getBegin() {
            return begin;
        }

        public int getLength() {
            return length;
        }
    }

    @JBRApi.Provided("TextInput.EventListener")
    public interface EventListener {
        void handleSelectTextRangeEvent(SelectTextRangeEvent event);
    }

    public synchronized void setGlobalEventListener(EventListener listener) {
        this.listener = listener;
    }
}
