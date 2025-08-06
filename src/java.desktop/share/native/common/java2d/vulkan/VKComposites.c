// Copyright 2025 JetBrains s.r.o.
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

#include "VKComposites.h"

#define ALPHA_BLEND(NAME, SRC_COLOR, DST_COLOR, SRC_ALPHA, DST_ALPHA)           \
    VKComposites_AddState(&composites, ALPHA_COMPOSITE_ ## NAME, (VKCompositeState)    \
    {{ .blendEnable = VK_TRUE,                                                  \
       .srcColorBlendFactor = VK_BLEND_FACTOR_ ## SRC_COLOR,                    \
       .dstColorBlendFactor = VK_BLEND_FACTOR_ ## DST_COLOR,                    \
       .colorBlendOp = VK_BLEND_OP_ADD,                                         \
       .srcAlphaBlendFactor = VK_BLEND_FACTOR_ ## SRC_ALPHA,                    \
       .dstAlphaBlendFactor = VK_BLEND_FACTOR_ ## DST_ALPHA,                    \
       .alphaBlendOp = VK_BLEND_OP_ADD,                                         \
       .colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |  \
                         VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT }, \
    { .logicOpEnable = VK_FALSE }, ALPHA_TYPE_PRE_MULTIPLIED })

static size_t hash(const void* ptr) {
    const VKCompositeDescriptor* d = ptr;
    return (size_t) (d->mode | (d->dstOpaque << 31));
}

static bool equals(const void* ap, const void* bp) {
    const VKCompositeDescriptor *a = ap, *b = bp;
    return a->mode == b->mode && a->dstOpaque == b->dstOpaque;
}

VKComposites VKComposites_Create() {
    const VKCompositeMode NEXT_FREE_MODE = ALPHA_COMPOSITE_GROUP + 1;
    VKComposites composites = { NULL };
    HASH_MAP_REHASH(composites.map, linear_probing, &equals, &hash, NEXT_FREE_MODE + 1, 10, 0.75);

    VKComposites_AddState(&composites, LOGIC_COMPOSITE_XOR, (VKCompositeState) {
        {   .blendEnable = VK_FALSE,
            .colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                              VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT },
        {   .logicOpEnable = VK_TRUE,
            .logicOp = VK_LOGIC_OP_XOR }, ALPHA_TYPE_PRE_MULTIPLIED });

    //                NAME |      SRC_COLOR       |       DST_COLOR      |      SRC_ALPHA       |       DST_ALPHA     ||
    ALPHA_BLEND(     CLEAR , ZERO                 , ZERO                 , ZERO                 , ZERO                );
    ALPHA_BLEND(       SRC , ONE                  , ZERO                 , ONE                  , ZERO                );
    ALPHA_BLEND(  SRC_OVER , ONE                  , ONE_MINUS_SRC_ALPHA  , ONE                  , ONE_MINUS_SRC_ALPHA );
    ALPHA_BLEND(  DST_OVER , ONE_MINUS_DST_ALPHA  , ONE                  , ONE_MINUS_DST_ALPHA  , ONE                 );
    ALPHA_BLEND(    SRC_IN , DST_ALPHA            , ZERO                 , DST_ALPHA            , ZERO                );
    ALPHA_BLEND(    DST_IN , ZERO                 , SRC_ALPHA            , ZERO                 , SRC_ALPHA           );
    ALPHA_BLEND(   SRC_OUT , ONE_MINUS_DST_ALPHA  , ZERO                 , ONE_MINUS_DST_ALPHA  , ZERO                );
    ALPHA_BLEND(   DST_OUT , ZERO                 , ONE_MINUS_SRC_ALPHA  , ZERO                 , ONE_MINUS_SRC_ALPHA );
    ALPHA_BLEND(       DST , ZERO                 , ONE                  , ZERO                 , ONE                 );
    ALPHA_BLEND(  SRC_ATOP , DST_ALPHA            , ONE_MINUS_SRC_ALPHA  , ZERO                 , ONE                 );
    ALPHA_BLEND(  DST_ATOP , ONE_MINUS_DST_ALPHA  , SRC_ALPHA            , ONE                  , ZERO                );
    ALPHA_BLEND(       XOR , ONE_MINUS_DST_ALPHA  , ONE_MINUS_SRC_ALPHA  , ONE_MINUS_DST_ALPHA  , ONE_MINUS_SRC_ALPHA );

    VKComposites_AddState(&composites, NO_COMPOSITE, (VKCompositeState) {
        {   .blendEnable = VK_FALSE,
            .colorWriteMask = 0 }, // For stencil-only operations.
        {   .logicOpEnable = VK_FALSE }, ALPHA_TYPE_PRE_MULTIPLIED });

    return composites;
}

