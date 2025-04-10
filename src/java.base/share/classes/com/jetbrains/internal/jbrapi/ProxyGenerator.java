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

import java.lang.classfile.*;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
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
import static com.jetbrains.internal.jbrapi.BytecodeUtils.*;
import static com.jetbrains.internal.jbrapi.JBRApi.EXTENSIONS_ENABLED;
import static java.lang.classfile.ClassFile.*;
import static java.lang.invoke.MethodHandles.Lookup;

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
    private static final Map<MethodSignature, Method> OBJECT_METHODS = Stream.of(Object.class.getMethods())
            .filter(ProxyGenerator::isValidInterfaceMethod)
            .collect(Collectors.toUnmodifiableMap(MethodSignature::of, m -> m));

    private static boolean isValidInterfaceMethod(Method method) {
        return (method.getModifiers() & (Modifier.STATIC | Modifier.FINAL)) == 0;
    }

    private final Proxy.Info info;
    private final Class<?> interFace;
    private final Lookup proxyGenLookup;
    private final Mapping[] specialization;
    private final Mapping.Context mappingContext;
    private final AccessContext accessContext;

    private final Class<?> constructorTargetParameterType;
    private final ClassDesc targetDesc, proxyDesc, superclassDesc;
    private final ClassDesc[] superinterfaceDescs;

    private final Map<Enum<?>, Boolean> supportedExtensions = new HashMap<>();
    private Map<MethodSignature, Method> remainingObjectMethods;

    private boolean supported = true;
    private byte[] bytecode;
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
        targetDesc = desc(constructorTargetParameterType);

        // Even though generated proxy is hidden and therefore has no qualified name,
        // it can reference itself via internal name, which can lead to name collisions.
        // Let's consider specialized proxy for java/util/List - if we name proxy similarly,
        // methods calls to java/util/List will be treated by VM as calls to proxy class,
        // not standard library interface. Therefore we append $$$ to proxy name to avoid name collision.
        proxyDesc = ClassDesc.of(proxyGenLookup.lookupClass().getPackageName(), interFace.getSimpleName() + "$$$");

        if (interFace.isInterface()) {
            superclassDesc = OBJECT_DESC;
            superinterfaceDescs = new ClassDesc[] {desc(interFace), PROXY_INTERFACE_DESC};
        } else {
            superclassDesc = desc(interFace);
            superinterfaceDescs = new ClassDesc[] {PROXY_INTERFACE_DESC};
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
                    generatedProxy.lookupClass(), t.name(), t.descriptor().descriptorString()
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
                    bytecode, false, Lookup.ClassOption.STRONG, Lookup.ClassOption.NESTMATE);
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
        bytecode = ClassFile.of().build(proxyDesc, cb -> {
            cb.withFlags(ACC_SUPER | ACC_FINAL | ACC_SYNTHETIC)
              .withSuperclass(superclassDesc)
              .withInterfaceSymbols(superinterfaceDescs);
            generateFields(cb);
            generateConstructor(cb);
            generateTargetGetter(cb);
            generateMethods(cb);
        });
        if (JBRApi.VERIFY_BYTECODE) {
            List<VerifyError> errors = ClassFile.of().verify(bytecode);
            if (!errors.isEmpty()) {
                VerifyError error = new VerifyError("Proxy verification failed");
                for (VerifyError e : errors) error.addSuppressed(e);
                throw error;
            }
        }
        return supported;
    }

    private void generateFields(ClassBuilder cb) {
        if (info.targetLookup != null) {
            cb.withField("target", targetDesc, ACC_PRIVATE | ACC_FINAL);
        }
        if (EXTENSIONS_ENABLED) {
            cb.withField("extensions", EXTENSION_ARRAY_DESC, ACC_PRIVATE | ACC_FINAL);
        }
    }

    private void generateConstructor(ClassBuilder cb) {
        int extensionsParamIndex = info.targetLookup != null ? 1 : 0;
        ClassDesc[] params = new ClassDesc[extensionsParamIndex + (EXTENSIONS_ENABLED ? 1 : 0)];
        if (info.targetLookup != null) params[0] = targetDesc;
        if (EXTENSIONS_ENABLED) params[extensionsParamIndex] = EXTENSION_ARRAY_DESC;
        cb.withMethodBody("<init>", MethodTypeDesc.of(VOID_DESC, params), ACC_PRIVATE, m -> {
            m.aload(0);
            if (info.targetLookup != null) {
                m.dup()
                 .aload(m.parameterSlot(0))
                 .putfield(proxyDesc, "target", targetDesc);
            }
            if (EXTENSIONS_ENABLED) {
                m.dup()
                 .aload(m.parameterSlot(extensionsParamIndex))
                 .putfield(proxyDesc, "extensions", EXTENSION_ARRAY_DESC);
            }
            m.invokespecial(superclassDesc, "<init>", MethodTypeDesc.of(VOID_DESC), false)
             .return_();
        });
    }

    private void generateTargetGetter(ClassBuilder cb) {
        cb.withMethodBody("$getProxyTarget", GET_PROXY_TARGET_DESC, ACC_PUBLIC | ACC_FINAL, m -> {
            if (info.targetLookup != null) {
                m.aload(0)
                 .getfield(proxyDesc, "target", targetDesc);
            } else m.aconst_null();
            m.areturn();
        });
    }

    private void generateMethods(ClassBuilder cb) {
        boolean isInterface = interFace.isInterface();
        // Generate implementation for interface methods.
        for (Method method : interFace.getMethods()) {
            if (isValidInterfaceMethod(method)) {
                if (isInterface) {
                    // Remember Object methods which we already encountered, they may be not included in getMethods().
                    MethodSignature signature = MethodSignature.of(method);
                    if (remainingObjectMethods == null && OBJECT_METHODS.containsKey(signature)) {
                        remainingObjectMethods = new HashMap<>(OBJECT_METHODS);
                    }
                    if (remainingObjectMethods != null) remainingObjectMethods.remove(signature);
                }
                generateMethod(cb, method);
            }
        }
        if (isInterface) {
            // Generate implementation for the remaining Object methods.
            for (Method method : (remainingObjectMethods != null ? remainingObjectMethods : OBJECT_METHODS).values()) {
                generateMethod(cb, method);
            }
        }
    }

    private void generateMethod(ClassBuilder cb, Method method) {
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
                generateMethod(cb, method, handle, methodMapping, extension, passInstance);
                if (extension != null) supportedExtensions.putIfAbsent(extension, true);
                return;
            }
        } else {
            exception = new Exception("Method mapping is invalid: " + methodMapping);
        }

        // Skip if possible.
        if (!Modifier.isAbstract(method.getModifiers())) return;

        // Generate unsupported stub.
        generateCompatibleMethod(cb, method, m ->
                throwUnsupportedOperationException(m, "No implementation found for this method"));
        if (JBRApi.VERBOSE) {
            synchronized (System.err) {
                System.err.println("Couldn't generate method " + method.getName());
                if (exception != null) exception.printStackTrace(System.err);
            }
        }
        if (extension == null) supported = false;
        else supportedExtensions.put(extension, false);
    }

    private void generateMethod(ClassBuilder cb, Method interfaceMethod, MethodHandle handle,
                                Mapping.Method mapping, Enum<?> extension, boolean passInstance) {
        generateCompatibleMethod(cb, interfaceMethod, m -> {
            boolean passExtensions = mapping.query().needsExtensions;
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

            AccessContext.Method methodContext = accessContext.new Method(m, extension == null);
            // Load `this`.
            if (passInstance || passExtensions || extension != null) {
                m.aload(0);
                if (passInstance && (passExtensions || extension != null)) m.dup();
            }
            // Check enabled extension.
            if (passExtensions || extension != null) {
                m.getfield(proxyDesc, "extensions", EXTENSION_ARRAY_DESC);
                if (passExtensions && extension != null) m.dup();
                if (passExtensions) {
                    // If this method converts any parameters, we need to store extensions for later use.
                    // We overwrite `this` slot, but we have it on stack, so it's ok.
                    m.astore(0);
                }
                if (extension != null) {
                    // Check the specific bit inside long[].
                    Label afterExtensionCheck = m.newLabel();
                    m.with(ConstantInstruction.ofArgument(
                            extension.ordinal() < 8192 ? Opcode.BIPUSH : Opcode.SIPUSH, extension.ordinal() / 64))
                     .laload()
                     .loadConstant(1L << (extension.ordinal() % 64))
                     .land()
                     .lconst_0()
                     .lcmp()
                     .ifne(afterExtensionCheck);
                    throwUnsupportedOperationException(m, interFace.getCanonicalName() + '.' +
                            interfaceMethod.getName() + " - extension " + extension.name() + " is disabled");
                    m.labelBinding(afterExtensionCheck);
                }
            }
            // Extract target from `this`.
            if (passInstance) {
                // We already have `this` on stack.
                m.getfield(proxyDesc, "target", targetDesc);
            }
            // Load and convert parameters.
            for (int i = 0; i < mapping.parameterMapping().length; i++) {
                Mapping param = mapping.parameterMapping()[i];
                m.loadLocal(TypeKind.from(param.from), m.parameterSlot(i));
                param.convert(methodContext);
            }
            // Invoke target method.
            if (directCall != null) methodContext.invokeDirect(directCall);
            else methodContext.invokeDynamic(handle.type(), futureHandle);
            // Convert return value.
            mapping.returnMapping().convert(methodContext);
            m.return_(TypeKind.from(mapping.returnMapping().to));
        });
    }

    private record MethodSignature(String name, Class<?>[] parameters) {
        private static MethodSignature of(Method method) {
            return new MethodSignature(method.getName(), method.getParameterTypes());
        }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            return (o instanceof MethodSignature(String n, Class<?>[] p)) &&
                    name.equals(n) && Arrays.equals(parameters, p);
        }
        @Override
        public int hashCode() {
            return name.hashCode() + 31 * Arrays.hashCode(parameters);
        }
    }
}
