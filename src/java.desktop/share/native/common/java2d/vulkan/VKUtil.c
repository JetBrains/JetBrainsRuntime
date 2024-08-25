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

Color VKUtil_DecodeJavaColor(uint32_t color) {
    assert(sizeof(RGBAColorComponents) == sizeof(float) * 4);
    // Just map [0, 255] integer colors onto [0, 1] floating-point range, it remains in sRGB color space.
    RGBAColorComponents srgb = {
            .r = (float)((color >> 16) & 0xFF) / 255.0F,
            .g = (float)((color >>  8) & 0xFF) / 255.0F,
            .b = (float)( color        & 0xFF) / 255.0F,
            .a = (float)((color >> 24) & 0xFF) / 255.0F
    };
    // This SRGB -> LINEAR conversion implementation is taken from Khronos Data Format Specification:
    // https://registry.khronos.org/DataFormat/specs/1.3/dataformat.1.3.html#TRANSFER_SRGB_EOTF
    RGBAColorComponents linear = {
            .r = (float) (srgb.r <= 0.04045 ? srgb.r / 12.92 : pow((srgb.r + 0.055) / 1.055, 2.4)),
            .g = (float) (srgb.g <= 0.04045 ? srgb.g / 12.92 : pow((srgb.g + 0.055) / 1.055, 2.4)),
            .b = (float) (srgb.b <= 0.04045 ? srgb.b / 12.92 : pow((srgb.b + 0.055) / 1.055, 2.4)),
            .a = srgb.a
    };
    // Convert to pre-multiplied alpha.
    srgb.r *= srgb.a;
    srgb.g *= srgb.a;
    srgb.b *= srgb.a;
    linear.r *= linear.a;
    linear.g *= linear.a;
    linear.b *= linear.a;
    return (Color) { .linear = linear, .nonlinearSrgb = srgb };
}

