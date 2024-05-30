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
import static jdk.internal.org.objectweb.asm.Type.getInternalName;

/**
 * Mapping defines conversion of parameters and return types between source and destination method.
 */
abstract class Mapping {

    static class Query {
        boolean valid = true;
        boolean needsExtensions = false;
    }

    final Class<?> from, to;

    private Mapping(Class<?> from, Class<?> to) {
        this.from = from;
        this.to = to;
    }

    void convert(AccessContext.Method context) {
        MethodVisitor m = context.writer;
        Label skipConvert = new Label();
        m.visitInsn(DUP);
        m.visitJumpInsn(IFNULL, skipConvert);
        convertNonNull(context);
        m.visitLabel(skipConvert);
    }

    abstract void convertNonNull(AccessContext.Method context);

    void cast(AccessContext.Method context) {
        if (context.access().canAccess(to)) context.writer.visitTypeInsn(CHECKCAST, getInternalName(to));
    }

    abstract Mapping inverse();

    void query(Query q) {}

    @Override
    public abstract boolean equals(Object obj);

    @Override
    public abstract int hashCode();

    @Override
    public abstract String toString();

    static class Identity extends Mapping {
        private Identity(Class<?> c) {
            super(c, c);
        }
        @Override
        void convert(AccessContext.Method context) {}
        @Override
        void convertNonNull(AccessContext.Method context) {}
        @Override
        Mapping inverse() { return this; }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Identity i = (Identity) o;
            return from.equals(i.from);
        }
        @Override
        public int hashCode() { return from.hashCode(); }
        @Override
        public String toString() { return from.getName(); }
    }

    static class Invalid extends Identity {
        private Invalid(Class<?> c) {
            super(c);
        }
        @Override
        void query(Query q) { q.valid = false; }
        @Override
        public String toString() { return "INVALID(" + from.getName() + ")"; }
    }

    static abstract class Nesting extends Mapping {
        final Mapping component;
        Nesting(Class<?> from, Class<?> to, Mapping component) {
            super(from, to);
            this.component = component;
        }
        @Override
        void query(Query q) { component.query(q); }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Nesting nesting = (Nesting) o;
            if (!Objects.equals(from, nesting.from)) return false;
            if (!Objects.equals(to, nesting.to)) return false;
            return component.equals(nesting.component);
        }
        @Override
        public int hashCode() {
            int result = from != null ? from.hashCode() : 0;
            result = 31 * result + (to != null ? to.hashCode() : 0);
            result = 31 * result + component.hashCode();
            return result;
        }
    }

    static class Array extends Nesting {
        private Array(Mapping component) {
            super(component.from.arrayType(), component.to.arrayType(), component);
        }
        static Mapping wrap(Mapping m) {
            if (m instanceof Identity) return new Identity(m.from.arrayType());
            else return new Array(m);
        }
        @Override
        void convert(AccessContext.Method context) {
            super.convert(context);
            cast(context); // Explicitly cast to result type after non-null branch
        }
        @Override
        void convertNonNull(AccessContext.Method context) {
            final int TEMP_COUNTER_SLOT = 1; // Warning! We overwrite 1st local slot.
            MethodVisitor m = context.writer;
            Label loopStart = new Label(), loopEnd = new Label();
            // Stack: fromArray -> toArray, fromArray, i=length
            if (!context.access().canAccess(from)) m.visitTypeInsn(CHECKCAST, "[Ljava/lang/Object;");
            m.visitInsn(DUP);
            m.visitInsn(ARRAYLENGTH);
            if (context.access().canAccess(to)) {
                m.visitTypeInsn(ANEWARRAY, getInternalName(Objects.requireNonNull(to.componentType())));
            } else context.invokeDynamic(MethodHandles.arrayConstructor(to));
            m.visitInsn(SWAP);
            m.visitInsn(DUP);
            m.visitInsn(ARRAYLENGTH);
            // Check loop conditions
            m.visitLabel(loopStart);
            m.visitInsn(DUP);
            m.visitJumpInsn(IFLE, loopEnd);
            // Stack: toArray, fromArray, i -> toArray, fromArray, i, toArray, i, from
            m.visitVarInsn(ISTORE, TEMP_COUNTER_SLOT);
            m.visitInsn(DUP2);
            m.visitIincInsn(TEMP_COUNTER_SLOT, -1);
            m.visitVarInsn(ILOAD, TEMP_COUNTER_SLOT);
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
        public String toString() { return "[" + component + "]"; }
    }

    private static abstract class ProxyConversion extends Mapping {
        final Proxy fromProxy, toProxy;
        private ProxyConversion(Class<?> from, Class<?> to, Proxy fromProxy, Proxy toProxy) {
            super(from, to);
            this.fromProxy = fromProxy;
            this.toProxy = toProxy;
        }
        void wrapNonNull(AccessContext.Method context) {
            context.addDependency(toProxy);
            MethodType mt;
            if (JBRApi.EXTENSIONS_ENABLED) {
                context.writer.visitVarInsn(ALOAD, 0);
                mt = MethodType.methodType(to, from, long[].class);
            } else {
                mt = MethodType.methodType(to, from);
            }
            context.invokeDynamic(mt, toProxy::getConstructor);
        }
        void extractNonNull(AccessContext.Method context) {
            context.addDependency(fromProxy);
            context.writer.visitMethodInsn(INVOKEINTERFACE,
                    "com/jetbrains/exported/JBRApiSupport$Proxy", "$getProxyTarget", "()Ljava/lang/Object;", true);
        }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ProxyConversion i = (ProxyConversion) o;
            return from.equals(i.from) && to.equals(i.to) &&
                    Objects.equals(fromProxy, i.fromProxy) && Objects.equals(toProxy, i.toProxy);
        }
        @Override
        public int hashCode() {
            int result = from.hashCode();
            result = 31 * result + to.hashCode();
            result = 31 * result + Objects.hashCode(fromProxy);
            result = 31 * result + Objects.hashCode(toProxy);
            return result;
        }
    }

    private static class Wrap extends ProxyConversion {
        private Wrap(Class<?> from, Class<?> to, Proxy proxy) {
            super(from, to, null, proxy);
        }
        @Override
        void convertNonNull(AccessContext.Method context) { wrapNonNull(context); }
        @Override
        Mapping inverse() { return new Extract(to, from, toProxy); }
        @Override
        void query(Query q) { q.needsExtensions = JBRApi.EXTENSIONS_ENABLED; }
        @Override
        public String toString() { return from.getName() + " --wrap-> " + to.getName(); }
    }

    private static class Extract extends ProxyConversion {
        private Extract(Class<?> from, Class<?> to, Proxy proxy) {
            super(from, to, proxy, null);
        }
        @Override
        void convert(AccessContext.Method context) {
            super.convert(context);
            cast(context); // Explicitly cast to result type after non-null branch
        }
        @Override
        void convertNonNull(AccessContext.Method context) { extractNonNull(context); }
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
        void convert(AccessContext.Method context) {
            Label elseBranch = new Label(), afterBranch = new Label();
            MethodVisitor m = context.writer;
            m.visitInsn(DUP);
            m.visitJumpInsn(IFNULL, afterBranch);
            m.visitInsn(DUP);
            m.visitTypeInsn(INSTANCEOF, "com/jetbrains/exported/JBRApiSupport$Proxy");
            m.visitJumpInsn(IFEQ, elseBranch);
            extractNonNull(context);
            m.visitJumpInsn(GOTO, afterBranch);
            m.visitLabel(elseBranch);
            wrapNonNull(context);
            m.visitLabel(afterBranch);
            cast(context); // Explicitly cast to result type after non-null branch
        }
        @Override
        void convertNonNull(AccessContext.Method context) {}
        @Override
        Mapping inverse() { return new Dynamic2Way(to, from, toProxy, fromProxy); }
        @Override
        void query(Query q) { q.needsExtensions = JBRApi.EXTENSIONS_ENABLED; }
        @Override
        public String toString() { return from.getName() + " --2way-> " + to.getName(); }
    }

    private static class CustomOptional extends Nesting {
        private CustomOptional(Mapping component) {
            super(Optional.class, Optional.class, component);
        }
        static Mapping wrap(Mapping m) {
            if (m instanceof Identity) return new Identity(Optional.class);
            else return new CustomOptional(m);
        }
        @Override
        void convertNonNull(AccessContext.Method context) {
            MethodVisitor m = context.writer;
            m.visitInsn(ACONST_NULL);
            m.visitMethodInsn(INVOKEVIRTUAL, "java/util/Optional", "orElse", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            component.convert(context);
            m.visitMethodInsn(INVOKESTATIC, "java/util/Optional", "ofNullable", "(Ljava/lang/Object;)Ljava/util/Optional;", false);
        }
        @Override
        Mapping inverse() { return new CustomOptional(component.inverse()); }
        @Override
        public String toString() { return "Optional<" + component + ">"; }
    }

    record Method(MethodType type, Mapping returnMapping, Mapping[] parameterMapping, Query query) {
        @Override
        public String toString() {
            return returnMapping + "(" + Arrays.toString(parameterMapping) + ")";
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
            return new Method(MethodType.methodType(returnMapping.from, ptypes), returnMapping, paramMappings,
                    query(returnMapping, paramMappings));
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
            Proxy p = proxyRepository.getProxy(userType, specialization);
            if (p.supported() != Boolean.FALSE && p.inverse().supported() != Boolean.FALSE &&
                    (p.getFlags() & Proxy.SERVICE) == 0 && (p.inverse().getFlags() & Proxy.SERVICE) == 0) {
                if (p.getInterface() != null && p.inverse().getInterface() != null) {
                    return new Dynamic2Way(userType, p.inverse().getInterface(), p, p.inverse());
                } else if (p.getInterface() != null) {
                    if (p.getTarget() != null) return new Extract(userType, p.getTarget(), p);
                } else if (p.inverse().getInterface() != null) {
                    return new Wrap(userType, p.inverse().getInterface(), p.inverse());
                } else {
                    return new Identity(userType);
                }
            }
            return new Invalid(userType);
        }

        private static Query query(Mapping returnMapping, Mapping[] parameterMapping) {
            Query q = new Query();
            returnMapping.query(q);
            for (Mapping p : parameterMapping) p.query(q);
            return q;
        }
    }
}
