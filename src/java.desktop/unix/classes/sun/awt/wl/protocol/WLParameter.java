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
import java.lang.foreign.MemorySegment;

import static sun.awt.wl.protocol.WLNative.wl_argument$array;
import static sun.awt.wl.protocol.WLNative.wl_argument$fd;
import static sun.awt.wl.protocol.WLNative.wl_argument$fixed;
import static sun.awt.wl.protocol.WLNative.wl_argument$int;
import static sun.awt.wl.protocol.WLNative.wl_argument$object;
import static sun.awt.wl.protocol.WLNative.wl_argument$string;
import static sun.awt.wl.protocol.WLNative.wl_argument$uint;
import static sun.awt.wl.protocol.WLNative.wl_array;
import static sun.awt.wl.protocol.WLNative.wl_array$alloc;
import static sun.awt.wl.protocol.WLNative.wl_array$data;
import static sun.awt.wl.protocol.WLNative.wl_array$size;

public abstract sealed class WLParameter {
    private final String name;

    protected WLParameter(String name) {
        this.name = name;
    }

    public static final class IntParameter extends WLParameter {
        private IntParameter(String name) {
            super(name);
        }

        @Override
        public String getKind() {
            return "int";
        }

        @Override
        public String getProtocolSignature() {
            return "i";
        }

        @Override
        public Class<?> getRepresentation() {
            return int.class;
        }

        @Override
        public void marshal(MemorySegment dest, Arena arena, Object value) {
            wl_argument$int.set(dest, 0L, (int) value);
        }

        @Override
        public Object unmarshal(MemorySegment src) {
            return (int) wl_argument$int.get(src, 0L);
        }
    }

    public static final class UintParameter extends WLParameter {
        private UintParameter(String name) {
            super(name);
        }

        @Override
        public String getKind() {
            return "uint";
        }

        @Override
        public String getProtocolSignature() {
            return "u";
        }

        @Override
        public Class<?> getRepresentation() {
            return int.class;
        }

        @Override
        public void marshal(MemorySegment dest, Arena arena, Object value) {
            wl_argument$uint.set(dest, 0L, (int) value);
        }

        @Override
        public Object unmarshal(MemorySegment src) {
            return (int) wl_argument$uint.get(src, 0L);
        }
    }

    public static final class FixedParameter extends WLParameter {
        private FixedParameter(String name) {
            super(name);
        }

        @Override
        public String getKind() {
            return "fixed";
        }

        @Override
        public String getProtocolSignature() {
            return "f";
        }

        @Override
        public Class<?> getRepresentation() {
            return double.class;
        }

        @Override
        public void marshal(MemorySegment dest, Arena arena, Object value) {
            int fixedValue = (int) Math.round((double) value * 256.0);
            wl_argument$fixed.set(dest, 0L, fixedValue);
        }

        @Override
        public Object unmarshal(MemorySegment src) {
            int fixedValue = (int) wl_argument$fixed.get(src, 0L);
            return (double) fixedValue / 256.0;
        }
    }

    public static final class StringParameter extends WLParameter {
        private final boolean isNullable;

        private StringParameter(String name, boolean isNullable) {
            super(name);
            this.isNullable = isNullable;
        }

        @Override
        public boolean isNullable() {
            return isNullable;
        }

        @Override
        public String getKind() {
            return "string";
        }

        @Override
        public String getProtocolSignature() {
            if (isNullable) {
                return "?s";
            } else {
                return "s";
            }
        }

        @Override
        public Class<?> getRepresentation() {
            return String.class;
        }

        @Override
        public void marshal(MemorySegment dest, Arena arena, Object value) {
            MemorySegment nativeString;
            if (value == null) {
                nativeString = MemorySegment.NULL;
            } else {
                nativeString = arena.allocateFrom((String) value);
            }
            wl_argument$string.set(dest, 0L, nativeString);
        }

        @Override
        public Object unmarshal(MemorySegment src) {
            MemorySegment nativeString = (MemorySegment) wl_argument$string.get(src, 0L);
            if (nativeString.address() == 0L) {
                return null;
            }
            return nativeString.getString(0L);
        }
    }

    public static final class ObjectParameter extends WLParameter {
        private final WLInterface wlInterface;
        private final boolean isNullable;

        private ObjectParameter(String name, WLInterface wlInterface, boolean isNullable) {
            super(name);
            this.wlInterface = wlInterface;
            this.isNullable = isNullable;
        }

        @Override
        public WLInterface getInterface() {
            return wlInterface;
        }

        @Override
        public boolean isNullable() {
            return isNullable;
        }

        @Override
        public String getKind() {
            return "object";
        }

        @Override
        public String getProtocolSignature() {
            if (isNullable) {
                return "?o";
            } else {
                return "s";
            }
        }

        @Override
        public Class<?> getRepresentation() {
            return wlInterface.getProxyClass();
        }

        @Override
        public void marshal(MemorySegment dest, Arena arena, Object value) {
            MemorySegment nativeProxy;
            if (value == null) {
                if (!isNullable) {
                    throw new NullPointerException("null passed to a non-nullable parameter '" + getName() + '"');
                }
                nativeProxy = MemorySegment.NULL;
            } else {
                nativeProxy = ((WLProxy) value).getNativeSegment();
            }
            wl_argument$object.set(dest, 0L, nativeProxy);
        }

        @Override
        public Object unmarshal(MemorySegment src) {
            MemorySegment nativeProxy = (MemorySegment) wl_argument$object.get(src, 0L);
            if (nativeProxy.address() == 0L) {
                return null;
            }
            return WLProxy.getExisting(nativeProxy);
        }
    }

