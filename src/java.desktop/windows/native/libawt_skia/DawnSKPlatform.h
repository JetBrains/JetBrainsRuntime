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

// Graphite on Dawn over Direct3D 11, behind the Windows platform layer
// (Win32SKPlatform.h): DawnSKGraphicsAPI (DawnSKPlatform.cpp, the Win32SKGraphicsAPI
// of Dawn) and its configurations and surfaces, SKPlatform.h's classes on Dawn.
//
// Presentation, as it is: a minimized window keeps a swap chain at its restored size
// (AWT keeps the restored bounds) and its presents are no-ops for DWM. Fullscreen is
// windowed fullscreen: the swap chain never enters exclusive fullscreen (no
// SetFullscreenState; Dawn sets DXGI_MWA_NO_ALT_ENTER), so DWM composes it. Present runs on
// the flusher thread under the render-queue lock and can block up to a vblank where only
// Fifo is offered (Mailbox and Immediate do not wait). A swap chain belongs to one device:
// after a device loss the window's is released and a new one made (DawnSKConfigInfo::createSurface).
// The swap chain's window belongs to AWT's toolkit thread, which must never wait for the flusher or
// the event thread while a chain is created, configured or presented, as DXGI may SendMessage to it
// meanwhile: README.md of the shared backend, "Presenting from the flusher thread".

#include "Win32SKPlatform.h"

namespace DawnSKPlatform {

// Dawn as a Win32SKGraphicsAPI, for the selection (Win32SKEnvironment); the maker of every Dawn
// configuration. Lives for the process.
[[nodiscard]] Win32SKGraphicsAPI &graphicsAPI();

} // namespace DawnSKPlatform

#endif /* DawnSKPlatform_h_Included */
