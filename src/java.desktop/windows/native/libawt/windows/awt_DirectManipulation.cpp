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

#include "awt_DirectManipulation.h"
#include "awt_Component.h"
#include "awt_Toolkit.h"
#include "awt_Win32GraphicsDevice.h"

#include <directmanipulation.h>
#include <mmsystem.h>

#ifndef PT_TOUCHPAD
#define PT_TOUCHPAD 0x00000005
#endif

#ifndef GET_POINTERID_WPARAM
#define GET_POINTERID_WPARAM(wParam) (LOWORD(wParam))
#endif

static volatile LONGLONG s_lastTouchpadInputUs = 0;
static volatile LONGLONG s_lastPhaseEndUs = 0;
static bool s_rawInputRegistered = false;

static LONGLONG GetPerfTimeUs() {
    static LARGE_INTEGER s_freq = {0};
    if (s_freq.QuadPart == 0) {
        ::QueryPerformanceFrequency(&s_freq);
    }
    LARGE_INTEGER counter = {0};
    ::QueryPerformanceCounter(&counter);
    return (s_freq.QuadPart > 0) ? (counter.QuadPart * 1000000LL) / s_freq.QuadPart : 0;
}

static void SetHighResTimer(bool enable) {
    typedef LONG(NTAPI* PFN_NtSetTimerResolution)(ULONG DesiredResolution, BOOLEAN SetResolution, PULONG CurrentResolution);
    static PFN_NtSetTimerResolution s_ntSetTimerResolution = NULL;
    static bool s_resolved = false;
    if (!s_resolved) {
        HMODULE hNtdll = ::GetModuleHandleW(L"ntdll.dll");
        if (hNtdll != NULL) {
            s_ntSetTimerResolution = reinterpret_cast<PFN_NtSetTimerResolution>(
                ::GetProcAddress(hNtdll, "NtSetTimerResolution"));
        }
        s_resolved = true;
    }
    if (s_ntSetTimerResolution != NULL) {
        ULONG actual = 0;
        s_ntSetTimerResolution(5000, enable ? TRUE : FALSE, &actual);
    }
    if (enable) {
        ::timeBeginPeriod(1);
    } else {
        ::timeEndPeriod(1);
    }
}

