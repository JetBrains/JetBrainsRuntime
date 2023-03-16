#include "VKBase.h"
#include <Trace.h>
#include <set>

static const uint32_t REQUIRED_VULKAN_VERSION = VK_MAKE_API_VERSION(0, 1, 0, 0);
static vk::raii::Context* context;
vk::raii::Instance vkInstance = nullptr;

static bool createInstance() {
    try {
        // Load library.
        vk::raii::Context ctx;
        uint32_t version = ctx.enumerateInstanceVersion();
        J2dRlsTraceLn3(J2D_TRACE_INFO, "Found Vulkan %d.%d.%d",
                       VK_API_VERSION_MAJOR(version), VK_API_VERSION_MINOR(version), VK_API_VERSION_PATCH(version));

        if (version < REQUIRED_VULKAN_VERSION) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "Unsupported Vulkan version");
            return false;
        }

        // Populate maps and log supported layers & extensions.
        std::set<std::string> layers, extensions;
        J2dRlsTraceLn(J2D_TRACE_VERBOSE, "Supported instance layers:");
        for (auto& l : ctx.enumerateInstanceLayerProperties()) {
            J2dRlsTrace1(J2D_TRACE_VERBOSE, "    %s\n", (char*) l.layerName);
            layers.emplace((char*) l.layerName);
        }
        J2dRlsTraceLn(J2D_TRACE_VERBOSE, "Supported instance extensions:");
        for (auto& e : ctx.enumerateInstanceExtensionProperties(nullptr)) {
            J2dRlsTrace1(J2D_TRACE_VERBOSE, "    %s\n", (char*) e.extensionName);
            extensions.emplace((char*) e.extensionName);
        }

        std::vector<const char*> enabledLayers, enabledExtensions;

        // Check required layers & extensions.
#if defined(VK_USE_PLATFORM_WAYLAND_KHR)
        enabledExtensions.push_back(VK_KHR_WAYLAND_SURFACE_EXTENSION_NAME);
#endif
        enabledExtensions.push_back(VK_KHR_SURFACE_EXTENSION_NAME);
        bool requiredNotFound = false;
        for (auto e : enabledExtensions) {
            if (extensions.find(e) == extensions.end()) {
                J2dRlsTraceLn1(J2D_TRACE_ERROR, "Required instance extension not supported: %s", (char*) e);
                requiredNotFound = true;
            }
        }
        if (requiredNotFound) return false;

        // Check optional layers & extensions.
#ifdef DEBUG
        const char* const VALIDATION_LAYER_NAME = "VK_LAYER_KHRONOS_validation";
        if (layers.find(VALIDATION_LAYER_NAME) != layers.end() &&
            extensions.find(VK_EXT_DEBUG_UTILS_EXTENSION_NAME) != extensions.end()) {
            enabledLayers.push_back(VALIDATION_LAYER_NAME);
            enabledExtensions.push_back(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
        } else {
            J2dRlsTraceLn2(J2D_TRACE_WARNING, "%s and %s are not supported",
                           VALIDATION_LAYER_NAME, VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
        }
#endif

        vk::ApplicationInfo applicationInfo {
                /*pApplicationName*/   "OpenJDK",
                /*applicationVersion*/ 0,
                /*pEngineName*/        "OpenJDK",
                /*engineVersion*/      0,
                /*apiVersion*/         REQUIRED_VULKAN_VERSION
        };

        vk::InstanceCreateInfo instanceCreateInfo {
                /*flags*/                   {},
                /*pApplicationInfo*/        &applicationInfo,
                /*ppEnabledLayerNames*/     enabledLayers,
                /*ppEnabledExtensionNames*/ enabledExtensions
        };

        // Save context object at persistent address before passing it further.
        context = new vk::raii::Context(std::move(ctx));

        vkInstance = vk::raii::Instance(*context, instanceCreateInfo);
        J2dRlsTraceLn(J2D_TRACE_VERBOSE, "Vulkan instance created");

        return true;
    } catch (std::exception& e) {
        // Usually this means we didn't find the shared library.
        J2dRlsTraceLn(J2D_TRACE_ERROR, e.what());
        return false;
    }
}

extern "C" jboolean VK_Init() {
    if (!createInstance()) return false;
    return true;
}