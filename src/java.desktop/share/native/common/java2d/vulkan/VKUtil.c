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

#include <math.h>
#include "VKUtil.h"

Color Color_DecodeFromJava(unsigned int srgb) {
    // Just map [0, 255] integer colors into [0, 1] floating-point range, it remains in SRGB color space.
    Color c = {
            .r = (float)((srgb >> 16) & 0xFF) / 255.0F,
            .g = (float)((srgb >>  8) & 0xFF) / 255.0F,
            .b = (float)( srgb        & 0xFF) / 255.0F,
            .a = (float)((srgb >> 24) & 0xFF) / 255.0F
    };
    // This SRGB -> LINEAR conversion implementation is taken from Khronos Data Format Specification:
    // https://registry.khronos.org/DataFormat/specs/1.3/dataformat.1.3.html#TRANSFER_SRGB_EOTF
    c.r = (float) (c.r <= 0.04045 ? c.r / 12.92 : pow((c.r + 0.055) / 1.055, 2.4));
    c.g = (float) (c.g <= 0.04045 ? c.g / 12.92 : pow((c.g + 0.055) / 1.055, 2.4));
    c.b = (float) (c.b <= 0.04045 ? c.b / 12.92 : pow((c.b + 0.055) / 1.055, 2.4));
    return c;
}

