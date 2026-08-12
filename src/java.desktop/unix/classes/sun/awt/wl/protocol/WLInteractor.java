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
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

import static sun.awt.wl.protocol.WLNative.wl_argument;

@SuppressWarnings("restricted")
public final class WLInteractor {
    public static final MemorySegment proxyDispatcherFunc;
    static {
        try {
            MethodHandle proxyDispatcherHandle = MethodHandles.lookup().findStatic(
                    WLInteractor.class, "proxyDispatcher",
                    MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class, int.class, MemorySegment.class, MemorySegment.class));
            FunctionDescriptor proxyDispatcherDesc = FunctionDescriptor.of(
                    WLNative.c_int, WLNative.c_pointer, WLNative.c_pointer,
                    WLNative.uint32_t, WLNative.c_pointer, WLNative.c_pointer);
            proxyDispatcherFunc = WLNative.linker.upcallStub(proxyDispatcherHandle, proxyDispatcherDesc, WLNative.globalArena);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static int proxyDispatcher(
            MemorySegment nativeUserData,
            MemorySegment nativeTarget,
            int opcode,
            MemorySegment nativeMessage,
            MemorySegment nativeArguments) {
        WLProxy target = WLProxy.getExisting(nativeTarget);
        WLInterface wlInterface = target.getInterface();
        WLMessage request = wlInterface.getRequest(opcode);
        List<WLParameter> signature = request.getSignature();
        int n = signature.size();

        nativeArguments = nativeArguments.reinterpret(n * wl_argument.byteSize());
        Object[] arguments = new Object[n];
        for (int i = 0; i < n; ++i) {
            WLParameter parameter = signature.get(i);
            MemorySegment nativeArgument = nativeArguments.asSlice(i * wl_argument.byteSize(), wl_argument);
            arguments[i] = parameter.unmarshal(nativeArgument);
        }

        WLEvent event = new WLEvent(target, opcode, arguments);
        dispatchEvent(event);
        return 0;
    }

    private static WLPoller poller = new WLPoller();

    private static void dispatchEvent(WLEvent event) {
        // TODO
    }

    private static void pollThreadMain() {
    }

    public static void run() {
    }
}
