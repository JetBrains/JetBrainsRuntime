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
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

@SuppressWarnings("restricted")
public final class WLNative {
    public static final Linker linker = Linker.nativeLinker();
    public static final Arena globalArena = Arena.global();
    public static final SymbolLookup libWaylandClient = SymbolLookup.libraryLookup("libwayland-client.so.0", globalArena);

    public static final int WL_MARSHAL_FLAG_DESTROY = 0x01;

    public static final short POLLIN = (short) getConstant("POLLIN");
    public static final short POLLOUT = (short) getConstant("POLLOUT");
    public static final short POLLERR = (short) getConstant("POLLERR");
    public static final short POLLHUP = (short) getConstant("POLLHUP");

    public static final ValueLayout c_short = ValueLayout.JAVA_SHORT;
    public static final ValueLayout c_int = ValueLayout.JAVA_INT;
    public static final ValueLayout c_string = ValueLayout.ADDRESS;
    public static final ValueLayout c_pointer = ValueLayout.ADDRESS;
    public static final ValueLayout int32_t = c_int;
    public static final ValueLayout uint32_t = int32_t;
    public static final ValueLayout wl_fixed_t = int32_t;
    public static final ValueLayout size_t = (ValueLayout) linker.canonicalLayouts().get("size_t");

    public static final MemoryLayout wl_array = MemoryLayout.structLayout(
            size_t.withName("size"),
            size_t.withName("alloc"),
            c_pointer.withName("data")
    );
    public static final VarHandle wl_array$size = field(wl_array, "size");
    public static final VarHandle wl_array$alloc = field(wl_array, "alloc");
    public static final VarHandle wl_array$data = field(wl_array, "data");

    public static final MemoryLayout wl_argument = MemoryLayout.unionLayout(
            int32_t.withName("int"),
            uint32_t.withName("uint"),
            wl_fixed_t.withName("fixed"),
            c_string.withName("string"),
            c_pointer.withName("object"),
            uint32_t.withName("new_id"),
            c_pointer.withName("array"),
            int32_t.withName("fd")
    );
    public static final VarHandle wl_argument$int = field(wl_argument, "int");
    public static final VarHandle wl_argument$uint = field(wl_argument, "uint");
    public static final VarHandle wl_argument$fixed = field(wl_argument, "fixed");
    public static final VarHandle wl_argument$string = field(wl_argument, "string");
    public static final VarHandle wl_argument$object = field(wl_argument, "object");
    public static final VarHandle wl_argument$new_id = field(wl_argument, "new_id");
    public static final VarHandle wl_argument$array = field(wl_argument, "array");
    public static final VarHandle wl_argument$fd = field(wl_argument, "fd");

    public static final MemoryLayout wl_message = MemoryLayout.structLayout(
            c_string.withName("name"),
            c_string.withName("signature"),
            c_pointer.withName("types")
    );
    public static final VarHandle wl_message$name = field(wl_message, "name");
    public static final VarHandle wl_message$signature = field(wl_message, "signature");
    public static final VarHandle wl_message$types = field(wl_message, "types");

    public static final MemoryLayout wl_interface = MemoryLayout.structLayout(
            c_string.withName("name"),
            c_int.withName("version"),
            c_int.withName("method_count"),
            c_pointer.withName("methods"),
            c_int.withName("event_count"),
            c_pointer.withName("events")
    );
    public static final VarHandle wl_interface$name = field(wl_interface, "name");
    public static final VarHandle wl_interface$version = field(wl_interface, "version");
    public static final VarHandle wl_interface$method_count = field(wl_interface, "method_count");
    public static final VarHandle wl_interface$methods = field(wl_interface, "methods");
    public static final VarHandle wl_interface$event_count = field(wl_interface, "event_count");
    public static final VarHandle wl_interface$events = field(wl_interface, "events");

    public static final MemoryLayout pollfd = MemoryLayout.structLayout(
            c_int.withName("fd"),
            c_short.withName("events"),
            c_short.withName("revents")
    );
    public static final VarHandle pollfd$fd = field(pollfd, "fd");
    public static final VarHandle pollfd$events = field(pollfd, "events");
    public static final VarHandle pollfd$revents = field(pollfd, "revents");

