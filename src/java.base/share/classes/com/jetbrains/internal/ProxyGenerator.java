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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.jetbrains.internal.ASMUtils.*;
import static java.lang.invoke.MethodHandles.Lookup;
import static jdk.internal.org.objectweb.asm.Opcodes.*;
import static jdk.internal.org.objectweb.asm.Type.getInternalName;
import static jdk.internal.org.objectweb.asm.Type.getDescriptor;

/**
 * This class generates {@linkplain Proxy proxy} classes.
 * Each proxy is just a generated class implementing some interface and
 * delegating method calls to method handles or directly to the implementation.
 * <p>
 * There are 3 proxy dispatch modes:
 * <ul>
 *     <li>interface -> proxy -> {@linkplain #generateBridge bridge} -> method handle -> implementation code</li>
 *     <li>interface -> proxy -> method handle -> implementation code</li>
 *     <li>interface -> proxy -> implementation code</li>
 * </ul>
 * Generated proxy is always located in the same package with its interface and optional bridge is located in the
 * same module with target implementation code. Bridge allows proxy to safely call hidden non-static implementation
 * methods and is only needed for {@code jetbrains.api} -> JBR calls. For JBR -> {@code jetbrains.api} calls, proxy can
 * invoke method handle directly. Also, implementation code can be called directly, when it is accessible.
 */
class ProxyGenerator {
    private static final String OBJECT_DESCRIPTOR = "Ljava/lang/Object;";
    /**
     * Print warnings about usage of deprecated interfaces and methods to {@link System#err}.
     */
    private static final boolean LOG_DEPRECATED = System.getProperty("jetbrains.api.logDeprecated", "true").equalsIgnoreCase("true");
    private static final boolean VERIFY_BYTECODE = Boolean.getBoolean("jetbrains.api.verifyBytecode");

    private static final ClassVisitor EMPTY_CLASS_VISITOR = new ClassVisitor(ASM9) {};
    private static final MethodVisitor EMPTY_METHOD_VISITOR = new MethodVisitor(ASM9) {};
    private static final AtomicInteger nameCounter = new AtomicInteger();

    private static class ProxyClassWriter extends ClassWriter {
        public ProxyClassWriter() {
            super(ClassWriter.COMPUTE_FRAMES);
        }
        @Override
        protected ClassLoader getClassLoader() {
            return JBRApi.outerLookup.lookupClass().getClassLoader();
        }
    }

    private final Class<?> interFace;
    private final Lookup targetLookup;
    /**
     * Full-privileged lookup for bridge generation. This is usually a JBR API module implementation class,
     * e.g. {@link com.jetbrains.base.JBRApiModule}. May be null.
     */
    private final Lookup bridgeGenLookup;
    /**
     * Full-privileged lookup for proxy generation. This is usually an interface class.
     */
    private final Lookup proxyGenLookup;
    private final Map<String, ProxyInfo.StaticMethodMapping> staticMethods;
    private final Mapping.Context mappingContext;
    private final Mapping[] specialization;

    private final boolean generateBridge;
    private final String proxyName, bridgeName, superclassName;
    private final String[] superinterfaceNames;
    private final ClassWriter originalProxyWriter, originalBridgeWriter;
    private final ClassVisitor proxyWriter, bridgeWriter, fieldWriter;
    private final Context context;

    private final List<Exception> exceptions = new ArrayList<>();

    private int bridgeMethodCounter;
    private boolean allMethodsImplemented = true;
    private Lookup generatedHandlesHolder, generatedProxy;

    /**
     * Creates new specialized implicit proxy.
     */
    ProxyGenerator(ProxyRepository proxyRepository, Lookup lookup, Mapping[] specialization) {
        this(proxyRepository, lookup.lookupClass(), lookup, null, lookup, Map.of(), specialization);
    }

    /**
     * Creates new proxy generator from given {@link ProxyInfo},
     * looks for abstract interface methods, corresponding implementation methods
     * and generates proxy bytecode. However, it doesn't actually load generated
     * classes until {@link #defineClasses()} is called.
     */
    ProxyGenerator(ProxyRepository proxyRepository, ProxyInfo info, Mapping[] specialization) {
        this(proxyRepository, info.interFace, info.target,
                info.type.isPublicApi() ? info.apiModule : null, info.interFaceLookup, info.staticMethods, specialization);
    }

