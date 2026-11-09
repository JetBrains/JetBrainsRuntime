/*
 * Copyright 2026 JetBrains s.r.o.
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
#ifndef DawnSKPlatform_h_Included
#define DawnSKPlatform_h_Included

// Graphite on Dawn over Direct3D 11 or 12, behind the Windows platform layer
// (Win32SKPlatform.h): its configurations and surfaces, and the GPU side of
// SKPlatform.h for them, as DawnSKGraphicsAPI (DawnSKPlatform.cpp, the
// Win32SKGraphicsAPI of Dawn).
//
// Presentation, as it is: a minimized window keeps a swap chain at its restored size
// (AWT keeps the restored bounds) and its presents are no-ops for DWM. Fullscreen is
// windowed fullscreen: the swap chain never enters exclusive fullscreen (no
// SetFullscreenState; Dawn sets DXGI_MWA_NO_ALT_ENTER), so DWM composes it. Present runs on
// the flusher thread under the render-queue lock and can block up to a vblank where only
// Fifo is offered (Mailbox and Immediate do not wait). A swap chain belongs to one device:
// after a device loss the window's is released and a new one made (DawnSKGraphicsAPI::createSurface).

#include "Win32SKPlatform.h"

namespace DawnSKPlatform {

// Dawn as a Win32SKGraphicsAPI, for the selection (Win32SKEnvironment); the API of every Dawn
// configuration and surface. Lives for the process.
[[nodiscard]] Win32SKGraphicsAPI &graphicsAPI();

// sun.java2d.skia.dawn.api: the Direct3D version Dawn tries first, 12 before 11 with `preferD3D12`
// (default: 11 first); call before the first adapter request.
void preferD3D12(bool preferD3D12);

} // namespace DawnSKPlatform

#endif /* DawnSKPlatform_h_Included */
