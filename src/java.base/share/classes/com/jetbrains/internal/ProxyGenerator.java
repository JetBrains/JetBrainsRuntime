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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.jetbrains.internal.ASMUtils.*;
import static java.lang.invoke.MethodHandles.Lookup;
import static jdk.internal.org.objectweb.asm.Opcodes.*;

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
    private static final String MH_NAME = "java/lang/invoke/MethodHandle";
    private static final String MH_DESCRIPTOR = "Ljava/lang/invoke/MethodHandle;";
    private static final String CONVERSION_DESCRIPTOR = "(Ljava/lang/Object;)Ljava/lang/Object;";
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

    private final ProxyInfo info;
    private final boolean generateBridge;
    private final String proxyName, bridgeName, superclassName;
    private final ClassWriter originalProxyWriter, originalBridgeWriter;
    private final ClassVisitor proxyWriter, bridgeWriter;
    private final List<Supplier<MethodHandle>> handles = new ArrayList<>();
    private final List<Supplier<Class<?>>> classReferences = new ArrayList<>();
    private final Set<Proxy<?>> directProxyDependencies = new HashSet<>();
    private final List<Exception> exceptions = new ArrayList<>();
    private int bridgeMethodCounter;
    private boolean allMethodsImplemented = true;
    private Lookup generatedHandlesHolder, generatedProxy;
    private MethodHandle generatedWrapperConstructor, generatedTargetExtractor, generatedServiceConstructor;

    /**
     * Creates new proxy generator from given {@link ProxyInfo},
     * looks for abstract interface methods, corresponding implementation methods
     * and generates proxy bytecode. However, it doesn't actually load generated
     * classes until {@link #defineClasses()} is called.
     */
    ProxyGenerator(ProxyInfo info) {
        this.info = info;
        generateBridge = info.type.isPublicApi();
        int nameId = nameCounter.getAndIncrement();
        proxyName = Type.getInternalName(info.interFace) + "$$JBRApiProxy$" + nameId;
        bridgeName = generateBridge ? info.apiModule.lookupClass().getPackageName().replace('.', '/') + "/" +
                info.interFace.getSimpleName() + "$$JBRApiBridge$" + nameId : null;

        originalProxyWriter = new ProxyClassWriter();
        proxyWriter = VERIFY_BYTECODE ? new CheckClassAdapter(originalProxyWriter, true) : originalProxyWriter;
        originalBridgeWriter = generateBridge ? new ProxyClassWriter() : null;
        if (generateBridge) {
            bridgeWriter = VERIFY_BYTECODE ? new CheckClassAdapter(originalBridgeWriter, true) : originalBridgeWriter;
        } else bridgeWriter = EMPTY_CLASS_VISITOR;
        String[] superinterfaces;
        if (info.interFace.isInterface()) {
            superclassName = "java/lang/Object";
            superinterfaces = new String[] {Type.getInternalName(info.interFace)};
        } else {
            superclassName = Type.getInternalName(info.interFace);
            superinterfaces = null;
        }
        proxyWriter.visit(CLASSFILE_VERSION, ACC_SUPER | ACC_FINAL | ACC_SYNTHETIC, proxyName, null,
                superclassName, superinterfaces);
        bridgeWriter.visit(CLASSFILE_VERSION, ACC_SUPER | ACC_FINAL | ACC_SYNTHETIC | ACC_PUBLIC, bridgeName, null,
                "java/lang/Object", null);
        if (JBRApi.VERBOSE) {
            System.out.println("Generating proxy " + info.interFace.getName() +
                    (generateBridge ? " (bridge)" : ""));
        }
        generateConstructor();
        generateMethods(info.interFace);
        if (info.interFace.isInterface()) generateMethods(Object.class);
        proxyWriter.visitEnd();
        bridgeWriter.visitEnd();
    }

    boolean areAllMethodsImplemented() {
        return allMethodsImplemented;
    }

    Set<Proxy<?>> getDirectProxyDependencies() {
        return directProxyDependencies;
    }

    /**
     * Insert all method handles and class references into static fields, so that proxy can call implementation methods.
     */
    void init() {
        try {
            for (int i = 0; i < handles.size(); i++) {
                generatedHandlesHolder
                        .findStaticVarHandle(generatedHandlesHolder.lookupClass(), "h" + i, MethodHandle.class)
                        .set(handles.get(i).get());
            }
            for (int i = 0; i < classReferences.size(); i++) {
                generatedHandlesHolder
                        .findStaticVarHandle(generatedHandlesHolder.lookupClass(), "c" + i, Class.class)
                        .set(classReferences.get(i).get());
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    Class<?> getProxyClass() {
        return generatedProxy.lookupClass();
    }

    /**
     * @return method handle to constructor of generated service class, or null. Constructor is no-arg.
     */
    MethodHandle getServiceConstructor() {
        return generatedServiceConstructor;
    }

    /**
     * @return method handle to wrapper constructor of generated proxy class, or null.
     * Constructor is single-arg, expecting target object to which it would delegate method calls.
     */
    MethodHandle getWrapperConstructor() {
        return generatedWrapperConstructor;
    }

    /**
     * @return method handle that receives proxy and returns its target, or null.
     */
    MethodHandle getTargetExtractor() {
        return generatedTargetExtractor;
    }

    /**
     * Loads generated classes.
     */
    void defineClasses() {
        try {
            Lookup bridge = !generateBridge ? null : MethodHandles.privateLookupIn(
                    info.apiModule.defineClass(originalBridgeWriter.toByteArray()), info.apiModule);
            generatedProxy = info.interFaceLookup.defineHiddenClass(
                    originalProxyWriter.toByteArray(), true, Lookup.ClassOption.STRONG, Lookup.ClassOption.NESTMATE);
            generatedHandlesHolder = generateBridge ? bridge : generatedProxy;
            findHandles();
        } catch (IllegalAccessException | NoSuchMethodException | NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    private void findHandles() throws IllegalAccessException, NoSuchMethodException, NoSuchFieldException {
        generatedWrapperConstructor = info.target == null ? null :
                generatedProxy.findConstructor(generatedProxy.lookupClass(),
                        MethodType.methodType(void.class, Object.class));
        generatedTargetExtractor = info.target == null ? null :
                generatedProxy.findGetter(generatedProxy.lookupClass(), "target", Object.class);
        if (!info.type.isService()) {
            generatedServiceConstructor = null;
        } else if (generatedWrapperConstructor != null) {
            try {
                generatedServiceConstructor = MethodHandles.foldArguments(generatedWrapperConstructor,
                        info.target.findConstructor(info.target.lookupClass(),
                                MethodType.methodType(void.class)).asType(MethodType.methodType(Object.class)));
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new RuntimeException("Service implementation must have no-args constructor: " +
                        info.target.lookupClass(), e);
            }
        } else {
            generatedServiceConstructor =
                    generatedProxy.findConstructor(generatedProxy.lookupClass(), MethodType.methodType(void.class));
        }
    }

    private void generateConstructor() {
        if (info.target != null) {
            proxyWriter.visitField(ACC_PRIVATE | ACC_FINAL, "target", OBJECT_DESCRIPTOR, null, null);
        }
        MethodVisitor p = proxyWriter.visitMethod(ACC_PRIVATE, "<init>", "(" +
                (info.target == null ? "" : OBJECT_DESCRIPTOR) + ")V", null, null);
        if (LOG_DEPRECATED && info.interFace.isAnnotationPresent(Deprecated.class)) {
            logDeprecated(p, "Warning: using deprecated JBR API interface " + info.interFace.getName());
        }
        p.visitCode();
        p.visitVarInsn(ALOAD, 0);
        if (info.target != null) {
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
            if (Modifier.isFinal(mod)) continue;
            Exception e1 = null, e2 = null;
            try {
                MethodMapping methodMapping = getTargetMethodMapping(method);

                ProxyInfo.StaticMethodMapping mapping = info.staticMethods.get(method.getName());
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

                if (info.target != null) {
                    try {
                        MethodHandle handle = info.target.findVirtual(
                                info.target.lookupClass(), method.getName(), methodMapping.type());
                        generateMethod(method, handle, methodMapping, true);
                        continue;
                    } catch (NoSuchMethodException | IllegalAccessException e) {
                        e2 = e;
                    }
                }
            } catch (Exception e) {
                e1 = e;
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

    private void generateMethod(Method interfaceMethod, MethodHandle handle, MethodMapping mapping, boolean passInstance) {
        InternalMethodInfo methodInfo = getInternalMethodInfo(interfaceMethod);
        String bridgeMethodDescriptor = mapping.getBridgeDescriptor(passInstance);

        ClassVisitor handleWriter = generateBridge ? bridgeWriter : proxyWriter;
        String bridgeOrProxyName = generateBridge ? bridgeName : proxyName;
        boolean noConversions = true;
        for (TypeMapping m : mapping) {
            if (m.conversion() != TypeConversion.IDENTITY) {
                noConversions = false;
                if (m.arrayLevel() > 0) {
                    m.metadata().createArrayHandle = new String[m.arrayLevel()];
                    Class<?> t = m.to;
                    for (int i = 0; i < m.arrayLevel(); i++) {
                        Class<?> c = t;
                        m.metadata().createArrayHandle[i] = addHandle(handleWriter,
                                () -> MethodHandles.arrayConstructor(c));
                        t = t.componentType();
                    }
                }
            }
            if (m.conversion() == TypeConversion.EXTRACT_TARGET ||
                    m.conversion() == TypeConversion.DYNAMIC_2_WAY) {
                Proxy<?> from = m.fromProxy();
                m.metadata().extractTargetHandle = addHandle(handleWriter, from::getTargetExtractor);
                directProxyDependencies.add(from);
            }
            if (m.conversion() == TypeConversion.WRAP_INTO_PROXY ||
                    m.conversion() == TypeConversion.DYNAMIC_2_WAY) {
                Proxy<?> to = m.toProxy();
                m.metadata().proxyConstructorHandle = addHandle(handleWriter, to::getWrapperConstructor);
                directProxyDependencies.add(to);
            }
            if (m.conversion() == TypeConversion.DYNAMIC_2_WAY) {
                String classField = "c" + classReferences.size();
                m.metadata().extractableClassField = classField;
                classReferences.add(m.fromProxy()::getProxyClass);
                handleWriter.visitField(ACC_PRIVATE | ACC_STATIC, classField, "Ljava/lang/Class;", null, null);
            }
        }
        MethodHandleInfo directCall = null;
        if (passInstance && (!generateBridge || noConversions)) {
            try {
                MethodHandleInfo mhi = info.interFaceLookup.revealDirect(handle);
                Class<?> declaringClass = mhi.getDeclaringClass();
                ClassLoader targetClassLoader = declaringClass.getClassLoader();
                for (ClassLoader cl = info.interFace.getClassLoader();; cl = cl.getParent()) {
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
        String handleName = directCall != null ? null : addHandle(handleWriter, () -> handle);
        if (JBRApi.VERBOSE) {
            System.out.println("  " +
                    mapping.returnMapping + " " +
                    interfaceMethod.getName() + "(" +
                    Stream.of(mapping.parameterMapping).map(TypeMapping::toString).collect(Collectors.joining(", ")) +
                    ")" + (directCall != null ? " (direct)" : "")
            );
        }
        boolean useBridge = generateBridge && directCall == null;
        String bridgeMethodName = useBridge ? methodInfo.name() + "$bridge$" + (bridgeMethodCounter++) : null;

        MethodVisitor p = proxyWriter.visitMethod(ACC_PUBLIC | ACC_FINAL, methodInfo.name(),
                methodInfo.descriptor(), methodInfo.genericSignature(), methodInfo.exceptionNames());
        MethodVisitor b = useBridge ? bridgeWriter.visitMethod(ACC_PUBLIC | ACC_STATIC, bridgeMethodName,
                bridgeMethodDescriptor, null, null) : EMPTY_METHOD_VISITOR;
        if (LOG_DEPRECATED && interfaceMethod.isAnnotationPresent(Deprecated.class)) {
            logDeprecated(p, "Warning: using deprecated JBR API method " +
                    interfaceMethod.getDeclaringClass().getName() + "#" + interfaceMethod.getName());
        }
        p.visitCode();
        b.visitCode();
        MethodVisitor bp = useBridge ? b : p;
        if (directCall == null) {
            bp.visitFieldInsn(GETSTATIC, bridgeOrProxyName, handleName, MH_DESCRIPTOR);
        }
        if (passInstance) {
            p.visitVarInsn(ALOAD, 0);
            p.visitFieldInsn(GETFIELD, proxyName, "target", OBJECT_DESCRIPTOR);
            b.visitVarInsn(ALOAD, 0);
        }
        int lvIndex = 1;
        for (TypeMapping param : mapping.parameterMapping) {
            int opcode = getLoadOpcode(param.from());
            p.visitVarInsn(opcode, lvIndex);
            b.visitVarInsn(opcode, lvIndex - (passInstance ? 0 : 1));
            lvIndex += getParameterSize(param.from());
            convertValue(bp, bridgeOrProxyName, param);
        }
        if (useBridge) {
            p.visitMethodInsn(INVOKESTATIC, bridgeName, bridgeMethodName, bridgeMethodDescriptor, false);
        }
        if (directCall == null) {
            bp.visitMethodInsn(INVOKEVIRTUAL, MH_NAME, "invoke", bridgeMethodDescriptor, false);
        } else {
            Class<?> t = directCall.getDeclaringClass();
            p.visitMethodInsn(t.isInterface() ? INVOKEINTERFACE : INVOKEVIRTUAL, Type.getInternalName(t),
                    directCall.getName(), directCall.getMethodType().descriptorString(), t.isInterface());
        }
        convertValue(bp, bridgeOrProxyName, mapping.returnMapping());
        int returnOpcode = getReturnOpcode(mapping.returnMapping().to());
        p.visitInsn(returnOpcode);
        b.visitInsn(returnOpcode);
        p.visitMaxs(0, 0);
        b.visitMaxs(0, 0);
        p.visitEnd();
        b.visitEnd();
    }

    private String addHandle(ClassVisitor classWriter, Supplier<MethodHandle> handleSupplier) {
        String handleName = "h" + handles.size();
        handles.add(handleSupplier);
        classWriter.visitField(ACC_PRIVATE | ACC_STATIC, handleName, MH_DESCRIPTOR, null, null);
        return handleName;
    }

    // Stack: from -> to
    private static void convertValue(MethodVisitor m, String handlesHolderName, TypeMapping mapping) {
        if (mapping.conversion() == TypeConversion.IDENTITY) return;
        // Early null-check
        Label skipConvert = new Label();
        m.visitInsn(DUP);
        m.visitJumpInsn(IFNULL, skipConvert);
        // Array loops
        record LoopLabels(Label loopStart, Label skipNull, Label loopEnd) {}
        LoopLabels[] loopLabels = null;
        if (mapping.arrayLevel() > 0) {
            Arrays.setAll(loopLabels = new LoopLabels[mapping.arrayLevel()],
                    i -> new LoopLabels(new Label(), new Label(), new Label()));
            for (int i = 0; i < mapping.arrayLevel(); i++) {
                // Stack: fromArray -> toArray, fromArray, i=length
                m.visitInsn(DUP);
                m.visitInsn(ARRAYLENGTH);
                m.visitFieldInsn(GETSTATIC, handlesHolderName, mapping.metadata.createArrayHandle[i], MH_DESCRIPTOR);
                m.visitInsn(SWAP);
                m.visitMethodInsn(INVOKEVIRTUAL, MH_NAME, "invoke", "(I)Ljava/lang/Object;", false);
                m.visitInsn(SWAP);
                m.visitInsn(DUP);
                m.visitInsn(ARRAYLENGTH);
                // Check loop conditions
                m.visitLabel(loopLabels[i].loopStart());
                m.visitInsn(DUP);
                m.visitJumpInsn(IFLE, loopLabels[i].loopEnd());
                // Stack: toArray, fromArray, i -> toArray, fromArray, i, toArray, i, from
                m.visitVarInsn(ISTORE, 0);
                m.visitInsn(DUP2);
                m.visitIincInsn(0, -1);
                m.visitVarInsn(ILOAD, 0);
                m.visitInsn(DUP_X2);
                m.visitInsn(DUP_X1);
                m.visitInsn(AALOAD);
                // Null-check
                m.visitInsn(DUP);
                m.visitJumpInsn(IFNULL, loopLabels[i].skipNull());
            }
        }
        // Stack: from -> to
        switch (mapping.conversion()) {
            case EXTRACT_TARGET ->
                    m.visitFieldInsn(GETSTATIC, handlesHolderName, mapping.metadata.extractTargetHandle, MH_DESCRIPTOR);
            case WRAP_INTO_PROXY ->
                    m.visitFieldInsn(GETSTATIC, handlesHolderName, mapping.metadata.proxyConstructorHandle, MH_DESCRIPTOR);
            case DYNAMIC_2_WAY -> {
                m.visitInsn(DUP);
                m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
                m.visitFieldInsn(GETSTATIC, handlesHolderName, mapping.metadata.extractableClassField, "Ljava/lang/Class;");
                Label elseBranch = new Label(), afterBranch = new Label();
                m.visitJumpInsn(IF_ACMPNE, elseBranch);
                m.visitFieldInsn(GETSTATIC, handlesHolderName, mapping.metadata.extractTargetHandle, MH_DESCRIPTOR);
                m.visitJumpInsn(GOTO, afterBranch);
                m.visitLabel(elseBranch);
                m.visitFieldInsn(GETSTATIC, handlesHolderName, mapping.metadata.proxyConstructorHandle, MH_DESCRIPTOR);
                m.visitLabel(afterBranch);
            }
        }
        m.visitInsn(SWAP);
        m.visitMethodInsn(INVOKEVIRTUAL, MH_NAME, "invoke", CONVERSION_DESCRIPTOR, false);
        if (mapping.arrayLevel() > 0) {
            for (int i = mapping.arrayLevel() - 1; i >= 0; i--) {
                m.visitLabel(loopLabels[i].skipNull());
                // Stack: toArray, fromArray, i, toArray, i, to -> toArray, fromArray, i
                m.visitInsn(AASTORE);
                m.visitJumpInsn(GOTO, loopLabels[i].loopStart());
                m.visitLabel(loopLabels[i].loopEnd());
                // Stack: toArray, fromArray, i -> toArray
                m.visitInsn(POP2);
            }
        }
        m.visitLabel(skipConvert);
    }

    private static MethodMapping getTargetMethodMapping(Method interfaceMethod) {
        Class<?>[] params = interfaceMethod.getParameterTypes();
        TypeMapping[] paramMappings = new TypeMapping[params.length];
        for (int i = 0; i < params.length; i++) {
            paramMappings[i] = getTargetTypeMapping(params[i]);
            params[i] = paramMappings[i].to();
        }
        TypeMapping returnMapping = getTargetTypeMapping(interfaceMethod.getReturnType()).inverse();
        return new MethodMapping(MethodType.methodType(returnMapping.from(), params), returnMapping, paramMappings);
    }

    private static <T> TypeMapping getTargetTypeMapping(Class<T> userType) {
        if (userType.isArray()) {
            TypeMapping m = getTargetTypeMapping(userType.componentType());
            return new TypeMapping(m.from().arrayType(), m.to().arrayType(),
                    m.conversion(), m.fromProxy(), m.toProxy(), m.arrayLevel() + 1, m.metadata());
        }
        TypeMappingMetadata m = new TypeMappingMetadata();
        Proxy<T> p = JBRApi.getProxy(userType);
        if (p != null && p.getInfo().target != null) {
            Proxy<?> r = JBRApi.getProxy(p.getInfo().target.lookupClass());
            if (r != null && r.getInfo().target != null) {
                return new TypeMapping(userType, p.getInfo().target.lookupClass(), TypeConversion.DYNAMIC_2_WAY, p, r, 0, m);
            }
            return new TypeMapping(userType, p.getInfo().target.lookupClass(), TypeConversion.EXTRACT_TARGET, p, null, 0, m);
        }
        Class<?> interFace = JBRApi.getProxyInterfaceByTargetName(userType.getName());
        if (interFace != null) {
            Proxy<?> r = JBRApi.getProxy(interFace);
            if (r != null) {
                return new TypeMapping(userType, interFace, TypeConversion.WRAP_INTO_PROXY, null, r, 0, m);
            }
        }
        return new TypeMapping(userType, userType, TypeConversion.IDENTITY, null, null, 0, m);
    }

    private record MethodMapping(MethodType type, TypeMapping returnMapping, TypeMapping[] parameterMapping)
            implements Iterable<TypeMapping> {
        @Override
        public Iterator<TypeMapping> iterator() {
            return new Iterator<>() {
                private int index = -1;
                @Override
                public boolean hasNext() {
                    return index < parameterMapping.length;
                }
                @Override
                public TypeMapping next() {
                    TypeMapping m = index == -1 ? returnMapping : parameterMapping[index];
                    index++;
                    return m;
                }
            };
        }

        /**
         * Every convertable parameter type is replaced with {@link Object} for bridge descriptor.
         * Optional {@link Object} is added as first parameter for instance methods.
         */
        String getBridgeDescriptor(boolean passInstance) {
            StringBuilder bd = new StringBuilder("(");
            if (passInstance) bd.append(OBJECT_DESCRIPTOR);
            for (TypeMapping m : parameterMapping) {
                bd.append(m.getBridgeDescriptor());
            }
            bd.append(')');
            bd.append(returnMapping.getBridgeDescriptor());
            return bd.toString();
        }
    }

    private record TypeMapping(Class<?> from, Class<?> to, TypeConversion conversion,
                               Proxy<?> fromProxy, Proxy<?> toProxy, int arrayLevel, TypeMappingMetadata metadata) {
        TypeMapping inverse() {
            return new TypeMapping(to, from, switch (conversion) {
                case EXTRACT_TARGET -> TypeConversion.WRAP_INTO_PROXY;
                case WRAP_INTO_PROXY -> TypeConversion.EXTRACT_TARGET;
                default -> conversion;
            }, toProxy, fromProxy, arrayLevel, metadata);
        }
        String getBridgeDescriptor() {
            if (conversion == TypeConversion.IDENTITY) return Type.getDescriptor(from);
            else if (arrayLevel > 0) return "[".repeat(arrayLevel) + "Ljava/lang/Object;";
            else return "Ljava/lang/Object;";
        }
        @Override
        public String toString() {
            if (from == to) return from.getName();
            return from.getName() + " -> " + to.getName();
        }
    }

    private static class TypeMappingMetadata {
        private String extractTargetHandle, proxyConstructorHandle, extractableClassField;
        private String[] createArrayHandle; // One handle per array level, 0 is the outermost array type.
    }

    private enum TypeConversion {
        /**
         * No conversion.
         */
        IDENTITY,
        /**
         * Take a proxy object and extract its target implementation object.
         */
        EXTRACT_TARGET,
        /**
         * Create new proxy targeting given implementation object.
         */
        WRAP_INTO_PROXY,
        /**
         * Decide between {@link #EXTRACT_TARGET} and {@link #WRAP_INTO_PROXY} at runtime, depending on actual object.
         */
        DYNAMIC_2_WAY
    }
}
