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
    VKComposites_AddState(&map, ALPHA_COMPOSITE_ ## NAME, (VKCompositeState)    \
    {{ .blendEnable = VK_TRUE,                                                  \
       .srcColorBlendFactor = VK_BLEND_FACTOR_ ## SRC_COLOR,                    \
       .dstColorBlendFactor = VK_BLEND_FACTOR_ ## DST_COLOR,                    \
       .colorBlendOp = VK_BLEND_OP_ADD,                                         \
       .srcAlphaBlendFactor = VK_BLEND_FACTOR_ ## SRC_ALPHA,                    \
       .dstAlphaBlendFactor = VK_BLEND_FACTOR_ ## DST_ALPHA,                    \
       .alphaBlendOp = VK_BLEND_OP_ADD,                                         \
       .colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |  \
                         VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT }, \
    { .logicOpEnable = VK_FALSE }})

static size_t hash(const void* ptr) {
    return (size_t) *(VKCompositeMode*)ptr;
}

static bool equals(const void* ap, const void* bp) {
    return *(VKCompositeMode*)ap == *(VKCompositeMode*)bp;
}

VKComposites VKComposites_Create() {
    VKComposites map = NULL;
    HASH_MAP_REHASH(map, linear_probing, &equals, &hash, ALPHA_COMPOSITE_GROUP + 2, 10, 0.75);

    VKComposites_AddState(&map, LOGIC_COMPOSITE_XOR, (VKCompositeState) {
        {   .blendEnable = VK_FALSE,
            .colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                              VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT },
        {   .logicOpEnable = VK_TRUE,
            .logicOp = VK_LOGIC_OP_XOR }});

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

    VKComposites_AddState(&map, NO_COMPOSITE, (VKCompositeState) {
        {   .blendEnable = VK_FALSE,
            .colorWriteMask = 0 }, // For stencil-only operations.
        {   .logicOpEnable = VK_FALSE }});

    return map;
}

void VKComposites_Destroy(VKComposites composites) {
    MAP_FREE(composites);
}

void VKComposites_AddState(VKComposites* composites, VKCompositeMode mode, VKCompositeState state) {
    state.blendState.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
    state.blendState.pNext = NULL;
    state.blendState.attachmentCount = 1;
    MAP_AT(*composites, mode) = state;
}

const VKCompositeState* VKComposites_GetState(VKComposites* composites, VKCompositeMode mode) {
    VKCompositeState* state = MAP_FIND(*composites, mode);
    state->blendState.pAttachments = &state->attachmentState;
    return state;
}
