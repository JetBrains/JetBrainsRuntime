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

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static sun.awt.wl.protocol.WLNative.WL_MARSHAL_FLAG_DESTROY;
import static sun.awt.wl.protocol.WLNative.wl_argument;
import static sun.awt.wl.protocol.WLNative.wl_proxy_add_dispatcher;
import static sun.awt.wl.protocol.WLNative.wl_proxy_get_id;
import static sun.awt.wl.protocol.WLNative.wl_proxy_get_user_data;
import static sun.awt.wl.protocol.WLNative.wl_proxy_get_version;
import static sun.awt.wl.protocol.WLNative.wl_proxy_marshal_array_flags;

public class WLProxy {
    private final WLInterface wlInterface;
    private final MemorySegment nativeProxy;
    private final int protocolId;
    private final int version;

    private static final Map<Integer, WLProxy> proxyMap = new HashMap<>();

    public WLProxy(WLInterface wlInterface, MemorySegment nativeProxy) {
        if (wlInterface == null) {
            throw new NullPointerException("wlInterface is null");
        }
        if (nativeProxy == null || nativeProxy.address() == 0) {
            throw new NullPointerException("nativeProxy is null");
        }

        this.wlInterface = wlInterface;
        this.nativeProxy = nativeProxy;
        this.protocolId = wl_proxy_get_id(nativeProxy);
        this.version = wl_proxy_get_version(nativeProxy);

        synchronized (proxyMap) {
            proxyMap.put(protocolId, this);
        }

        if (!wlInterface.getName().equals("wl_display")) {
            MemorySegment oldUserData = wl_proxy_get_user_data(nativeProxy);
            wl_proxy_add_dispatcher(nativeProxy, WLInteractor.proxyDispatcherFunc, MemorySegment.NULL, oldUserData);
        }
    }

    public static WLProxy getExisting(MemorySegment nativeProxy) {
        if (nativeProxy == null || nativeProxy.address() == 0) {
            throw new NullPointerException("nativeProxy is null");
        }

        int protocolId = wl_proxy_get_id(nativeProxy);
        WLProxy proxy;
        synchronized (proxyMap) {
            proxy = proxyMap.get(protocolId);
        }

        if (proxy == null) {
            throw new IllegalStateException("nativeProxy not registered");
        }

        return proxy;
    }

    public WLProxy marshalRequest(int opcode, Object... arguments) {
        if (opcode < 0 || opcode >= wlInterface.getRequests().size()) {
            throw new IllegalArgumentException("invalid opcode");
        }

        WLMessage request = wlInterface.getRequest(opcode);
        if (request.getSinceVersion() > version) {
            throw new IllegalArgumentException("cannot invoke " + wlInterface.getName() + "::" + request.getName() +
                    ": this request exists only since version " + request.getSinceVersion() + ", but the proxy object is of version " + version);
        }
        List<WLParameter> signature = request.getSignature();
        if (signature.size() != arguments.length) {
            throw new IllegalArgumentException(
                    "wrong number of parameters to request " + wlInterface.getName() + "::" + request.getName() +
                    ": expected " + signature.size() + ", got " + arguments.length);
        }

        WLInterface returnType = signature
                .stream().filter(parameter -> parameter instanceof WLParameter.NewIdParameter)
                .findFirst().map(WLParameter::getInterface).orElse(null);

        int proxyVersion = this.version;

        int flags = 0;
        if (request.isDestructor()) {
            flags |= WL_MARSHAL_FLAG_DESTROY;
        }

        MemorySegment resultNative;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment nativeArguments = arena.allocate(wl_argument, signature.size());
            for (int i = 0; i < signature.size(); ++i) {
                WLParameter parameter = signature.get(i);
                Object argument = arguments[i];
                if (parameter instanceof WLParameter.NewIdParameter newId) {
                    if (newId.isTyped()) {
                        continue;
                    }
                    // untyped new_id
                    WLInterfaceVersion interfaceVersion = (WLInterfaceVersion) argument;
                    returnType = interfaceVersion.getInterface();
                    proxyVersion = interfaceVersion.getVersion();
                    continue;
                }
                MemorySegment nativeArgument = nativeArguments.asSlice(i * wl_argument.byteSize(), wl_argument);
                parameter.marshal(nativeArgument, arena, argument);
            }

            resultNative = wl_proxy_marshal_array_flags(
                    nativeProxy, opcode,
                    returnType != null ? returnType.getNativeSegment() : MemorySegment.NULL,
                    proxyVersion, flags, nativeArguments);
        }

        WLProxy result;
        if (returnType != null) {
            result = returnType.makeProxy(resultNative);
        } else {
            result = null;
        }

        if (request.isDestructor()) {
            doDispose();
        }

        return result;
    }

    private void doDispose() {
        synchronized (proxyMap) {
            proxyMap.remove(protocolId);
        }
    }

    public WLInterface getInterface() {
        return wlInterface;
    }

    public MemorySegment getNativeSegment() {
        return nativeProxy;
    }

    public long getNativePtr() {
        return nativeProxy.address();
    }

    public int getProtocolId() {
        return protocolId;
    }

    public int getVersion() {
        return version;
    }
}
