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
#include <math.h>
#include "VKUtil.h"

static RGBA VKUtil_ConvertAlphaType(RGBA rgba, AlphaType newAlphaType) {
    float mul = rgba.a;
    if (newAlphaType == ALPHA_TYPE_STRAIGHT && mul != 0.0f) mul = 1.0f / mul;
    rgba.r *= mul;
    rgba.g *= mul;
    rgba.b *= mul;
    return rgba;
}

Color VKUtil_DecodeJavaColor(uint32_t color, AlphaType alphaType) {
    assert(sizeof(RGBA) == sizeof(float) * 4);
    // Just map [0, 255] integer colors onto [0, 1] floating-point range, it remains in sRGB color space.
    // sRGB gamma correction remains unsupported.
    static const float NormTable256[256] = {
#define NORM1(N) ((float)(N) / 255.0F)
#define NORM8(N) NORM1(N),NORM1(N+1),NORM1(N+2),NORM1(N+3),NORM1(N+4),NORM1(N+5),NORM1(N+6),NORM1(N+7)
#define NORM64(N) NORM8(N),NORM8(N+8),NORM8(N+16),NORM8(N+24),NORM8(N+32),NORM8(N+40),NORM8(N+48),NORM8(N+56)
            NORM64(0),NORM64(64),NORM64(128),NORM64(192)
    };
    // Decode color in its original type.
    static const RGBA NAN_RGBA = {.r = NAN, .g = NAN, .b = NAN, .a = NAN};
    Color result = {{ NAN_RGBA, NAN_RGBA }};
    result.values[alphaType] = (RGBA) {
        .r = NormTable256[(color >> 16) & 0xFF],
        .g = NormTable256[(color >>  8) & 0xFF],
        .b = NormTable256[ color        & 0xFF],
        .a = NormTable256[(color >> 24) & 0xFF]
    };
    return result;
}

RGBA VKUtil_GetRGBA(Color color, AlphaType alphaType) {
    if (isnan(color.values[alphaType].a)) {
        AlphaType otherAlphaType = alphaType ^ 1;
        assert(!isnan(color.values[otherAlphaType].a));
        color.values[alphaType] = VKUtil_ConvertAlphaType(color.values[otherAlphaType], alphaType);
    }
    return color.values[alphaType];
}

uint32_t VKUtil_Log2(uint64_t i) {
    // https://graphics.stanford.edu/~seander/bithacks.html#IntegerLogLookup
    static const char LogTable256[256] = {
#define LT(n) n, n, n, n, n, n, n, n, n, n, n, n, n, n, n, n
            -1, 0, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3,
            LT(4), LT(5), LT(5), LT(6), LT(6), LT(6), LT(6),
            LT(7), LT(7), LT(7), LT(7), LT(7), LT(7), LT(7), LT(7) };
    register uint32_t t;
    if ((t = i >> 56)) return 56 + LogTable256[t];
    if ((t = i >> 48)) return 48 + LogTable256[t];
    if ((t = i >> 40)) return 40 + LogTable256[t];
    if ((t = i >> 32)) return 32 + LogTable256[t];
    if ((t = i >> 24)) return 24 + LogTable256[t];
    if ((t = i >> 16)) return 16 + LogTable256[t];
    if ((t = i >>  8)) return  8 + LogTable256[t];
    return LogTable256[i & 0xFF];
}

