package com.jetbrains.internal;

import jdk.internal.org.objectweb.asm.Handle;
import jdk.internal.org.objectweb.asm.MethodVisitor;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodType;
import java.util.*;
import java.util.function.Supplier;

import static java.lang.invoke.MethodHandles.Lookup;
import static jdk.internal.org.objectweb.asm.Opcodes.*;
import static jdk.internal.org.objectweb.asm.Opcodes.PUTSTATIC;
import static jdk.internal.org.objectweb.asm.Type.getInternalName;

/**
 * Context used by {@link ProxyGenerator} to determine whether class or method is
 * accessible from a caller class and generate appropriate direct or dynamic calls.
 */
class AccessContext {

    private final Map<Class<?>, Boolean> accessibleClasses = new HashMap<>();
    final Map<Proxy, Boolean> dependencies = new HashMap<>(); // true for required, false for optional
    final List<DynamicCallTarget> dynamicCallTargets = new ArrayList<>();
    final Lookup caller;

    AccessContext(Lookup caller) {
        this.caller = caller;
    }

    record DynamicCallTarget(String name, String descriptor, Supplier<MethodHandle> futureHandle) {}

    class Method {
        final MethodVisitor writer;
        private final boolean methodRequired;

        Method(MethodVisitor writer, boolean methodRequired) {
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
            String descriptor = erase(type).descriptorString();
            DynamicCallTarget t = new DynamicCallTarget("dynamic" + dynamicCallTargets.size(), descriptor, futureHandle);
            dynamicCallTargets.add(t);
            writer.visitInvokeDynamicInsn(t.name, descriptor,
                    new Handle(H_INVOKESTATIC, "com/jetbrains/exported/JBRApiSupport", "bootstrapDynamic",
                            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false));
        }

        void invokeDirect(MethodHandleInfo handleInfo) {
            int opcode = switch (handleInfo.getReferenceKind()) {
                default -> throw new RuntimeException("Unknown reference type");
                case MethodHandleInfo.REF_getField         -> GETFIELD;
                case MethodHandleInfo.REF_getStatic        -> GETSTATIC;
                case MethodHandleInfo.REF_putField         -> PUTFIELD;
                case MethodHandleInfo.REF_putStatic        -> PUTSTATIC;
                case MethodHandleInfo.REF_invokeVirtual    -> INVOKEVIRTUAL;
                case MethodHandleInfo.REF_invokeStatic     -> INVOKESTATIC;
                case MethodHandleInfo.REF_invokeSpecial    -> INVOKESPECIAL;
                case MethodHandleInfo.REF_newInvokeSpecial -> NEW;
                case MethodHandleInfo.REF_invokeInterface  -> INVOKEINTERFACE;
            };
            String targetName = getInternalName(handleInfo.getDeclaringClass());
            if (opcode == NEW) {
                writer.visitTypeInsn(NEW, targetName);
                writer.visitInsn(DUP);
                opcode = INVOKESPECIAL;
            }
            switch (opcode) {
                case GETFIELD, GETSTATIC -> writer.visitFieldInsn(opcode, targetName, handleInfo.getName(),
                        handleInfo.getMethodType().returnType().descriptorString());
                case PUTFIELD, PUTSTATIC -> writer.visitFieldInsn(opcode, targetName, handleInfo.getName(),
                        handleInfo.getMethodType().parameterType(0).descriptorString());
                default -> writer.visitMethodInsn(opcode, targetName, handleInfo.getName(),
                        handleInfo.getMethodType().descriptorString(), handleInfo.getDeclaringClass().isInterface());
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
