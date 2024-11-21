/*
 * Copyright (c) 2022, 2024, Oracle and/or its affiliates. All rights reserved.
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
package jdk.internal.classfile.impl;


import java.nio.ByteBuffer;
import java.util.Arrays;

import java.lang.classfile.BufWriter;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.ConstantPool;
import java.lang.classfile.constantpool.ConstantPoolBuilder;
import java.lang.classfile.constantpool.PoolEntry;

public final class BufWriterImpl implements BufWriter {

    private final ConstantPoolBuilder constantPool;
    private final ClassFileImpl context;
    private LabelContext labelContext;
    private final ClassEntry thisClass;
    private final int majorVersion;
    byte[] elems;
    int offset = 0;

    public BufWriterImpl(ConstantPoolBuilder constantPool, ClassFileImpl context) {
        this(constantPool, context, 64, null, 0);
    }

    public BufWriterImpl(ConstantPoolBuilder constantPool, ClassFileImpl context, int initialSize) {
        this(constantPool, context, initialSize, null, 0);
    }

    public BufWriterImpl(ConstantPoolBuilder constantPool, ClassFileImpl context, int initialSize, ClassEntry thisClass, int majorVersion) {
        this.constantPool = constantPool;
        this.context = context;
        elems = new byte[initialSize];
        this.thisClass = thisClass;
        this.majorVersion = majorVersion;
    }

    @Override
    public ConstantPoolBuilder constantPool() {
        return constantPool;
    }

    public LabelContext labelContext() {
        return labelContext;
    }

    public void setLabelContext(LabelContext labelContext) {
        this.labelContext = labelContext;
    }
    @Override
    public boolean canWriteDirect(ConstantPool other) {
        return constantPool.canWriteDirect(other);
    }

    public ClassEntry thisClass() {
        return thisClass;
    }

    public int getMajorVersion() {
        return majorVersion;
    }

    public ClassFileImpl context() {
        return context;
    }

    @Override
    public void writeU1(int x) {
        reserveSpace(1);
        elems[offset++] = (byte) x;
    }

    @Override
    public void writeU2(int x) {
        reserveSpace(2);
        byte[] elems = this.elems;
        int offset = this.offset;
        elems[offset    ] = (byte) (x >> 8);
        elems[offset + 1] = (byte) x;
        this.offset = offset + 2;
    }

    @Override
    public void writeInt(int x) {
        reserveSpace(4);
        byte[] elems = this.elems;
        int offset = this.offset;
        elems[offset    ] = (byte) (x >> 24);
        elems[offset + 1] = (byte) (x >> 16);
        elems[offset + 2] = (byte) (x >> 8);
        elems[offset + 3] = (byte)  x;
        this.offset = offset + 4;
    }

    @Override
    public void writeFloat(float x) {
        writeInt(Float.floatToIntBits(x));
    }

    @Override
    public void writeLong(long x) {
        reserveSpace(8);
        byte[] elems = this.elems;
        int offset = this.offset;
        elems[offset    ] = (byte) (x >> 56);
        elems[offset + 1] = (byte) (x >> 48);
        elems[offset + 2] = (byte) (x >> 40);
        elems[offset + 3] = (byte) (x >> 32);
        elems[offset + 4] = (byte) (x >> 24);
        elems[offset + 5] = (byte) (x >> 16);
        elems[offset + 6] = (byte) (x >> 8);
        elems[offset + 7] = (byte)  x;
        this.offset = offset + 8;
    }

    @Override
    public void writeDouble(double x) {
        writeLong(Double.doubleToLongBits(x));
    }

    @Override
    public void writeBytes(byte[] arr) {
        writeBytes(arr, 0, arr.length);
    }

    public void writeBytes(BufWriterImpl other) {
        writeBytes(other.elems, 0, other.offset);
    }

    @Override
    public void writeBytes(byte[] arr, int start, int length) {
        reserveSpace(length);
        System.arraycopy(arr, start, elems, offset, length);
        offset += length;
    }

    @Override
    public void patchInt(int offset, int size, int value) {
        int prevOffset = this.offset;
        this.offset = offset;
        writeIntBytes(size, value);
        this.offset = prevOffset;
    }

    @Override
    public void writeIntBytes(int intSize, long intValue) {
        reserveSpace(intSize);
        for (int i = 0; i < intSize; i++) {
            elems[offset++] = (byte) ((intValue >> 8 * (intSize - i - 1)) & 0xFF);
        }
    }

    @Override
    public void reserveSpace(int freeBytes) {
        int minCapacity = offset + freeBytes;
        if (minCapacity > elems.length) {
            grow(minCapacity);
        }
    }

    private void grow(int minCapacity) {
        int newsize = elems.length * 2;
        while (minCapacity > newsize) {
            newsize *= 2;
        }
        elems = Arrays.copyOf(elems, newsize);
    }

    @Override
    public int size() {
        return offset;
    }

    public ByteBuffer asByteBuffer() {
        return ByteBuffer.wrap(elems, 0, offset).slice();
    }

    public void copyTo(byte[] array, int bufferOffset) {
        System.arraycopy(elems, 0, array, bufferOffset, size());
    }

    // writeIndex methods ensure that any CP info written
    // is relative to the correct constant pool

    @Override
    public void writeIndex(PoolEntry entry) {
        int idx = AbstractPoolEntry.maybeClone(constantPool, entry).index();
        if (idx < 1 || idx > Character.MAX_VALUE)
            throw new IllegalArgumentException(idx + " is not a valid index. Entry: " + entry);
        writeU2(idx);
    }

    @Override
    public void writeIndexOrZero(PoolEntry entry) {
        if (entry == null || entry.index() == 0)
            writeU2(0);
        else
            writeIndex(entry);
    }
}
