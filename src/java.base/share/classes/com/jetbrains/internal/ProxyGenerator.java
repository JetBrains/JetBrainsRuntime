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

import jdk.internal.org.objectweb.asm.*;
import jdk.internal.org.objectweb.asm.util.CheckClassAdapter;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.jetbrains.exported.JBRApi.ServiceNotAvailableException;
import static com.jetbrains.internal.ASMUtils.*;
import static com.jetbrains.internal.JBRApi.EXTENSIONS_ENABLED;
import static java.lang.invoke.MethodHandles.Lookup;
import static jdk.internal.org.objectweb.asm.Opcodes.*;
import static jdk.internal.org.objectweb.asm.Type.getInternalName;

/**
 * Generates {@linkplain Proxy proxy} classes.
 * <p>
 * Proxy class always implements/extends a base interface/class. It's defined as a
 * {@linkplain MethodHandles.Lookup#defineHiddenClass(byte[], boolean, Lookup.ClassOption...) hidden class}
 * sharing the nest with either interface or target implementation class.
 * Defining proxy as a nestmate of a target class is preferred,
 * as in this case proxy can call implementation methods directly.
 * However, this may not be possible if interface is not accessible by target
 * (thus making it impossible for proxy to implement it), or if there is no target class at all
 * (e.g. a service may delegate all calls exclusively to static methods).
 * <p>
 * Proxy invokes target methods in two ways: either directly, when accessible
 * ({@code invokestatic}, {@code invokevirtual}, {@code invokeinterface}),
 * or via {@code invokedynamic} in cases when target method is inaccessible
 * (see {@link com.jetbrains.exported.JBRApiSupport#bootstrapDynamic(Lookup, String, MethodType)}).
 * @see Proxy
 */
class ProxyGenerator {
    private static final String PROXY_INTERFACE_NAME = getInternalName(com.jetbrains.exported.JBRApiSupport.Proxy.class);

    private final Proxy.Info info;
    private final Class<?> interFace;
    private final Lookup proxyGenLookup;
    private final Mapping[] specialization;
    private final Mapping.Context mappingContext;
    private final AccessContext accessContext;

    private final Class<?> constructorTargetParameterType;
    private final String targetDescriptor, proxyName, superclassName;
    private final String[] superinterfaceNames;
    private final ClassWriter originalProxyWriter;
    private final ClassVisitor proxyWriter;

    private final Map<Enum<?>, Boolean> supportedExtensions = new HashMap<>();

    private boolean supported = true;
    private Lookup generatedProxy;

