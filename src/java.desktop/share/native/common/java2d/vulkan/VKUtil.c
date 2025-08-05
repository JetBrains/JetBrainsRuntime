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

#include "VKUtil.h"

Color VKUtil_DecodeJavaColor(uint32_t srgb) {
    // Just map [0, 255] integer colors onto [0, 1] floating-point range, it remains in sRGB color space.
    // sRGB gamma correction remains unsupported.
    static const float NormTable256[256] = {
#define NORM1(N) ((float)(N) / 255.0F)
#define NORM8(N) NORM1(N),NORM1(N+1),NORM1(N+2),NORM1(N+3),NORM1(N+4),NORM1(N+5),NORM1(N+6),NORM1(N+7)
#define NORM64(N) NORM8(N),NORM8(N+8),NORM8(N+16),NORM8(N+24),NORM8(N+32),NORM8(N+40),NORM8(N+48),NORM8(N+56)
            NORM64(0),NORM64(64),NORM64(128),NORM64(192)
    };
    Color c = {
            .r = NormTable256[(srgb >> 16) & 0xFF],
            .g = NormTable256[(srgb >>  8) & 0xFF],
            .b = NormTable256[ srgb        & 0xFF],
            .a = NormTable256[(srgb >> 24) & 0xFF]
    };
    return c;
}

void VKUtil_LogResultError(const char* string, VkResult result) {
    const char* r;
    switch (result) {
#define RESULT(T) case T: r = #T; break
        RESULT(VK_SUCCESS);
        RESULT(VK_NOT_READY);
        RESULT(VK_TIMEOUT);
        RESULT(VK_EVENT_SET);
        RESULT(VK_EVENT_RESET);
        RESULT(VK_INCOMPLETE);
        RESULT(VK_ERROR_OUT_OF_HOST_MEMORY);
        RESULT(VK_ERROR_OUT_OF_DEVICE_MEMORY);
        RESULT(VK_ERROR_INITIALIZATION_FAILED);
        RESULT(VK_ERROR_DEVICE_LOST);
        RESULT(VK_ERROR_MEMORY_MAP_FAILED);
        RESULT(VK_ERROR_LAYER_NOT_PRESENT);
        RESULT(VK_ERROR_EXTENSION_NOT_PRESENT);
        RESULT(VK_ERROR_FEATURE_NOT_PRESENT);
        RESULT(VK_ERROR_INCOMPATIBLE_DRIVER);
        RESULT(VK_ERROR_TOO_MANY_OBJECTS);
        RESULT(VK_ERROR_FORMAT_NOT_SUPPORTED);
        RESULT(VK_ERROR_FRAGMENTED_POOL);
        RESULT(VK_ERROR_UNKNOWN);
        RESULT(VK_ERROR_OUT_OF_POOL_MEMORY);
        RESULT(VK_ERROR_INVALID_EXTERNAL_HANDLE);
        RESULT(VK_ERROR_FRAGMENTATION);
        RESULT(VK_ERROR_INVALID_OPAQUE_CAPTURE_ADDRESS);
        RESULT(VK_PIPELINE_COMPILE_REQUIRED);
        RESULT(VK_ERROR_SURFACE_LOST_KHR);
        RESULT(VK_ERROR_NATIVE_WINDOW_IN_USE_KHR);
        RESULT(VK_SUBOPTIMAL_KHR);
        RESULT(VK_ERROR_OUT_OF_DATE_KHR);
        RESULT(VK_ERROR_INCOMPATIBLE_DISPLAY_KHR);
        RESULT(VK_ERROR_VALIDATION_FAILED_EXT);
        RESULT(VK_ERROR_INVALID_SHADER_NV);
        RESULT(VK_ERROR_IMAGE_USAGE_NOT_SUPPORTED_KHR);
        RESULT(VK_ERROR_VIDEO_PICTURE_LAYOUT_NOT_SUPPORTED_KHR);
        RESULT(VK_ERROR_VIDEO_PROFILE_OPERATION_NOT_SUPPORTED_KHR);
        RESULT(VK_ERROR_VIDEO_PROFILE_FORMAT_NOT_SUPPORTED_KHR);
        RESULT(VK_ERROR_VIDEO_PROFILE_CODEC_NOT_SUPPORTED_KHR);
        RESULT(VK_ERROR_VIDEO_STD_VERSION_NOT_SUPPORTED_KHR);
        RESULT(VK_ERROR_INVALID_DRM_FORMAT_MODIFIER_PLANE_LAYOUT_EXT);
        RESULT(VK_ERROR_NOT_PERMITTED_KHR);
        RESULT(VK_ERROR_FULL_SCREEN_EXCLUSIVE_MODE_LOST_EXT);
        RESULT(VK_THREAD_IDLE_KHR);
        RESULT(VK_THREAD_DONE_KHR);
        RESULT(VK_OPERATION_DEFERRED_KHR);
        RESULT(VK_OPERATION_NOT_DEFERRED_KHR);
        RESULT(VK_ERROR_INVALID_VIDEO_STD_PARAMETERS_KHR);
        RESULT(VK_ERROR_COMPRESSION_EXHAUSTED_EXT);
        RESULT(VK_ERROR_INCOMPATIBLE_SHADER_BINARY_EXT);
        default: r = "<UNKNOWN>"; break;
    }
    J2dRlsTraceLn(J2D_TRACE_ERROR, string, r);
}
