/*
 * Copyright 2000-2021 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jetbrains.internal;

import jdk.internal.misc.VM;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Type;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
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

    public static final int CLASSFILE_VERSION = VM.classFileVersion();

    public static void generateUnsupportedMethod(ClassVisitor writer, Method interfaceMethod) {
        InternalMethodInfo methodInfo = getInternalMethodInfo(interfaceMethod);
        MethodVisitor p = writer.visitMethod(ACC_PUBLIC | ACC_FINAL, methodInfo.name(),
                methodInfo.descriptor(), methodInfo.genericSignature(), methodInfo.exceptionNames());
        p.visitTypeInsn(NEW, "java/lang/UnsupportedOperationException");
        p.visitInsn(DUP);
        p.visitLdcInsn("No implementation found for this method");
        p.visitMethodInsn(INVOKESPECIAL, "java/lang/UnsupportedOperationException", "<init>", "(Ljava/lang/String;)V", false);
        p.visitInsn(ATHROW);
        p.visitMaxs(-1, -1);
    }

    public static void logDeprecated(MethodVisitor writer, String message) {
        writer.visitTypeInsn(NEW, "java/lang/Exception");
        writer.visitInsn(DUP);
        writer.visitLdcInsn(message);
        writer.visitMethodInsn(INVOKESPECIAL, "java/lang/Exception", "<init>", "(Ljava/lang/String;)V", false);
        writer.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Exception", "printStackTrace", "()V", false);
    }

    protected record InternalMethodInfo(String name, String descriptor, String genericSignature,
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

    public static int getOpcodeOffset(Class<?> c) {
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
