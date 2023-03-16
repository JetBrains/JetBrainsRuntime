#ifndef VKBase_h_Included
#define VKBase_h_Included
#ifdef __cplusplus


#define VK_NO_PROTOTYPES
#define VULKAN_HPP_NO_DEFAULT_DISPATCHER
#include <vulkan/vulkan_raii.hpp>

extern vk::raii::Instance vkInstance;


extern "C" {
#endif //__cplusplus

typedef unsigned char jboolean;

jboolean VK_Init();


#ifdef __cplusplus
}
#endif //__cplusplus
#endif //VKBase_h_Included