class AwtDirectManipulation::ViewportEventHandler
    : public IDirectManipulationViewportEventHandler,
      public IDirectManipulationInteractionEventHandler {
public:
    explicit ViewportEventHandler(AwtDirectManipulation* owner)
        : m_refCount(1),
          m_owner(owner),
          m_lastPanX(0.0f),
          m_lastPanY(0.0f),
          m_inZoomToRect(false),
          m_duringInertia(false),
          m_isRunning(false),
          m_hasScrolled(false) {}

    void Detach() {
        m_owner = NULL;
    }

    bool IsRunning() const {
        return m_isRunning;
    }

    bool IsActive() const {
        return m_isRunning || m_duringInertia;
    }

    // IUnknown
    STDMETHODIMP QueryInterface(REFIID riid, void** ppvObject) override {
        if (ppvObject == NULL) {
            return E_POINTER;
        }
        if (riid == IID_IUnknown || riid == __uuidof(IDirectManipulationViewportEventHandler)) {
            *ppvObject = static_cast<IDirectManipulationViewportEventHandler*>(this);
            AddRef();
            return S_OK;
        }
        if (riid == __uuidof(IDirectManipulationInteractionEventHandler)) {
            *ppvObject = static_cast<IDirectManipulationInteractionEventHandler*>(this);
            AddRef();
            return S_OK;
        }
        *ppvObject = NULL;
        return E_NOINTERFACE;
    }

    STDMETHODIMP_(ULONG) AddRef() override {
        return ::InterlockedIncrement(&m_refCount);
    }

    STDMETHODIMP_(ULONG) Release() override {
        ULONG count = ::InterlockedDecrement(&m_refCount);
        if (count == 0) {
            delete this;
        }
        return count;
    }

    // IDirectManipulationInteractionEventHandler
    STDMETHODIMP OnInteraction(IDirectManipulationViewport2* /*viewport*/,
                               DIRECTMANIPULATION_INTERACTION_TYPE /*interaction*/) override {
        return S_OK;
    }

    // IDirectManipulationViewportEventHandler
    STDMETHODIMP OnViewportStatusChanged(IDirectManipulationViewport* viewport,
                                         DIRECTMANIPULATION_STATUS current,
                                         DIRECTMANIPULATION_STATUS previous) override {
        if (m_owner == NULL || viewport == NULL || current == previous || m_inZoomToRect) {
            return S_OK;
        }

        if (current == DIRECTMANIPULATION_RUNNING) {
            m_duringInertia = false;
            if (previous == DIRECTMANIPULATION_INERTIA) {
                m_isRunning = true;
                m_hasScrolled = true;
                m_owner->NotifyScroll(0.0, 0.0, PHASE_BEGIN);
            } else if (!m_isRunning) {
                m_isRunning = true;
                m_hasScrolled = false;
            }
            return S_OK;
        }

        if (m_isRunning) {
            m_isRunning = false;
            if (m_hasScrolled) {
                m_hasScrolled = false;
                s_lastPhaseEndUs = GetPerfTimeUs();
                s_lastTouchpadInputUs = s_lastPhaseEndUs;
                m_owner->NotifyScroll(0.0, 0.0, PHASE_END);
            }
        }
        m_duringInertia = (current == DIRECTMANIPULATION_INERTIA);

        if ((current == DIRECTMANIPULATION_READY || current == DIRECTMANIPULATION_ENABLED) &&
            (m_lastPanX != 0.0f || m_lastPanY != 0.0f)) {
            RECT rect = {0, 0, 1000, 1000};
            if (SUCCEEDED(viewport->GetViewportRect(&rect))) {
                m_inZoomToRect = true;
                HRESULT hr = viewport->ZoomToRect(static_cast<float>(rect.left),
                                                  static_cast<float>(rect.top),
                                                  static_cast<float>(rect.right),
                                                  static_cast<float>(rect.bottom),
                                                  FALSE);
                m_inZoomToRect = false;
                if (SUCCEEDED(hr)) {
                    m_lastPanX = 0.0f;
                    m_lastPanY = 0.0f;
                }
            }
        }
        return S_OK;
    }

    STDMETHODIMP OnViewportUpdated(IDirectManipulationViewport* /*viewport*/) override {
        return S_OK;
    }

    STDMETHODIMP OnContentUpdated(IDirectManipulationViewport* viewport,
                                  IDirectManipulationContent* content) override {
        if (m_owner == NULL || viewport == NULL || content == NULL || m_inZoomToRect) {
            return S_OK;
        }

        float transform[6] = {0};
        if (FAILED(content->GetContentTransform(transform, 6)) || transform[0] == 0.0f) {
            return S_OK;
        }

        const float deltaX = transform[4] - m_lastPanX;
        const float deltaY = transform[5] - m_lastPanY;
        if (deltaX == 0.0f && deltaY == 0.0f) {
            return S_OK;
        }
        m_lastPanX = transform[4];
        m_lastPanY = transform[5];

        if (m_duringInertia) {
            return S_OK;
        }

        AwtComponent* comp = m_owner->GetComponent();
        if (comp == NULL) {
            return S_OK;
        }
        if (!m_isRunning) {
            m_isRunning = true;
            m_hasScrolled = false;
        }
        if (!m_hasScrolled) {
            m_hasScrolled = true;
            m_owner->NotifyScroll(0.0, 0.0, PHASE_BEGIN);
            if (m_owner == NULL) {
                return S_OK;
            }
        }

        float scaleX = 1.0f;
        float scaleY = 1.0f;
        {
            int screen = comp->GetScreenImOn();
            Devices::InstanceAccess devices;
            AwtWin32GraphicsDevice* device = devices->GetDevice(screen);
            if (device != NULL) {
                if (device->GetScaleX() > 0.0f) {
                    scaleX = device->GetScaleX();
                }
                if (device->GetScaleY() > 0.0f) {
                    scaleY = device->GetScaleY();
                }
            }
        }

        const double dx = static_cast<double>(-deltaX) / scaleX;
        const double dy = static_cast<double>(-deltaY) / scaleY;
        m_owner->NotifyScroll(dx, dy, PHASE_UPDATE);
        return S_OK;
    }

private:
    virtual ~ViewportEventHandler() {}

    volatile LONG m_refCount;
    AwtDirectManipulation* m_owner;
    float m_lastPanX;
    float m_lastPanY;
    bool m_inZoomToRect;
    bool m_duringInertia;
    bool m_isRunning;
    bool m_hasScrolled;
};

