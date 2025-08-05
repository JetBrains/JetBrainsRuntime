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

#ifndef VKRenderer_h_Included
#define VKRenderer_h_Included
#ifdef __cplusplus

#include "VKBase.h"
#include "VKSurfaceData.h"

class VKRecorder{
    VKDevice                            *_device;
    vk::raii::CommandBuffer              _commandBuffer = nullptr;
    std::vector<vk::raii::CommandBuffer> _secondaryBuffers;
    std::vector<vk::Semaphore>           _waitSemaphores, _signalSemaphores;
    std::vector<vk::PipelineStageFlags>  _waitSemaphoreStages;
    std::vector<VKBuffer>                _vertexBuffers;
    struct RenderPass {
        vk::raii::CommandBuffer *commandBuffer = nullptr;
        VKSurfaceData           *surface = nullptr;
        VKBuffer                *vertexBuffer = nullptr;
        vk::ImageView            surfaceView;
        vk::Framebuffer          surfaceFramebuffer; // Only if dynamic rendering is off.
        vk::AttachmentLoadOp     attachmentLoadOp;
        vk::ClearValue           clearValue;
    } _renderPass {};

protected:
    struct Vertex {
        float x, y;
    };

    Vertex* draw(uint32_t numVertices);

    VKDevice& device() {
        return *_device;
    }
    VKDevice* setDevice(VKDevice *device);

public:
    void waitSemaphore(vk::Semaphore semaphore, vk::PipelineStageFlags stage);

    void signalSemaphore(vk::Semaphore semaphore);

    const vk::raii::CommandBuffer& record(bool flushRenderPass = true); // Prepare for ordinary commands

    const vk::raii::CommandBuffer& render(VKSurfaceData& surface,
                                          vk::ClearColorValue* clear = nullptr); // Prepare for render pass commands

    void flush();
};

class VKRenderer : private VKRecorder{
    VKSurfaceData *_srcSurface, *_dstSurface;
    struct alignas(16) Color {
        float r, g, b, a;
        Color& operator=(uint32_t c) {
            r = (float) ((c >> 16) & 0xff) / 255.0f;
            g = (float) ((c >> 8) & 0xff) / 255.0f;
            b = (float) (c & 0xff) / 255.0f;
            a = (float) ((c >> 24) & 0xff) / 255.0f;
            return *this;
        }
        operator vk::ClearValue() const {
            return vk::ClearColorValue {r, g, b, a};
        }
    } _color;

public:
    using VKRecorder::flush;

    // draw ops
    void drawLine(jint x1, jint y1, jint x2, jint y2);
    void drawRect(jint x, jint y, jint w, jint h);
    void drawPoly(/*TODO*/);
    void drawPixel(jint x, jint y);
    void drawScanlines(/*TODO*/);
    void drawParallelogram(jfloat x11, jfloat y11,
                           jfloat dx21, jfloat dy21,
                           jfloat dx12, jfloat dy12,
                           jfloat lwr21, jfloat lwr12);
    void drawAAParallelogram(jfloat x11, jfloat y11,
                             jfloat dx21, jfloat dy21,
                             jfloat dx12, jfloat dy12,
                             jfloat lwr21, jfloat lwr12);

    // fill ops
    void fillRect(jint x, jint y, jint w, jint h);
    void fillSpans(/*TODO*/);
    void fillParallelogram(jfloat x11, jfloat y11,
                           jfloat dx21, jfloat dy21,
                           jfloat dx12, jfloat dy12);
    void fillAAParallelogram(jfloat x11, jfloat y11,
                             jfloat dx21, jfloat dy21,
                             jfloat dx12, jfloat dy12);

    // text-related ops
    void drawGlyphList(/*TODO*/);

    // copy-related ops
    void copyArea(jint x, jint y, jint w, jint h, jint dx, jint dy);
    void blit(/*TODO*/);
    void surfaceToSwBlit(/*TODO*/);
    void maskFill(/*TODO*/);
    void maskBlit(/*TODO*/);

    // state-related ops
    void setRectClip(jint x1, jint y1, jint x2, jint y2);
    void beginShapeClip();
    void setShapeClipSpans(/*TODO*/);
    void endShapeClip();
    void resetClip();
    void setAlphaComposite(/*TODO*/);
    void setXorComposite(/*TODO*/);
    void resetComposite();
    void setTransform(jdouble m00, jdouble m10,
                      jdouble m01, jdouble m11,
                      jdouble m02, jdouble m12);
    void resetTransform();

    // context-related ops
    void setSurfaces(VKSurfaceData& src, VKSurfaceData& dst);
    void setScratchSurface(/*TODO*/);
    void flushSurface(VKSurfaceData& surface);
    void disposeSurface(/*TODO*/);
    void disposeConfig(/*TODO*/);
    void invalidateContext();
    void sync();

    // multibuffering ops
    void swapBuffers(/*TODO*/);

    // paint-related ops
    void resetPaint();
    void setColor(uint32_t pixel);
    void setGradientPaint(/*TODO*/);
    void setLinearGradientPaint(/*TODO*/);
    void setRadialGradientPaint(/*TODO*/);
    void setTexturePaint(/*TODO*/);

    // BufferedImageOp-related ops
    void enableConvolveOp(/*TODO*/);
    void disableConvolveOp();
    void enableRescaleOp(/*TODO*/);
    void disableRescaleOp();
    void enableLookupOp();
    void disableLookupOp();
};

#endif //__cplusplus
#endif //VKRenderer_h_Included