    // struct wl_display * wl_display_connect(const char *name);
    private static final MethodHandle wl_display_connect = function(
            libWaylandClient, "wl_display_connect", c_pointer, c_string);
    public static MemorySegment wl_display_connect(MemorySegment name) {
        return invokeUnchecked(() -> (MemorySegment) wl_display_connect.invokeExact(name));
    }

    // void wl_display_disconnect(struct wl_display *display);
    private static final MethodHandle wl_display_disconnect = functionVoid(
            libWaylandClient, "wl_display_disconnect", c_pointer);
    public static void wl_display_disconnect(MemorySegment display) {
        invokeVoidUnchecked(() -> wl_display_disconnect.invokeExact(display));
    }

    // int wl_display_get_fd(struct wl_display *display);
    private static final MethodHandle wl_display_get_fd = function(
            libWaylandClient, "wl_display_get_fd", c_int, c_pointer);
    public static int wl_display_get_fd(MemorySegment display) {
        return invokeUnchecked(() -> (int) wl_display_get_fd.invokeExact(display));
    }

    // void wl_proxy_set_user_data(struct wl_proxy *proxy, void *user_data);
    private static final MethodHandle wl_proxy_set_user_data = functionVoid(
            libWaylandClient, "wl_proxy_set_user_data", c_pointer, c_pointer);
    public static void wl_proxy_set_user_data(MemorySegment proxy, MemorySegment userData) {
        invokeVoidUnchecked(() -> wl_proxy_set_user_data.invokeExact(proxy, userData));
    }

    // void * wl_proxy_get_user_data(struct wl_proxy *proxy);
    private static final MethodHandle wl_proxy_get_user_data = function(
            libWaylandClient, "wl_proxy_get_user_data", c_pointer, c_pointer);
    public static MemorySegment wl_proxy_get_user_data(MemorySegment proxy) {
        return invokeUnchecked(() -> (MemorySegment) wl_proxy_get_user_data.invokeExact(proxy));
    }

    // uint32_t wl_proxy_get_id(struct wl_proxy *proxy);
    private static final MethodHandle wl_proxy_get_id = function(
            libWaylandClient, "wl_proxy_get_id", uint32_t, c_pointer);
    public static int wl_proxy_get_id(MemorySegment proxy) {
        return invokeUnchecked(() -> (int) wl_proxy_get_id.invokeExact(proxy));
    }

    // uint32_t wl_proxy_get_version(struct wl_proxy *proxy);
    private static final MethodHandle wl_proxy_get_version = function(
            libWaylandClient, "wl_proxy_get_version", uint32_t, c_pointer);
    public static int wl_proxy_get_version(MemorySegment proxy) {
        return invokeUnchecked(() -> (int) wl_proxy_get_version.invokeExact(proxy));
    }

    // int wl_display_dispatch_pending(struct wl_display *display);
    private static final MethodHandle wl_display_dispatch_pending = function(
            libWaylandClient, "wl_display_dispatch_pending", c_int, c_pointer);
    public static int wl_display_dispatch_pending(MemorySegment display) {
        return invokeUnchecked(() -> (int) wl_display_dispatch_pending.invokeExact(display));
    }

    // int wl_display_flush(struct wl_display *display);
    private static final MethodHandle wl_display_flush = function(
            libWaylandClient, "wl_display_flush", c_int, c_pointer);
    public static int wl_display_flush(MemorySegment display) {
        return invokeUnchecked(() -> (int) wl_display_flush.invokeExact(display));
    }

    // int wl_display_roundtrip(struct wl_display *display);
    private static final MethodHandle wl_display_roundtrip = function(
            libWaylandClient, "wl_display_roundtrip", c_int, c_pointer);
    public static int wl_display_roundtrip(MemorySegment display) {
        return invokeUnchecked(() -> (int) wl_display_roundtrip.invokeExact(display));
    }

    // int wl_display_prepare_read(struct wl_display *display);
    private static final MethodHandle wl_display_prepare_read = function(
            libWaylandClient, "wl_display_prepare_read", c_int, c_pointer);
    public static int wl_display_prepare_read(MemorySegment display) {
        return invokeUnchecked(() -> (int) wl_display_prepare_read.invokeExact(display));
    }

    // void wl_display_cancel_read(struct wl_display *display);
    private static final MethodHandle wl_display_cancel_read = functionVoid(
            libWaylandClient, "wl_display_cancel_read", c_pointer);
    public static void wl_display_cancel_read(MemorySegment display) {
        invokeVoidUnchecked(() -> wl_display_cancel_read.invokeExact(display));
    }

