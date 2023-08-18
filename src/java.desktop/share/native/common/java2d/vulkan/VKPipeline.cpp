/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, JetBrains s.r.o.. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

#include "VKPipeline.h"

void VKPipelines::init(const vk::raii::Device& device) {
    shaders.init(device);

    vk::PushConstantRange pushConstantRange {vk::ShaderStageFlagBits::eVertex, 0, sizeof(float) * 2};
    testLayout = device.createPipelineLayout(vk::PipelineLayoutCreateInfo {{}, {}, pushConstantRange});

    std::array<vk::PipelineShaderStageCreateInfo, 2> testStages {shaders.test_vert.stage(), shaders.test_frag.stage()};
    vk::VertexInputBindingDescription vertexInputBindingDescription {0, 8, vk::VertexInputRate::eVertex};
    vk::VertexInputAttributeDescription vertexInputAttributeDescription {0, 0, vk::Format::eR32G32Sfloat, 0};
    vk::PipelineVertexInputStateCreateInfo vertexInputStateCreateInfo {{}, vertexInputBindingDescription, vertexInputAttributeDescription};
    vk::PipelineInputAssemblyStateCreateInfo inputAssemblyStateCreateInfo {{}, vk::PrimitiveTopology::eTriangleFan, false};
    vk::Viewport viewport;
    vk::Rect2D scissor;
    vk::PipelineViewportStateCreateInfo viewportStateCreateInfo {{}, viewport, scissor};
    vk::PipelineRasterizationStateCreateInfo rasterizationStateCreateInfo {
            {}, false, false, vk::PolygonMode::eFill, vk::CullModeFlagBits::eNone,
            vk::FrontFace::eClockwise, false, 0, 0, 0, 1
    };
    vk::PipelineMultisampleStateCreateInfo multisampleStateCreateInfo {};
    vk::PipelineColorBlendAttachmentState colorBlendAttachmentState {false}; // TODO No blending yet
    colorBlendAttachmentState.colorWriteMask = vk::ColorComponentFlagBits::eR | vk::ColorComponentFlagBits::eG | vk::ColorComponentFlagBits::eB | vk::ColorComponentFlagBits::eA;
    vk::PipelineColorBlendStateCreateInfo colorBlendStateCreateInfo {{}, false, vk::LogicOp::eXor, colorBlendAttachmentState};
    std::array<vk::DynamicState, 2> dynamicStates {vk::DynamicState::eViewport, vk::DynamicState::eScissor};
    vk::PipelineDynamicStateCreateInfo dynamicStateCreateInfo {{}, dynamicStates};
    vk::Format format = vk::Format::eB8G8R8A8Unorm; // TODO
    vk::PipelineRenderingCreateInfo renderingCreateInfo {0, format};
    auto pipelines = device.createGraphicsPipelines(nullptr, {
        vk::GraphicsPipelineCreateInfo {
                {}, testStages,
                &vertexInputStateCreateInfo,
                &inputAssemblyStateCreateInfo,
                nullptr,
                &viewportStateCreateInfo,
                &rasterizationStateCreateInfo,
                &multisampleStateCreateInfo,
                nullptr,
                &colorBlendStateCreateInfo,
                &dynamicStateCreateInfo,
                *testLayout,
                nullptr, 0, nullptr, 0,
                &renderingCreateInfo
        }
    });
    // TODO pipeline cache
    test = std::move(pipelines[0]);
}
