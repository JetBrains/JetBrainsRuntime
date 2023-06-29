/*
 * Copyright 2023 JetBrains s.r.o.
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

import jdk.internal.org.objectweb.asm.Label;
import jdk.internal.org.objectweb.asm.MethodVisitor;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Stream;

import static jdk.internal.org.objectweb.asm.Opcodes.*;

abstract class Mapping {

    final Class<?> from, to;

    private Mapping(Class<?> from, Class<?> to) {
        this.from = from;
        this.to = to;
    }

    void convert(ProxyGenerator.Context context) {
        MethodVisitor m = context.methodWriter();
        Label skipConvert = new Label();
        m.visitInsn(DUP);
        m.visitJumpInsn(IFNULL, skipConvert);
        convertNonNull(context);
        m.visitLabel(skipConvert);
    }

    abstract void convertNonNull(ProxyGenerator.Context context);

    abstract Mapping inverse();

    @Override
    public abstract int hashCode();

    @Override
    public abstract boolean equals(Object obj);

    @Override
    public abstract String toString();

    static class Identity extends Mapping {
        private Identity(Class<?> c) {
            super(c, c);
        }
        @Override
        void convert(ProxyGenerator.Context context) {}
        @Override
        void convertNonNull(ProxyGenerator.Context context) {}
        @Override
        Mapping inverse() { return this; }
        @Override
        public int hashCode() { return from.hashCode(); }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Identity i = (Identity) o;
            return from.equals(i.from);
        }
        @Override
        public String toString() { return from.getName(); }
    }

    static class Array extends Mapping {
        final Mapping component;
        private Array(Mapping component) {
            super(component.from.arrayType(), component.to.arrayType());
            this.component = component;
        }
        static Mapping wrap(Mapping m) {
            if (m instanceof Identity) return new Identity(m.from.arrayType());
            else return new Array(m);
        }
        @Override
        void convertNonNull(ProxyGenerator.Context context) {
            record ConstructorKey(Class<?> type) implements ProxyGenerator.Cached<String> {
                @Override
                public String create(ProxyGenerator.Context context) {
                    return context.addField(() -> MethodHandles.arrayConstructor(type));
                }
            }
            String constructorHandle = context.cached(new ConstructorKey(to));
            MethodVisitor m = context.methodWriter();
            Label loopStart = new Label(), loopEnd = new Label();
            // Stack: fromArray -> toArray, fromArray, i=length
            m.visitInsn(DUP);
            m.visitInsn(ARRAYLENGTH);
            context.loadField(constructorHandle);
            m.visitInsn(SWAP);
            context.invokeMethodHandle("(I)Ljava/lang/Object;");
            m.visitInsn(SWAP);
            m.visitInsn(DUP);
            m.visitInsn(ARRAYLENGTH);
            // Check loop conditions
            m.visitLabel(loopStart);
            m.visitInsn(DUP);
            m.visitJumpInsn(IFLE, loopEnd);
            // Stack: toArray, fromArray, i -> toArray, fromArray, i, toArray, i, from
            m.visitVarInsn(ISTORE, 0);
            m.visitInsn(DUP2);
            m.visitIincInsn(0, -1);
            m.visitVarInsn(ILOAD, 0);
            m.visitInsn(DUP_X2);
            m.visitInsn(DUP_X1);
            m.visitInsn(AALOAD);
            // Stack from -> to
            component.convert(context);
            // Stack: toArray, fromArray, i, toArray, i, to -> toArray, fromArray, i
            m.visitInsn(AASTORE);
            m.visitJumpInsn(GOTO, loopStart);
            m.visitLabel(loopEnd);
            // Stack: toArray, fromArray, i -> toArray
            m.visitInsn(POP2);
        }
        @Override
        Mapping inverse() { return new Array(component.inverse()); }
        @Override
        public int hashCode() {
            return 31 * component.hashCode();
        }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Array i = (Array) o;
            return component.equals(i.component);
        }
        @Override
        public String toString() { return "[" + component + "]"; }
    }

    private static abstract class ProxyConversion extends Mapping {
        final Proxy fromProxy, toProxy;
        private ProxyConversion(Class<?> from, Class<?> to, Proxy fromProxy, Proxy toProxy) {
            super(from, to);
            this.fromProxy = fromProxy;
            this.toProxy = toProxy;
        }
        @Override
        void convertNonNull(ProxyGenerator.Context context) {
            context.methodWriter().visitInsn(SWAP);
            context.invokeMethodHandle("(Ljava/lang/Object;)Ljava/lang/Object;");
        }
        @Override
        public int hashCode() {
            int result = from.hashCode();
            result = 31 * result + to.hashCode();
            result = 31 * result + Objects.hashCode(fromProxy);
            result = 31 * result + Objects.hashCode(toProxy);
            return result;
        }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ProxyConversion i = (ProxyConversion) o;
            return from.equals(i.from) && to.equals(i.to) &&
                    Objects.equals(fromProxy, i.fromProxy) && Objects.equals(toProxy, i.toProxy);
        }
        record HandleKey(Proxy proxy, boolean extract) implements ProxyGenerator.Cached<String> {
            @Override
            public String create(ProxyGenerator.Context context) {
                context.addDirectDependency(proxy);
                return context.addField(extract ? proxy::getTargetExtractor : proxy::getWrapperConstructor);
            }
        }
    }

    private static class Wrap extends ProxyConversion {
        private Wrap(Class<?> from, Class<?> to, Proxy proxy) {
            super(from, to, null, proxy);
        }
        @Override
        void convertNonNull(ProxyGenerator.Context context) {
            String wrapperConstructorHandle = context.cached(new HandleKey(toProxy, false));
            context.loadField(wrapperConstructorHandle);
            super.convertNonNull(context);
        }
        @Override
        Mapping inverse() { return new Extract(to, from, toProxy); }
        @Override
        public String toString() { return from.getName() + " --wrap-> " + to.getName(); }
    }

    private static class Extract extends ProxyConversion {
        private Extract(Class<?> from, Class<?> to, Proxy proxy) {
            super(from, to, proxy, null);
        }
        @Override
        void convertNonNull(ProxyGenerator.Context context) {
            String targetExtractorHandle = context.cached(new HandleKey(fromProxy, true));
            context.loadField(targetExtractorHandle);
            super.convertNonNull(context);
        }
        @Override
        Mapping inverse() { return new Wrap(to, from, fromProxy); }
        @Override
        public String toString() { return from.getName() + " --extract-> " + to.getName(); }
    }

    private static class Dynamic2Way extends ProxyConversion {
        private Dynamic2Way(Class<?> from, Class<?> to, Proxy fromProxy, Proxy toProxy) {
            super(from, to, fromProxy, toProxy);
        }
        @Override
        void convertNonNull(ProxyGenerator.Context context) {
            record ClassKey(Proxy proxy) implements ProxyGenerator.Cached<String> {
                @Override
                public String create(ProxyGenerator.Context context) {
                    return context.addField(proxy::getProxyClass);
                }
            }
            String wrapperConstructorHandle = context.cached(new HandleKey(toProxy, false));
            String targetExtractorHandle = context.cached(new HandleKey(fromProxy, true));
            String extractableClassField = context.cached(new ClassKey(fromProxy));

            MethodVisitor m = context.methodWriter();
            m.visitInsn(DUP);
            m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
            context.loadField(extractableClassField);
            Label elseBranch = new Label(), afterBranch = new Label();
            m.visitJumpInsn(IF_ACMPNE, elseBranch);
            context.loadField(targetExtractorHandle);
            m.visitJumpInsn(GOTO, afterBranch);
            m.visitLabel(elseBranch);
            context.loadField(wrapperConstructorHandle);
            m.visitLabel(afterBranch);
            super.convertNonNull(context);
        }
        @Override
        Mapping inverse() { return new Dynamic2Way(to, from, toProxy, fromProxy); }
        @Override
        public String toString() { return from.getName() + " --2way-> " + to.getName(); }
    }

    private static class CustomOptional extends Mapping {
        private final Mapping component;
        private CustomOptional(Mapping component) {
            super(Optional.class, Optional.class);
            this.component = component;
        }
        static Mapping wrap(Mapping m) {
            if (m instanceof Identity) return new Identity(Optional.class);
            else return new CustomOptional(m);
        }
        @Override
        void convertNonNull(ProxyGenerator.Context context) {
            MethodVisitor m = context.methodWriter();
            m.visitInsn(ACONST_NULL);
            m.visitMethodInsn(INVOKEVIRTUAL, "java/util/Optional", "orElse", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            component.convert(context);
            m.visitMethodInsn(INVOKESTATIC, "java/util/Optional", "ofNullable", "(Ljava/lang/Object;)Ljava/util/Optional;", false);
        }
        @Override
        Mapping inverse() { return new CustomOptional(component.inverse()); }
        @Override
        public int hashCode() {
            return 31 * component.hashCode() + Optional.class.hashCode();
        }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CustomOptional i = (CustomOptional) o;
            return component.equals(i.component);
        }
        @Override
        public String toString() { return "Optional<" + component + ">"; }
    }

    record Method(MethodType type, Mapping returnMapping, Mapping[] parameterMapping) {
        boolean convertible() {
            if (!(returnMapping instanceof Identity)) return true;
            for (Mapping t : parameterMapping) {
                if (!(t instanceof Identity)) return true;
            }
            return false;
        }
    }

    static class Context {

        private final ProxyRepository proxyRepository;
        private final Map<TypeVariable<?>, Mapping> tvMappings = new HashMap<>();

        Context(ProxyRepository proxyRepository) {
            this.proxyRepository = proxyRepository;
        }

        void initTypeParameters(Class<?> type, Stream<Mapping> typeParameters) {
            if (type == null) return;
            if (typeParameters != null) {
                TypeVariable<?>[] tvs = type.getTypeParameters();
                Iterator<Mapping> tpIterator = typeParameters.iterator();
                for (int i = 0;; i++) {
                    if ((i < tvs.length) ^ tpIterator.hasNext()) throw new RuntimeException("Number of type parameters doesn't match");
                    if (i >= tvs.length) break;
                    tvMappings.put(tvs[i], tpIterator.next());
                }
            }
            initTypeParameters(type.getGenericSuperclass());
            for (Type t : type.getGenericInterfaces()) initTypeParameters(t);
        }

        void initTypeParameters(Type supertype) {
            if (supertype instanceof ParameterizedType type) {
                initTypeParameters((Class<?>) type.getRawType(),
                        Stream.of(type.getActualTypeArguments()).map(this::getMapping));
            } else if (supertype instanceof Class<?> c) {
                initTypeParameters(c, null);
            } else if (supertype != null) {
                throw new RuntimeException("Unknown supertype kind: " + supertype.getClass());
            }
        }

        Method getMapping(java.lang.reflect.Method interfaceMethod) {
            Type[] params = interfaceMethod.getGenericParameterTypes();
            List<Class<?>> ptypes = new ArrayList<>(params.length);
            Mapping[] paramMappings = new Mapping[params.length];
            for (int i = 0; i < params.length; i++) {
                paramMappings[i] = getMapping(params[i]);
                ptypes.add(paramMappings[i].to);
            }
            Mapping returnMapping = getMapping(interfaceMethod.getGenericReturnType()).inverse();
            return new Method(MethodType.methodType(returnMapping.from, ptypes), returnMapping, paramMappings);
        }

        private Mapping getMapping(Type userType) {
            if (userType instanceof Class<?> t) {
                return getMapping(t, null);
            } else if (userType instanceof GenericArrayType t) {
                return Array.wrap(getMapping(t.getGenericComponentType()));
            } else if (userType instanceof ParameterizedType t) {
                return getMapping(t);
            } else if (userType instanceof TypeVariable<?> t) {
                Mapping tvMapping = tvMappings.get(t);
                if (tvMapping != null) return tvMapping;
                Type[] bounds = t.getBounds();
                return getMapping(bounds.length > 0 ? bounds[0] : Object.class);
            } else if (userType instanceof WildcardType t) {
                Type[] bounds = t.getUpperBounds();
                return getMapping(bounds.length > 0 ? bounds[0] : Object.class);
            } else {
                throw new RuntimeException("Unknown type kind: " + userType.getClass());
            }
        }

        private Mapping getMapping(ParameterizedType userType) {
            Type[] actual = userType.getActualTypeArguments();
            Mapping[] specialization = null;
            for (int i = 0; i < actual.length; i++) {
                Mapping m = getMapping(actual[i]);
                if (m instanceof Identity) continue;
                if (specialization == null) specialization = new Mapping[actual.length];
                specialization[i] = m;
            }
            return getMapping((Class<?>) userType.getRawType(), specialization);
        }

        private Mapping getMapping(Class<?> userType, Mapping[] specialization) {
            if (userType.isArray()) {
                return Array.wrap(getMapping(userType.componentType()));
            }
            if (specialization != null && specialization.length == 1 && specialization[0] != null &&
                    userType.equals(Optional.class)) {
                return CustomOptional.wrap(specialization[0]);
            }
            ProxyRepository.Pair p = proxyRepository.getProxies(userType, specialization);
            if (p.byInterface() != null && p.byTarget() != null) {
                return new Dynamic2Way(userType, p.byTarget().getInterface(), p.byInterface(), p.byTarget());
            } else if (p.byInterface() != null && p.byInterface().getTarget() != null) {
                return new Extract(userType, p.byInterface().getTarget(), p.byInterface());
            } else if (p.byTarget() != null) {
                return new Wrap(userType, p.byTarget().getInterface(), p.byTarget());
            } else {
                return new Identity(userType);
            }
        }
    }
}
