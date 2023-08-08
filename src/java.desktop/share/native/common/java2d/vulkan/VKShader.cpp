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

#include "VKShader.h"

// Inline bytecode of all shaders
#define INCLUDE_BYTECODE
#define SHADER_ENTRY(NAME, TYPE) static uint32_t NAME ## _ ## TYPE ## _data[] = {
#define BYTECODE_END };
#include "vulkan/shader_list.h"
#undef INCLUDE_BYTECODE
#undef SHADER_ENTRY
#undef BYTECODE_END

void VKShaders::init(const vk::raii::Device& device) {
    // Declare file extensions as stage flags
    auto vert = vk::ShaderStageFlagBits::eVertex;
    auto frag = vk::ShaderStageFlagBits::eFragment;
    // Init all shader modules
#   define SHADER_ENTRY(NAME, TYPE) \
    NAME ## _ ## TYPE.init(device, sizeof NAME ## _ ## TYPE ## _data, NAME ## _ ## TYPE ## _data, TYPE);
#   include "vulkan/shader_list.h"
#   undef SHADER_ENTRY
}
