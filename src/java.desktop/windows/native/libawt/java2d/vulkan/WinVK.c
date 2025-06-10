#define VK_USE_PLATFORM_WIN32_KHR
#include <windows.h>

#include "jni_util.h"
#include "VKEnv.h"
#include "VKUtil.h"
#include "VKSurfaceData.h"

#define PLATFORM_FUNCTION_TABLE(ENTRY, ...) \
ENTRY(__VA_ARGS__, vkGetPhysicalDeviceWin32PresentationSupportKHR); \
ENTRY(__VA_ARGS__, vkCreateWin32SurfaceKHR); \

PLATFORM_FUNCTION_TABLE(DECL_PFN)
static HWND hwnd;

static VkBool32 WinVK_InitFunctions(VKEnv* vk, PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr) {
    VkBool32 missingAPI = JNI_FALSE;
    PLATFORM_FUNCTION_TABLE(CHECK_PROC_ADDR, missingAPI, vkGetInstanceProcAddr, vk->instance,)
    if (missingAPI) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: Required API is missing:")
        PLATFORM_FUNCTION_TABLE(LOG_MISSING_PFN,)
    }
    return !missingAPI;
}

static VkBool32 WinVK_CheckPresentationSupport(VKEnv* vk, VkPhysicalDevice device, uint32_t family) {
    return vkGetPhysicalDeviceWin32PresentationSupportKHR(device, family);
}

static VKPlatformData platformData = {
        .surfaceExtensionName = VK_KHR_WIN32_SURFACE_EXTENSION_NAME,
        .initFunctions = WinVK_InitFunctions,
        .checkPresentationSupport = WinVK_CheckPresentationSupport
};

/*
 * Class:     sun_java2d_vulkan_VKEnv
 * Method:    initPlatform
 * Signature: (J)[Lsun/java2d/vulkan/VKDevice;
 */
JNIEXPORT jlong JNICALL
Java_sun_java2d_vulkan_VKEnv_initPlatform(JNIEnv* env, jclass vkenv, jlong windowHandle) {
    hwnd = (HWND)windowHandle;
    return ptr_to_jlong(&platformData);
}

static void WinVK_InitSurfaceData(VKWinSDOps* surface, void* data) {
    if (data != NULL) {
        HWND win32Window = (HWND)data;
        VKEnv* vk = VKEnv_GetInstance();
        VkWin32SurfaceCreateInfoKHR surfaceCreateInfo = {
                .sType = VK_STRUCTURE_TYPE_WIN32_SURFACE_CREATE_INFO_KHR,
                .hinstance = GetModuleHandle(NULL),
                .hwnd = win32Window
        };
        VK_IF_ERROR(vkCreateWin32SurfaceKHR(vk->instance, &surfaceCreateInfo, NULL, &surface->surface)) {
            VK_UNHANDLED_ERROR();
        }
    }
}

static void WinVK_OnSurfaceResize(VKWinSDOps* surface, VkExtent2D extent) {
    // JNIEnv* env = JNU_GetEnv(jvm, JNI_VERSION_1_2);
    // JNU_CallMethodByName(env, NULL, surface->vksdOps.sdOps.sdObject, "bufferAttached", "()V");
}

/*
 * Class:     sun_java2d_vulkan_WinVKSurfaceData_WinVKWindowSurfaceData
 * Method:    initOps
 * Signature: (I)V
 */
JNIEXPORT void JNICALL Java_sun_java2d_vulkan_WinVKWindowSurfaceData_initOps(
        JNIEnv *env, jobject vksd, jint format, jint backgroundRGB) {
VKSD_CreateSurface(env, vksd, VKSD_WINDOW, format, backgroundRGB, WinVK_OnSurfaceResize);
}
