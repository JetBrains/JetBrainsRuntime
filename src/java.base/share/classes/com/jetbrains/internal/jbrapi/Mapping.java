/*
 * Copyright 2023-2025 JetBrains s.r.o.
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

import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Stream;

import static com.jetbrains.internal.jbrapi.BytecodeUtils.*;

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
        CodeBuilder m = context.writer;
        Label skipConvert = m.newLabel();
        m.dup()
         .ifnull(skipConvert);
        convertNonNull(context);
        m.labelBinding(skipConvert);
    }

    abstract void convertNonNull(AccessContext.Method context);

    void cast(AccessContext.Method context) {
        if (context.access().canAccess(to)) context.writer.checkcast(desc(to));
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

    static class Cast extends Nesting {
        private Cast(Mapping component) {
            super(component.from, component.to, component);
        }
        static Mapping wrap(Mapping m) {
            return m == null || m instanceof Cast ? m : new Cast(m);
        }
        @Override
        void convert(AccessContext.Method context) {
            if (context.access().canAccess(from)) context.writer.checkcast(desc(from));
            component.convert(context);
        }
        @Override
        void convertNonNull(AccessContext.Method context) { convert(context); }
        @Override
        Mapping inverse() { return wrap(component.inverse()); }
        @Override
        public String toString() { return component.toString(); }
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
            CodeBuilder m = context.writer;
            // Stack: fromArray -> toArray, fromArray, i=length
            if (!context.access().canAccess(from)) m.checkcast(OBJECT_ARRAY_DESC);
            m.dup()
             .arraylength();
            if (context.access().canAccess(to)) {
                m.anewarray(desc(to.componentType()));
            } else context.invokeDynamic(MethodHandles.arrayConstructor(to));
            m.swap()
             .dup()
             .arraylength();
            // Check loop conditions
            Label loopStart = m.newBoundLabel(), loopEnd = m.newLabel();
            m.dup()
             .ifle(loopEnd);
            // Stack: toArray, fromArray, i -> toArray, fromArray, i, toArray, i, from
            m.istore(TEMP_COUNTER_SLOT)
             .dup2()
             .iinc(TEMP_COUNTER_SLOT, -1)
             .iload(TEMP_COUNTER_SLOT)
             .dup_x2()
             .dup_x1()
             .aaload();
            // Stack from -> to
            component.convert(context);
            // Stack: toArray, fromArray, i, toArray, i, to -> toArray, fromArray, i
            m.aastore()
             .goto_(loopStart)
             .labelBinding(loopEnd);
            // Stack: toArray, fromArray, i -> toArray
            m.pop2();
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
                context.writer.aload(0);
                mt = MethodType.methodType(to, from, long[].class);
            } else {
                mt = MethodType.methodType(to, from);
            }
            context.invokeDynamic(mt, toProxy::getConstructor);
        }
        void extractNonNull(AccessContext.Method context) {
            context.addDependency(fromProxy);
            context.writer.invokeinterface(PROXY_INTERFACE_DESC, "$getProxyTarget", GET_PROXY_TARGET_DESC);
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
            CodeBuilder m = context.writer;
            Label elseBranch = m.newLabel(), afterBranch = m.newLabel();
            m.dup()
             .ifnull(afterBranch)
             .dup()
             .instanceOf(PROXY_INTERFACE_DESC)
             .ifeq(elseBranch);
            extractNonNull(context);
            m.goto_(afterBranch)
             .labelBinding(elseBranch);
            wrapNonNull(context);
            m.labelBinding(afterBranch);
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
        private static final ClassDesc OPTIONAL_DESC = desc(Optional.class);
        private static final MethodTypeDesc OR_ELSE_DESC = desc(Object.class, Object.class);
        private static final MethodTypeDesc OF_NULLABLE_DESC = desc(Optional.class, Object.class);
        private CustomOptional(Mapping component) {
            super(Optional.class, Optional.class, component);
        }
        static Mapping wrap(Mapping m) {
            if (m instanceof Identity) return new Identity(Optional.class);
            else return new CustomOptional(m);
        }
        @Override
        void convertNonNull(AccessContext.Method context) {
            CodeBuilder m = context.writer;
            m.aconst_null()
             .invokevirtual(OPTIONAL_DESC, "orElse", OR_ELSE_DESC);
            component.convert(context);
            m.invokestatic(OPTIONAL_DESC, "ofNullable", OF_NULLABLE_DESC);
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
                    tvMappings.put(tvs[i], Cast.wrap(tpIterator.next()));
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
            return switch (userType) {
                case Class<?> t -> getMapping(t, null);
                case GenericArrayType t -> Array.wrap(getMapping(t.getGenericComponentType()));
                case ParameterizedType t -> getMapping(t);
                case TypeVariable<?> t -> {
                    Mapping tvMapping = tvMappings.get(t);
                    if (tvMapping != null) yield tvMapping;
                    Type[] bounds = t.getBounds();
                    yield getMapping(bounds.length > 0 ? bounds[0] : Object.class);
                }
                case WildcardType t -> {
                    Type[] bounds = t.getUpperBounds();
                    yield getMapping(bounds.length > 0 ? bounds[0] : Object.class);
                }
                case null, default -> throw new RuntimeException("Unknown type kind: " +
                        (userType == null ? null : userType.getClass()));
            };
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
