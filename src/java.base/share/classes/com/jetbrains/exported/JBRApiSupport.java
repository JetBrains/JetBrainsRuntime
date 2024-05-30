package com.jetbrains.exported;

import java.lang.annotation.Annotation;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Function;

/**
 * Supporting exported API for JBR API backend. Not intended to be used by JBR or client code directly.
 */
public class JBRApiSupport {
    private JBRApiSupport() {}

    /**
     * Initializes JBR API.
     * @param apiInterface internal {@code JBR.ServiceApi} interface
     * @param serviceAnnotation {@code @Service} annotation class
     * @param providedAnnotation {@code @Provided} annotation class
     * @param providesAnnotation {@code @Provides} annotation class
     * @param knownExtensions map of known extension enums and classes defining them
     * @param extensionExtractor receives method, returns its extension enum, or null
     * @return implementation for {@code JBR.ServiceApi} interface
     */
    @SuppressWarnings("rawtypes")
    public static synchronized Object bootstrap(Class<?> apiInterface,
                                                Class<? extends Annotation> serviceAnnotation,
                                                Class<? extends Annotation> providedAnnotation,
                                                Class<? extends Annotation> providesAnnotation,
                                                Map<Enum<?>, Class[]> knownExtensions,
                                                Function<Method, Enum<?>> extensionExtractor) {
        if (!com.jetbrains.internal.JBRApi.ENABLED) return null;
        com.jetbrains.internal.JBRApi.init(
                null,
                serviceAnnotation,
                providedAnnotation,
                providesAnnotation,
                knownExtensions,
                extensionExtractor);
        return com.jetbrains.internal.JBRApi.getService(apiInterface);
    }

    /**
     * Bootstrap method for JBR API dynamic invocations.
     */
    public static CallSite bootstrapDynamic(MethodHandles.Lookup caller, String name, MethodType type) {
        return new ConstantCallSite(com.jetbrains.internal.JBRApi.bindDynamic(caller, name, type));
    }

    /**
     * JBR API proxy class. Every generated proxy class implements this interface.
     */
    public interface Proxy {
        /**
         * We really don't want to clash with other possible method names.
         * @return target object
         */
        Object $getProxyTarget();
    }
}