    public static final class NewIdParameter extends WLParameter {
        private final WLInterface wlInterface;

        private NewIdParameter(String name, WLInterface wlInterface) {
            super(name);
            this.wlInterface = wlInterface;
        }

        @Override
        public WLInterface getInterface() {
            return wlInterface;
        }

        public boolean isTyped() {
            return wlInterface != null;
        }

        @Override
        public String getKind() {
            return "new_id";
        }

        @Override
        public String getProtocolSignature() {
            return "n";
        }

        @Override
        public Class<?> getRepresentation() {
            return wlInterface.getProxyClass();
        }

        @Override
        public void marshal(MemorySegment dest, Arena arena, Object value) {
            MemorySegment nativeProxy = ((WLProxy) value).getNativeSegment();
            wl_argument$object.set(dest, 0L, nativeProxy);
        }

        @Override
        public Object unmarshal(MemorySegment src) {
            MemorySegment nativeProxy = (MemorySegment) wl_argument$object.get(src, 0L);
            if (nativeProxy.address() == 0L) {
                return null;
            }
            return wlInterface.makeProxy(nativeProxy);
        }
    }

    public static final class ArrayParameter extends WLParameter {
        private ArrayParameter(String name) {
            super(name);
        }

        @Override
        public String getKind() {
            return "array";
        }

        @Override
        public String getProtocolSignature() {
            return "a";
        }

        @Override
        public Class<?> getRepresentation() {
            return MemorySegment.class;
        }

        @Override
        public void marshal(MemorySegment dest, Arena arena, Object value) {
            // Copy to native segment with lifetime of the arena,
            // because we're not sure how long the lifetime of the passed segment is
            MemorySegment nativeSegment = WLNative.makeNativeSegment((MemorySegment) value, arena);

            // Construct the `wl_array` struct
            MemorySegment wlArray = arena.allocate(wl_array);

            long len = nativeSegment.byteSize();
            WLNative.writeSizeT(wl_array$size, wlArray, len);
            WLNative.writeSizeT(wl_array$alloc, wlArray, len);

            wl_array$data.set(wlArray, 0L, nativeSegment);
            wl_argument$array.set(dest, 0L, wlArray);
        }

        @Override
        @SuppressWarnings("restricted")
        public Object unmarshal(MemorySegment src) {
            MemorySegment wlArray = (MemorySegment) wl_argument$array.get(src, 0L);
            if (wlArray.address() == 0L) {
                throw new NullPointerException("wl_array can't be null");
            }

            long arrayLen = WLNative.readSizeT(wl_array$size, wlArray);
            MemorySegment arrayBase = (MemorySegment) wl_array$data.get(wlArray, 0L);
            MemorySegment array = arrayBase.reinterpret(arrayLen);
            return WLNative.makeHeapSegment(array);
        }
    }

    public static final class FdParameter extends WLParameter {
        private FdParameter(String name) {
            super(name);
        }

        @Override
        public String getKind() {
            return "fd";
        }

        @Override
        public String getProtocolSignature() {
            return "h";
        }

        @Override
        public Class<?> getRepresentation() {
            return FileDescriptor.class;
        }

        @Override
        public void marshal(MemorySegment dest, Arena arena, Object value) {
            FileDescriptor fileDescriptor = (FileDescriptor) value;
            int fd = WLNative.fileDescriptorToInt(fileDescriptor);
            wl_argument$fd.set(dest, 0L, fd);
        }

        @Override
        public Object unmarshal(MemorySegment src) {
            int fd = (int) wl_argument$fd.get(src, 0L);
            return WLNative.intToFileDescriptor(fd);
        }
    }

    public static WLParameter ofInt(String name) {
        return new IntParameter(name);
    }

    public static WLParameter ofUint(String name) {
        return new UintParameter(name);
    }

    public static WLParameter ofFixed(String name) {
        return new FixedParameter(name);
    }

    public static WLParameter ofString(String name) {
        return new StringParameter(name, false);
    }

    public static WLParameter ofNullableString(String name) {
        return new StringParameter(name, true);
    }

    public static WLParameter ofObject(String name, WLInterface wlInterface) {
        return new ObjectParameter(name, wlInterface, false);
    }

    public static WLParameter ofNullableObject(String name, WLInterface wlInterface) {
        return new ObjectParameter(name, wlInterface, true);
    }

    public static WLParameter ofNewId(String name, WLInterface wlInterface) {
        return new NewIdParameter(name, wlInterface);
    }

    public static WLParameter ofUntypedNewId(String name) {
        return new NewIdParameter(name, null);
    }

    public static WLParameter ofArray(String name) {
        return new ArrayParameter(name);
    }

    public static WLParameter ofFd(String name) {
        return new FdParameter(name);
    }

    public String getName() {
        return name;
    }


    public WLInterface getInterface() {
        return null;
    }

    public boolean isNullable() {
        return false;
    }

    abstract public String getKind();
    abstract public String getProtocolSignature();
    abstract public Class<?> getRepresentation();
    abstract public void marshal(MemorySegment dest, Arena arena, Object value);
    abstract public Object unmarshal(MemorySegment src);

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder();
        if (isNullable()) {
            result.append("nullable ");
        }
        result.append(getKind());
        if (getInterface() != null) {
            result.append('<');
            result.append(getInterface().getName());
            result.append('>');
        }

        if (!name.isEmpty()) {
            result.append(" ");
            result.append(name);
        }
        return result.toString();
    }
}