void VKComposites_Destroy(VKComposites composites) {
    MAP_FREE(composites.map);
}

typedef union {
    struct {
        VkBlendFactor sc[2], dc[2], sa[2], da[2];
    };
    VkBlendFactor all[8];
} VKBlendVariables;
typedef enum {
    COLOR,
    ALPHA,
    ALL
} VKReplace;
static void VKComposites_ReplaceVariables(VKBlendVariables* vars, VKReplace type, VkBlendFactor of, VkBlendFactor nf) {
    for (int i = type == ALPHA ? 4 : 0; i < (type == COLOR ? 4 : 8); i++) if (vars->all[i] == of) vars->all[i] = nf;
}
static VkBool32 VKComposites_IsMultiplicativelyDistributive(VkBlendOp op) {
    // We can ignore MIN and MAX, as they ignore blending factors and therefore there's nothing to factor out.
    return op == VK_BLEND_OP_ADD || op == VK_BLEND_OP_SUBTRACT || op == VK_BLEND_OP_REVERSE_SUBTRACT;
}
static void VKComposites_CollapseCommonMultipliers(VKBlendVariables* vars, VkBlendOp colorOp, VkBlendOp alphaOp) {
    if (VKComposites_IsMultiplicativelyDistributive(colorOp) &&
        VKComposites_IsMultiplicativelyDistributive(alphaOp)) {
        for (int sc = 0; sc < 2; sc++) {
            VkBlendFactor common = VK_BLEND_FACTOR_ZERO;
            VkBlendFactor* f[4];
            if (*(f[0] = &vars->sc[sc]) == VK_BLEND_FACTOR_ONE) continue;
            common = *f[0];
            for (int dc = 0; dc < 2; dc++) {
                if (*(f[1] = &vars->dc[dc]) == VK_BLEND_FACTOR_ONE) continue;
                if (common == VK_BLEND_FACTOR_ZERO) common = *f[1];
                else if (*f[1] != VK_BLEND_FACTOR_ZERO && *f[1] != common) continue;
                for (int sa = 0; sa < 2; sa++) {
                    if (*(f[2] = &vars->sa[sa]) == VK_BLEND_FACTOR_ONE) continue;
                    if (common == VK_BLEND_FACTOR_ZERO) common = *f[2];
                    else if (*f[2] != VK_BLEND_FACTOR_ZERO && *f[2] != common) continue;
                    for (int da = 0; da < 2; da++) {
                        if (*(f[3] = &vars->da[da]) == VK_BLEND_FACTOR_ONE) continue;
                        if (common == VK_BLEND_FACTOR_ZERO) common = *f[3];
                        else if (*f[3] != VK_BLEND_FACTOR_ZERO && *f[3] != common) continue;
                        // We have found a common multiplier.
                        if (common == VK_BLEND_FACTOR_ZERO) return; // All zero.
                        for (int i = 0; i < 4; i++) { // Collapse.
                            if (*f[i] != VK_BLEND_FACTOR_ZERO) *f[i] = VK_BLEND_FACTOR_ONE;
                        }
                    }
                }
            }
        }
    }
}

