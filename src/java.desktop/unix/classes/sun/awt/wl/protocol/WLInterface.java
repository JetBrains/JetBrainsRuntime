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
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static sun.awt.wl.protocol.WLNative.c_pointer;
import static sun.awt.wl.protocol.WLNative.wl_interface;
import static sun.awt.wl.protocol.WLNative.wl_interface$event_count;
import static sun.awt.wl.protocol.WLNative.wl_interface$events;
import static sun.awt.wl.protocol.WLNative.wl_interface$method_count;
import static sun.awt.wl.protocol.WLNative.wl_interface$methods;
import static sun.awt.wl.protocol.WLNative.wl_interface$name;
import static sun.awt.wl.protocol.WLNative.wl_interface$version;
import static sun.awt.wl.protocol.WLNative.wl_message;
import static sun.awt.wl.protocol.WLNative.wl_message$name;
import static sun.awt.wl.protocol.WLNative.wl_message$signature;
import static sun.awt.wl.protocol.WLNative.wl_message$types;

public final class WLInterface {
    private final String name;
    private int version;
    private List<WLMessage> requests;
    private List<WLMessage> events;
    private Class<? extends WLProxy> proxyClass;
    private MethodHandle proxyConstructor;

    private int destructorOpcode;
    private List<MethodHandle> eventMethodHandles;

    private final MemorySegment nativeSegment;
    private final Consumer<LazyInit> lazyInitializer;
    private boolean initialized = false;

    private WLInterface(String name, Consumer<LazyInit> lazyInitializer) {
        this.name = name;
        this.lazyInitializer = lazyInitializer;
        this.nativeSegment = WLNative.globalArena.allocate(wl_interface);
    }

    public synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;

        LazyInit lazyInit = new LazyInit(this);
        lazyInitializer.accept(lazyInit);
        lazyInit.done();
    }

    private void doInit(
            int version,
            List<WLMessage> requests,
            List<WLMessage> events,
            Class<? extends WLProxy> proxyClass,
            Class<?> listenerClass) {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        this.version = version;
        this.requests = requests;
        this.events = events;
        this.proxyClass = proxyClass;
        try {
            this.proxyConstructor = lookup.findConstructor(proxyClass, MethodType.methodType(proxyClass, MemorySegment.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }

        Arena arena = WLNative.globalArena;
        wl_interface$name.set(nativeSegment, 0L, arena.allocateFrom(name));
        wl_interface$version.set(nativeSegment, 0L, version);
        wl_interface$methods.set(nativeSegment, 0L, allocMessageArray(arena, requests));
        wl_interface$method_count.set(nativeSegment, 0L, requests.size());
        wl_interface$events.set(nativeSegment, 0L, allocMessageArray(arena, events));
        wl_interface$event_count.set(nativeSegment, 0L, events.size());

        int destructor = -1;
        for (WLMessage request : requests) {
            if (request.isDestructor() && request.getSignature().isEmpty()) {
                if (destructor == -1) {
                    destructor = request.getOpcode();
                } else {
                    // more than one no-arguments destructor? strange
                    destructor = -1;
                    break;
                }
            }
        }
        this.destructorOpcode = destructor;

        this.eventMethodHandles = new ArrayList<>();
        for (WLMessage event : events) {
            List<WLParameter> signature = event.getSignature();
            Class<?>[] parameters = new Class[signature.size()];
            for (int i = 0; i < signature.size(); ++i) {
                parameters[i] = signature.get(i).getRepresentation();
            }
            MethodType methodType = MethodType.methodType(void.class, parameters);
            MethodHandle handle = null;
            try {
                handle = lookup.findVirtual(listenerClass, event.getName(), methodType);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            } catch (NoSuchMethodException ignored) {
                // will add a null handle
            }
            this.eventMethodHandles.add(handle);
        }
    }

    public static class LazyInit {
        private final WLInterface target;
        private int version = 1;
        private final List<WLMessage> requests = new ArrayList<>();
        private final List<WLMessage> events = new ArrayList<>();
        private Class<? extends WLProxy> proxyClass = null;
        private Class<?> listenerClass = null;

        private LazyInit(WLInterface target) {
            this.target = target;
        }

        public LazyInit version(int version) {
            this.version = version;
            return this;
        }

        public LazyInit request(String name, int sinceVersion, WLParameter... signature) {
            int opcode = requests.size();
            requests.add(new WLMessage(name, opcode, sinceVersion, Arrays.stream(signature).toList(), false));
            return this;
        }

        public LazyInit destructor(String name, int sinceVersion, WLParameter... signature) {
            int opcode = requests.size();
            requests.add(new WLMessage(name, opcode, sinceVersion, Arrays.stream(signature).toList(), true));
            return this;
        }

        public LazyInit event(String name, int sinceVersion, WLParameter... signature) {
            int opcode = events.size();
            events.add(new WLMessage(name, opcode, sinceVersion, Arrays.stream(signature).toList(), false));
            return this;
        }

        public LazyInit proxy(Class<? extends WLProxy> proxyClass) {
            this.proxyClass = proxyClass;
            return this;
        }

        public LazyInit listener(Class<?> listenerClass) {
            this.listenerClass = listenerClass;
            return this;
        }

        private void done() {
            target.doInit(version, requests, events, proxyClass, listenerClass);
        }
    }

    public static WLInterface lazy(String name, Consumer<LazyInit> configure) {
        return new WLInterface(name, configure);
    }

    private MemorySegment allocMessageArray(Arena arena, List<WLMessage> messages) {
        int n = messages.size();
        if (n == 0) {
            return MemorySegment.NULL;
        }
        MemorySegment result = arena.allocate(wl_message, n);
        for (int messageIdx = 0; messageIdx < n; ++messageIdx) {
            WLMessage message = messages.get(messageIdx);
            List<WLParameter> signature = message.getSignature();
            MemorySegment segment = result.asSlice(messageIdx * wl_message.byteSize(), wl_message);
            wl_message$name.set(segment, 0L, arena.allocateFrom(message.getName()));
            wl_message$signature.set(segment, 0L, arena.allocateFrom(message.getProtocolSignature()));

            MemorySegment types = arena.allocate(c_pointer, signature.size());
            wl_message$types.set(segment, 0L, types);

            for (int parameterIdx = 0; parameterIdx < signature.size(); ++parameterIdx) {
                WLParameter parameter = signature.get(parameterIdx);
                WLInterface parameterInterface = parameter.getInterface();
                types.setAtIndex(ValueLayout.ADDRESS, parameterIdx, parameterInterface.getNativeSegment());
            }
        }

        return result;
    }

    public MemorySegment getNativeSegment() {
        return nativeSegment;
    }

    public WLProxy makeProxy(MemorySegment nativeProxy) {
        try {
            return (WLProxy) proxyConstructor.invoke(nativeProxy);
        } catch (Error | RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public WLInterfaceVersion withVersion(int version) {
        return new WLInterfaceVersion(this, version);
    }

    public String getName() {
        return name;
    }

    public int getVersion() {
        return version;
    }

    public List<WLMessage> getRequests() {
        return requests;
    }

    public WLMessage getRequest(int opcode) {
        return requests.get(opcode);
    }

    public List<WLMessage> getEvents() {
        return events;
    }

    public WLMessage getEvent(int opcode) {
        return events.get(opcode);
    }

    public int getDestructorOpcode() {
        return destructorOpcode;
    }

    public boolean hasDefaultDestructor() {
        return destructorOpcode != -1;
    }

    public Class<? extends WLProxy> getProxyClass() {
        return proxyClass;
    }

    @Override
    public String toString() {
        return "WLInterface[" + name + "]";
    }
}
