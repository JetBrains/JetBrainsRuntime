/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2026, JetBrains s.r.o.. All rights reserved.
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

#ifndef AWT_DIRECT_MANIPULATION_H
#define AWT_DIRECT_MANIPULATION_H

#include "awt.h"

#ifndef WM_POINTERDOWN
#define WM_POINTERDOWN 0x0246
#endif

#ifndef DM_POINTERHITTEST
#define DM_POINTERHITTEST 0x0250
#endif

class AwtComponent;
struct IDirectManipulationManager;
struct IDirectManipulationUpdateManager;
struct IDirectManipulationViewport;

class AwtDirectManipulation {
public:
    enum ScrollPhase {
        PHASE_BEGIN  = 0,
        PHASE_UPDATE = 1,
        PHASE_END    = 2
    };

    static const UINT_PTR TIMER_ID = 0x444D5343; // 'DMSC'

    static bool IsEnabled();
    static AwtDirectManipulation* Create(AwtComponent* comp);
    static void Destroy(AwtDirectManipulation* dm);

    bool OnPointerHitTest(WPARAM wParam);
    void OnRawInput(LPARAM lParam);
    void OnTimer();
    void StartUpdateTimer();
    void StopUpdateTimer();
    void NotifyScroll(double dx, double dy, ScrollPhase phase);
    AwtComponent* GetComponent() const { return m_comp; }

private:
    class ViewportEventHandler;

    explicit AwtDirectManipulation(AwtComponent* comp);
    ~AwtDirectManipulation();

    bool Initialize();
    void Cleanup();

    AwtComponent* m_comp;
    IDirectManipulationManager* m_manager;
    IDirectManipulationUpdateManager* m_updateManager;
    IDirectManipulationViewport* m_viewport;
    ViewportEventHandler* m_eventHandler;
    DWORD m_eventCookie;
    bool m_timerRunning;
    int m_idleTicks;
    ULONGLONG m_lastUpdateTick;
};

#endif /* AWT_DIRECT_MANIPULATION_H */