bool AwtDirectManipulation::IsEnabled() {
    static int enabled = -1;
    if (enabled == -1) {
        if (!AwtToolkit::GetInstance().IsWin8OrLater()) {
            enabled = 0;
            return false;
        }
        JNIEnv* env = (JNIEnv*)JNU_GetEnv(jvm, JNI_VERSION_1_2);
        if (env == NULL) {
            return false;
        }
        jclass peerCls = env->FindClass("sun/awt/windows/WKineticScroller");
        if (peerCls == NULL) {
            env->ExceptionClear();
            enabled = 0;
            return false;
        }
        jfieldID fid = env->GetStaticFieldID(peerCls, "ENABLED", "Z");
        if (fid == NULL) {
            env->DeleteLocalRef(peerCls);
            env->ExceptionClear();
            enabled = 0;
            return false;
        }
        jboolean val = env->GetStaticBooleanField(peerCls, fid);
        env->DeleteLocalRef(peerCls);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            enabled = 0;
            return false;
        }
        enabled = (val == JNI_TRUE) ? 1 : 0;
    }
    return enabled == 1;
}

AwtDirectManipulation* AwtDirectManipulation::Create(AwtComponent* comp) {
    if (!IsEnabled() || comp == NULL || comp->GetHWnd() == NULL) {
        return NULL;
    }
    AwtDirectManipulation* dm = new AwtDirectManipulation(comp);
    if (!dm->Initialize()) {
        delete dm;
        return NULL;
    }
    return dm;
}

void AwtDirectManipulation::Destroy(AwtDirectManipulation* dm) {
    if (dm != NULL) {
        delete dm;
    }
}

AwtDirectManipulation::AwtDirectManipulation(AwtComponent* comp)
    : m_comp(comp),
      m_manager(NULL),
      m_updateManager(NULL),
      m_viewport(NULL),
      m_eventHandler(NULL),
      m_eventCookie(0),
      m_timerRunning(false),
      m_idleTicks(0),
      m_lastUpdateTick(0) {}

AwtDirectManipulation::~AwtDirectManipulation() {
    Cleanup();
}

bool AwtDirectManipulation::Initialize() {
    HWND hwnd = m_comp->GetHWnd();
    if (hwnd == NULL) {
        return false;
    }

    HRESULT hr = ::CoCreateInstance(
        CLSID_DirectManipulationManager,
        NULL,
        CLSCTX_INPROC_SERVER,
        __uuidof(IDirectManipulationManager),
        reinterpret_cast<void**>(&m_manager));
    if (FAILED(hr) || m_manager == NULL) {
        return false;
    }

    hr = m_manager->GetUpdateManager(
        __uuidof(IDirectManipulationUpdateManager),
        reinterpret_cast<void**>(&m_updateManager));
    if (FAILED(hr) || m_updateManager == NULL) {
        return false;
    }

    hr = m_manager->CreateViewport(
        NULL,
        hwnd,
        __uuidof(IDirectManipulationViewport),
        reinterpret_cast<void**>(&m_viewport));
    if (FAILED(hr) || m_viewport == NULL) {
        return false;
    }

    DIRECTMANIPULATION_CONFIGURATION config =
        DIRECTMANIPULATION_CONFIGURATION_INTERACTION |
        DIRECTMANIPULATION_CONFIGURATION_TRANSLATION_X |
        DIRECTMANIPULATION_CONFIGURATION_TRANSLATION_Y |
        DIRECTMANIPULATION_CONFIGURATION_TRANSLATION_INERTIA;

    hr = m_viewport->ActivateConfiguration(config);
    if (FAILED(hr)) {
        return false;
    }

    hr = m_viewport->SetViewportOptions(
        DIRECTMANIPULATION_VIEWPORT_OPTIONS_DISABLEPIXELSNAPPING);
    if (FAILED(hr)) {
        // Non-fatal: continue with default viewport options if unsupported
    }

    RECT rc = {0, 0, 1000, 1000};
    m_viewport->SetViewportRect(&rc);

    m_eventHandler = new ViewportEventHandler(this);
    hr = m_viewport->AddEventHandler(hwnd,
                                    static_cast<IDirectManipulationViewportEventHandler*>(m_eventHandler),
                                    &m_eventCookie);
    if (FAILED(hr)) {
        return false;
    }

    hr = m_manager->Activate(hwnd);
    if (FAILED(hr)) {
        return false;
    }

    hr = m_viewport->Enable();
    if (FAILED(hr)) {
        return false;
    }

    if (!s_rawInputRegistered) {
        RAWINPUTDEVICE rid = {0};
        rid.usUsagePage = 0x000D; // HID_USAGE_PAGE_DIGITIZER
        rid.usUsage = 0x0005;     // HID_USAGE_DIGITIZER_TOUCH_PAD
        rid.dwFlags = 0;
        rid.hwndTarget = NULL;    // Follow keyboard focus
        if (::RegisterRawInputDevices(&rid, 1, sizeof(rid))) {
            s_rawInputRegistered = true;
        }
    }

    m_updateManager->Update(NULL);
    return true;
}

