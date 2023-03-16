#include "VKWLGraphicsConfig.h"
#include <dlfcn.h>
#include <Trace.h>
#include <stdint.h>
#include <jvm_md.h>
#include "jni.h"

#ifndef HEADLESS
#ifdef VKWL_GRAPHICS
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_wayland.h>
#endif
/**
 * Returns JNI_TRUE if vulkan is available for the current wayland display
 */
jboolean VKWLGC_IsVKWLAvailable()
{
#ifdef VKWL_GRAPHICS
    void *lib = dlopen(JNI_LIB_NAME("vulkan"), RTLD_LOCAL|RTLD_LAZY);

    if (lib == NULL)  {
        lib = dlopen(VERSIONED_JNI_LIB_NAME("vulkan", "1"), RTLD_LOCAL|RTLD_LAZY);
    }

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
#else
    return JNI_FALSE;
#endif
}
#endif