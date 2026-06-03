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

static VkBool32 Win32VK_InitFunctions(VKEnv* vk, PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr) {
    VkBool32 missingAPI = JNI_FALSE;
    PLATFORM_FUNCTION_TABLE(CHECK_PROC_ADDR, missingAPI, vkGetInstanceProcAddr, vk->instance,)
    if (missingAPI) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: Required API is missing:");
        PLATFORM_FUNCTION_TABLE(LOG_MISSING_PFN,)
    }
    return !missingAPI;
}

static VkBool32 Win32VK_CheckPresentationSupport(VKEnv* vk, VkPhysicalDevice device, uint32_t family) {
    return vkGetPhysicalDeviceWin32PresentationSupportKHR(device, family);
}

static VKPlatformData platformData = {
        .surfaceExtensionName = VK_KHR_WIN32_SURFACE_EXTENSION_NAME,
        .initFunctions = Win32VK_InitFunctions,
        .checkPresentationSupport = Win32VK_CheckPresentationSupport
};

/*
 * Class:     sun_java2d_vulkan_VKEnv
 * Method:    initPlatformWin32
 * Signature: ()J
 */
JNIEXPORT jlong JNICALL
Java_sun_java2d_vulkan_VKEnv_initPlatformWin32(JNIEnv* env, jclass vkenv) {
    return ptr_to_jlong(&platformData);
}

static void Win32VK_InitSurfaceData(VKWinSDOps* surface, void* data) {
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

static void Win32VK_OnSurfaceResize(VKWinSDOps* surface) {
    // No-op
}

/*
 * Class:     sun_java2d_vulkan_Win32VKSurfaceData_Win32VKWindowSurfaceData
 * Method:    initOps
 * Signature: (I)V
 */
JNIEXPORT void JNICALL Java_sun_java2d_vulkan_Win32VKWindowSurfaceData_initOps(
        JNIEnv *env, jobject vksd, jint format) {
VKSD_CreateSurface(env, vksd, VKSD_WINDOW, format, Win32VK_OnSurfaceResize);
}

/*
 * Class:     sun_java2d_vulkan_Win32VKSurfaceData_Win32VKWindowSurfaceData
 * Method:    assignWindow
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_sun_java2d_vulkan_Win32VKWindowSurfaceData_assignWindow(
        JNIEnv *env, jobject vksd, jlong hwnd) {
    VKSD_InitWindowSurface(env, vksd, Win32VK_InitSurfaceData, jlong_to_ptr(hwnd));
}

/*
 * Class:     sun_java2d_vulkan_Win32VKWindowSurfaceData
 * Method:    getClientAreaSizePackedIntoLong
 * Signature: (J)J
 */
JNIEXPORT jlong JNICALL Java_sun_java2d_vulkan_Win32VKWindowSurfaceData_getClientAreaSizePackedIntoLong(
        JNIEnv *env, jclass cls, jlong hwnd) {
    RECT rect = {0}; // fields are 32-bit signed ints
    // According to WinAPI, the worst thing that can happen is getting stale data here,
    // and I don't think using SyncCall would help with that in any way...
    GetClientRect(jlong_to_ptr(hwnd), &rect);
    return ((jlong)rect.right << 32) | rect.bottom;
}