void AwtDirectManipulation::Cleanup() {
    StopUpdateTimer();
    if (m_eventHandler != NULL) {
        m_eventHandler->Detach();
    }
    if (m_viewport != NULL) {
        if (m_eventCookie != 0) {
            m_viewport->RemoveEventHandler(m_eventCookie);
            m_eventCookie = 0;
        }
        m_viewport->Stop();
        m_viewport->Disable();
        m_viewport->Abandon();
        m_viewport->Release();
        m_viewport = NULL;
    }
    if (m_eventHandler != NULL) {
        m_eventHandler->Release();
        m_eventHandler = NULL;
    }
    if (m_manager != NULL && m_comp != NULL && m_comp->GetHWnd() != NULL) {
        m_manager->Deactivate(m_comp->GetHWnd());
    }
    if (m_updateManager != NULL) {
        m_updateManager->Release();
        m_updateManager = NULL;
    }
    if (m_manager != NULL) {
        m_manager->Release();
        m_manager = NULL;
    }
}

void AwtDirectManipulation::StartUpdateTimer() {
    m_idleTicks = 0;
    if (!m_timerRunning && m_comp != NULL && m_comp->GetHWnd() != NULL) {
        if (::SetTimer(m_comp->GetHWnd(), TIMER_ID, 8, NULL) != 0) {
            SetHighResTimer(true);
            m_timerRunning = true;
        }
    }
}

void AwtDirectManipulation::StopUpdateTimer() {
    m_idleTicks = 0;
    if (m_timerRunning) {
        if (m_comp != NULL && m_comp->GetHWnd() != NULL) {
            ::KillTimer(m_comp->GetHWnd(), TIMER_ID);
        }
        SetHighResTimer(false);
        m_timerRunning = false;
    }
}

void AwtDirectManipulation::OnTimer() {
    const ULONGLONG nowUs = static_cast<ULONGLONG>(GetPerfTimeUs());
    if (m_updateManager != NULL && (nowUs - m_lastUpdateTick) >= 6000) {
        m_lastUpdateTick = nowUs;
        m_updateManager->Update(NULL);
    }
    if (m_eventHandler != NULL && m_eventHandler->IsActive()) {
        m_idleTicks = 0;
    } else if (++m_idleTicks > 250) {
        StopUpdateTimer();
    }
}

