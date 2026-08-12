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

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static sun.awt.wl.protocol.WLNative.c_pointer;

public final class WLMessage {
    private final String name;
    private final int opcode;
    private final int sinceVersion;
    private final List<WLParameter> signature;
    private final boolean isDestructor;

    public WLMessage(String name, int opcode, int sinceVersion, List<WLParameter> signature, boolean isDestructor) {
        if (opcode < 0) {
            throw new IllegalArgumentException("opcode must be >= 0");
        }
        this.name = name;
        this.opcode = opcode;
        this.sinceVersion = sinceVersion;
        this.signature = signature;
        this.isDestructor = isDestructor;
    }

    public String getProtocolSignature() {
        StringBuilder result = new StringBuilder();
        if (sinceVersion > 1) {
            result.append(sinceVersion);
        }
        for (WLParameter parameter : signature) {
            result.append(parameter.getProtocolSignature());
        }
        return result.toString();
    }

    @SuppressWarnings("restricted")
    public MethodHandle getNativeMethodHandle() {
        int n = signature.size();
        MemoryLayout[] parameters = new MemoryLayout[n + 2];
        parameters[0] = c_pointer; // struct wl_proxy *
        parameters[1] = c_pointer; // user pointer
        for (int i = 0; i < n; ++i) {
            parameters[i + 2] = signature.get(i).getArgumentLayout();
        }
        FunctionDescriptor descriptor = FunctionDescriptor.ofVoid();
        return WLNative.linker.downcallHandle(descriptor);
    }

    public String getName() {
        return name;
    }

    public int getOpcode() {
        return opcode;
    }

    public int getSinceVersion() {
        return sinceVersion;
    }

    public List<WLParameter> getSignature() {
        return signature;
    }

    public boolean isDestructor() {
        return isDestructor;
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder();
        result.append(name);
        result.append('(');
        boolean first = true;
        for (WLParameter parameter : signature) {
            if (first) {
                first = false;
            } else {
                result.append(", ");
            }
            result.append(parameter.toString());
        }
        result.append(')');
        return result.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        WLMessage wlMessage = (WLMessage) o;
        return opcode == wlMessage.opcode && sinceVersion == wlMessage.sinceVersion &&
                isDestructor == wlMessage.isDestructor && Objects.equals(name, wlMessage.name) &&
                Objects.equals(signature, wlMessage.signature);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, opcode, sinceVersion, signature, isDestructor);
    }
}
