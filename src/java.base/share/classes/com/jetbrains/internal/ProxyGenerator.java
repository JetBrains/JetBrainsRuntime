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
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.jetbrains.internal.ASMUtils.*;
import static java.lang.invoke.MethodHandles.Lookup;
import static jdk.internal.org.objectweb.asm.Opcodes.*;

/**
 * This class generates {@linkplain Proxy proxy} classes.
 * Each proxy is just a generated class implementing some interface and
 * delegating method calls to method handles.
 * <p>
 * There are 2 proxy dispatch modes:
 * <ul>
 *     <li>interface -> proxy -> {@linkplain #generateBridge bridge} -> method handle -> implementation code</li>
 *     <li>interface -> proxy -> method handle -> implementation code</li>
 * </ul>
 * Generated proxy is always located in the same package with its interface and optional bridge is located in the
 * same module with target implementation code. Bridge allows proxy to safely call hidden non-static implementation
 * methods and is only needed for {@code jetbrains.api} -> JBR calls. For JBR -> {@code jetbrains.api} calls, proxy can
 * invoke method handle directly.
 */
class ProxyGenerator {
    private static final String OBJECT_DESCRIPTOR = "Ljava/lang/Object;";
    private static final String MH_NAME = "java/lang/invoke/MethodHandle";
    private static final String MH_DESCRIPTOR = "Ljava/lang/invoke/MethodHandle;";
    private static final String CONVERSION_DESCRIPTOR = "(Ljava/lang/Object;)Ljava/lang/Object;";
    /**
     * Print warnings about usage of deprecated interfaces and methods to {@link System#err}.
     */
    private static final boolean LOG_DEPRECATED = System.getProperty("jetbrains.api.logDeprecated", String.valueOf(JBRApi.VERBOSE)).equalsIgnoreCase("true");
    private static final boolean VERIFY_BYTECODE = Boolean.getBoolean("jetbrains.api.verifyBytecode");

    private static final AtomicInteger nameCounter = new AtomicInteger();

    private final ProxyInfo info;
    private final boolean generateBridge;
    private final String proxyName, bridgeName;
    private final ClassWriter originalProxyWriter, originalBridgeWriter;
    private final ClassVisitor proxyWriter, bridgeWriter;
    private final List<Supplier<MethodHandle>> handles = new ArrayList<>();
    private final List<Supplier<Class<?>>> classReferences = new ArrayList<>();
    private final Set<Proxy<?>> directProxyDependencies = new HashSet<>();
    private final List<Exception> exceptions = new ArrayList<>();
    private int bridgeMethodCounter;
    private boolean allMethodsImplemented = true;
    private Lookup generatedHandlesHolder, generatedProxy;