void VKComposites_AddState(VKComposites* composites, VKCompositeMode mode, VKCompositeState state) {
    state.blendState.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
    state.blendState.pNext = NULL;
    state.blendState.attachmentCount = 1;
    state.outAlphaType = ALPHA_TYPE_PRE_MULTIPLIED;
    MAP_AT(composites->map, (VKCompositeDescriptor) { mode, VK_FALSE }) = state;

    // Using pre-multiplied alpha is necessary for correct blending,
    // but it can lead to information loss, which is crucial in case of opaque destinations.
    // For instance, doing SRC blend onto an opaque surface is expected to simply discard the (straight) alpha,
    // but doing this with a zero pre-multiplied alpha will always yield transparent black (0,0,0,0).
    // Here is how we are solving this:
    // General form of blending equation (r-result, s-source, sf-source factor, d-destination, df-destination factor):
    //   r = OP(s * sf, d * df)
    // To restore information lost due to alpha multiplication, let's express it in straight alpha form:
    //   r.a = OP(s.a * sf.a, d.a * df.a)
    //   r.rgb = OP(s.rgb * s.a * sf.rgb, d.rgb * d.a * df.rgb) / r.a
    // Now, with some specific parameter combinations we might be able to get rid of 0/0-type ambiguities
    // by outputting color in a straight-alpha form.

    // Generate opaque mode mapping, if needed.
    if (!state.blendState.logicOpEnable && state.attachmentState.blendEnable) {
        VKBlendVariables vars = {
            .sc = {VK_BLEND_FACTOR_SRC_ALPHA, state.attachmentState.srcColorBlendFactor},
            .dc = {VK_BLEND_FACTOR_DST_ALPHA, state.attachmentState.dstColorBlendFactor},
            .sa = {VK_BLEND_FACTOR_SRC_ALPHA, state.attachmentState.srcAlphaBlendFactor},
            .da = {VK_BLEND_FACTOR_DST_ALPHA, state.attachmentState.dstAlphaBlendFactor},
        };

        // Opaque destination - replace DST_ALPHA.
        VKComposites_ReplaceVariables(&vars, ALL, VK_BLEND_FACTOR_DST_ALPHA, VK_BLEND_FACTOR_ONE);
        VKComposites_ReplaceVariables(&vars, ALL, VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA, VK_BLEND_FACTOR_ZERO);
        VKComposites_ReplaceVariables(&vars, COLOR, VK_BLEND_FACTOR_SRC_ALPHA_SATURATE, VK_BLEND_FACTOR_ZERO);
        VKComposites_ReplaceVariables(&vars, ALPHA, VK_BLEND_FACTOR_SRC_ALPHA_SATURATE, VK_BLEND_FACTOR_ONE);

        // Simplify constants.
        const float* constants = state.blendState.blendConstants;
        if (constants[0] == 0.0f && constants[1] == 0.0f && constants[2] == 0.0f) {
            VKComposites_ReplaceVariables(&vars, COLOR, VK_BLEND_FACTOR_CONSTANT_COLOR, VK_BLEND_FACTOR_ZERO);
            VKComposites_ReplaceVariables(&vars, COLOR, VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR, VK_BLEND_FACTOR_ONE);
        } else if (constants[0] == 1.0f && constants[1] == 1.0f && constants[2] == 1.0f) {
            VKComposites_ReplaceVariables(&vars, COLOR, VK_BLEND_FACTOR_CONSTANT_COLOR, VK_BLEND_FACTOR_ONE);
            VKComposites_ReplaceVariables(&vars, COLOR, VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR, VK_BLEND_FACTOR_ZERO);
        }
        if (constants[3] == 0.0f) {
            VKComposites_ReplaceVariables(&vars, ALL, VK_BLEND_FACTOR_CONSTANT_ALPHA, VK_BLEND_FACTOR_ZERO);
            VKComposites_ReplaceVariables(&vars, ALL, VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA, VK_BLEND_FACTOR_ONE);
            VKComposites_ReplaceVariables(&vars, ALPHA, VK_BLEND_FACTOR_CONSTANT_COLOR, VK_BLEND_FACTOR_ZERO);
            VKComposites_ReplaceVariables(&vars, ALPHA, VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR, VK_BLEND_FACTOR_ONE);
        } else if (constants[3] == 1.0f) {
            VKComposites_ReplaceVariables(&vars, ALL, VK_BLEND_FACTOR_CONSTANT_ALPHA, VK_BLEND_FACTOR_ONE);
            VKComposites_ReplaceVariables(&vars, ALL, VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA, VK_BLEND_FACTOR_ZERO);
            VKComposites_ReplaceVariables(&vars, ALPHA, VK_BLEND_FACTOR_CONSTANT_COLOR, VK_BLEND_FACTOR_ONE);
            VKComposites_ReplaceVariables(&vars, ALPHA, VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR, VK_BLEND_FACTOR_ZERO);
        }

        VKComposites_CollapseCommonMultipliers(&vars,
                                               state.attachmentState.colorBlendOp, state.attachmentState.alphaBlendOp);

        VkBool32 straightSrcAlpha = vars.sc[0] == VK_BLEND_FACTOR_ONE && vars.sc[1] != VK_BLEND_FACTOR_ZERO;
        if (vars.sc[1] != state.attachmentState.srcColorBlendFactor ||
            vars.dc[1] != state.attachmentState.dstColorBlendFactor || straightSrcAlpha) {
            // Need to use opaque-specific blending.
            state.attachmentState.srcColorBlendFactor = vars.sc[1];
            state.attachmentState.dstColorBlendFactor = vars.dc[1];
            state.attachmentState.srcAlphaBlendFactor = VK_BLEND_FACTOR_ZERO;
            state.attachmentState.dstAlphaBlendFactor = VK_BLEND_FACTOR_ZERO;
            state.outAlphaType = straightSrcAlpha ? ALPHA_TYPE_STRAIGHT : ALPHA_TYPE_PRE_MULTIPLIED;
        }
    }
    MAP_AT(composites->map, (VKCompositeDescriptor) { mode, VK_TRUE }) = state;
}

const VKCompositeState* VKComposites_GetState(VKComposites* composites, VKCompositeMode mode, VkBool32 dstOpaque) {
    VKCompositeState* state = MAP_FIND(composites->map, (VKCompositeDescriptor) { mode, dstOpaque });
    state->blendState.pAttachments = &state->attachmentState;
    return state;
}