    // int wl_display_read_events(struct wl_display *display);
    private static final MethodHandle wl_display_read_events = function(
            libWaylandClient, "wl_display_read_events", c_int, c_pointer);
    public static int wl_display_read_events(MemorySegment display) {
        return invokeUnchecked(() -> (int) wl_display_read_events.invokeExact(display));
    }

    // int wl_display_get_error(struct wl_display *display);
    private static final MethodHandle wl_display_get_error = function(
            libWaylandClient, "wl_display_get_error", c_int, c_pointer);
    public static int wl_display_get_error(MemorySegment display) {
        return invokeUnchecked(() -> (int) wl_display_get_error.invokeExact(display));
    }

    // uint32_t wl_display_get_protocol_error(
    //     struct wl_display *display,
    //	   const struct wl_interface **interface,
    //     uint32_t *id);
    private static final MethodHandle wl_display_get_protocol_error = function(
            libWaylandClient, "wl_display_get_protocol_error", uint32_t,
            c_pointer /* struct wl_display *display */,
            c_pointer /* const struct wl_interface **interface */,
            c_pointer /* uint32_t *id */);
    public static int wl_display_get_protocol_error(MemorySegment display, MemorySegment interface_, MemorySegment id) {
        return invokeUnchecked(() -> (int) wl_display_get_protocol_error.invokeExact(display, interface_, id));
    }

    // int wl_proxy_add_dispatcher(
    //     struct wl_proxy *proxy,
    //     wl_dispatcher_func_t dispatcher_func,
    //     const void * dispatcher_data,
    //     void *data);
    private static final MethodHandle wl_proxy_add_dispatcher = function(
            libWaylandClient, "wl_proxy_add_dispatcher", c_int,
            c_pointer /* struct wl_proxy *proxy */,
            c_pointer /* wl_dispatcher_func_t dispatcher_func */,
            c_pointer /* const void * dispatcher_data */,
            c_pointer /* void *data */);
    public static int wl_proxy_add_dispatcher(
            MemorySegment proxy,
            MemorySegment dispatcher_func,
            MemorySegment dispatcher_data,
            MemorySegment data) {
        return invokeUnchecked(() -> (int) wl_proxy_add_dispatcher.invokeExact(proxy, dispatcher_func, dispatcher_data, data));
    }

    // struct wl_proxy * wl_proxy_marshal_array_flags(
    //     struct wl_proxy *proxy,
    //     uint32_t opcode,
    //     const struct wl_interface *interface,
    //	   uint32_t version,
    //	   uint32_t flags,
    //	   union wl_argument *args);
    private static final MethodHandle wl_proxy_marshal_array_flags = function(
            libWaylandClient, "wl_proxy_marshal_array_flags", c_pointer,
            c_pointer /* struct wl_proxy *proxy */,
            uint32_t /* uint32_t opcode */,
            c_pointer /* const struct wl_interface *interface */,
            uint32_t /* uint32_t version */,
            uint32_t /* uint32_t flags */,
            c_pointer /* union wl_argument *args */);
    public static MemorySegment wl_proxy_marshal_array_flags(
            MemorySegment proxy,
            int opcode,
            MemorySegment interface_,
            int version,
            int flags,
            MemorySegment args
    ) {
        return invokeUnchecked(() -> (MemorySegment) wl_proxy_marshal_array_flags.invokeExact(
                proxy, opcode, interface_, version, flags, args));
    }

    public static long readSizeT(VarHandle handle, MemorySegment segment) {
        if (size_t instanceof ValueLayout.OfLong) {
            return (long) handle.get(segment, 0L);
        } else if (size_t instanceof ValueLayout.OfInt) {
            return (int) handle.get(segment, 0L);
        } else {
            throw new IllegalStateException("unexpected size of size_t");
        }
    }

    public static void writeSizeT(VarHandle handle, MemorySegment segment, long value) {
        if (size_t instanceof ValueLayout.OfLong) {
            handle.set(segment, 0L, value);
        } else if (size_t instanceof ValueLayout.OfInt) {
            handle.set(segment, 0L, Math.toIntExact(value));
        } else {
            throw new IllegalStateException("unexpected size of size_t");
        }
    }