    /**
     * Creates new proxy generator from given {@link Proxy.Info},
     */
    ProxyGenerator(ProxyRepository proxyRepository, Proxy.Info info, Mapping[] specialization) {
        this.info = info;
        this.interFace = info.interfaceLookup.lookupClass();
        this.specialization = specialization;

        // Placing our proxy implementation into the target's nest is preferred, as it gives us direct method calls.
        this.proxyGenLookup = info.targetLookup != null && AccessContext.canAccess(info.targetLookup, interFace) ?
                info.targetLookup : info.interfaceLookup;

        this.mappingContext = new Mapping.Context(proxyRepository);
        this.accessContext = new AccessContext(proxyGenLookup);

        constructorTargetParameterType = info.targetLookup == null ? null :
                accessContext.canAccess(info.targetLookup.lookupClass()) ? info.targetLookup.lookupClass() : Object.class;
        targetDescriptor = constructorTargetParameterType == null ? "" : constructorTargetParameterType.descriptorString();

        // Even though generated proxy is hidden and therefore has no qualified name,
        // it can reference itself via internal name, which can lead to name collisions.
        // Let's consider specialized proxy for java/util/List - if we name proxy similarly,
        // methods calls to java/util/List will be treated by VM as calls to proxy class,
        // not standard library interface. Therefore we append $$$ to proxy name to avoid name collision.
        proxyName = proxyGenLookup.lookupClass().getPackageName().replace('.', '/') + "/" + interFace.getSimpleName() + "$$$";

        originalProxyWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return ProxyGenerator.this.proxyGenLookup.lookupClass().getClassLoader();
            }
        };
        proxyWriter = JBRApi.VERIFY_BYTECODE ? new CheckClassAdapter(originalProxyWriter, true) : originalProxyWriter;
        if (interFace.isInterface()) {
            superclassName = "java/lang/Object";
            superinterfaceNames = new String[] {getInternalName(interFace), PROXY_INTERFACE_NAME};
        } else {
            superclassName = getInternalName(interFace);
            superinterfaceNames = new String[] {PROXY_INTERFACE_NAME};
        }
    }

    /**
     * Direct (non-transitive) only. These are the proxies accessed by current one.
     * True for required and false for optional.
     */
    Map<Proxy, Boolean> getDependencies() {
        return accessContext.dependencies;
    }

    Set<Enum<?>> getSupportedExtensions() {
        return supportedExtensions.entrySet().stream()
                .filter(Map.Entry::getValue).map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * @return service target instance, if any
     */
    private Object createServiceTarget() throws Throwable {
        Exception exception = null;
        MethodHandle constructor = null;
        try {
            constructor = info.targetLookup.findStatic(
                    info.targetLookup.lookupClass(), "create", MethodType.methodType(info.targetLookup.lookupClass()));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            exception = e;
        }
        try {
            if (constructor == null) constructor = info.targetLookup.findConstructor(
                    info.targetLookup.lookupClass(), MethodType.methodType(void.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            if (exception == null) exception = e;
            else exception.addSuppressed(e);
        }
        if (constructor == null) throw new ServiceNotAvailableException("No service factory method or constructor found", exception);
        return constructor.invoke();
    }

    /**
     * First parameter is target object to which it would delegate method calls (if target exists).
     * Second parameter is extensions bitfield (if extensions are enabled).
     * @return method handle to constructor of generated proxy class, or null.
     */
    private MethodHandle findConstructor() throws NoSuchMethodException, IllegalAccessException {
        MethodType constructorType = MethodType.methodType(void.class);
        if (info.targetLookup != null) constructorType = constructorType.appendParameterTypes(constructorTargetParameterType);
        if (EXTENSIONS_ENABLED) constructorType = constructorType.appendParameterTypes(long[].class);
        return generatedProxy.findConstructor(generatedProxy.lookupClass(), constructorType);
    }

    /**
     * Initialize method handles used by generated class via {@code invokedynamic}.
     */
    void init() {
        if (JBRApi.VERBOSE) {
            System.out.println("Initializing proxy " + interFace.getName());
        }
        for (var t : accessContext.dynamicCallTargets) {
            JBRApi.dynamicCallTargets.put(new JBRApi.DynamicCallTargetKey(
                    generatedProxy.lookupClass(), t.name(), t.descriptor()
            ), t.futureHandle());
        }
    }

    /**
     * Define generated class.
     * @return method handle to constructor of generated proxy class, or null.
     */
    MethodHandle define(boolean service) {
        try {
            Object sericeTarget = service && info.targetLookup != null ? createServiceTarget() : null;
            generatedProxy = proxyGenLookup.defineHiddenClass(
                    originalProxyWriter.toByteArray(), false, Lookup.ClassOption.STRONG, Lookup.ClassOption.NESTMATE);
            MethodHandle constructor = findConstructor();
            if (sericeTarget != null) constructor = MethodHandles.insertArguments(constructor, 0, sericeTarget);
            return constructor;
        } catch (ServiceNotAvailableException e) {
            if (JBRApi.VERBOSE) e.printStackTrace(System.err);
            return null;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Generate bytecode for class.
     * @return true if generated proxy is considered supported
     */
    boolean generate() {
        if (!supported) return false;
        proxyWriter.visit(CLASSFILE_VERSION, ACC_SUPER | ACC_FINAL | ACC_SYNTHETIC, proxyName, null,
                superclassName, superinterfaceNames);
        if (JBRApi.VERBOSE) {
            synchronized (System.out) {
                System.out.print("Generating proxy " + interFace.getName());
                if (specialization != null) System.out.print(" <" +
                        Stream.of(specialization).map(t -> t == null ? "?" : t.toString()).collect(Collectors.joining(", ")) + ">");
                System.out.println();
            }
        }
        if (specialization != null) {
            mappingContext.initTypeParameters(interFace, Stream.of(specialization));
        }
        mappingContext.initTypeParameters(interFace);
        generateFields();
        generateConstructor();
        generateTargetGetter();
        generateMethods(interFace);
        if (interFace.isInterface()) generateMethods(Object.class);
        proxyWriter.visitEnd();
        return supported;
    }

    private void generateFields() {
        if (info.targetLookup != null) {
            proxyWriter.visitField(ACC_PRIVATE | ACC_FINAL, "target", targetDescriptor, null, null);
        }
        if (EXTENSIONS_ENABLED) {
            proxyWriter.visitField(ACC_PRIVATE | ACC_FINAL, "extensions", "[J", null, null);
        }
    }

    private void generateConstructor() {
        MethodVisitor m = proxyWriter.visitMethod(ACC_PRIVATE, "<init>", "(" + targetDescriptor +
                (EXTENSIONS_ENABLED ? "[J" : "") + ")V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        if (info.targetLookup != null) {
            m.visitInsn(DUP);
            m.visitVarInsn(ALOAD, 1);
            m.visitFieldInsn(PUTFIELD, proxyName, "target", targetDescriptor);
        }
        if (EXTENSIONS_ENABLED) {
            m.visitInsn(DUP);
            m.visitVarInsn(ALOAD, info.targetLookup != null ? 2 : 1);
            m.visitFieldInsn(PUTFIELD, proxyName, "extensions", "[J");
        }
        m.visitMethodInsn(INVOKESPECIAL, superclassName, "<init>", "()V", false);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private void generateTargetGetter() {
        MethodVisitor m = proxyWriter.visitMethod(ACC_PUBLIC | ACC_FINAL, "$getProxyTarget", "()" +
                Object.class.descriptorString(), null, null);
        m.visitCode();
        if (info.targetLookup != null) {
            m.visitVarInsn(ALOAD, 0);
            m.visitFieldInsn(GETFIELD, proxyName, "target", targetDescriptor);
        } else m.visitInsn(ACONST_NULL);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private void generateMethods(Class<?> interFace) {
        for (Method method : interFace.getMethods()) {
            int mod = method.getModifiers();
            if ((mod & (Modifier.STATIC | Modifier.FINAL)) != 0) continue;

            Exception exception = null;
            Enum<?> extension = EXTENSIONS_ENABLED && JBRApi.extensionExtractor != null ?
                    JBRApi.extensionExtractor.apply(method) : null;
            Mapping.Method methodMapping = mappingContext.getMapping(method);
            MethodHandle handle;
            boolean passInstance;

            if (methodMapping.query().valid) {
                // Try static method.
                handle = info.getStaticMethod(method.getName(), methodMapping.type());
                passInstance = false;

                // Try target class.
                if (handle == null && info.targetLookup != null) {
                    try {
                        handle = interFace.equals(info.targetLookup.lookupClass()) ?
                                info.targetLookup.unreflect(method) : info.targetLookup.findVirtual(
                                info.targetLookup.lookupClass(), method.getName(), methodMapping.type());
                        passInstance = true;
                    } catch (NoSuchMethodException | IllegalAccessException e) {
                        exception = e;
                    }
                }

                if (handle != null) {
                    // Method found.
                    generateMethod(method, handle, methodMapping, extension, passInstance);
                    if (extension != null) supportedExtensions.putIfAbsent(extension, true);
                    continue;
                }
            } else {
                exception = new Exception("Method mapping is invalid: " + methodMapping);
            }

            // Skip if possible.
            if (!Modifier.isAbstract(mod)) continue;

            // Generate unsupported stub.
            generateUnsupportedMethod(proxyWriter, method);
            if (JBRApi.VERBOSE) {
                synchronized (System.err) {
                    System.err.println("Couldn't generate method " + method.getName());
                    if (exception != null) exception.printStackTrace(System.err);
                }
            }
            if (extension == null) supported = false;
            else supportedExtensions.put(extension, false);
        }
    }

    private void generateMethod(Method interfaceMethod, MethodHandle handle, Mapping.Method mapping, Enum<?> extension, boolean passInstance) {
        boolean passExtensions = mapping.query().needsExtensions;
        InternalMethodInfo methodInfo = getInternalMethodInfo(interfaceMethod);
        MethodHandleInfo directCall = accessContext.resolveDirect(handle);
        Supplier<MethodHandle> futureHandle = () -> handle;

        // Check usage of deprecated API.
        if (JBRApi.LOG_DEPRECATED && interfaceMethod.isAnnotationPresent(Deprecated.class)) {
            directCall = null; // Force invokedynamic.
            futureHandle = () -> { // Log warning when binding the call site.
                Utils.log(Utils.BEFORE_BOOTSTRAP_DYNAMIC, System.err, "Warning: using deprecated JBR API method " +
                        interfaceMethod.getDeclaringClass().getCanonicalName() + "." + interfaceMethod.getName());
                return handle;
            };
        }

        if (JBRApi.VERBOSE) {
            System.out.println("  " +
                    mapping.returnMapping() + " " +
                    interfaceMethod.getName() + "(" +
                    Stream.of(mapping.parameterMapping()).map(Mapping::toString).collect(Collectors.joining(", ")) +
                    ")" + (directCall != null ? " (direct)" : "")
            );
        }

        MethodVisitor m = proxyWriter.visitMethod(ACC_PUBLIC | ACC_FINAL, methodInfo.name(),
                methodInfo.descriptor(), methodInfo.genericSignature(), methodInfo.exceptionNames());
        AccessContext.Method methodContext = accessContext.new Method(m, extension == null);
        m.visitCode();
        // Load `this`.
        if (passInstance || passExtensions || extension != null) {
            m.visitVarInsn(ALOAD, 0);
            if (passInstance && (passExtensions || extension != null)) m.visitInsn(DUP);
        }
        // Check enabled extension.
        if (passExtensions || extension != null) {
            m.visitFieldInsn(GETFIELD, proxyName, "extensions", "[J");
            if (passExtensions && extension != null) m.visitInsn(DUP);
            if (passExtensions) {
                // If this method converts any parameters, we need to store extensions for later use.
                // We overwrite `this` slot, but we have it on stack, so it's ok.
                m.visitVarInsn(ASTORE, 0);
            }
            if (extension != null) {
                // Check the specific bit inside long[].
                Label afterExtensionCheck = new Label();
                m.visitIntInsn(extension.ordinal() < 8192 ? BIPUSH : SIPUSH, extension.ordinal() / 64);
                m.visitInsn(LALOAD);
                m.visitLdcInsn(1L << (extension.ordinal() % 64));
                m.visitInsn(LAND);
                m.visitInsn(LCONST_0);
                m.visitInsn(LCMP);
                m.visitJumpInsn(IFNE, afterExtensionCheck);
                throwException(m, "java/lang/UnsupportedOperationException",
                        interFace.getCanonicalName() + '.' + interfaceMethod.getName() +
                                " - extension " + extension.name() + " is disabled");
                m.visitLabel(afterExtensionCheck);
            }
        }
        // Extract target from `this`.
        if (passInstance) {
            // We already have `this` on stack.
            m.visitFieldInsn(GETFIELD, proxyName, "target", targetDescriptor);
        }
        // Load and convert parameters.
        for (int lvIndex = 1, i = 0; i < mapping.parameterMapping().length; i++) {
            Mapping param = mapping.parameterMapping()[i];
            int opcode = getLoadOpcode(param.from);
            m.visitVarInsn(opcode, lvIndex);
            lvIndex += getParameterSize(param.from);
            param.convert(methodContext);
        }
        // Invoke target method.
        if (directCall != null) methodContext.invokeDirect(directCall);
        else methodContext.invokeDynamic(handle.type(), futureHandle);
        // Convert return value.
        mapping.returnMapping().convert(methodContext);
        int opcode = getReturnOpcode(mapping.returnMapping().to);
        m.visitInsn(opcode);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }
}
