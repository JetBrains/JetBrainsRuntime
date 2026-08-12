/*
 * Copyright 2026 JetBrains s.r.o.
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

package sun.awt.wl.protocol;

import java.io.FileDescriptor;

public final class WLEvent {
    private final WLProxy target;
    private final int opcode;
    private final Object[] arguments;
    private boolean isConsumed = false;

    public WLEvent(WLProxy target, int opcode, Object[] arguments) {
        this.target = target;
        this.opcode = opcode;
        this.arguments = arguments;
    }

    public WLProxy getTarget() {
        return target;
    }

    public int getOpcode() {
        return opcode;
    }

    public Object[] getArguments() {
        return arguments;
    }

    public boolean isConsumed() {
        return isConsumed;
    }

    public void consume() {
        if (isConsumed) {
            throw new IllegalStateException("cannot consume a consumed event");
        }
        isConsumed = true;
    }

    public void discard() {
        if (isConsumed) {
            throw new IllegalStateException("cannot discard a consumed event");
        }
        closeFileDescriptors();
        isConsumed = true;
    }

    private void closeFileDescriptors() {
        for (int i = 0; i < arguments.length; ++i) {
            Object argument = arguments[i];
            if (argument instanceof FileDescriptor fileDescriptor) {
                WLNative.closeFd(WLNative.fileDescriptorToInt(fileDescriptor));
                arguments[i] = null;
            }
        }
    }
}
