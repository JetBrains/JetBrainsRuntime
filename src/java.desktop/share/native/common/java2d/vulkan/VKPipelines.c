// Copyright 2024 JetBrains s.r.o.
// DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
//
// This code is free software; you can redistribute it and/or modify it
// under the terms of the GNU General Public License version 2 only, as
// published by the Free Software Foundation.  Oracle designates this
// particular file as subject to the "Classpath" exception as provided
// by Oracle in the LICENSE file that accompanied this code.
//
// This code is distributed in the hope that it will be useful, but WITHOUT
// ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
// FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
// version 2 for more details (a copy is included in the LICENSE file that
// accompanied this code).
//
// You should have received a copy of the GNU General Public License version
// 2 along with this work; if not, write to the Free Software Foundation,
// Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
//
// Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
// or visit www.oracle.com if you need additional information or have any
// questions.

#include <assert.h>
#include "VKUtil.h"
#include "VKEnv.h"
#include "VKPipelines.h"

#define INCLUDE_BYTECODE
#define SHADER_ENTRY(NAME, TYPE) static uint32_t NAME ## _ ## TYPE ## _data[] = {
#define BYTECODE_END };
#include "vulkan/shader_list.h"
#undef INCLUDE_BYTECODE
#undef SHADER_ENTRY
#undef BYTECODE_END

inline void hash(uint32_t* result, int i) { // Good for hashing enums.
    uint32_t x = (uint32_t) i;
    x = ((x >> 16U) ^ x) * 0x45d9f3bU;
    x = ((x >> 16U) ^ x) * 0x45d9f3bU;
    x =  (x >> 16U) ^ x;
    *result ^= x + 0x9e3779b9U + (*result << 6U) + (*result >> 2U);
}
static size_t pipelineDescriptorHash(const void* ptr) {
    const VKPipelineDescriptor* d = ptr;
    uint32_t h = 0U;
    hash(&h, d->stencilMode);
    hash(&h, d->dstOpaque);
    hash(&h, d->inAlphaType);
    hash(&h, d->composite);
    hash(&h, d->shader);
    hash(&h, d->topology);
    return (size_t) h;
}
static bool pipelineDescriptorEquals(const void* ap, const void* bp) {
    const VKPipelineDescriptor *a = ap, *b = bp;
    return a->stencilMode == b->stencilMode &&
             a->dstOpaque == b->dstOpaque &&
           a->inAlphaType == b->inAlphaType &&
             a->composite == b->composite &&
                a->shader == b->shader &&
              a->topology == b->topology;
}

typedef struct VKShaders {
#   define SHADER_ENTRY(NAME, TYPE) VkPipelineShaderStageCreateInfo NAME ## _ ## TYPE;
#   include "vulkan/shader_list.h"
#   undef SHADER_ENTRY
} VKShaders;