FormatGroup VKUtil_GetFormatGroup(VkFormat format) {
#define GROUP(ASPECT, SIZE, ...) return ((FormatGroup) { \
    .bytes = SIZE, .aspect = VK_IMAGE_ASPECT_ ## ASPECT ## _BIT, .aliases[FORMAT_ALIAS_ORIGINAL] = format, __VA_ARGS__ })
    switch (format) {
        case VK_FORMAT_R4G4_UNORM_PACK8:      GROUP(COLOR, 1, .aliases[FORMAT_ALIAS_UNORM] = VK_FORMAT_R4G4_UNORM_PACK8);
        case VK_FORMAT_R4G4B4A4_UNORM_PACK16: GROUP(COLOR, 2, .aliases[FORMAT_ALIAS_UNORM] = VK_FORMAT_R4G4B4A4_UNORM_PACK16);
        case VK_FORMAT_B4G4R4A4_UNORM_PACK16: GROUP(COLOR, 2, .aliases[FORMAT_ALIAS_UNORM] = VK_FORMAT_B4G4R4A4_UNORM_PACK16);
        case VK_FORMAT_R5G6B5_UNORM_PACK16:   GROUP(COLOR, 2, .aliases[FORMAT_ALIAS_UNORM] = VK_FORMAT_R5G6B5_UNORM_PACK16);
        case VK_FORMAT_B5G6R5_UNORM_PACK16:   GROUP(COLOR, 2, .aliases[FORMAT_ALIAS_UNORM] = VK_FORMAT_B5G6R5_UNORM_PACK16);
        case VK_FORMAT_R5G5B5A1_UNORM_PACK16: GROUP(COLOR, 2, .aliases[FORMAT_ALIAS_UNORM] = VK_FORMAT_R5G5B5A1_UNORM_PACK16);
        case VK_FORMAT_B5G5R5A1_UNORM_PACK16: GROUP(COLOR, 2, .aliases[FORMAT_ALIAS_UNORM] = VK_FORMAT_B5G5R5A1_UNORM_PACK16);
        case VK_FORMAT_A1R5G5B5_UNORM_PACK16: GROUP(COLOR, 2, .aliases[FORMAT_ALIAS_UNORM] = VK_FORMAT_A1R5G5B5_UNORM_PACK16);
        case VK_FORMAT_R8_UNORM:
        case VK_FORMAT_R8_SNORM:
        case VK_FORMAT_R8_USCALED:
        case VK_FORMAT_R8_SSCALED:
        case VK_FORMAT_R8_UINT:
        case VK_FORMAT_R8_SINT:
        case VK_FORMAT_R8_SRGB:
            GROUP(COLOR, 1, .aliases[FORMAT_ALIAS_SRGB] = VK_FORMAT_R8_SRGB,
                  .aliases[FORMAT_ALIAS_UNORM] = VK_FORMAT_R8_UNORM, .aliases[FORMAT_ALIAS_SNORM] = VK_FORMAT_R8_SNORM,
                  .aliases[FORMAT_ALIAS_USCALED] = VK_FORMAT_R8_USCALED, .aliases[FORMAT_ALIAS_SSCALED] = VK_FORMAT_R8_SSCALED,
                  .aliases[FORMAT_ALIAS_UINT] = VK_FORMAT_R8_UINT, .aliases[FORMAT_ALIAS_SINT] = VK_FORMAT_R8_SINT);
        case VK_FORMAT_R8G8_UNORM:
        case VK_FORMAT_R8G8_SNORM:
        case VK_FORMAT_R8G8_USCALED:
        case VK_FORMAT_R8G8_SSCALED:
        case VK_FORMAT_R8G8_UINT:
        case VK_FORMAT_R8G8_SINT:
        case VK_FORMAT_R8G8_SRGB:
            GROUP(COLOR, 2, .aliases[FORMAT_ALIAS_SRGB] = VK_FORMAT_R8G8_SRGB,
                  .aliases[FORMAT_ALIAS_UNORM] = VK_FORMAT_R8G8_UNORM, .aliases[FORMAT_ALIAS_SNORM] = VK_FORMAT_R8G8_SNORM,
                  .aliases[FORMAT_ALIAS_USCALED] = VK_FORMAT_R8G8_USCALED, .aliases[FORMAT_ALIAS_SSCALED] = VK_FORMAT_R8G8_SSCALED,
                  .aliases[FORMAT_ALIAS_UINT] = VK_FORMAT_R8G8_UINT, .aliases[FORMAT_ALIAS_SINT] = VK_FORMAT_R8G8_SINT);
        case VK_FORMAT_R8G8B8_UNORM:
        case VK_FORMAT_R8G8B8_SNORM:
        case VK_FORMAT_R8G8B8_USCALED:
        case VK_FORMAT_R8G8B8_SSCALED:
        case VK_FORMAT_R8G8B8_UINT:
        case VK_FORMAT_R8G8B8_SINT:
        case VK_FORMAT_R8G8B8_SRGB:
            GROUP(COLOR, 3, .aliases[FORMAT_ALIAS_SRGB] = VK_FORMAT_R8G8B8_SRGB,
                  .aliases[FORMAT_ALIAS_UNORM] = VK_FORMAT_R8G8B8_UNORM, .aliases[FORMAT_ALIAS_SNORM] = VK_FORMAT_R8G8B8_SNORM,
                  .aliases[FORMAT_ALIAS_USCALED] = VK_FORMAT_R8G8B8_USCALED, .aliases[FORMAT_ALIAS_SSCALED] = VK_FORMAT_R8G8B8_SSCALED,
                  .aliases[FORMAT_ALIAS_UINT] = VK_FORMAT_R8G8B8_UINT, .aliases[FORMAT_ALIAS_SINT] = VK_FORMAT_R8G8B8_SINT);
        case VK_FORMAT_B8G8R8_UNORM:
        case VK_FORMAT_B8G8R8_SNORM:
        case VK_FORMAT_B8G8R8_USCALED:
        case VK_FORMAT_B8G8R8_SSCALED:
        case VK_FORMAT_B8G8R8_UINT:
        case VK_FORMAT_B8G8R8_SINT:
        case VK_FORMAT_B8G8R8_SRGB:
            GROUP(COLOR, 3, .aliases[FORMAT_ALIAS_SRGB] = VK_FORMAT_B8G8R8_SRGB,
                  .aliases[FORMAT_ALIAS_UNORM] = VK_FORMAT_B8G8R8_UNORM, .aliases[FORMAT_ALIAS_SNORM] = VK_FORMAT_B8G8R8_SNORM,
                  .aliases[FORMAT_ALIAS_USCALED] = VK_FORMAT_B8G8R8_USCALED, .aliases[FORMAT_ALIAS_SSCALED] = VK_FORMAT_B8G8R8_SSCALED,
                  .aliases[FORMAT_ALIAS_UINT] = VK_FORMAT_B8G8R8_UINT, .aliases[FORMAT_ALIAS_SINT] = VK_FORMAT_B8G8R8_SINT);
        case VK_FORMAT_R8G8B8A8_UNORM:
        case VK_FORMAT_R8G8B8A8_SNORM:
        case VK_FORMAT_R8G8B8A8_USCALED:
        case VK_FORMAT_R8G8B8A8_SSCALED:
        case VK_FORMAT_R8G8B8A8_UINT:
        case VK_FORMAT_R8G8B8A8_SINT:
        case VK_FORMAT_R8G8B8A8_SRGB:
            GROUP(COLOR, 4, .aliases[FORMAT_ALIAS_SRGB] = VK_FORMAT_R8G8B8A8_SRGB,
                  .aliases[FORMAT_ALIAS_UNORM] = VK_FORMAT_R8G8B8A8_UNORM, .aliases[FORMAT_ALIAS_SNORM] = VK_FORMAT_R8G8B8A8_SNORM,
                  .aliases[FORMAT_ALIAS_USCALED] = VK_FORMAT_R8G8B8A8_USCALED, .aliases[FORMAT_ALIAS_SSCALED] = VK_FORMAT_R8G8B8A8_SSCALED,
                  .aliases[FORMAT_ALIAS_UINT] = VK_FORMAT_R8G8B8A8_UINT, .aliases[FORMAT_ALIAS_SINT] = VK_FORMAT_R8G8B8A8_SINT);
        case VK_FORMAT_B8G8R8A8_UNORM:
        case VK_FORMAT_B8G8R8A8_SNORM:
        case VK_FORMAT_B8G8R8A8_USCALED:
        case VK_FORMAT_B8G8R8A8_SSCALED:
        case VK_FORMAT_B8G8R8A8_UINT:
        case VK_FORMAT_B8G8R8A8_SINT:
        case VK_FORMAT_B8G8R8A8_SRGB:
            GROUP(COLOR, 4, .aliases[FORMAT_ALIAS_SRGB] = VK_FORMAT_B8G8R8A8_SRGB,
                  .aliases[FORMAT_ALIAS_UNORM] = VK_FORMAT_B8G8R8A8_UNORM, .aliases[FORMAT_ALIAS_SNORM] = VK_FORMAT_B8G8R8A8_SNORM,
                  .aliases[FORMAT_ALIAS_USCALED] = VK_FORMAT_B8G8R8A8_USCALED, .aliases[FORMAT_ALIAS_SSCALED] = VK_FORMAT_B8G8R8A8_SSCALED,
                  .aliases[FORMAT_ALIAS_UINT] = VK_FORMAT_B8G8R8A8_UINT, .aliases[FORMAT_ALIAS_SINT] = VK_FORMAT_B8G8R8A8_SINT);
        case VK_FORMAT_A8B8G8R8_UNORM_PACK32:
        case VK_FORMAT_A8B8G8R8_SNORM_PACK32:
        case VK_FORMAT_A8B8G8R8_USCALED_PACK32:
        case VK_FORMAT_A8B8G8R8_SSCALED_PACK32:
        case VK_FORMAT_A8B8G8R8_UINT_PACK32:
        case VK_FORMAT_A8B8G8R8_SINT_PACK32:
        case VK_FORMAT_A8B8G8R8_SRGB_PACK32:
            GROUP(COLOR, 4, .aliases[FORMAT_ALIAS_SRGB] = VK_FORMAT_A8B8G8R8_SRGB_PACK32,
                  .aliases[FORMAT_ALIAS_UNORM] = VK_FORMAT_A8B8G8R8_UNORM_PACK32, .aliases[FORMAT_ALIAS_SNORM] = VK_FORMAT_A8B8G8R8_SNORM_PACK32,
                  .aliases[FORMAT_ALIAS_USCALED] = VK_FORMAT_A8B8G8R8_USCALED_PACK32, .aliases[FORMAT_ALIAS_SSCALED] = VK_FORMAT_A8B8G8R8_SSCALED_PACK32,
                  .aliases[FORMAT_ALIAS_UINT] = VK_FORMAT_A8B8G8R8_UINT_PACK32, .aliases[FORMAT_ALIAS_SINT] = VK_FORMAT_A8B8G8R8_SINT_PACK32);
        case VK_FORMAT_A2R10G10B10_UNORM_PACK32:
        case VK_FORMAT_A2R10G10B10_SNORM_PACK32:
        case VK_FORMAT_A2R10G10B10_USCALED_PACK32:
        case VK_FORMAT_A2R10G10B10_SSCALED_PACK32:
        case VK_FORMAT_A2R10G10B10_UINT_PACK32:
        case VK_FORMAT_A2R10G10B10_SINT_PACK32:
            GROUP(COLOR, 4,
                  .aliases[FORMAT_ALIAS_UNORM] = VK_FORMAT_A2R10G10B10_UNORM_PACK32, .aliases[FORMAT_ALIAS_SNORM] = VK_FORMAT_A2R10G10B10_SNORM_PACK32,
                  .aliases[FORMAT_ALIAS_USCALED] = VK_FORMAT_A2R10G10B10_USCALED_PACK32, .aliases[FORMAT_ALIAS_SSCALED] = VK_FORMAT_A2R10G10B10_SSCALED_PACK32,
                  .aliases[FORMAT_ALIAS_UINT] = VK_FORMAT_A2R10G10B10_UINT_PACK32, .aliases[FORMAT_ALIAS_SINT] = VK_FORMAT_A2R10G10B10_SINT_PACK32);
        case VK_FORMAT_A2B10G10R10_UNORM_PACK32:
        case VK_FORMAT_A2B10G10R10_SNORM_PACK32:
        case VK_FORMAT_A2B10G10R10_USCALED_PACK32:
        case VK_FORMAT_A2B10G10R10_SSCALED_PACK32:
        case VK_FORMAT_A2B10G10R10_UINT_PACK32:
        case VK_FORMAT_A2B10G10R10_SINT_PACK32:
            GROUP(COLOR, 4,
                  .aliases[FORMAT_ALIAS_UNORM] = VK_FORMAT_A2B10G10R10_UNORM_PACK32, .aliases[FORMAT_ALIAS_SNORM] = VK_FORMAT_A2B10G10R10_SNORM_PACK32,
                  .aliases[FORMAT_ALIAS_USCALED] = VK_FORMAT_A2B10G10R10_USCALED_PACK32, .aliases[FORMAT_ALIAS_SSCALED] = VK_FORMAT_A2B10G10R10_SSCALED_PACK32,
                  .aliases[FORMAT_ALIAS_UINT] = VK_FORMAT_A2B10G10R10_UINT_PACK32, .aliases[FORMAT_ALIAS_SINT] = VK_FORMAT_A2B10G10R10_SINT_PACK32);
        case VK_FORMAT_R16_UNORM:
        case VK_FORMAT_R16_SNORM:
        case VK_FORMAT_R16_USCALED:
        case VK_FORMAT_R16_SSCALED:
        case VK_FORMAT_R16_UINT:
        case VK_FORMAT_R16_SINT:
        case VK_FORMAT_R16_SFLOAT:
            GROUP(COLOR, 2, .aliases[FORMAT_ALIAS_SFLOAT] = VK_FORMAT_R16_SFLOAT,
                  .aliases[FORMAT_ALIAS_UNORM] = VK_FORMAT_R16_UNORM, .aliases[FORMAT_ALIAS_SNORM] = VK_FORMAT_R16_SNORM,
                  .aliases[FORMAT_ALIAS_USCALED] = VK_FORMAT_R16_USCALED, .aliases[FORMAT_ALIAS_SSCALED] = VK_FORMAT_R16_SSCALED,
                  .aliases[FORMAT_ALIAS_UINT] = VK_FORMAT_R16_UINT, .aliases[FORMAT_ALIAS_SINT] = VK_FORMAT_R16_SINT);
        case VK_FORMAT_R16G16_UNORM:
        case VK_FORMAT_R16G16_SNORM:
        case VK_FORMAT_R16G16_USCALED:
        case VK_FORMAT_R16G16_SSCALED:
        case VK_FORMAT_R16G16_UINT:
        case VK_FORMAT_R16G16_SINT:
        case VK_FORMAT_R16G16_SFLOAT:
            GROUP(COLOR, 4, .aliases[FORMAT_ALIAS_SFLOAT] = VK_FORMAT_R16G16_SFLOAT,
                  .aliases[FORMAT_ALIAS_UNORM] = VK_FORMAT_R16G16_UNORM, .aliases[FORMAT_ALIAS_SNORM] = VK_FORMAT_R16G16_SNORM,
                  .aliases[FORMAT_ALIAS_USCALED] = VK_FORMAT_R16G16_USCALED, .aliases[FORMAT_ALIAS_SSCALED] = VK_FORMAT_R16G16_SSCALED,
                  .aliases[FORMAT_ALIAS_UINT] = VK_FORMAT_R16G16_UINT, .aliases[FORMAT_ALIAS_SINT] = VK_FORMAT_R16G16_SINT);
        case VK_FORMAT_R16G16B16_UNORM:
        case VK_FORMAT_R16G16B16_SNORM:
        case VK_FORMAT_R16G16B16_USCALED:
        case VK_FORMAT_R16G16B16_SSCALED:
        case VK_FORMAT_R16G16B16_UINT:
        case VK_FORMAT_R16G16B16_SINT:
        case VK_FORMAT_R16G16B16_SFLOAT:
            GROUP(COLOR, 6, .aliases[FORMAT_ALIAS_SFLOAT] = VK_FORMAT_R16G16B16_SFLOAT,
                  .aliases[FORMAT_ALIAS_UNORM] = VK_FORMAT_R16G16B16_UNORM, .aliases[FORMAT_ALIAS_SNORM] = VK_FORMAT_R16G16B16_SNORM,
                  .aliases[FORMAT_ALIAS_USCALED] = VK_FORMAT_R16G16B16_USCALED, .aliases[FORMAT_ALIAS_SSCALED] = VK_FORMAT_R16G16B16_SSCALED,
                  .aliases[FORMAT_ALIAS_UINT] = VK_FORMAT_R16G16B16_UINT, .aliases[FORMAT_ALIAS_SINT] = VK_FORMAT_R16G16B16_SINT);
        case VK_FORMAT_R16G16B16A16_UNORM:
        case VK_FORMAT_R16G16B16A16_SNORM:
        case VK_FORMAT_R16G16B16A16_USCALED:
        case VK_FORMAT_R16G16B16A16_SSCALED:
        case VK_FORMAT_R16G16B16A16_UINT:
        case VK_FORMAT_R16G16B16A16_SINT:
        case VK_FORMAT_R16G16B16A16_SFLOAT:
            GROUP(COLOR, 8, .aliases[FORMAT_ALIAS_SFLOAT] = VK_FORMAT_R16G16B16A16_SFLOAT,
                  .aliases[FORMAT_ALIAS_UNORM] = VK_FORMAT_R16G16B16A16_UNORM, .aliases[FORMAT_ALIAS_SNORM] = VK_FORMAT_R16G16B16A16_SNORM,
                  .aliases[FORMAT_ALIAS_USCALED] = VK_FORMAT_R16G16B16A16_USCALED, .aliases[FORMAT_ALIAS_SSCALED] = VK_FORMAT_R16G16B16A16_SSCALED,
                  .aliases[FORMAT_ALIAS_UINT] = VK_FORMAT_R16G16B16A16_UINT, .aliases[FORMAT_ALIAS_SINT] = VK_FORMAT_R16G16B16A16_SINT);
        case VK_FORMAT_R32_UINT:
        case VK_FORMAT_R32_SINT:
        case VK_FORMAT_R32_SFLOAT:
            GROUP(COLOR, 4, .aliases[FORMAT_ALIAS_SFLOAT] = VK_FORMAT_R32_SFLOAT,
                  .aliases[FORMAT_ALIAS_UINT] = VK_FORMAT_R32_UINT, .aliases[FORMAT_ALIAS_SINT] = VK_FORMAT_R32_SINT);
        case VK_FORMAT_R32G32_UINT:
        case VK_FORMAT_R32G32_SINT:
        case VK_FORMAT_R32G32_SFLOAT:
            GROUP(COLOR, 8, .aliases[FORMAT_ALIAS_SFLOAT] = VK_FORMAT_R32G32_SFLOAT,
                  .aliases[FORMAT_ALIAS_UINT] = VK_FORMAT_R32G32_UINT, .aliases[FORMAT_ALIAS_SINT] = VK_FORMAT_R32G32_SINT);
        case VK_FORMAT_R32G32B32_UINT:
        case VK_FORMAT_R32G32B32_SINT:
        case VK_FORMAT_R32G32B32_SFLOAT:
            GROUP(COLOR, 12, .aliases[FORMAT_ALIAS_SFLOAT] = VK_FORMAT_R32G32B32_SFLOAT,
                  .aliases[FORMAT_ALIAS_UINT] = VK_FORMAT_R32G32B32_UINT, .aliases[FORMAT_ALIAS_SINT] = VK_FORMAT_R32G32B32_SINT);
        case VK_FORMAT_R32G32B32A32_UINT:
        case VK_FORMAT_R32G32B32A32_SINT:
        case VK_FORMAT_R32G32B32A32_SFLOAT:
            GROUP(COLOR, 16, .aliases[FORMAT_ALIAS_SFLOAT] = VK_FORMAT_R32G32B32A32_SFLOAT,
                  .aliases[FORMAT_ALIAS_UINT] = VK_FORMAT_R32G32B32A32_UINT, .aliases[FORMAT_ALIAS_SINT] = VK_FORMAT_R32G32B32A32_SINT);
        case VK_FORMAT_R64_UINT:
        case VK_FORMAT_R64_SINT:
        case VK_FORMAT_R64_SFLOAT:
            GROUP(COLOR, 8, .aliases[FORMAT_ALIAS_SFLOAT] = VK_FORMAT_R64_SFLOAT,
                  .aliases[FORMAT_ALIAS_UINT] = VK_FORMAT_R64_UINT, .aliases[FORMAT_ALIAS_SINT] = VK_FORMAT_R64_SINT);
        case VK_FORMAT_R64G64_UINT:
        case VK_FORMAT_R64G64_SINT:
        case VK_FORMAT_R64G64_SFLOAT:
            GROUP(COLOR, 16, .aliases[FORMAT_ALIAS_SFLOAT] = VK_FORMAT_R64G64_SFLOAT,
                  .aliases[FORMAT_ALIAS_UINT] = VK_FORMAT_R64G64_UINT, .aliases[FORMAT_ALIAS_SINT] = VK_FORMAT_R64G64_SINT);
        case VK_FORMAT_R64G64B64_UINT:
        case VK_FORMAT_R64G64B64_SINT:
        case VK_FORMAT_R64G64B64_SFLOAT:
            GROUP(COLOR, 24, .aliases[FORMAT_ALIAS_SFLOAT] = VK_FORMAT_R64G64B64_SFLOAT,
                  .aliases[FORMAT_ALIAS_UINT] = VK_FORMAT_R64G64B64_UINT, .aliases[FORMAT_ALIAS_SINT] = VK_FORMAT_R64G64B64_SINT);
        case VK_FORMAT_R64G64B64A64_UINT:
        case VK_FORMAT_R64G64B64A64_SINT:
        case VK_FORMAT_R64G64B64A64_SFLOAT:
            GROUP(COLOR, 32, .aliases[FORMAT_ALIAS_SFLOAT] = VK_FORMAT_R64G64B64A64_SFLOAT,
                  .aliases[FORMAT_ALIAS_UINT] = VK_FORMAT_R64G64B64A64_UINT, .aliases[FORMAT_ALIAS_SINT] = VK_FORMAT_R64G64B64A64_SINT);
        case VK_FORMAT_R10X6_UNORM_PACK16:                 GROUP(COLOR, 2, .aliases[FORMAT_ALIAS_UNORM] = VK_FORMAT_R10X6_UNORM_PACK16);
        case VK_FORMAT_R10X6G10X6_UNORM_2PACK16:           GROUP(COLOR, 4, .aliases[FORMAT_ALIAS_UNORM] = VK_FORMAT_R10X6G10X6_UNORM_2PACK16);
        case VK_FORMAT_R10X6G10X6B10X6A10X6_UNORM_4PACK16: GROUP(COLOR, 8, .aliases[FORMAT_ALIAS_UNORM] = VK_FORMAT_R10X6G10X6B10X6A10X6_UNORM_4PACK16);
        case VK_FORMAT_R12X4_UNORM_PACK16:                 GROUP(COLOR, 2, .aliases[FORMAT_ALIAS_UNORM] = VK_FORMAT_R12X4_UNORM_PACK16);
        case VK_FORMAT_R12X4G12X4_UNORM_2PACK16:           GROUP(COLOR, 4, .aliases[FORMAT_ALIAS_UNORM] = VK_FORMAT_R12X4G12X4_UNORM_2PACK16);
        case VK_FORMAT_R12X4G12X4B12X4A12X4_UNORM_4PACK16: GROUP(COLOR, 8, .aliases[FORMAT_ALIAS_UNORM] = VK_FORMAT_R12X4G12X4B12X4A12X4_UNORM_4PACK16);
        case VK_FORMAT_S8_UINT:            GROUP(STENCIL, 1);
        case VK_FORMAT_D16_UNORM_S8_UINT:  GROUP(STENCIL, 3);
        case VK_FORMAT_D24_UNORM_S8_UINT:  GROUP(STENCIL, 4);
        case VK_FORMAT_D32_SFLOAT_S8_UINT: GROUP(STENCIL, 5);
        default: return (FormatGroup) { .bytes = 0, .aspect = (VkImageAspectFlagBits) 0, .aliases[FORMAT_ALIAS_ORIGINAL] = format };
    }
#undef GROUP
}

