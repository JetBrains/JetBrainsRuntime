#include <dlfcn.h>
#include <Trace.h>
#include <stdint.h>
#include "jni.h"

//#include "vulkan/vulkan.h"
//#include "vulkan/vulkan_wayland.h"
//TODO: Replace the stubs below with headers above and add appropriate detection in configure
typedef struct VkApplicationInfo {
    uint32_t           sType;
    const void*        pNext;
    const char*        pApplicationName;
    uint32_t           applicationVersion;
    const char*        pEngineName;
    uint32_t           engineVersion;
    uint32_t           apiVersion;
} VkApplicationInfo;


typedef struct VkInstanceCreateInfo {
    uint32_t                    sType;
    const void*                 pNext;
    uint32_t                    flags;
    const VkApplicationInfo*    pApplicationInfo;
    uint32_t                    enabledLayerCount;
    const char* const*          ppEnabledLayerNames;
    uint32_t                    enabledExtensionCount;
    const char* const*          ppEnabledExtensionNames;
} VkInstanceCreateInfo;

#define VK_KHR_SURFACE_EXTENSION_NAME     "VK_KHR_surface"
#define VK_KHR_WAYLAND_SURFACE_EXTENSION_NAME "VK_KHR_wayland_surface"
#define VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO  1


//#ifndef HEADLESS
/**
 * Returns JNI_TRUE if vulkan is available for the current wayland display
 */
jboolean VKWLGC_IsVKWLAvailable()
{
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
//#endif