    ProxyGenerator(ProxyRepository proxyRepository, Class<?> interFace, Lookup targetLookup, Lookup bridgeGenLookup,
                   Lookup proxyGenLookup, Map<String, ProxyInfo.StaticMethodMapping> staticMethods, Mapping[] specialization) {
        this.interFace = interFace;
        this.targetLookup = targetLookup;
        this.bridgeGenLookup = bridgeGenLookup;
        this.proxyGenLookup = proxyGenLookup;
        this.staticMethods = staticMethods;
        this.mappingContext = new Mapping.Context(proxyRepository);
        this.specialization = specialization;
        generateBridge = bridgeGenLookup != null;

        int nameId = nameCounter.getAndIncrement();
        proxyName = getInternalName(interFace) + "$$JBRApiProxy$" + nameId;
        bridgeName = generateBridge ? bridgeGenLookup.lookupClass().getPackageName().replace('.', '/') + "/" +
                interFace.getSimpleName() + "$$JBRApiBridge$" + nameId : null;

        originalProxyWriter = new ProxyClassWriter();
        proxyWriter = VERIFY_BYTECODE ? new CheckClassAdapter(originalProxyWriter, true) : originalProxyWriter;
        originalBridgeWriter = generateBridge ? new ProxyClassWriter() : null;
        if (generateBridge) {
            bridgeWriter = VERIFY_BYTECODE ? new CheckClassAdapter(originalBridgeWriter, true) : originalBridgeWriter;
        } else bridgeWriter = EMPTY_CLASS_VISITOR;
        if (interFace.isInterface()) {
            superclassName = "java/lang/Object";
            superinterfaceNames = new String[] {getInternalName(interFace)};
        } else {
            superclassName = getInternalName(interFace);
            superinterfaceNames = null;
        }

        fieldWriter = generateBridge ? bridgeWriter : proxyWriter;
        context = new Context(generateBridge ? bridgeName : proxyName);
    }

    Set<Proxy> getDirectProxyDependencies() {
        return context.directProxyDependencies;
    }

