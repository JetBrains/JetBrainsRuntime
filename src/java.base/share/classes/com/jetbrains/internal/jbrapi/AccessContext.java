/*
 * Copyright 2025 JetBrains s.r.o.
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

import com.jetbrains.exported.JBRApiSupport;

import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Opcode;
import java.lang.constant.*;
import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodType;
import java.util.*;
import java.util.function.Supplier;

import static java.lang.invoke.MethodHandles.Lookup;
import static com.jetbrains.internal.jbrapi.BytecodeUtils.*;

/**
 * Context used by {@link ProxyGenerator} to determine whether class or method is
 * accessible from a caller class and generate appropriate direct or dynamic calls.
 */
class AccessContext {

    static final int DYNAMIC_CALL_TARGET_NAME_OFFSET = 128;
    @SuppressWarnings("unchecked")
    static Supplier<MethodHandle>[] getDynamicCallTargets(Lookup target) {
        try {
            return (Supplier<MethodHandle>[]) target.findStaticVarHandle(
                    target.lookupClass(), "dynamicCallTargets", Supplier[].class).get();
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
    private static final DirectMethodHandleDesc BOOTSTRAP_DYNAMIC_DESC = MethodHandleDesc.ofMethod(
            DirectMethodHandleDesc.Kind.STATIC, desc(JBRApiSupport.class), "bootstrapDynamic",
            desc(CallSite.class, Lookup.class, String.class, MethodType.class));

    private final Map<Class<?>, Boolean> accessibleClasses = new HashMap<>();
    final Map<Proxy, Boolean> dependencies = new HashMap<>(); // true for required, false for optional
    final List<Supplier<MethodHandle>> dynamicCallTargets = new ArrayList<>();
    final Lookup caller;

    AccessContext(Lookup caller) {
        this.caller = caller;
    }

    class Method {
        final CodeBuilder writer;
        private final boolean methodRequired;

        Method(CodeBuilder writer, boolean methodRequired) {
            this.writer = writer;
            this.methodRequired = methodRequired;
        }

        AccessContext access() {
            return AccessContext.this;
        }

        void addDependency(Proxy p) {
            if (methodRequired) dependencies.put(p, true);
            else dependencies.putIfAbsent(p, false);
        }

        void invokeDynamic(MethodHandle handle) {
            invokeDynamic(handle.type(), () -> handle);
        }

        void invokeDynamic(MethodType type, Supplier<MethodHandle> futureHandle) {
            String name = String.valueOf((char) (dynamicCallTargets.size() + DYNAMIC_CALL_TARGET_NAME_OFFSET));
            dynamicCallTargets.add(futureHandle);
            writer.invokedynamic(DynamicCallSiteDesc.of(BOOTSTRAP_DYNAMIC_DESC, name, desc(erase(type))));
        }

        void invokeDirect(MethodHandleInfo handleInfo) {
            Opcode opcode = switch (handleInfo.getReferenceKind()) {
                case MethodHandleInfo.REF_getField         -> Opcode.GETFIELD;
                case MethodHandleInfo.REF_getStatic        -> Opcode.GETSTATIC;
                case MethodHandleInfo.REF_putField         -> Opcode.PUTFIELD;
                case MethodHandleInfo.REF_putStatic        -> Opcode.PUTSTATIC;
                case MethodHandleInfo.REF_invokeVirtual    -> Opcode.INVOKEVIRTUAL;
                case MethodHandleInfo.REF_invokeStatic     -> Opcode.INVOKESTATIC;
                case MethodHandleInfo.REF_invokeSpecial    -> Opcode.INVOKESPECIAL;
                case MethodHandleInfo.REF_newInvokeSpecial -> Opcode.NEW;
                case MethodHandleInfo.REF_invokeInterface  -> Opcode.INVOKEINTERFACE;
                default -> throw new RuntimeException("Unknown reference type");
            };
            ClassDesc target = desc(handleInfo.getDeclaringClass());
            if (opcode == Opcode.NEW) {
                writer.new_(target)
                      .dup();
                opcode = Opcode.INVOKESPECIAL;
            }
            switch (opcode) {
                case GETFIELD, GETSTATIC -> writer.fieldAccess(opcode, target, handleInfo.getName(),
                        ClassDesc.ofDescriptor(handleInfo.getMethodType().returnType().descriptorString()));
                case PUTFIELD, PUTSTATIC -> writer.fieldAccess(opcode, target, handleInfo.getName(),
                        ClassDesc.ofDescriptor(handleInfo.getMethodType().parameterType(0).descriptorString()));
                default -> writer.invoke(opcode, target, handleInfo.getName(), MethodTypeDesc.ofDescriptor(
                        handleInfo.getMethodType().descriptorString()), handleInfo.getDeclaringClass().isInterface());
            }
        }
    }

    MethodHandleInfo resolveDirect(MethodHandle handle) {
        try {
            MethodHandleInfo handleInfo = caller.revealDirect(handle);
            if (isClassLoaderAccessible(caller.lookupClass().getClassLoader(),
                    handleInfo.getDeclaringClass().getClassLoader())) {
                // Accessible directly.
                return handleInfo;
            }
        } catch (Exception ignore) {}
        // Not accessible directly.
        return null;
    }

    /**
     * Replaces all inaccessible types with {@link Object}.
     * @param type original method type
     * @return erased method type
     */
    private MethodType erase(MethodType type) {
        if (!canAccess(type.returnType())) type = type.changeReturnType(Object.class);
        for (int i = 0; i < type.parameterCount(); i++) {
            if (!canAccess(type.parameterType(i))) type = type.changeParameterType(i, Object.class);
        }
        return type;
    }

    boolean canAccess(Class<?> clazz) {
        return accessibleClasses.computeIfAbsent(clazz, c -> canAccess(caller, c));
    }

    static boolean canAccess(Lookup caller, Class<?> target) {
        try {
            if (isClassLoaderAccessible(caller.lookupClass().getClassLoader(),
                    caller.accessClass(target).getClassLoader())) return true;
        } catch (IllegalAccessException ignore) {}
        return false;
    }

    private static boolean isClassLoaderAccessible(ClassLoader caller, ClassLoader target) {
        if (target == null) return true;
        for (ClassLoader cl = caller; cl != null; cl = cl.getParent()) {
            if (cl == target) return true;
        }
        return false;
    }
}
