/*
 * Copyright 2000-2025 JetBrains s.r.o.
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

package com.jetbrains.internal.jbrapi;

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.MethodSignature;
import java.lang.classfile.attribute.ExceptionsAttribute;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.lang.classfile.ClassFile.ACC_FINAL;
import static java.lang.classfile.ClassFile.ACC_PUBLIC;

/**
 * Utility class that helps with bytecode generation.
 */
class BytecodeUtils {

    public static final ClassDesc VOID_DESC = desc(void.class);
    public static final ClassDesc OBJECT_DESC = desc(Object.class);
    public static final ClassDesc OBJECT_ARRAY_DESC = OBJECT_DESC.arrayType();
    public static final ClassDesc SUPPLIER_DESC = desc(Supplier.class);
    public static final ClassDesc SUPPLIER_ARRAY_DESC = SUPPLIER_DESC.arrayType();
    public static final ClassDesc EXTENSION_ARRAY_DESC = desc(long[].class);
    public static final ClassDesc PROXY_INTERFACE_DESC = desc(com.jetbrains.exported.JBRApiSupport.Proxy.class);
    public static final MethodTypeDesc GET_PROXY_TARGET_DESC = MethodTypeDesc.of(OBJECT_DESC);

    private static final ClassDesc UNSUPPORTED_OPERATION_EXCEPTION_DESC = desc(UnsupportedOperationException.class);
    private static final MethodTypeDesc EXCEPTION_CONSTRUCTOR_DESC = desc(void.class, String.class);

    private static final MethodHandle genericSignatureGetter;
    static {
        try {
            genericSignatureGetter = MethodHandles.privateLookupIn(Method.class, MethodHandles.lookup())
                    .findVirtual(Method.class, "getGenericSignature", MethodType.methodType(String.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new Error(e);
        }
    }

    public static ClassDesc desc(Class<?> c) {
        return c == null ? null : c.describeConstable().orElseThrow();
    }

    public static ClassDesc[] desc(Class<?>[] cs) {
        if (cs == null) return null;
        ClassDesc[] result = new ClassDesc[cs.length];
        for (int i = 0; i < cs.length; i++) result[i] = desc(cs[i]);
        return result;
    }

    public static MethodTypeDesc desc(MethodType mt) {
        return mt == null ? null : mt.describeConstable().orElseThrow();
    }

    public static MethodTypeDesc desc(Method method) {
        return method == null ? null : desc(method.getReturnType(), method.getParameterTypes());
    }

    public static MethodTypeDesc desc(Class<?> returnType, Class<?>... parameterTypes) {
        return MethodTypeDesc.of(desc(returnType), desc(parameterTypes));
    }

    public static void throwUnsupportedOperationException(CodeBuilder m, String message) {
        ClassDesc type = UNSUPPORTED_OPERATION_EXCEPTION_DESC;
        m.new_(type)
         .dup()
         .loadConstant(message)
         .invokespecial(type, "<init>", EXCEPTION_CONSTRUCTOR_DESC, false)
         .athrow();
    }

    public static void generateCompatibleMethod(ClassBuilder cb, Method method, Consumer<? super CodeBuilder> code) {
        MethodSignature genericSignature;
        try {
            String gs = (String) genericSignatureGetter.invoke(method);
            genericSignature = gs == null ? null : MethodSignature.parseFrom(gs);
        } catch (Throwable e) {
            throw new Error(e);
        }
        ClassDesc[] exceptions = desc(method.getExceptionTypes());
        cb.withMethod(method.getName(), desc(method), ACC_PUBLIC | ACC_FINAL, mb -> {
            if (genericSignature != null) mb.with(SignatureAttribute.of(genericSignature));
            if (exceptions != null) mb.with(ExceptionsAttribute.ofSymbols(exceptions));
            mb.withCode(code);
        });
    }
}