void VKUtil_LogResultError(const char* string, VkResult result) {
    const char* r;
    switch (result) {
        case VK_SUCCESS: r = "VK_SUCCESS"; break;
        case VK_NOT_READY: r = "VK_NOT_READY"; break;
        case VK_TIMEOUT: r = "VK_TIMEOUT"; break;
        case VK_EVENT_SET: r = "VK_EVENT_SET"; break;
        case VK_EVENT_RESET: r = "VK_EVENT_RESET"; break;
        case VK_INCOMPLETE: r = "VK_INCOMPLETE"; break;
        case VK_ERROR_OUT_OF_HOST_MEMORY: r = "VK_ERROR_OUT_OF_HOST_MEMORY"; break;
        case VK_ERROR_OUT_OF_DEVICE_MEMORY: r = "VK_ERROR_OUT_OF_DEVICE_MEMORY"; break;
        case VK_ERROR_INITIALIZATION_FAILED: r = "VK_ERROR_INITIALIZATION_FAILED"; break;
        case VK_ERROR_DEVICE_LOST: r = "VK_ERROR_DEVICE_LOST"; break;
        case VK_ERROR_MEMORY_MAP_FAILED: r = "VK_ERROR_MEMORY_MAP_FAILED"; break;
        case VK_ERROR_LAYER_NOT_PRESENT: r = "VK_ERROR_LAYER_NOT_PRESENT"; break;
        case VK_ERROR_EXTENSION_NOT_PRESENT: r = "VK_ERROR_EXTENSION_NOT_PRESENT"; break;
        case VK_ERROR_FEATURE_NOT_PRESENT: r = "VK_ERROR_FEATURE_NOT_PRESENT"; break;
        case VK_ERROR_INCOMPATIBLE_DRIVER: r = "VK_ERROR_INCOMPATIBLE_DRIVER"; break;
        case VK_ERROR_TOO_MANY_OBJECTS: r = "VK_ERROR_TOO_MANY_OBJECTS"; break;
        case VK_ERROR_FORMAT_NOT_SUPPORTED: r = "VK_ERROR_FORMAT_NOT_SUPPORTED"; break;
        case VK_ERROR_FRAGMENTED_POOL: r = "VK_ERROR_FRAGMENTED_POOL"; break;
        case VK_ERROR_UNKNOWN: r = "VK_ERROR_UNKNOWN"; break;
        case VK_ERROR_OUT_OF_POOL_MEMORY: r = "VK_ERROR_OUT_OF_POOL_MEMORY"; break;
        case VK_ERROR_INVALID_EXTERNAL_HANDLE: r = "VK_ERROR_INVALID_EXTERNAL_HANDLE"; break;
        case VK_ERROR_FRAGMENTATION: r = "VK_ERROR_FRAGMENTATION"; break;
        case VK_ERROR_INVALID_OPAQUE_CAPTURE_ADDRESS: r = "VK_ERROR_INVALID_OPAQUE_CAPTURE_ADDRESS"; break;
        case VK_PIPELINE_COMPILE_REQUIRED: r = "VK_PIPELINE_COMPILE_REQUIRED"; break;
        case VK_ERROR_SURFACE_LOST_KHR: r = "VK_ERROR_SURFACE_LOST_KHR"; break;
        case VK_ERROR_NATIVE_WINDOW_IN_USE_KHR: r = "VK_ERROR_NATIVE_WINDOW_IN_USE_KHR"; break;
        case VK_SUBOPTIMAL_KHR: r = "VK_SUBOPTIMAL_KHR"; break;
        case VK_ERROR_OUT_OF_DATE_KHR: r = "VK_ERROR_OUT_OF_DATE_KHR"; break;
        case VK_ERROR_INCOMPATIBLE_DISPLAY_KHR: r = "VK_ERROR_INCOMPATIBLE_DISPLAY_KHR"; break;
        case VK_ERROR_VALIDATION_FAILED_EXT: r = "VK_ERROR_VALIDATION_FAILED_EXT"; break;
        case VK_ERROR_INVALID_SHADER_NV: r = "VK_ERROR_INVALID_SHADER_NV"; break;
        case VK_ERROR_IMAGE_USAGE_NOT_SUPPORTED_KHR: r = "VK_ERROR_IMAGE_USAGE_NOT_SUPPORTED_KHR"; break;
        case VK_ERROR_VIDEO_PICTURE_LAYOUT_NOT_SUPPORTED_KHR: r = "VK_ERROR_VIDEO_PICTURE_LAYOUT_NOT_SUPPORTED_KHR"; break;
        case VK_ERROR_VIDEO_PROFILE_OPERATION_NOT_SUPPORTED_KHR: r = "VK_ERROR_VIDEO_PROFILE_OPERATION_NOT_SUPPORTED_KHR"; break;
        case VK_ERROR_VIDEO_PROFILE_FORMAT_NOT_SUPPORTED_KHR: r = "VK_ERROR_VIDEO_PROFILE_FORMAT_NOT_SUPPORTED_KHR"; break;
        case VK_ERROR_VIDEO_PROFILE_CODEC_NOT_SUPPORTED_KHR: r = "VK_ERROR_VIDEO_PROFILE_CODEC_NOT_SUPPORTED_KHR"; break;
        case VK_ERROR_VIDEO_STD_VERSION_NOT_SUPPORTED_KHR: r = "VK_ERROR_VIDEO_STD_VERSION_NOT_SUPPORTED_KHR"; break;
        case VK_ERROR_INVALID_DRM_FORMAT_MODIFIER_PLANE_LAYOUT_EXT: r = "VK_ERROR_INVALID_DRM_FORMAT_MODIFIER_PLANE_LAYOUT_EXT"; break;
        case VK_ERROR_NOT_PERMITTED_KHR: r = "VK_ERROR_NOT_PERMITTED_KHR"; break;
        case VK_ERROR_FULL_SCREEN_EXCLUSIVE_MODE_LOST_EXT: r = "VK_ERROR_FULL_SCREEN_EXCLUSIVE_MODE_LOST_EXT"; break;
        case VK_THREAD_IDLE_KHR: r = "VK_THREAD_IDLE_KHR"; break;
        case VK_THREAD_DONE_KHR: r = "VK_THREAD_DONE_KHR"; break;
        case VK_OPERATION_DEFERRED_KHR: r = "VK_OPERATION_DEFERRED_KHR"; break;
        case VK_OPERATION_NOT_DEFERRED_KHR: r = "VK_OPERATION_NOT_DEFERRED_KHR"; break;
        case VK_ERROR_INVALID_VIDEO_STD_PARAMETERS_KHR: r = "VK_ERROR_INVALID_VIDEO_STD_PARAMETERS_KHR"; break;
        case VK_ERROR_COMPRESSION_EXHAUSTED_EXT: r = "VK_ERROR_COMPRESSION_EXHAUSTED_EXT"; break;
        case VK_ERROR_INCOMPATIBLE_SHADER_BINARY_EXT: r = "VK_ERROR_INCOMPATIBLE_SHADER_BINARY_EXT"; break;
        default: r = "<UNKNOWN>"; break;
    }
    J2dRlsTraceLn1(J2D_TRACE_ERROR, string, r)
}
