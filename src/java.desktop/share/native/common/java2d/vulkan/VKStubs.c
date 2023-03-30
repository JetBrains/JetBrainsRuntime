// These are stubs in case we were built with Vulkan disabled.
#ifndef VULKAN_ENABLED

typedef unsigned char jboolean;

jboolean VK_Init() {
    return 0;
}

#endif