    /**
     * Creates new proxy generator from given {@link ProxyInfo},
     * looks for abstract interface methods, corresponding implementation methods
     * and generates proxy bytecode. However, it doesn't actually load generated
     * classes until {@link #defineClasses()} is called.
     */
    ProxyGenerator(ProxyInfo info) {
        if (JBRApi.VERBOSE) {
            System.out.println("Generating proxy " + info.interFace.getName());
        }
        this.info = info;
        generateBridge = info.type.isPublicApi();
        int nameId = nameCounter.getAndIncrement();
        proxyName = Type.getInternalName(info.interFace) + "$$JBRApiProxy$" + nameId;
        bridgeName = generateBridge ? info.apiModule.lookupClass().getPackageName().replace('.', '/') + "/" +
                info.interFace.getSimpleName() + "$$JBRApiBridge$" + nameId : null;

        originalProxyWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        proxyWriter = VERIFY_BYTECODE ? new CheckClassAdapter(originalProxyWriter, true) : originalProxyWriter;
        originalBridgeWriter = generateBridge ? new ClassWriter(ClassWriter.COMPUTE_FRAMES) : null;
        if (generateBridge) {
            bridgeWriter = VERIFY_BYTECODE ? new CheckClassAdapter(originalBridgeWriter, true) : originalBridgeWriter;
        } else bridgeWriter = new ClassVisitor(Opcodes.ASM9) { // Empty visitor
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(api) {};
            }
        };
        proxyWriter.visit(CLASSFILE_VERSION, ACC_SUPER | ACC_FINAL | ACC_SYNTHETIC, proxyName, null,
                "java/lang/Object", new String[] {Type.getInternalName(info.interFace)});
        bridgeWriter.visit(CLASSFILE_VERSION, ACC_SUPER | ACC_FINAL | ACC_SYNTHETIC | ACC_PUBLIC, bridgeName, null,
                "java/lang/Object", null);
        generateConstructor();
        generateMethods();
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
     * @return method handle to constructor of generated proxy class.
     * <ul>
     *     <li>For {@linkplain ProxyInfo.Type#SERVICE services}, constructor is no-arg.</li>
     *     <li>For non-{@linkplain ProxyInfo.Type#SERVICE services}, constructor is single-arg,
     *     expecting target object to which it would delegate method calls.</li>
     * </ul>
     */
    MethodHandle findConstructor() {
        try {
            if (info.target == null) {
                return generatedProxy.findConstructor(generatedProxy.lookupClass(), MethodType.methodType(void.class));
            } else {
                MethodHandle c = generatedProxy.findConstructor(generatedProxy.lookupClass(),
                        MethodType.methodType(void.class, Object.class));
                if (info.type.isService()) {
                    try {
                        return MethodHandles.foldArguments(c, info.target.findConstructor(info.target.lookupClass(),
                                MethodType.methodType(void.class)).asType(MethodType.methodType(Object.class)));
                    } catch (NoSuchMethodException | IllegalAccessException e) {
                        throw new RuntimeException("Service implementation must have no-args constructor: " +
                                info.target.lookupClass(), e);
                    }
                }
                return c;
            }
        } catch (IllegalAccessException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @return method handle that receives proxy and returns its target, or null
     */
    MethodHandle findTargetExtractor() {
        if (info.target == null) return null;
        try {
            return generatedProxy.findGetter(generatedProxy.lookupClass(), "target", Object.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
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
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
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
        p.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        p.visitInsn(RETURN);
        p.visitMaxs(0, 0);
        p.visitEnd();
    }

    private void generateMethods() {
        for (Method method : info.interFace.getMethods()) {
            int mod = method.getModifiers();
            if (!Modifier.isAbstract(mod)) continue;
            MethodMapping methodMapping = getTargetMethodMapping(method);

            Exception e1 = null;
            if (info.target != null) {
                try {
                    MethodHandle handle = info.target.findVirtual(
                            info.target.lookupClass(), method.getName(), methodMapping.type());
                    generateMethod(method, handle, methodMapping, true);
                    continue;
                } catch (NoSuchMethodException | IllegalAccessException e) {
                    e1 = e;
                }
            }

            Exception e2 = null;
            ProxyInfo.StaticMethodMapping mapping = info.staticMethods.get(method.getName());
            if (mapping != null) {
                try {
                    MethodHandle staticHandle =
                            mapping.lookup().findStatic(mapping.lookup().lookupClass(), mapping.methodName(), methodMapping.type());
                    generateMethod(method, staticHandle, methodMapping, false);
                    continue;
                } catch (NoSuchMethodException | IllegalAccessException e) {
                    e2 = e;
                }
            }

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
        String handleName = addHandle(handleWriter, () -> handle);
        for (TypeMapping m : mapping) {
            if (m.conversion() == TypeConversion.EXTRACT_TARGET ||
                    m.conversion() == TypeConversion.DYNAMIC_2_WAY) {
                Proxy<?> from = m.fromProxy();
                m.metadata.extractTargetHandle = addHandle(handleWriter, from::getTargetExtractor);
                directProxyDependencies.add(from);
            }
            if (m.conversion() == TypeConversion.WRAP_INTO_PROXY ||
                    m.conversion() == TypeConversion.DYNAMIC_2_WAY) {
                Proxy<?> to = m.toProxy();
                m.metadata.proxyConstructorHandle = addHandle(handleWriter, to::getConstructor);
                directProxyDependencies.add(to);
            }
            if (m.conversion() == TypeConversion.DYNAMIC_2_WAY) {
                String classField = "c" + classReferences.size();
                m.metadata.extractableClassField = classField;
                classReferences.add(m.fromProxy()::getProxyClass);
                handleWriter.visitField(ACC_PRIVATE | ACC_STATIC, classField, "Ljava/lang/Class;", null, null);
            }
        }
        String bridgeMethodName = methodInfo.name() + "$bridge$" + bridgeMethodCounter;
        bridgeMethodCounter++;

        MethodVisitor p = proxyWriter.visitMethod(ACC_PUBLIC | ACC_FINAL, methodInfo.name(),
                methodInfo.descriptor(), methodInfo.genericSignature(), methodInfo.exceptionNames());
        MethodVisitor b = bridgeWriter.visitMethod(ACC_PUBLIC | ACC_STATIC, bridgeMethodName,
                bridgeMethodDescriptor, null, null);
        if (LOG_DEPRECATED && interfaceMethod.isAnnotationPresent(Deprecated.class)) {
            logDeprecated(p, "Warning: using deprecated JBR API method " +
                    interfaceMethod.getDeclaringClass().getName() + "#" + interfaceMethod.getName());
        }
        p.visitCode();
        b.visitCode();
        MethodVisitor bp = generateBridge ? b : p;
        bp.visitFieldInsn(GETSTATIC, bridgeOrProxyName, handleName, MH_DESCRIPTOR);
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
        if (generateBridge) {
            p.visitMethodInsn(INVOKESTATIC, bridgeName, bridgeMethodName, bridgeMethodDescriptor, false);
        }
        bp.visitMethodInsn(INVOKEVIRTUAL, MH_NAME, "invoke", bridgeMethodDescriptor, false);
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

    private static void convertValue(MethodVisitor m, String handlesHolderName, TypeMapping mapping) {
        if (mapping.conversion() == TypeConversion.IDENTITY) return;
        Label skipConvert = new Label();
        m.visitInsn(DUP);
        m.visitJumpInsn(IFNULL, skipConvert);
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
        TypeMappingMetadata m = new TypeMappingMetadata();
        Proxy<T> p = JBRApi.getProxy(userType);
        if (p != null && p.getInfo().target != null) {
            Proxy<?> r = JBRApi.getProxy(p.getInfo().target.lookupClass());
            if (r != null && r.getInfo().target != null) {
                return new TypeMapping(userType, p.getInfo().target.lookupClass(), TypeConversion.DYNAMIC_2_WAY, p, r, m);
            }
            return new TypeMapping(userType, p.getInfo().target.lookupClass(), TypeConversion.EXTRACT_TARGET, p, null, m);
        }
        Class<?> interFace = JBRApi.getProxyInterfaceByTargetName(userType.getName());
        if (interFace != null) {
            Proxy<?> r = JBRApi.getProxy(interFace);
            if (r != null) {
                return new TypeMapping(userType, interFace, TypeConversion.WRAP_INTO_PROXY, null, r, m);
            }
        }
        return new TypeMapping(userType, userType, TypeConversion.IDENTITY, null, null, m);
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
                               Proxy<?> fromProxy, Proxy<?> toProxy, TypeMappingMetadata metadata) {
        TypeMapping inverse() {
            return new TypeMapping(to, from, switch (conversion) {
                case EXTRACT_TARGET -> TypeConversion.WRAP_INTO_PROXY;
                case WRAP_INTO_PROXY -> TypeConversion.EXTRACT_TARGET;
                default -> conversion;
            }, toProxy, fromProxy, metadata);
        }
        String getBridgeDescriptor() {
            if (conversion == TypeConversion.IDENTITY) return Type.getDescriptor(from);
            else return "Ljava/lang/Object;";
        }
    }

    private static class TypeMappingMetadata {
        private String extractTargetHandle, proxyConstructorHandle, extractableClassField;
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