void AwtDirectManipulation::OnRawInput(LPARAM lParam) {
    if (lParam == 0) {
        return;
    }
    RAWINPUTHEADER header = {0};
    UINT headerSize = sizeof(header);
    if (::GetRawInputData(reinterpret_cast<HRAWINPUT>(lParam), RID_HEADER,
                          &header, &headerSize, sizeof(RAWINPUTHEADER)) == (UINT)-1) {
        return;
    }
    if (header.dwType != RIM_TYPEHID) {
        return;
    }

    const LONGLONG nowUs = GetPerfTimeUs();
    const LONGLONG gapUs = nowUs - s_lastTouchpadInputUs;
    s_lastTouchpadInputUs = nowUs;

    if (m_updateManager != NULL && (m_timerRunning || (m_eventHandler != NULL && m_eventHandler->IsActive()))) {
        m_lastUpdateTick = static_cast<ULONGLONG>(nowUs);
        m_updateManager->Update(NULL);
    }

    // If fingers were lifted from the touchpad (gap > 45 ms between touchpad HID reports)
    // and touch down again while no scroll gesture is running, stop any active kinetic fling
    // immediately (equivalent to Wayland's zwp_pointer_gesture_hold_v1).
    if (gapUs > 45000 && (nowUs - s_lastPhaseEndUs) > 45000 &&
        (m_eventHandler == NULL || !m_eventHandler->IsRunning())) {
        NotifyScroll(0.0, 0.0, PHASE_BEGIN);
    }
}

bool AwtDirectManipulation::OnPointerHitTest(WPARAM wParam) {
    if (m_viewport == NULL) {
        return false;
    }
    typedef BOOL(WINAPI* PFN_GetPointerType)(UINT32 pointerId, POINTER_INPUT_TYPE* pointerType);
    static PFN_GetPointerType s_getPointerType = reinterpret_cast<PFN_GetPointerType>(
        ::GetProcAddress(::GetModuleHandleW(L"user32.dll"), "GetPointerType"));
    if (s_getPointerType == NULL) {
        return false;
    }
    const UINT32 pointerId = GET_POINTERID_WPARAM(wParam);
    POINTER_INPUT_TYPE pointerType = PT_POINTER;
    if (!s_getPointerType(pointerId, &pointerType) || pointerType != PT_TOUCHPAD) {
        return false;
    }

    const LONGLONG nowUs = GetPerfTimeUs();
    s_lastTouchpadInputUs = nowUs;
    HRESULT hr = m_viewport->SetContact(pointerId);
    if (SUCCEEDED(hr)) {
        StartUpdateTimer();
        if (m_updateManager != NULL) {
            m_lastUpdateTick = static_cast<ULONGLONG>(nowUs);
            m_updateManager->Update(NULL);
        }
        return true;
    }
    return false;
}

void AwtDirectManipulation::NotifyScroll(double dx, double dy, ScrollPhase phase) {
    if (m_comp == NULL) {
        return;
    }
    JNIEnv* env = (JNIEnv*)JNU_GetEnv(jvm, JNI_VERSION_1_2);
    if (env == NULL) {
        return;
    }
    jobject peer = m_comp->GetPeer(env);
    if (peer == NULL) {
        return;
    }

    static jmethodID handleDirectScrollMID = NULL;
    if (handleDirectScrollMID == NULL) {
        jclass peerCls = env->GetObjectClass(peer);
        if (peerCls == NULL) {
            env->ExceptionClear();
            return;
        }
        handleDirectScrollMID = env->GetMethodID(peerCls, "handleDirectScroll", "(DDIIIIII)V");
        env->DeleteLocalRef(peerCls);
        if (handleDirectScrollMID == NULL) {
            env->ExceptionClear();
            return;
        }
    }

    POINT pt = {0, 0};
    ::GetCursorPos(&pt);
    const jint xAbs = m_comp->ScaleDownAbsX(pt.x);
    const jint yAbs = m_comp->ScaleDownAbsY(pt.y);

    HWND hwnd = m_comp->GetHWnd();
    if (hwnd != NULL) {
        ::ScreenToClient(hwnd, &pt);
    }
    RECT insets = {0, 0, 0, 0};
    m_comp->GetInsets(&insets);
    const jint x = m_comp->ScaleDownX(pt.x + insets.left);
    const jint y = m_comp->ScaleDownY(pt.y + insets.top);
    const jint modifiers = m_comp->GetJavaModifiers();

    env->CallVoidMethod(peer, handleDirectScrollMID,
                        static_cast<jdouble>(dx),
                        static_cast<jdouble>(dy),
                        static_cast<jint>(phase),
                        x, y, xAbs, yAbs, modifiers);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
}