    public static MemorySegment makeNativeSegment(MemorySegment segment, Arena arena) {
        MemorySegment nativeSegment = arena.allocate(segment.byteSize(), ValueLayout.JAVA_LONG.byteAlignment());
        nativeSegment.copyFrom(segment);
        return nativeSegment;
    }

    public static MemorySegment makeHeapSegment(MemorySegment segment) {
        long numLongs = (segment.byteSize() + Long.BYTES - 1) / Long.BYTES;
        long[] backingArray = new long[Math.toIntExact(numLongs)];
        MemorySegment heapSegment = MemorySegment.ofArray(backingArray);
        heapSegment.copyFrom(segment);
        return heapSegment.asSlice(0, segment.byteSize());
    }

    public static int fileDescriptorToInt(FileDescriptor fileDescriptor) {
        return jdk.internal.access.SharedSecrets.getJavaIOFileDescriptorAccess().get(fileDescriptor);
    }

    public static FileDescriptor intToFileDescriptor(int fd) {
        FileDescriptor fileDescriptor = new FileDescriptor();
        jdk.internal.access.SharedSecrets.getJavaIOFileDescriptorAccess().set(fileDescriptor, fd);
        return fileDescriptor;
    }

    private static VarHandle field(MemoryLayout aggregate, String name) {
        return aggregate.varHandle(MemoryLayout.PathElement.groupElement(name));
    }

    private static MethodHandle function(SymbolLookup lookup, String name, MemoryLayout returnType, MemoryLayout... parameterTypes) {
        MemorySegment segment = lookup.findOrThrow(name);
        FunctionDescriptor descriptor = FunctionDescriptor.of(returnType, parameterTypes);
        return linker.downcallHandle(segment, descriptor);
    }

    private static MethodHandle functionVoid(SymbolLookup lookup, String name, MemoryLayout... parameterTypes) {
        MemorySegment segment = lookup.findOrThrow(name);
        FunctionDescriptor descriptor = FunctionDescriptor.ofVoid(parameterTypes);
        return linker.downcallHandle(segment, descriptor);
    }

    @FunctionalInterface
    private interface InvokeHelper<T> {
        T invoke() throws Throwable;
    }

    @FunctionalInterface
    private interface InvokeVoidHelper {
        void invoke() throws Throwable;
    }

    private static <T> T invokeUnchecked(InvokeHelper<T> helper) {
        try {
            return helper.invoke();
        } catch (Error | RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private static void invokeVoidUnchecked(InvokeVoidHelper helper) {
        try {
            helper.invoke();
        } catch (Error | RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private static native int nativeGetConstant(long name);
    private static int getConstant(String name) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment nativeName = arena.allocateFrom(name);
            return nativeGetConstant(nativeName.address());
        }
    }

    private static native void nativeCloseFd(int fd);
    public static void closeFd(int fd) {
        nativeCloseFd(fd);
    }

    private static native int nativeFdNonBlock(int fd);
    public static void fdNonBlock(int fd) {
        int err = nativeFdNonBlock(fd);
        if (err != 0) {
            throw new RuntimeException("failed to set O_NONBLOCK: " + err);
        }
    }

    public record Pipe(int readFd, int writeFd) {}
    private static native int nativeCreatePipe(long ptr);
    public static Pipe createPipe() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment fds = arena.allocate(c_int, 2);
            int err = nativeCreatePipe(fds.address());
            if (err != 0) {
                throw new RuntimeException("failed to create pipe: " + err);
            }
            int readFd = fds.getAtIndex(ValueLayout.JAVA_INT, 0);
            int writeFd = fds.getAtIndex(ValueLayout.JAVA_INT, 1);
            return new Pipe(readFd, writeFd);
        }
    }

    public static Pipe createPipeNonBlocking() {
        Pipe pipe = createPipe();
        try {
            nativeFdNonBlock(pipe.readFd);
            nativeFdNonBlock(pipe.writeFd);
        } catch (Throwable e) {
            closeFd(pipe.readFd);
            closeFd(pipe.writeFd);
            throw e;
        }
        return pipe;
    }

    private static native int nativePoll(long ptr, long n);
    public static void poll(MemorySegment pollfds) {
        long n = pollfds.byteSize() / pollfd.byteSize();
        int err = nativePoll(pollfds.address(), n);
        if (err != 0) {
            throw new RuntimeException("failed to poll: " + err);
        }
    }
}
