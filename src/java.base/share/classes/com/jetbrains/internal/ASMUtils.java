/*
 * Copyright 2000-2023 JetBrains s.r.o.
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

package com.jetbrains.internal;

import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Type;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.ClassFileFormatVersion;
import java.lang.reflect.Method;

import static jdk.internal.org.objectweb.asm.Opcodes.*;

/**
 * Utility class that helps with bytecode generation
 */
class ASMUtils {

    private static final MethodHandle genericSignatureGetter;
    static {
        try {
            genericSignatureGetter = MethodHandles.privateLookupIn(Method.class, MethodHandles.lookup())
                    .findVirtual(Method.class, "getGenericSignature", MethodType.methodType(String.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new Error(e);
        }
    }

    public static final int CLASSFILE_VERSION = ClassFileFormatVersion.latest().major();

    public static void generateUnsupportedMethod(ClassVisitor writer, Method interfaceMethod) {
        InternalMethodInfo methodInfo = getInternalMethodInfo(interfaceMethod);
        MethodVisitor p = writer.visitMethod(ACC_PUBLIC | ACC_FINAL, methodInfo.name(),
                methodInfo.descriptor(), methodInfo.genericSignature(), methodInfo.exceptionNames());
        p.visitCode();
        throwException(p, "java/lang/UnsupportedOperationException", "No implementation found for this method");
        p.visitMaxs(0, 0);
        p.visitEnd();
    }

    public static void throwException(MethodVisitor p, String type, String message) {
        p.visitTypeInsn(NEW, type);
        p.visitInsn(DUP);
        p.visitLdcInsn(message);
        p.visitMethodInsn(INVOKESPECIAL, type, "<init>", "(Ljava/lang/String;)V", false);
        p.visitInsn(ATHROW);
    }

    public record InternalMethodInfo(String name, String descriptor, String genericSignature,
                                        String[] exceptionNames) {}

    public static InternalMethodInfo getInternalMethodInfo(Method method) {
        try {
            return new InternalMethodInfo(
                    method.getName(),
                    Type.getMethodDescriptor(method),
                    (String) genericSignatureGetter.invoke(method),
                    getExceptionNames(method));
        } catch (Throwable e) {
            throw new Error(e);
        }
    }

    private static String[] getExceptionNames(Method method) {
        Class<?>[] exceptionTypes = method.getExceptionTypes();
        String[] exceptionNames = new String[exceptionTypes.length];
        for (int i = 0; i < exceptionTypes.length; i++) {
            exceptionNames[i] = Type.getInternalName(exceptionTypes[i]);
        }
        return exceptionNames;
    }

    public static int getParameterSize(Class<?> c) {
        if (c == Void.TYPE) {
            return 0;
        } else if (c == Long.TYPE || c == Double.TYPE) {
            return 2;
        }
        return 1;
    }

    public static int getLoadOpcode(Class<?> c) {
        if (c == Void.TYPE) {
            throw new InternalError("Unexpected void type of load opcode");
        }
        return ILOAD + getOpcodeOffset(c);
    }

    public static int getReturnOpcode(Class<?> c) {
        if (c == Void.TYPE) {
            return RETURN;
        }
        return IRETURN + getOpcodeOffset(c);
    }

    private static int getOpcodeOffset(Class<?> c) {
        if (c.isPrimitive()) {
            if (c == Long.TYPE) {
                return 1;
            } else if (c == Float.TYPE) {
                return 2;
            } else if (c == Double.TYPE) {
                return 3;
            }
            return 0;
        } else {
            return 4;
        }
    }
}