static void VKPipelines_DestroyShaders(VKDevice* device, VKShaders* shaders) {
    assert(device != NULL);
    if (shaders == NULL) return;
#   define SHADER_ENTRY(NAME, TYPE) if (shaders->NAME##_##TYPE.module != VK_NULL_HANDLE) \
    device->vkDestroyShaderModule(device->handle, shaders->NAME##_##TYPE.module, NULL);
#   include "vulkan/shader_list.h"
#   undef SHADER_ENTRY
    free(shaders);
}

static VKShaders* VKPipelines_CreateShaders(VKDevice* device) {
    assert(device != NULL);
    const VkShaderStageFlagBits vert = VK_SHADER_STAGE_VERTEX_BIT;
    const VkShaderStageFlagBits frag = VK_SHADER_STAGE_FRAGMENT_BIT;

    VKShaders* shaders = (VKShaders*) calloc(1, sizeof(VKShaders));
    VK_RUNTIME_ASSERT(shaders);
    VkShaderModuleCreateInfo createInfo = { .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO };
#   define SHADER_ENTRY(NAME, TYPE)                                   \
    shaders->NAME ## _ ## TYPE = (VkPipelineShaderStageCreateInfo) {  \
        .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, \
        .stage = (TYPE),                                              \
        .pName = "main"                                               \
    };                                                                \
    createInfo.codeSize = sizeof(NAME ## _ ## TYPE ## _data);         \
    createInfo.pCode = NAME ## _ ## TYPE ## _data;                    \
    VK_IF_ERROR(device->vkCreateShaderModule(device->handle,          \
                &createInfo, NULL, &shaders->NAME##_##TYPE.module)) { \
        VKPipelines_DestroyShaders(device, shaders);                  \
        return NULL;                                                  \
    }
#   include "vulkan/shader_list.h"
#   undef SHADER_ENTRY
    return shaders;
}

#define MAKE_INPUT_STATE(NAME, TYPE, ...)                                                  \
const VkFormat INPUT_STATE_ATTRIBUTE_FORMATS_##NAME[] = { __VA_ARGS__ };                   \
VkVertexInputAttributeDescription                                                          \
    INPUT_STATE_ATTRIBUTES_##NAME[SARRAY_COUNT_OF(INPUT_STATE_ATTRIBUTE_FORMATS_##NAME)];  \
const VkVertexInputBindingDescription INPUT_STATE_BINDING_##NAME = {                       \
        .binding = 0,                                                                      \
        .stride = sizeof(TYPE),                                                            \
        .inputRate = VK_VERTEX_INPUT_RATE_VERTEX                                           \
};                                                                                         \
const VkPipelineVertexInputStateCreateInfo INPUT_STATE_##NAME = {                          \
        .sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO,                \
        .vertexBindingDescriptionCount = 1,                                                \
        .pVertexBindingDescriptions = &INPUT_STATE_BINDING_##NAME,                         \
        .vertexAttributeDescriptionCount = SARRAY_COUNT_OF(INPUT_STATE_ATTRIBUTES_##NAME), \
        .pVertexAttributeDescriptions = INPUT_STATE_ATTRIBUTES_##NAME                      \
};                                                                                         \
uint32_t INPUT_STATE_BINDING_SIZE_##NAME = 0;                                              \
for (uint32_t i = 0; i < SARRAY_COUNT_OF(INPUT_STATE_ATTRIBUTES_##NAME); i++) {            \
    INPUT_STATE_ATTRIBUTES_##NAME[i] = (VkVertexInputAttributeDescription) {               \
        i, 0, INPUT_STATE_ATTRIBUTE_FORMATS_##NAME[i], INPUT_STATE_BINDING_SIZE_##NAME};   \
    INPUT_STATE_BINDING_SIZE_##NAME +=                                                     \
        VKUtil_GetFormatGroup(INPUT_STATE_ATTRIBUTE_FORMATS_##NAME[i]).bytes;              \
} if (sizeof(TYPE) != INPUT_STATE_BINDING_SIZE_##NAME) VK_FATAL_ERROR("Vertex size mismatch for input state " #NAME)

static VKPipelineInfo VKPipelines_CreatePipelines(VKRenderPassContext* renderPassContext, uint32_t count,
                                                  const VKPipelineDescriptor* descriptors) {
    assert(renderPassContext != NULL && renderPassContext->pipelineContext != NULL);
    assert(count > 0 && descriptors != NULL);
    VKPipelineContext* pipelineContext = renderPassContext->pipelineContext;
    VKDevice* device = pipelineContext->device;
    VKShaders* shaders = pipelineContext->shaders;
    VKComposites* composites = &VKEnv_GetInstance()->composites;

    VKPipelineInfo pipelineInfos[count];
    // Setup pipeline creation structs.
    static const uint32_t MAX_DYNAMIC_STATES = 2;
    typedef struct {
        VkPipelineShaderStageCreateInfo createInfos[2]; // vert + frag
    } ShaderStages;
    ShaderStages stages[count];
    typedef struct {
        VkSpecializationInfo info;
        VkSpecializationMapEntry entries[2];
        uint64_t data[1];
    } Specialization;
    Specialization specializations[count][2];
    VkPipelineInputAssemblyStateCreateInfo inputAssemblyStates[count];
    VkPipelineDepthStencilStateCreateInfo depthStencilStates[count];
    VkPipelineDynamicStateCreateInfo dynamicStates[count];
    VkDynamicState dynamicStateValues[count][MAX_DYNAMIC_STATES];
    VkGraphicsPipelineCreateInfo createInfos[count];
    for (uint32_t i = 0; i < count; i++) {
        const VKCompositeState* compositeState =
            VKComposites_GetState(composites, descriptors[i].composite, descriptors[i].dstOpaque);
        pipelineInfos[i].outAlphaType = compositeState->outAlphaType;
        // Init default pipeline state. Some members are left uninitialized:
        // - pStages (but stageCount is set to 2)
        // - pVertexInputState
        // - createInfo.layout
        for (uint32_t j = 0; j < SARRAY_COUNT_OF(specializations[i]); j++) {
            specializations[i][j].info = (VkSpecializationInfo) {
                .mapEntryCount = 0,
                .pMapEntries = specializations[i][j].entries,
                .dataSize = 0,
                .pData = specializations[i][j].data
            };
        }
        inputAssemblyStates[i] = (VkPipelineInputAssemblyStateCreateInfo) {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
            .topology = descriptors[i].topology
        };
        static const VkViewport viewport = {};
        static const VkRect2D scissor = {};
        static const VkPipelineViewportStateCreateInfo viewportState = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO,
            .viewportCount = 1,
            .pViewports = &viewport,
            .scissorCount = 1,
            .pScissors = &scissor
        };
        static const VkPipelineRasterizationStateCreateInfo rasterizationState = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,
            .polygonMode = VK_POLYGON_MODE_FILL,
            .cullMode = VK_CULL_MODE_NONE,
            .lineWidth = 1.0f
        };
        static const VkPipelineMultisampleStateCreateInfo multisampleState = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO,
            .rasterizationSamples = VK_SAMPLE_COUNT_1_BIT
        };
        static const VkStencilOpState stencilOpState = {
            .failOp = VK_STENCIL_OP_KEEP,
            .passOp = VK_STENCIL_OP_KEEP,
            .compareOp = VK_COMPARE_OP_NOT_EQUAL,
            .compareMask = 0xFFFFFFFFU,
            .writeMask = 0U,
            .reference = CLIP_STENCIL_EXCLUDE_VALUE
        };
        depthStencilStates[i] = (VkPipelineDepthStencilStateCreateInfo) {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO,
            .stencilTestEnable = descriptors[i].stencilMode == STENCIL_MODE_ON,
            .front = stencilOpState,
            .back = stencilOpState
        };
        dynamicStates[i] = (VkPipelineDynamicStateCreateInfo) {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO,
            .dynamicStateCount = 2,
            .pDynamicStates = dynamicStateValues[i]
        };
        dynamicStateValues[i][0] = VK_DYNAMIC_STATE_VIEWPORT;
        dynamicStateValues[i][1] = VK_DYNAMIC_STATE_SCISSOR;
        createInfos[i] = (VkGraphicsPipelineCreateInfo) {
            .sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
            .stageCount = 2,
            .pStages = stages[i].createInfos,
            .pInputAssemblyState = &inputAssemblyStates[i],
            .pViewportState = &viewportState,
            .pRasterizationState = &rasterizationState,
            .pMultisampleState = &multisampleState,
            .pDepthStencilState = &depthStencilStates[i],
            .pColorBlendState = &compositeState->blendState,
            .pDynamicState = &dynamicStates[i],
            .renderPass = renderPassContext->renderPass[descriptors[i].stencilMode != STENCIL_MODE_NONE],
            .subpass = 0,
            .basePipelineHandle = VK_NULL_HANDLE,
            .basePipelineIndex = -1
        };
    }

    // Setup input states.
    MAKE_INPUT_STATE(COLOR, VKColorVertex, VK_FORMAT_R32G32_SFLOAT, VK_FORMAT_R32G32B32A32_SFLOAT);
    MAKE_INPUT_STATE(MASK_FILL_COLOR, VKMaskFillColorVertex, VK_FORMAT_R32G32B32A32_SINT, VK_FORMAT_R32G32B32A32_SFLOAT);
    MAKE_INPUT_STATE(BLIT, VKTxVertex, VK_FORMAT_R32G32_SFLOAT, VK_FORMAT_R32G32_SFLOAT);
    MAKE_INPUT_STATE(CLIP, VKIntVertex, VK_FORMAT_R32G32_SINT);

    for (uint32_t i = 0; i < count; i++) {
        // Setup shader-specific pipeline parameters.
        switch (descriptors[i].shader) {
        case SHADER_COLOR:
            createInfos[i].pVertexInputState = &INPUT_STATE_COLOR;
            createInfos[i].layout = pipelineContext->colorPipelineLayout;
            stages[i] = (ShaderStages) {{ shaders->color_vert, shaders->color_frag }};
            break;
        case SHADER_MASK_FILL_COLOR:
            createInfos[i].pVertexInputState = &INPUT_STATE_MASK_FILL_COLOR;
            createInfos[i].layout = pipelineContext->maskFillPipelineLayout;
            stages[i] = (ShaderStages) {{ shaders->mask_fill_color_vert, shaders->mask_fill_color_frag }};
            break;
        case SHADER_BLIT:
            createInfos[i].pVertexInputState = &INPUT_STATE_BLIT;
            createInfos[i].layout = pipelineContext->texturePipelineLayout;
            stages[i] = (ShaderStages) {{ shaders->blit_vert, shaders->blit_frag }};
            // Alpha conversion specialization.
            uint32_t* spec = (uint32_t*) specializations[i][1].data;
            spec[0] = descriptors[i].inAlphaType;
            spec[1] = pipelineInfos[i].outAlphaType;
            specializations[i][1].info.dataSize = 8;
            specializations[i][1].entries[0] = (VkSpecializationMapEntry) { 0, 0, 4 };
            specializations[i][1].entries[1] = (VkSpecializationMapEntry) { 1, 4, 4 };
            specializations[i][1].info.mapEntryCount = 2;
            stages[i].createInfos[1].pSpecializationInfo = &specializations[i][1].info;
            break;
        case SHADER_CLIP:
            createInfos[i].pVertexInputState = &INPUT_STATE_CLIP;
            static const VkStencilOpState CLIP_STENCIL_OP = {
                .failOp = VK_STENCIL_OP_REPLACE,
                .passOp = VK_STENCIL_OP_REPLACE,
                .compareOp = VK_COMPARE_OP_NEVER,
                .compareMask = 0U,
                .writeMask = 0xFFFFFFFFU,
                .reference = CLIP_STENCIL_INCLUDE_VALUE
            };
            static const VkPipelineDepthStencilStateCreateInfo CLIP_STENCIL_STATE = {
                .sType = VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO,
                .stencilTestEnable = VK_TRUE,
                .front = CLIP_STENCIL_OP,
                .back = CLIP_STENCIL_OP
            };
            createInfos[i].pDepthStencilState = &CLIP_STENCIL_STATE;
            createInfos[i].layout = pipelineContext->texturePipelineLayout;
            createInfos[i].stageCount = 1;
            stages[i] = (ShaderStages) {{ shaders->clip_vert }};
            break;
        default:
            VK_FATAL_ERROR("Cannot create pipeline, unknown shader requested!");
        }
        assert(createInfos[i].pDynamicState->dynamicStateCount <= MAX_DYNAMIC_STATES);
        J2dRlsTraceLn(J2D_TRACE_INFO, "VKPipelines_CreatePipelines: stencilMode=%d, dstOpaque=%d, composite=%d, shader=%d, topology=%d",
                descriptors[i].stencilMode, descriptors[i].dstOpaque, descriptors[i].composite, descriptors[i].shader, descriptors[i].topology);
    }

    // Create pipelines.
    // TODO pipeline cache
    VkPipeline pipelines[count];
    VK_IF_ERROR(device->vkCreateGraphicsPipelines(device->handle, VK_NULL_HANDLE, count,
                                                  createInfos, NULL, pipelines)) VK_UNHANDLED_ERROR();
    J2dRlsTraceLn(J2D_TRACE_INFO, "VKPipelines_CreatePipelines: created %d pipelines", count);
    for (uint32_t i = 0; i < count; i++) {
        pipelineInfos[i].pipeline = pipelines[i];
        MAP_AT(renderPassContext->pipelines, descriptors[i]) = pipelineInfos[i];
    }
    return pipelineInfos[0];
}

static VkResult VKPipelines_InitRenderPasses(VKDevice* device, VKRenderPassContext* renderPassContext) {
    assert(device != NULL && renderPassContext != NULL);
    VkAttachmentDescription attachments[] = {{
            .format = renderPassContext->format,
            .samples = VK_SAMPLE_COUNT_1_BIT,
            .loadOp = VK_ATTACHMENT_LOAD_OP_LOAD,
            .storeOp = VK_ATTACHMENT_STORE_OP_STORE,
            .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE,
            .stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
            .initialLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
            .finalLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
    }, {
            .format = VK_FORMAT_S8_UINT,
            .samples = VK_SAMPLE_COUNT_1_BIT,
            .loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE,
            .storeOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
            .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_LOAD,
            .stencilStoreOp = VK_ATTACHMENT_STORE_OP_STORE,
            .initialLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
            .finalLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
    }};
    VkAttachmentReference colorReference = {
            .attachment = 0,
            .layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
    };
    VkAttachmentReference stencilReference = {
            .attachment = 1,
            .layout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
    };
    VkSubpassDescription subpassDescription = {
            .pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS,
            .colorAttachmentCount = 1,
            .pColorAttachments = &colorReference
    };
    VkRenderPassCreateInfo createInfo = {
            .sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO,
            .attachmentCount = 1,
            .pAttachments = attachments,
            .subpassCount = 1,
            .pSubpasses = &subpassDescription,
            .dependencyCount = 0,
            .pDependencies = NULL
    };
    for (uint32_t i = 0; i < 2; i++) {
        if (i == 1) {
            createInfo.attachmentCount = 2;
            subpassDescription.pDepthStencilAttachment = &stencilReference;
        }
        VkResult result = device->vkCreateRenderPass(device->handle, &createInfo, NULL, &renderPassContext->renderPass[i]);
        VK_IF_ERROR(result) return result;
    }
    return VK_SUCCESS;
}

static void VKPipelines_DestroyRenderPassContext(VKRenderPassContext* renderPassContext) {
    if (renderPassContext == NULL) return;
    VKDevice* device = renderPassContext->pipelineContext->device;
    assert(device != NULL);
    for (const VKPipelineDescriptor* k = NULL; (k = MAP_NEXT_KEY(renderPassContext->pipelines, k)) != NULL;) {
        const VKPipelineInfo* info = MAP_FIND(renderPassContext->pipelines, *k);
        device->vkDestroyPipeline(device->handle, info->pipeline, NULL);
    }
    MAP_FREE(renderPassContext->pipelines);
    for (uint32_t i = 0; i < 2; i++) {
        device->vkDestroyRenderPass(device->handle, renderPassContext->renderPass[i], NULL);
    }
    J2dRlsTraceLn(J2D_TRACE_INFO, "VKPipelines_DestroyRenderPassContext(%p): format=%d",
                  renderPassContext, renderPassContext->format);
    free(renderPassContext);
}

static VKRenderPassContext* VKPipelines_CreateRenderPassContext(VKPipelineContext* pipelineContext, VkFormat format) {
    assert(pipelineContext != NULL && pipelineContext->device != NULL);
    VKRenderPassContext* renderPassContext = calloc(1, sizeof(VKRenderPassContext));
    VK_RUNTIME_ASSERT(renderPassContext);
    HASH_MAP_REHASH(renderPassContext->pipelines, linear_probing,
                    &pipelineDescriptorEquals, &pipelineDescriptorHash, 0, 10, 0.75);
    renderPassContext->pipelineContext = pipelineContext;
    renderPassContext->format = format;

    VK_IF_ERROR(VKPipelines_InitRenderPasses(pipelineContext->device, renderPassContext)) {
        VKPipelines_DestroyRenderPassContext(renderPassContext);
        return NULL;
    }

    // TODO create few common pipelines in advance? Like default shaders for SRC_OVER composite.

    J2dRlsTraceLn(J2D_TRACE_INFO, "VKPipelines_CreateRenderPassContext(%p): format=%d", renderPassContext, format);
    return renderPassContext;
}

static VkResult VKPipelines_InitPipelineLayouts(VKDevice* device, VKPipelineContext* pipelines) {
    assert(device != NULL && pipelines != NULL);
    VkResult result;

    // We want all our pipelines to have the same push constant range in vertex shader to ensure a common state is compatible between pipelines.
    VkPushConstantRange pushConstantRanges[] = {{
            .stageFlags = VK_SHADER_STAGE_VERTEX_BIT,
            .offset = 0,
            .size = sizeof(VKTransform)
    }, {
            .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT,
            .offset = sizeof(VKTransform),
            .size = sizeof(VKCompositeConstants)
    }};

    // Color pipeline.
    VkPipelineLayoutCreateInfo createInfo = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
            .setLayoutCount = 0,
            .pushConstantRangeCount = 1,
            .pPushConstantRanges = pushConstantRanges
    };
    result = device->vkCreatePipelineLayout(device->handle, &createInfo, NULL, &pipelines->colorPipelineLayout);
    VK_IF_ERROR(result) return result;

    // Mask fill pipeline.
    VkDescriptorSetLayoutBinding maskBufferLayoutBinding = {
            .binding = 0,
            .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_TEXEL_BUFFER,
            .descriptorCount = 1,
            .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT,
            .pImmutableSamplers = NULL
    };
    VkDescriptorSetLayoutCreateInfo descriptorSetLayoutCreateInfo = {
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
            .bindingCount = 1,
            .pBindings = &maskBufferLayoutBinding
    };
    result = device->vkCreateDescriptorSetLayout(device->handle, &descriptorSetLayoutCreateInfo, NULL, &pipelines->maskFillDescriptorSetLayout);
    VK_IF_ERROR(result) return result;

    createInfo.setLayoutCount = 1;
    createInfo.pSetLayouts = &pipelines->maskFillDescriptorSetLayout;
    result = device->vkCreatePipelineLayout(device->handle, &createInfo, NULL, &pipelines->maskFillPipelineLayout);
    VK_IF_ERROR(result) return result;

    // Texture pipeline.
    VkDescriptorSetLayoutBinding textureLayoutBinding = {
            .binding = 0,
            .descriptorType = VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE,
            .descriptorCount = 1,
            .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT,
            .pImmutableSamplers = NULL
    };
    VkDescriptorSetLayoutCreateInfo textureDescriptorSetLayoutCreateInfo = {
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
            .bindingCount = 1,
            .pBindings = &textureLayoutBinding
    };
    result = device->vkCreateDescriptorSetLayout(device->handle, &textureDescriptorSetLayoutCreateInfo, NULL, &pipelines->textureDescriptorSetLayout);
    VK_IF_ERROR(result) return result;

    VkDescriptorSetLayout textureDescriptorSetLayouts[] = {
        pipelines->textureDescriptorSetLayout,
        pipelines->samplers.descriptorSetLayout
    };
    createInfo.setLayoutCount = SARRAY_COUNT_OF(textureDescriptorSetLayouts);
    createInfo.pSetLayouts = textureDescriptorSetLayouts;
    createInfo.pushConstantRangeCount = SARRAY_COUNT_OF(pushConstantRanges);
    result = device->vkCreatePipelineLayout(device->handle, &createInfo, NULL, &pipelines->texturePipelineLayout);
    VK_IF_ERROR(result) return result;

    return VK_SUCCESS;
}

