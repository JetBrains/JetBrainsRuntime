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

#ifndef VKPipelines_h_Included
#define VKPipelines_h_Included

#include "java_awt_AlphaComposite.h"
#include "VKTypes.h"

/**
 * All pipeline types.
 */
typedef enum {
    PIPELINE_FILL_COLOR      = 0,
    PIPELINE_DRAW_COLOR      = 1,
    PIPELINE_BLIT            = 2,
    PIPELINE_MASK_FILL_COLOR = 3,
    PIPELINE_COUNT           = 4,
    NO_PIPELINE              = 0x7FFFFFFF
} PipelineType;

/**
 * There are two groups of composite modes:
 * - Logic composite - using logicOp.
 * - Alpha compisite - using blending.
 */
typedef enum {
    LOGIC_COMPOSITE_XOR      = 0,
    LOGIC_COMPOSITE_COUNT    = 1,
    ALPHA_COMPOSITE_CLEAR    = java_awt_AlphaComposite_CLEAR,
    ALPHA_COMPOSITE_SRC      = java_awt_AlphaComposite_SRC,
    ALPHA_COMPOSITE_DST      = java_awt_AlphaComposite_DST,
    ALPHA_COMPOSITE_SRC_OVER = java_awt_AlphaComposite_SRC_OVER,
    ALPHA_COMPOSITE_DST_OVER = java_awt_AlphaComposite_DST_OVER,
    ALPHA_COMPOSITE_SRC_IN   = java_awt_AlphaComposite_SRC_IN,
    ALPHA_COMPOSITE_DST_IN   = java_awt_AlphaComposite_DST_IN,
    ALPHA_COMPOSITE_SRC_OUT  = java_awt_AlphaComposite_SRC_OUT,
    ALPHA_COMPOSITE_DST_OUT  = java_awt_AlphaComposite_DST_OUT,
    ALPHA_COMPOSITE_SRC_ATOP = java_awt_AlphaComposite_SRC_ATOP,
    ALPHA_COMPOSITE_DST_ATOP = java_awt_AlphaComposite_DST_ATOP,
    ALPHA_COMPOSITE_XOR      = java_awt_AlphaComposite_XOR,
    COMPOSITE_COUNT          = ALPHA_COMPOSITE_XOR + 1,
    NO_COMPOSITE             = 0x7FFFFFFF
} CompositeMode;
#define IS_LOGIC_COMPOSITE(COMPOSITE) ((COMPOSITE) < LOGIC_COMPOSITE_COUNT)
#define IS_ALPHA_COMPOSITE(COMPOSITE) ((COMPOSITE) >= LOGIC_COMPOSITE_COUNT)

/**
 * Logic composite can only work with integer and normalized color attachments.
 * In order to make it work with sRGB images, we reinterpret their format as *_UNORM.
 * Therefore for any resource related to attachment format we need two copies:
 * - Original, with real format.
 * - Normalized, with format reinterpreted as *_UNORM.
 * Such resources include:
 * - Surface image view.
 * - Surface framebuffer
 * - Render pass instance
 */
typedef enum {
    FORMAT_ALIAS_REAL  = 0,
    FORMAT_ALIAS_UNORM = 1,
    FORMAT_ALIAS_COUNT = 2
} FormatAlias;
#define COMPOSITE_TO_FORMAT_ALIAS(COMPOSITE) ((FormatAlias) IS_LOGIC_COMPOSITE(COMPOSITE))

/**
 * Set format aliases from base format.
 */
#define SET_ALIASED_FORMAT_FROM_REAL(FORMAT, REAL) \
(FORMAT)[FORMAT_ALIAS_REAL] = (REAL);              \
(FORMAT)[FORMAT_ALIAS_UNORM] = VKUtil_GetFormatGroup(REAL).unorm
/**
 * Iterate over format aliases.
 */
#define FOR_EACH_FORMAT_ALIAS(ALIAS) for (FormatAlias (ALIAS) = FORMAT_ALIAS_REAL; (ALIAS) < FORMAT_ALIAS_COUNT; (ALIAS)++)
/**
 * Initialize format-aliased handle, reusing matching handle aliases.
 * CHECK is anything which can be used for matching handle check, usually format.
 */
#define INIT_FORMAT_ALIASED_HANDLE(HANDLE, CHECK, ALIAS) FOR_EACH_FORMAT_ALIAS(ALIAS)     \
    if ((ALIAS) != FORMAT_ALIAS_REAL && (CHECK)[(ALIAS)] == (CHECK)[FORMAT_ALIAS_REAL]) { \
        (HANDLE)[(ALIAS)] = (HANDLE)[FORMAT_ALIAS_REAL]; } else
/**
 * Destroy format-aliased handle, removing duplicated aliases.
 */
#define DESTROY_FORMAT_ALIASED_HANDLE(HANDLE, ALIAS) FOR_EACH_FORMAT_ALIAS(ALIAS)         \
    if ((ALIAS) != FORMAT_ALIAS_REAL && (HANDLE)[(ALIAS)] == (HANDLE)[FORMAT_ALIAS_REAL]) \
        (HANDLE)[(ALIAS)] = VK_NULL_HANDLE;                                               \
FOR_EACH_FORMAT_ALIAS(ALIAS) if ((HANDLE)[(ALIAS)] != VK_NULL_HANDLE)

extern const VkPipelineColorBlendAttachmentState COMPOSITE_BLEND_STATES[COMPOSITE_COUNT];

/**
 * Global pipeline context.
 */
struct VKPipelineContext {
    VKDevice*             device;
    VkPipelineLayout      pipelineLayout;
    VkDescriptorSetLayout textureDescriptorSetLayout;
    VkPipelineLayout      texturePipelineLayout;
    VkDescriptorSetLayout maskFillDescriptorSetLayout;
    VkPipelineLayout      maskFillPipelineLayout;

    VkSampler             linearRepeatSampler;

    struct VKShaders*     shaders;
    VKRenderPassContext** renderPassContexts;
};

/**
 * Per-format context.
 */
struct VKRenderPassContext {
    VKPipelineContext*     pipelineContext;
    VkFormat               format[FORMAT_ALIAS_COUNT];
    VkRenderPass           renderPass[FORMAT_ALIAS_COUNT];
    struct VKPipelineSet** pipelineSets; // TODO we will need a real hash map for this in the future.
};

typedef struct {
    float x, y;
    Color color;
} VKColorVertex;

typedef struct {
    float px, py;
    float u, v;
} VKTxVertex;

typedef struct {
    int x, y, maskOffset, maskScanline;
    Color color;
} VKMaskFillColorVertex;

VKPipelineContext* VKPipelines_CreateContext(VKDevice* device);
void VKPipelines_DestroyContext(VKPipelineContext* pipelines);

VKRenderPassContext* VKPipelines_GetRenderPassContext(VKPipelineContext* pipelineContext, VkFormat format);
VkPipeline VKPipelines_GetPipeline(VKRenderPassContext* renderPassContext, CompositeMode composite, PipelineType pipeline);

#endif //VKPipelines_h_Included