JNIEXPORT void VKUtil_LogResultError(const char* string, VkResult result) {
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
        RESULT(VK_ERROR_SURFACE_LOST_KHR);
        RESULT(VK_ERROR_NATIVE_WINDOW_IN_USE_KHR);
        RESULT(VK_SUBOPTIMAL_KHR);
        RESULT(VK_ERROR_OUT_OF_DATE_KHR);
        RESULT(VK_ERROR_INCOMPATIBLE_DISPLAY_KHR);
        RESULT(VK_ERROR_VALIDATION_FAILED_EXT);
        RESULT(VK_ERROR_INVALID_SHADER_NV);
        RESULT(VK_ERROR_FULL_SCREEN_EXCLUSIVE_MODE_LOST_EXT);
        RESULT(VK_THREAD_IDLE_KHR);
        RESULT(VK_THREAD_DONE_KHR);
        RESULT(VK_OPERATION_DEFERRED_KHR);
        RESULT(VK_OPERATION_NOT_DEFERRED_KHR);
        default: r = "<UNKNOWN>"; break;
    }
    J2dRlsTraceLn(J2D_TRACE_ERROR, string, r);
}

/**
 * Concatenate src transform to dst
 * [d00 d01 d02] [s00 s01 s02]   [d00s00+d01s10 d00s01+d01s11 d00s02+d01s12+d02]
 * [d10 d11 d12] [s10 s11 s12] = [d10s11+d11s10 d10s01+d11s11 d10s02+d11s12+d12]
 * [ 0   0   1 ] [ 0   0   1 ]   [      0             0             1          ]
 */
void VKUtil_ConcatenateTransform(VKTransform* dst, const VKTransform* src) {
  float s00 = src->m00, s01 = src->m01, s02 = src->m02;
  float s10 = src->m10, s11 = src->m11, s12 = src->m12;
  float d00 = dst->m00, d01 = dst->m01, d02 = dst->m02;
  float d10 = dst->m10, d11 = dst->m11, d12 = dst->m12;

  dst->m00 = d00 * s00 + d01 * s10;
  dst->m01 = d00 * s01 + d01 * s11;
  dst->m02 = d00 * s02 + d01 * s12 + d02;

  dst->m10 = d10 * s00 + d11 * s10;
  dst->m11 = d10 * s01 + d11 * s11;
  dst->m12 = d10 * s02 + d11 * s12 + d12;
}