VKPipelineContext* VKPipelines_CreateContext(VKDevice* device) {
    assert(device != NULL);
    VKPipelineContext* pipelineContext = (VKPipelineContext*) calloc(1, sizeof(VKPipelineContext));
    VK_RUNTIME_ASSERT(pipelineContext);
    pipelineContext->device = device;

    pipelineContext->samplers = VKSamplers_Create(device);
    if (pipelineContext->samplers.descriptorPool == VK_NULL_HANDLE) {
        VKPipelines_DestroyContext(pipelineContext);
        return NULL;
    }

    pipelineContext->shaders = VKPipelines_CreateShaders(device);
    if (pipelineContext->shaders == NULL) {
        VKPipelines_DestroyContext(pipelineContext);
        return NULL;
    }

    VK_IF_ERROR(VKPipelines_InitPipelineLayouts(device, pipelineContext)) {
        VKPipelines_DestroyContext(pipelineContext);
        return NULL;
    }

    J2dRlsTraceLn(J2D_TRACE_INFO, "VKPipelines_CreateContext(%p)", pipelineContext);
    return pipelineContext;
}

void VKPipelines_DestroyContext(VKPipelineContext* pipelineContext) {
    if (pipelineContext == NULL) return;
    VKDevice* device = pipelineContext->device;
    assert(device != NULL);

    for (uint32_t i = 0; i < ARRAY_SIZE(pipelineContext->renderPassContexts); i++) {
        VKPipelines_DestroyRenderPassContext(pipelineContext->renderPassContexts[i]);
    }
    ARRAY_FREE(pipelineContext->renderPassContexts);

    VKPipelines_DestroyShaders(device, pipelineContext->shaders);

    device->vkDestroyPipelineLayout(device->handle, pipelineContext->colorPipelineLayout, NULL);
    device->vkDestroyPipelineLayout(device->handle, pipelineContext->texturePipelineLayout, NULL);
    device->vkDestroyDescriptorSetLayout(device->handle, pipelineContext->textureDescriptorSetLayout, NULL);
    device->vkDestroyPipelineLayout(device->handle, pipelineContext->maskFillPipelineLayout, NULL);
    device->vkDestroyDescriptorSetLayout(device->handle, pipelineContext->maskFillDescriptorSetLayout, NULL);

    VKSamplers_Destroy(device, pipelineContext->samplers);

    J2dRlsTraceLn(J2D_TRACE_INFO, "VKPipelines_DestroyContext(%p)", pipelineContext);
    free(pipelineContext);
}

VKRenderPassContext* VKPipelines_GetRenderPassContext(VKPipelineContext* pipelineContext, VkFormat format) {
    assert(pipelineContext != NULL && pipelineContext->device != NULL);
    for (uint32_t i = 0; i < ARRAY_SIZE(pipelineContext->renderPassContexts); i++) {
        if (pipelineContext->renderPassContexts[i]->format == format) {
            return pipelineContext->renderPassContexts[i];
        }
    }
    // Not found, create.
    VKRenderPassContext* renderPassContext = VKPipelines_CreateRenderPassContext(pipelineContext, format);
    ARRAY_PUSH_BACK(pipelineContext->renderPassContexts) = renderPassContext;
    return renderPassContext;
}

VKPipelineInfo VKPipelines_GetPipelineInfo(VKRenderPassContext* renderPassContext, VKPipelineDescriptor descriptor) {
    assert(renderPassContext != NULL);
    VKPipelineInfo info = MAP_AT(renderPassContext->pipelines, descriptor);
    if (info.pipeline == VK_NULL_HANDLE) {
        info = VKPipelines_CreatePipelines(renderPassContext, 1, &descriptor);
    }
    return info;
}