FormatGroup VKUtil_GetFormatGroup(VkFormat format) {
#define GROUP(SIZE, ...) return ((FormatGroup) { .bytes = SIZE, __VA_ARGS__ })
    switch (format) {
        case VK_FORMAT_R4G4_UNORM_PACK8:      GROUP(1, .unorm = VK_FORMAT_R4G4_UNORM_PACK8);
        case VK_FORMAT_R4G4B4A4_UNORM_PACK16: GROUP(2, .unorm = VK_FORMAT_R4G4B4A4_UNORM_PACK16);
        case VK_FORMAT_B4G4R4A4_UNORM_PACK16: GROUP(2, .unorm = VK_FORMAT_B4G4R4A4_UNORM_PACK16);
        case VK_FORMAT_R5G6B5_UNORM_PACK16:   GROUP(2, .unorm = VK_FORMAT_R5G6B5_UNORM_PACK16);
        case VK_FORMAT_B5G6R5_UNORM_PACK16:   GROUP(2, .unorm = VK_FORMAT_B5G6R5_UNORM_PACK16);
        case VK_FORMAT_R5G5B5A1_UNORM_PACK16: GROUP(2, .unorm = VK_FORMAT_R5G5B5A1_UNORM_PACK16);
        case VK_FORMAT_B5G5R5A1_UNORM_PACK16: GROUP(2, .unorm = VK_FORMAT_B5G5R5A1_UNORM_PACK16);
        case VK_FORMAT_A1R5G5B5_UNORM_PACK16: GROUP(2, .unorm = VK_FORMAT_A1R5G5B5_UNORM_PACK16);
        case VK_FORMAT_R8_UNORM:
        case VK_FORMAT_R8_SNORM:
        case VK_FORMAT_R8_USCALED:
        case VK_FORMAT_R8_SSCALED:
        case VK_FORMAT_R8_UINT:
        case VK_FORMAT_R8_SINT:
        case VK_FORMAT_R8_SRGB:
            GROUP(1, .srgb = VK_FORMAT_R8_SRGB,
                  .unorm = VK_FORMAT_R8_UNORM, .snorm = VK_FORMAT_R8_SNORM,
                  .uscaled = VK_FORMAT_R8_USCALED, .sscaled = VK_FORMAT_R8_SSCALED,
                  .uint = VK_FORMAT_R8_UINT, .sint = VK_FORMAT_R8_SINT);
        case VK_FORMAT_R8G8_UNORM:
        case VK_FORMAT_R8G8_SNORM:
        case VK_FORMAT_R8G8_USCALED:
        case VK_FORMAT_R8G8_SSCALED:
        case VK_FORMAT_R8G8_UINT:
        case VK_FORMAT_R8G8_SINT:
        case VK_FORMAT_R8G8_SRGB:
            GROUP(2, .srgb = VK_FORMAT_R8G8_SRGB,
                  .unorm = VK_FORMAT_R8G8_UNORM, .snorm = VK_FORMAT_R8G8_SNORM,
                  .uscaled = VK_FORMAT_R8G8_USCALED, .sscaled = VK_FORMAT_R8G8_SSCALED,
                  .uint = VK_FORMAT_R8G8_UINT, .sint = VK_FORMAT_R8G8_SINT);
        case VK_FORMAT_R8G8B8_UNORM:
        case VK_FORMAT_R8G8B8_SNORM:
        case VK_FORMAT_R8G8B8_USCALED:
        case VK_FORMAT_R8G8B8_SSCALED:
        case VK_FORMAT_R8G8B8_UINT:
        case VK_FORMAT_R8G8B8_SINT:
        case VK_FORMAT_R8G8B8_SRGB:
            GROUP(3, .srgb = VK_FORMAT_R8G8B8_SRGB,
                  .unorm = VK_FORMAT_R8G8B8_UNORM, .snorm = VK_FORMAT_R8G8B8_SNORM,
                  .uscaled = VK_FORMAT_R8G8B8_USCALED, .sscaled = VK_FORMAT_R8G8B8_SSCALED,
                  .uint = VK_FORMAT_R8G8B8_UINT, .sint = VK_FORMAT_R8G8B8_SINT);
        case VK_FORMAT_B8G8R8_UNORM:
        case VK_FORMAT_B8G8R8_SNORM:
        case VK_FORMAT_B8G8R8_USCALED:
        case VK_FORMAT_B8G8R8_SSCALED:
        case VK_FORMAT_B8G8R8_UINT:
        case VK_FORMAT_B8G8R8_SINT:
        case VK_FORMAT_B8G8R8_SRGB:
            GROUP(3, .srgb = VK_FORMAT_B8G8R8_SRGB,
                  .unorm = VK_FORMAT_B8G8R8_UNORM, .snorm = VK_FORMAT_B8G8R8_SNORM,
                  .uscaled = VK_FORMAT_B8G8R8_USCALED, .sscaled = VK_FORMAT_B8G8R8_SSCALED,
                  .uint = VK_FORMAT_B8G8R8_UINT, .sint = VK_FORMAT_B8G8R8_SINT);
        case VK_FORMAT_R8G8B8A8_UNORM:
        case VK_FORMAT_R8G8B8A8_SNORM:
        case VK_FORMAT_R8G8B8A8_USCALED:
        case VK_FORMAT_R8G8B8A8_SSCALED:
        case VK_FORMAT_R8G8B8A8_UINT:
        case VK_FORMAT_R8G8B8A8_SINT:
        case VK_FORMAT_R8G8B8A8_SRGB:
            GROUP(4, .srgb = VK_FORMAT_R8G8B8A8_SRGB,
                  .unorm = VK_FORMAT_R8G8B8A8_UNORM, .snorm = VK_FORMAT_R8G8B8A8_SNORM,
                  .uscaled = VK_FORMAT_R8G8B8A8_USCALED, .sscaled = VK_FORMAT_R8G8B8A8_SSCALED,
                  .uint = VK_FORMAT_R8G8B8A8_UINT, .sint = VK_FORMAT_R8G8B8A8_SINT);
        case VK_FORMAT_B8G8R8A8_UNORM:
        case VK_FORMAT_B8G8R8A8_SNORM:
        case VK_FORMAT_B8G8R8A8_USCALED:
        case VK_FORMAT_B8G8R8A8_SSCALED:
        case VK_FORMAT_B8G8R8A8_UINT:
        case VK_FORMAT_B8G8R8A8_SINT:
        case VK_FORMAT_B8G8R8A8_SRGB:
            GROUP(4, .srgb = VK_FORMAT_B8G8R8A8_SRGB,
                  .unorm = VK_FORMAT_B8G8R8A8_UNORM, .snorm = VK_FORMAT_B8G8R8A8_SNORM,
                  .uscaled = VK_FORMAT_B8G8R8A8_USCALED, .sscaled = VK_FORMAT_B8G8R8A8_SSCALED,
                  .uint = VK_FORMAT_B8G8R8A8_UINT, .sint = VK_FORMAT_B8G8R8A8_SINT);
        case VK_FORMAT_A8B8G8R8_UNORM_PACK32:
        case VK_FORMAT_A8B8G8R8_SNORM_PACK32:
        case VK_FORMAT_A8B8G8R8_USCALED_PACK32:
        case VK_FORMAT_A8B8G8R8_SSCALED_PACK32:
        case VK_FORMAT_A8B8G8R8_UINT_PACK32:
        case VK_FORMAT_A8B8G8R8_SINT_PACK32:
        case VK_FORMAT_A8B8G8R8_SRGB_PACK32:
            GROUP(4, .srgb = VK_FORMAT_A8B8G8R8_SRGB_PACK32,
                  .unorm = VK_FORMAT_A8B8G8R8_UNORM_PACK32, .snorm = VK_FORMAT_A8B8G8R8_SNORM_PACK32,
                  .uscaled = VK_FORMAT_A8B8G8R8_USCALED_PACK32, .sscaled = VK_FORMAT_A8B8G8R8_SSCALED_PACK32,
                  .uint = VK_FORMAT_A8B8G8R8_UINT_PACK32, .sint = VK_FORMAT_A8B8G8R8_SINT_PACK32);
        case VK_FORMAT_A2R10G10B10_UNORM_PACK32:
        case VK_FORMAT_A2R10G10B10_SNORM_PACK32:
        case VK_FORMAT_A2R10G10B10_USCALED_PACK32:
        case VK_FORMAT_A2R10G10B10_SSCALED_PACK32:
        case VK_FORMAT_A2R10G10B10_UINT_PACK32:
        case VK_FORMAT_A2R10G10B10_SINT_PACK32:
            GROUP(4,
                  .unorm = VK_FORMAT_A2R10G10B10_UNORM_PACK32, .snorm = VK_FORMAT_A2R10G10B10_SNORM_PACK32,
                  .uscaled = VK_FORMAT_A2R10G10B10_USCALED_PACK32, .sscaled = VK_FORMAT_A2R10G10B10_SSCALED_PACK32,
                  .uint = VK_FORMAT_A2R10G10B10_UINT_PACK32, .sint = VK_FORMAT_A2R10G10B10_SINT_PACK32);
        case VK_FORMAT_A2B10G10R10_UNORM_PACK32:
        case VK_FORMAT_A2B10G10R10_SNORM_PACK32:
        case VK_FORMAT_A2B10G10R10_USCALED_PACK32:
        case VK_FORMAT_A2B10G10R10_SSCALED_PACK32:
        case VK_FORMAT_A2B10G10R10_UINT_PACK32:
        case VK_FORMAT_A2B10G10R10_SINT_PACK32:
            GROUP(4,
                  .unorm = VK_FORMAT_A2B10G10R10_UNORM_PACK32, .snorm = VK_FORMAT_A2B10G10R10_SNORM_PACK32,
                  .uscaled = VK_FORMAT_A2B10G10R10_USCALED_PACK32, .sscaled = VK_FORMAT_A2B10G10R10_SSCALED_PACK32,
                  .uint = VK_FORMAT_A2B10G10R10_UINT_PACK32, .sint = VK_FORMAT_A2B10G10R10_SINT_PACK32);
        case VK_FORMAT_R16_UNORM:
        case VK_FORMAT_R16_SNORM:
        case VK_FORMAT_R16_USCALED:
        case VK_FORMAT_R16_SSCALED:
        case VK_FORMAT_R16_UINT:
        case VK_FORMAT_R16_SINT:
        case VK_FORMAT_R16_SFLOAT:
            GROUP(2, .sfloat = VK_FORMAT_R16_SFLOAT,
                  .unorm = VK_FORMAT_R16_UNORM, .snorm = VK_FORMAT_R16_SNORM,
                  .uscaled = VK_FORMAT_R16_USCALED, .sscaled = VK_FORMAT_R16_SSCALED,
                  .uint = VK_FORMAT_R16_UINT, .sint = VK_FORMAT_R16_SINT);
        case VK_FORMAT_R16G16_UNORM:
        case VK_FORMAT_R16G16_SNORM:
        case VK_FORMAT_R16G16_USCALED:
        case VK_FORMAT_R16G16_SSCALED:
        case VK_FORMAT_R16G16_UINT:
        case VK_FORMAT_R16G16_SINT:
        case VK_FORMAT_R16G16_SFLOAT:
            GROUP(4, .sfloat = VK_FORMAT_R16G16_SFLOAT,
                  .unorm = VK_FORMAT_R16G16_UNORM, .snorm = VK_FORMAT_R16G16_SNORM,
                  .uscaled = VK_FORMAT_R16G16_USCALED, .sscaled = VK_FORMAT_R16G16_SSCALED,
                  .uint = VK_FORMAT_R16G16_UINT, .sint = VK_FORMAT_R16G16_SINT);
        case VK_FORMAT_R16G16B16_UNORM:
        case VK_FORMAT_R16G16B16_SNORM:
        case VK_FORMAT_R16G16B16_USCALED:
        case VK_FORMAT_R16G16B16_SSCALED:
        case VK_FORMAT_R16G16B16_UINT:
        case VK_FORMAT_R16G16B16_SINT:
        case VK_FORMAT_R16G16B16_SFLOAT:
            GROUP(6, .sfloat = VK_FORMAT_R16G16B16_SFLOAT,
                  .unorm = VK_FORMAT_R16G16B16_UNORM, .snorm = VK_FORMAT_R16G16B16_SNORM,
                  .uscaled = VK_FORMAT_R16G16B16_USCALED, .sscaled = VK_FORMAT_R16G16B16_SSCALED,
                  .uint = VK_FORMAT_R16G16B16_UINT, .sint = VK_FORMAT_R16G16B16_SINT);
        case VK_FORMAT_R16G16B16A16_UNORM:
        case VK_FORMAT_R16G16B16A16_SNORM:
        case VK_FORMAT_R16G16B16A16_USCALED:
        case VK_FORMAT_R16G16B16A16_SSCALED:
        case VK_FORMAT_R16G16B16A16_UINT:
        case VK_FORMAT_R16G16B16A16_SINT:
        case VK_FORMAT_R16G16B16A16_SFLOAT:
            GROUP(8, .sfloat = VK_FORMAT_R16G16B16A16_SFLOAT,
                  .unorm = VK_FORMAT_R16G16B16A16_UNORM, .snorm = VK_FORMAT_R16G16B16A16_SNORM,
                  .uscaled = VK_FORMAT_R16G16B16A16_USCALED, .sscaled = VK_FORMAT_R16G16B16A16_SSCALED,
                  .uint = VK_FORMAT_R16G16B16A16_UINT, .sint = VK_FORMAT_R16G16B16A16_SINT);
        case VK_FORMAT_R32_UINT:
        case VK_FORMAT_R32_SINT:
        case VK_FORMAT_R32_SFLOAT:
            GROUP(4, .sfloat = VK_FORMAT_R32_SFLOAT,
                  .uint = VK_FORMAT_R32_UINT, .sint = VK_FORMAT_R32_SINT);
        case VK_FORMAT_R32G32_UINT:
        case VK_FORMAT_R32G32_SINT:
        case VK_FORMAT_R32G32_SFLOAT:
            GROUP(8, .sfloat = VK_FORMAT_R32G32_SFLOAT,
                  .uint = VK_FORMAT_R32G32_UINT, .sint = VK_FORMAT_R32G32_SINT);
        case VK_FORMAT_R32G32B32_UINT:
        case VK_FORMAT_R32G32B32_SINT:
        case VK_FORMAT_R32G32B32_SFLOAT:
            GROUP(12, .sfloat = VK_FORMAT_R32G32B32_SFLOAT,
                  .uint = VK_FORMAT_R32G32B32_UINT, .sint = VK_FORMAT_R32G32B32_SINT);
        case VK_FORMAT_R32G32B32A32_UINT:
        case VK_FORMAT_R32G32B32A32_SINT:
        case VK_FORMAT_R32G32B32A32_SFLOAT:
            GROUP(16, .sfloat = VK_FORMAT_R32G32B32A32_SFLOAT,
                  .uint = VK_FORMAT_R32G32B32A32_UINT, .sint = VK_FORMAT_R32G32B32A32_SINT);
        case VK_FORMAT_R64_UINT:
        case VK_FORMAT_R64_SINT:
        case VK_FORMAT_R64_SFLOAT:
            GROUP(8, .sfloat = VK_FORMAT_R64_SFLOAT,
                  .uint = VK_FORMAT_R64_UINT, .sint = VK_FORMAT_R64_SINT);
        case VK_FORMAT_R64G64_UINT:
        case VK_FORMAT_R64G64_SINT:
        case VK_FORMAT_R64G64_SFLOAT:
            GROUP(16, .sfloat = VK_FORMAT_R64G64_SFLOAT,
                  .uint = VK_FORMAT_R64G64_UINT, .sint = VK_FORMAT_R64G64_SINT);
        case VK_FORMAT_R64G64B64_UINT:
        case VK_FORMAT_R64G64B64_SINT:
        case VK_FORMAT_R64G64B64_SFLOAT:
            GROUP(24, .sfloat = VK_FORMAT_R64G64B64_SFLOAT,
                  .uint = VK_FORMAT_R64G64B64_UINT, .sint = VK_FORMAT_R64G64B64_SINT);
        case VK_FORMAT_R64G64B64A64_UINT:
        case VK_FORMAT_R64G64B64A64_SINT:
        case VK_FORMAT_R64G64B64A64_SFLOAT:
            GROUP(32, .sfloat = VK_FORMAT_R64G64B64A64_SFLOAT,
                  .uint = VK_FORMAT_R64G64B64A64_UINT, .sint = VK_FORMAT_R64G64B64A64_SINT);
        case VK_FORMAT_R10X6_UNORM_PACK16:                 GROUP(2, .unorm = VK_FORMAT_R10X6_UNORM_PACK16);
        case VK_FORMAT_R10X6G10X6_UNORM_2PACK16:           GROUP(4, .unorm = VK_FORMAT_R10X6G10X6_UNORM_2PACK16);
        case VK_FORMAT_R10X6G10X6B10X6A10X6_UNORM_4PACK16: GROUP(8, .unorm = VK_FORMAT_R10X6G10X6B10X6A10X6_UNORM_4PACK16);
        case VK_FORMAT_R12X4_UNORM_PACK16:                 GROUP(2, .unorm = VK_FORMAT_R12X4_UNORM_PACK16);
        case VK_FORMAT_R12X4G12X4_UNORM_2PACK16:           GROUP(4, .unorm = VK_FORMAT_R12X4G12X4_UNORM_2PACK16);
        case VK_FORMAT_R12X4G12X4B12X4A12X4_UNORM_4PACK16: GROUP(8, .unorm = VK_FORMAT_R12X4G12X4B12X4A12X4_UNORM_4PACK16);
        case VK_FORMAT_A4R4G4B4_UNORM_PACK16:              GROUP(2, .unorm = VK_FORMAT_A4R4G4B4_UNORM_PACK16);
        case VK_FORMAT_A4B4G4R4_UNORM_PACK16:              GROUP(2, .unorm = VK_FORMAT_A4B4G4R4_UNORM_PACK16);
        case VK_FORMAT_A1B5G5R5_UNORM_PACK16_KHR:          GROUP(2, .unorm = VK_FORMAT_A1B5G5R5_UNORM_PACK16_KHR);
        case VK_FORMAT_A8_UNORM_KHR:                       GROUP(1, .unorm = VK_FORMAT_A8_UNORM_KHR);
        default: GROUP(0);
    }
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