    /**
     * Insert all method handles and class references into static fields, so that proxy can call implementation methods.
     */
    void init() {
        try {
            for (int i = 0; i < context.fields.size(); i++) {
                generatedHandlesHolder
                        .findStaticVarHandle(generatedHandlesHolder.lookupClass(), "f" + i, Object.class)
                        .set(context.fields.get(i).get());
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @return method handle to constructor of generated service class, or null. Constructor is no-arg.
     */
    MethodHandle findServiceConstructor(MethodHandle wrapperConstructor) {
        if (wrapperConstructor != null) {
            try {
                return MethodHandles.foldArguments(wrapperConstructor,
                        targetLookup.findConstructor(targetLookup.lookupClass(),
                                MethodType.methodType(void.class)).asType(MethodType.methodType(Object.class)));
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new RuntimeException("Service implementation must have no-args constructor: " +
                        targetLookup.lookupClass(), e);
            }
        } else {
            try {
                return generatedProxy.findConstructor(generatedProxy.lookupClass(), MethodType.methodType(void.class));
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * @return method handle to wrapper constructor of generated proxy class, or null.
     * Constructor is single-arg, expecting target object to which it would delegate method calls.
     */
    MethodHandle findWrapperConstructor() {
        try {
            return targetLookup == null ? null :
                    generatedProxy.findConstructor(generatedProxy.lookupClass(),
                            MethodType.methodType(void.class, Object.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @return method handle that receives proxy and returns its target, or null.
     */
    MethodHandle findTargetExtractor() {
        try {
            return targetLookup == null ? null :
                    generatedProxy.findGetter(generatedProxy.lookupClass(), "target", Object.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Loads generated classes.
     * @return generated proxy class.
     */
    Class<?> defineClasses() {
        try {
            Lookup bridge = !generateBridge ? null : MethodHandles.privateLookupIn(
                    bridgeGenLookup.defineClass(originalBridgeWriter.toByteArray()), bridgeGenLookup);
            generatedProxy = proxyGenLookup.defineHiddenClass(
                    originalProxyWriter.toByteArray(), true, Lookup.ClassOption.STRONG, Lookup.ClassOption.NESTMATE);
            generatedHandlesHolder = generateBridge ? bridge : generatedProxy;
            return generatedProxy.lookupClass();
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    boolean generate() {
        proxyWriter.visit(CLASSFILE_VERSION, ACC_SUPER | ACC_FINAL | ACC_SYNTHETIC, proxyName, null,
                superclassName, superinterfaceNames);
        bridgeWriter.visit(CLASSFILE_VERSION, ACC_SUPER | ACC_FINAL | ACC_SYNTHETIC | ACC_PUBLIC, bridgeName, null,
                "java/lang/Object", null);
        if (JBRApi.VERBOSE) {
            synchronized (System.out) {
                System.out.print("Generating proxy " + interFace.getName());
                if (generateBridge) System.out.print(" (bridge)");
                if (specialization != null) System.out.print(" <" +
                        Stream.of(specialization).map(t -> t == null ? "?" : t.toString()).collect(Collectors.joining(", ")) + ">");
                System.out.println();
            }
        }
        if (specialization != null) {
            mappingContext.initTypeParameters(interFace, Stream.of(specialization));
        }
        mappingContext.initTypeParameters(interFace);
        generateConstructor();
        generateMethods(interFace);
        if (interFace.isInterface()) generateMethods(Object.class);
        for (int i = 0; i < context.fields.size(); i++) fieldWriter.visitField(ACC_PRIVATE | ACC_STATIC, "f" + i, OBJECT_DESCRIPTOR, null, null);
        proxyWriter.visitEnd();
        bridgeWriter.visitEnd();
        return allMethodsImplemented;
    }

    private void generateConstructor() {
        if (targetLookup != null) {
            proxyWriter.visitField(ACC_PRIVATE | ACC_FINAL, "target", OBJECT_DESCRIPTOR, null, null);
        }
        MethodVisitor p = proxyWriter.visitMethod(ACC_PRIVATE, "<init>", "(" +
                (targetLookup == null ? "" : OBJECT_DESCRIPTOR) + ")V", null, null);
        if (LOG_DEPRECATED && interFace.isAnnotationPresent(Deprecated.class)) {
            logDeprecated(p, "Warning: using deprecated JBR API interface " + interFace.getName());
        }
        p.visitCode();
        p.visitVarInsn(ALOAD, 0);
        if (targetLookup != null) {
            p.visitInsn(DUP);
            p.visitVarInsn(ALOAD, 1);
            p.visitFieldInsn(PUTFIELD, proxyName, "target", OBJECT_DESCRIPTOR);
        }
        p.visitMethodInsn(INVOKESPECIAL, superclassName, "<init>", "()V", false);
        p.visitInsn(RETURN);
        p.visitMaxs(0, 0);
        p.visitEnd();
    }

    private void generateMethods(Class<?> interFace) {
        for (Method method : interFace.getMethods()) {
            int mod = method.getModifiers();
            if ((mod & (Modifier.STATIC | Modifier.FINAL)) != 0) continue;
            Exception e1 = null, e2 = null;

            Mapping.Method methodMapping = mappingContext.getMapping(method);

            ProxyInfo.StaticMethodMapping mapping = staticMethods.get(method.getName());
            if (mapping != null) {
                try {
                    MethodHandle staticHandle =
                            mapping.lookup().findStatic(mapping.lookup().lookupClass(), mapping.methodName(), methodMapping.type());
                    generateMethod(method, staticHandle, methodMapping, false);
                    continue;
                } catch (NoSuchMethodException | IllegalAccessException e) {
                    e1 = e;
                }
            }

            if (targetLookup != null) {
                try {
                    MethodHandle handle = interFace.equals(targetLookup.lookupClass()) ?
                            targetLookup.unreflect(method) : targetLookup.findVirtual(
                            targetLookup.lookupClass(), method.getName(), methodMapping.type());
                    generateMethod(method, handle, methodMapping, true);
                    continue;
                } catch (NoSuchMethodException | IllegalAccessException e) {
                    e2 = e;
                }
            }

            if (!Modifier.isAbstract(mod)) continue;

            if (e1 != null) exceptions.add(e1);
            if (e2 != null) exceptions.add(e2);
            generateUnsupportedMethod(proxyWriter, method);
            if (JBRApi.VERBOSE) {
                System.err.println("Couldn't generate method " + method.getName());
                if (e1 != null) e1.printStackTrace();
                if (e2 != null) e2.printStackTrace();
            }
            allMethodsImplemented = false;
        }
    }

    private void generateMethod(Method interfaceMethod, MethodHandle handle, Mapping.Method mapping, boolean passInstance) {
        InternalMethodInfo methodInfo = getInternalMethodInfo(interfaceMethod);
        String bridgeMethodDescriptor = getBridgeDescriptor(mapping, passInstance);

        MethodHandleInfo directCall = null;
        if (passInstance && (!generateBridge || !mapping.convertible())) {
            try {
                MethodHandleInfo mhi = proxyGenLookup.revealDirect(handle);
                Class<?> declaringClass = mhi.getDeclaringClass();
                ClassLoader targetClassLoader = declaringClass.getClassLoader();
                for (ClassLoader cl = interFace.getClassLoader();; cl = cl.getParent()) {
                    if (cl == targetClassLoader) {
                        // Accessible directly.
                        directCall = mhi;
                        break;
                    }
                    if (cl == null) break;
                }
            } catch (Exception ignore) {
                // Not accessible.
            }
        }
        String handleName = directCall != null ? null : context.addField(() -> handle);
        if (JBRApi.VERBOSE) {
            System.out.println("  " +
                    mapping.returnMapping() + " " +
                    interfaceMethod.getName() + "(" +
                    Stream.of(mapping.parameterMapping()).map(Mapping::toString).collect(Collectors.joining(", ")) +
                    ")" + (directCall != null ? " (direct)" : "")
            );
        }
        boolean useBridge = generateBridge && directCall == null;
        String bridgeMethodName = useBridge ? methodInfo.name() + "$bridge$" + (bridgeMethodCounter++) : null;

        MethodVisitor p = proxyWriter.visitMethod(ACC_PUBLIC | ACC_FINAL, methodInfo.name(),
                methodInfo.descriptor(), methodInfo.genericSignature(), methodInfo.exceptionNames());
        MethodVisitor b = useBridge ? bridgeWriter.visitMethod(ACC_PUBLIC | ACC_STATIC, bridgeMethodName,
                bridgeMethodDescriptor, null, null) : EMPTY_METHOD_VISITOR;
        context.methodWriter = useBridge ? b : p;
        if (LOG_DEPRECATED && interfaceMethod.isAnnotationPresent(Deprecated.class)) {
            logDeprecated(p, "Warning: using deprecated JBR API method " +
                    interfaceMethod.getDeclaringClass().getName() + "#" + interfaceMethod.getName());
        }
        p.visitCode();
        b.visitCode();
        if (directCall == null) {
            context.loadField(handleName);
        }
        if (passInstance) {
            p.visitVarInsn(ALOAD, 0);
            p.visitFieldInsn(GETFIELD, proxyName, "target", OBJECT_DESCRIPTOR);
            b.visitVarInsn(ALOAD, 0);
        }
        int lvIndex = 1;
        for (int i = 0; i < mapping.parameterMapping().length; i++) {
            Mapping param = mapping.parameterMapping()[i];
            int opcode = getLoadOpcode(param.from);
            p.visitVarInsn(opcode, lvIndex);
            b.visitVarInsn(opcode, lvIndex - (passInstance ? 0 : 1));
            lvIndex += getParameterSize(param.from);
            param.convert(context);
        }
        if (useBridge) {
            p.visitMethodInsn(INVOKESTATIC, bridgeName, bridgeMethodName, bridgeMethodDescriptor, false);
        }
        if (directCall == null) {
            context.invokeMethodHandle(bridgeMethodDescriptor);
        } else {
            Class<?> t = directCall.getDeclaringClass();
            p.visitMethodInsn(t.isInterface() ? INVOKEINTERFACE : INVOKEVIRTUAL, getInternalName(t),
                    directCall.getName(), directCall.getMethodType().descriptorString(), t.isInterface());
        }
        mapping.returnMapping().convert(context);
        int returnOpcode = getReturnOpcode(mapping.returnMapping().to);
        p.visitInsn(returnOpcode);
        b.visitInsn(returnOpcode);
        p.visitMaxs(0, 0);
        b.visitMaxs(0, 0);
        p.visitEnd();
        b.visitEnd();
    }

    /**
     * Every convertable parameter type is replaced with {@link Object} for bridge descriptor.
     * Optional {@link Object} is added as first parameter for instance methods.
     */
    private static String getBridgeDescriptor(Mapping.Method method, boolean passInstance) {
        StringBuilder bd = new StringBuilder("(");
        if (passInstance) bd.append(OBJECT_DESCRIPTOR);
        for (Mapping m : method.parameterMapping()) {
            bd.append(getBridgeDescriptor(m));
        }
        bd.append(')');
        bd.append(getBridgeDescriptor(method.returnMapping()));
        return bd.toString();
    }

    private static String getBridgeDescriptor(Mapping type) {
        if (type instanceof Mapping.Identity) return getDescriptor(type.from);
        if (type instanceof Mapping.Array a) return "[" + getBridgeDescriptor(a.component);
        else return OBJECT_DESCRIPTOR;
    }

    static class Context {
        private final String fieldHolder;
        private final Set<Proxy> directProxyDependencies = new HashSet<>();
        private final List<Supplier<?>> fields = new ArrayList<>();
        private final Map<Cached<?>, Object> cache = new HashMap<>();
        private MethodVisitor methodWriter;

        private Context(String fieldHolder) {
            this.fieldHolder = fieldHolder;
        }

        void addDirectDependency(Proxy proxy) { directProxyDependencies.add(proxy); }

        String addField(Supplier<?> fieldSupplier) {
            String name = "f" + fields.size();
            fields.add(fieldSupplier);
            return name;
        }

        void loadField(String name) {
            methodWriter.visitFieldInsn(GETSTATIC, fieldHolder, name, OBJECT_DESCRIPTOR);
        }

        void invokeMethodHandle(String descriptor) {
            methodWriter.visitMethodInsn(INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invoke", descriptor, false);
        }

        @SuppressWarnings("unchecked")
        <T> T cached(Cached<T> c) { return (T) cache.computeIfAbsent(c, t -> t.create(this)); }

        MethodVisitor methodWriter() { return methodWriter; }
    }

    interface Cached<T> {
        T create(ProxyGenerator.Context context);
    }
}
