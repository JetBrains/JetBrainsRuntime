/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2022, JetBrains s.r.o.. All rights reserved.
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

#include <dlfcn.h>
#include <Trace.h>
#include "vulkan/vulkan.h"
#include "vulkan/vulkan_wayland.h"
#include "jni.h"

/*
 * Class:     sun_java2d_vulkan_VKGraphicsConfig
 * Method:    isVulkanAvailable
 * Signature: ()Z
 */
JNIEXPORT jboolean JNICALL Java_sun_java2d_vulkan_VKGraphicsConfig_isVulkanAvailable
        (JNIEnv *env, jclass vkgc) {
    void *lib = dlopen("libvulkan.so", RTLD_LOCAL|RTLD_LAZY);
    if (lib == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Could not open vulkan library");
        return JNI_FALSE;
    }

    J2dRlsTraceLn(J2D_TRACE_INFO, "Found vulkan library");

    void *(*vkGetInstanceProcAddr)(void *, const char *) = dlsym(lib, "vkGetInstanceProcAddr");

    if (!vkGetInstanceProcAddr) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Could not find vkGetInstanceProcAddr");
        return JNI_FALSE;
    }

    int (*vkCreateInstance)(VkInstanceCreateInfo*, void *, void **) = vkGetInstanceProcAddr(0, "vkCreateInstance");

    if (!vkCreateInstance) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Could not find vkCreateInstance");
        return JNI_FALSE;
    }

    const char* instanceExtensions[] = { VK_KHR_SURFACE_EXTENSION_NAME,  VK_KHR_WAYLAND_SURFACE_EXTENSION_NAME};

    VkInstanceCreateInfo instanceCreateInfo = {};
    instanceCreateInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instanceCreateInfo.pNext = NULL;
    instanceCreateInfo.pApplicationInfo = NULL;

    instanceCreateInfo.enabledExtensionCount = 2;
    instanceCreateInfo.ppEnabledExtensionNames = instanceExtensions;

    void *instance = 0;
    int result = vkCreateInstance(&instanceCreateInfo, NULL, &instance);

    if (!instance || result != 0) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Cannot create vulkan instance");
        return JNI_FALSE;
    }
    J2dRlsTraceLn(J2D_TRACE_INFO, "Vulkan is available");
    dlclose(lib);
    return JNI_TRUE;
}

/*
 * Class:     sun_java2d_vulkan_VKGraphicsConfig
 * Method:    getMTLConfigInfo
 * Signature: (I)J
 */
JNIEXPORT jlong JNICALL Java_sun_java2d_vulkan_VKGraphicsConfig_getMTLConfigInfo
        (JNIEnv *env, jclass vkgc, jint displayID) {
    fprintf(stderr, "Java_sun_java2d_vulkan_VKGraphicsConfig_getMTLConfigInfo\n");
    return 0;
}

/*
 * Class:     sun_java2d_vulkan_VKGraphicsConfig
 * Method:    nativeGetMaxTextureSize
 * Signature: ()I
 */
JNIEXPORT jint JNICALL Java_sun_java2d_vulkan_VKGraphicsConfig_nativeGetMaxTextureSize
        (JNIEnv *env, jclass vkgc) {
    fprintf(stderr, "Java_sun_java2d_vulkan_VKGraphicsConfig_nativeGetMaxTextureSize\n");
    return 0;
}

