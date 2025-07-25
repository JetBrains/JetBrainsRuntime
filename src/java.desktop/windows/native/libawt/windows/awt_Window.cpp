/*
 * Copyright (c) 1996, 2025, Oracle and/or its affiliates. All rights reserved.
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

#include <cmath>
#include "awt.h"
#include <jlong.h>

#include "awt_Component.h"
#include "awt_Container.h"
#include "awt_Frame.h"
#include "awt_Dialog.h"
#include "awt_Insets.h"
#include "awt_Panel.h"
#include "awt_Toolkit.h"
#include "awt_Window.h"
#include "awt_Win32GraphicsDevice.h"
#include "awt_BitmapUtil.h"
#include "awt_IconCursor.h"
#include "ComCtl32Util.h"

#include "java_awt_Insets.h"
#include <java_awt_Container.h>
#include <java_awt_event_ComponentEvent.h>
#include "sun_awt_windows_WCanvasPeer.h"

#include <windowsx.h>
#include <dwmapi.h>
#if !defined(__int3264)
typedef int32_t LONG_PTR;
#endif // __int3264

// Define these to be able to build with older SDKs
#define DWM_WINDOW_CORNER_PREFERENCE int
#define DWMWA_WINDOW_CORNER_PREFERENCE 33
#define DWMWA_BORDER_COLOR 34

// Used for Swing's Menu/Tooltip animation Support
const int UNSPECIFIED = 0;
const int TOOLTIP = 1;
const int MENU = 2;
const int SUBMENU = 3;
const int POPUPMENU = 4;
const int COMBOBOX_POPUP = 5;
const int TYPES_COUNT = 6;
jint windowTYPES[TYPES_COUNT];


/* IMPORTANT! Read the README.JNI file for notes on JNI converted AWT code.
 */

/***********************************************************************/
// struct for _SetAlwaysOnTop() method
struct SetAlwaysOnTopStruct {
    jobject window;
    jboolean value;
};
// struct for _SetTitle() method
struct SetTitleStruct {
    jobject window;
    jstring title;
};
// struct for _SetResizable() method
struct SetResizableStruct {
    jobject window;
    jboolean resizable;
};
// struct for _UpdateInsets() method
struct UpdateInsetsStruct {
    jobject window;
    jobject insets;
};
// struct for _ReshapeFrame() method
struct ReshapeFrameStruct {
    jobject frame;
    jint x, y;
    jint w, h;
};
// struct for _SetIconImagesData
struct SetIconImagesDataStruct {
    jobject window;
    jintArray iconRaster;
    jint w, h;
    jintArray smallIconRaster;
    jint smw, smh;
};
// struct for _SetMinSize() method
// and other methods setting sizes
struct SizeStruct {
    jobject window;
    jint w, h;
};
// struct for _SetFocusableWindow() method
struct SetFocusableWindowStruct {
    jobject window;
    jboolean isFocusableWindow;
};
// struct for _ModalDisable() method
struct ModalDisableStruct {
    jobject window;
    jlong blockerHWnd;
};
// struct for _SetOpacity() method
struct OpacityStruct {
    jobject window;
    jint iOpacity;
};
// struct for _SetOpaque() method
struct OpaqueStruct {
    jobject window;
    jboolean isOpaque;
};
// struct for _SetRoundedCorners() method
struct RoundedCornersStruct {
    jobject window;
    DWM_WINDOW_CORNER_PREFERENCE type;
    jboolean isBorderColor;
    jint borderColor;
};
// struct for _UpdateWindow() method
struct UpdateWindowStruct {
    jobject window;
    jintArray data;
    HBITMAP hBitmap;
    jint width, height;
};
// Struct for _RequestWindowFocus() method
struct RequestWindowFocusStruct {
    jobject component;
    jboolean isMouseEventCause;
};

struct SetFullScreenExclusiveModeStateStruct {
    jobject window;
    jboolean isFSEMState;
};

struct OverrideHandle {
    jobject frame;
    HWND handle;
};

/************************************************************************
 * AwtWindow fields
 */

jfieldID AwtWindow::locationByPlatformID;
jfieldID AwtWindow::autoRequestFocusID;
jfieldID AwtWindow::customTitleBarHitTestID;
jfieldID AwtWindow::customTitleBarHitTestQueryID;

jfieldID AwtWindow::windowTypeID;
jmethodID AwtWindow::notifyWindowStateChangedMID;
jfieldID AwtWindow::sysInsetsID;

jmethodID AwtWindow::windowTypeNameMID;
jmethodID AwtWindow::internalCustomTitleBarHeightMID;

int AwtWindow::ms_instanceCounter = 0;
HHOOK AwtWindow::ms_hCBTFilter;
AwtWindow * AwtWindow::m_grabbedWindow = NULL;
BOOL AwtWindow::sm_resizing = FALSE;

/************************************************************************
 * AwtWindow class methods
 */

AwtWindow::AwtWindow() {
    m_sizePt.x = m_sizePt.y = 0;
    m_owningFrameDialog = NULL;
    m_isResizable = FALSE;//Default value is replaced after construction
    m_minSize.x = m_minSize.y = 0;
    m_hIcon = NULL;
    m_hIconSm = NULL;
    m_iconInherited = FALSE;
    VERIFY(::SetRectEmpty(&m_insets));
    VERIFY(::SetRectEmpty(&m_old_insets));

    // what's the best initial value?
    m_screenNum = -1;
    ms_instanceCounter++;
    m_grabbed = FALSE;
    m_isFocusableWindow = TRUE;
    m_isRetainingHierarchyZOrder = FALSE;
    m_filterFocusAndActivation = FALSE;
    m_isIgnoringMouseEvents = FALSE;

    if (AwtWindow::ms_instanceCounter == 1) {
        AwtWindow::ms_hCBTFilter =
            ::SetWindowsHookEx(WH_CBT, (HOOKPROC)AwtWindow::CBTFilter,
                               0, AwtToolkit::MainThread());
    }

    m_opaque = TRUE;
    m_opacity = 0xff;

    currentWmSizeState = SIZE_RESTORED;

    hContentBitmap = NULL;

    ::InitializeCriticalSection(&contentBitmapCS);

    m_windowType = NORMAL;
    m_alwaysOnTop = false;

    fullScreenExclusiveModeState = FALSE;
    m_winSizeMove = FALSE;
    prevScaleRec.screen = -1;
    prevScaleRec.scaleX = -1.0f;
    prevScaleRec.scaleY = -1.0f;
    m_overriddenHwnd = NULL;
}

AwtWindow::~AwtWindow()
{
    DeleteContentBitmap();
    ::DeleteCriticalSection(&contentBitmapCS);
}

void AwtWindow::Dispose()
{
    // Fix 4745575 GDI Resource Leak
    // MSDN
    // Before a window is destroyed (that is, before it returns from processing
    // the WM_NCDESTROY message), an application must remove all entries it has
    // added to the property list. The application must use the RemoveProp function
    // to remove the entries.

    if (--AwtWindow::ms_instanceCounter == 0) {
        ::UnhookWindowsHookEx(AwtWindow::ms_hCBTFilter);
    }

    ::RemoveProp(GetHWnd(), ModalBlockerProp);

    if (m_grabbedWindow == this) {
        Ungrab();
    }
    if ((m_hIcon != NULL) && !m_iconInherited) {
        ::DestroyIcon(m_hIcon);
    }
    if ((m_hIconSm != NULL) && !m_iconInherited) {
        ::DestroyIcon(m_hIconSm);
    }

    AwtCanvas::Dispose();
}

void
AwtWindow::Grab() {
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);
    if (m_grabbedWindow != NULL) {
        m_grabbedWindow->Ungrab();
    }
    m_grabbed = TRUE;
    m_grabbedWindow = this;
    if (AwtComponent::GetFocusedWindow() == NULL && IsFocusableWindow()) {
        // we shouldn't perform grab in this case (see 4841881 & 6539458)
        Ungrab();
    } else if (GetHWnd() != AwtComponent::GetFocusedWindow()) {
        _ToFront(env->NewGlobalRef(GetPeer(env)), FALSE);
        // Global ref was deleted in _ToFront
    }
}

void
AwtWindow::Ungrab(BOOL doPost) {
    if (m_grabbed && m_grabbedWindow == this) {
        if (doPost) {
            PostUngrabEvent();
        }
        m_grabbedWindow = NULL;
        m_grabbed = FALSE;
    }
}

void
AwtWindow::Ungrab() {
    Ungrab(TRUE);
}

void AwtWindow::_Grab(void * param) {
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    jobject self = (jobject)param;

    if (env->EnsureLocalCapacity(1) < 0)
    {
        env->DeleteGlobalRef(self);
        return;
    }

    AwtWindow *p = NULL;

    PDATA pData;
    JNI_CHECK_PEER_GOTO(self, ret);
    p = (AwtWindow *)pData;
    p->Grab();

  ret:
    env->DeleteGlobalRef(self);
}

void AwtWindow::_Ungrab(void * param) {
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    jobject self = (jobject)param;

    if (env->EnsureLocalCapacity(1) < 0)
    {
        env->DeleteGlobalRef(self);
        return;
    }

    AwtWindow *p = NULL;

    PDATA pData;
    JNI_CHECK_PEER_GOTO(self, ret);
    p = (AwtWindow *)pData;
    p->Ungrab(FALSE);

  ret:
    env->DeleteGlobalRef(self);
}

MsgRouting AwtWindow::WmNcMouseDown(WPARAM hitTest, int x, int y, int button) {
    if (m_grabbedWindow != NULL && !m_grabbedWindow->IsOneOfOwnersOf(this)) {
        m_grabbedWindow->Ungrab();
    }
    return AwtCanvas::WmNcMouseDown(hitTest, x, y, button);
}

MsgRouting AwtWindow::WmWindowPosChanging(LPARAM windowPos) {
    return mrDoDefault;
}

MsgRouting AwtWindow::WmWindowPosChanged(LPARAM windowPos) {
    WINDOWPOS * wp = (WINDOWPOS *)windowPos;

    // There's no good way to detect partial maximization (e.g. Aero Snap),
    // but by inspecting SWP_* flags we can guess it and reset
    // prevScaleRec to neutralize the CheckWindowDPIChange logic.
    // Here are the flags, observed on Windows 11 for reference:
    // Restore/maximize:        SWP_NOZORDER | SWP_DRAWFRAME
    // Partial Aero Snap:       SWP_NOZORDER | SWP_NOREPOSITION
    // DPI change (new screen): SWP_NOZORDER | SWP_NOACTIVATE | SWP_NOCOPYBITS
    if (!(wp->flags & (SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE)) &&
        prevScaleRec.screen != -1 && prevScaleRec.screen != m_screenNum) {
        prevScaleRec.screen = -1;
        prevScaleRec.scaleX = -1.0f;
        prevScaleRec.scaleY = -1.0f;
    }

    if (wp->flags & SWP_HIDEWINDOW) {
        EnableTranslucency(FALSE);
    }
    if (wp->flags & SWP_SHOWWINDOW) {
        EnableTranslucency(TRUE);
    }

    return mrDoDefault;
}

LPCTSTR AwtWindow::GetClassName() {
  return TEXT("SunAwtWindow");
}

void AwtWindow::FillClassInfo(WNDCLASSEX *lpwc)
{
    AwtComponent::FillClassInfo(lpwc);
    /*
     * This line causes bug #4189244 (Swing Popup menu is not being refreshed (cleared) under a Dialog)
     * so it's comment out (son@sparc.spb.su)
     *
     * lpwc->style     |= CS_SAVEBITS; // improve pull-down menu performance
     */
    lpwc->cbWndExtra = DLGWINDOWEXTRA;
}

LRESULT CALLBACK AwtWindow::CBTFilter(int nCode, WPARAM wParam, LPARAM lParam)
{
    if (nCode == HCBT_ACTIVATE || nCode == HCBT_SETFOCUS) {
        HWND hWnd = (HWND)wParam;
        AwtComponent *comp = AwtComponent::GetComponent(hWnd);

        if (comp != NULL) {
            if (comp->IsTopLevel()) {
                AwtWindow* win = (AwtWindow*)comp;

                if (!win->IsFocusableWindow() ||
                        win->m_filterFocusAndActivation)
                {
                    return 1; // Don't change focus/activation.
                }
            }
        }
    }
    return ::CallNextHookEx(AwtWindow::ms_hCBTFilter, nCode, wParam, lParam);
}

void AwtWindow::CreateHWnd(JNIEnv *env, LPCWSTR title,
        DWORD windowStyle,
        DWORD windowExStyle,
        int x, int y, int w, int h,
        HWND hWndParent, HMENU hMenu,
        COLORREF colorForeground,
        COLORREF colorBackground,
        jobject peer)
{
    InitType(env, peer);
    JNU_CHECK_EXCEPTION(env);

    TweakStyle(windowStyle, windowExStyle);

    AwtCanvas::CreateHWnd(env, title,
            windowStyle,
            windowExStyle,
            x, y, w, h,
            hWndParent, hMenu,
            colorForeground,
            colorBackground,
            peer);
}

void AwtWindow::DestroyHWnd()
{
    AwtCanvas::DestroyHWnd();
}

void AwtWindow::SetLayered(HWND window, bool layered)
{
    const LONG ex_style = ::GetWindowLong(window, GWL_EXSTYLE);
    ::SetWindowLong(window, GWL_EXSTYLE, layered ?
            ex_style | WS_EX_LAYERED : ex_style & ~WS_EX_LAYERED);
}

bool AwtWindow::IsLayered(HWND window)
{
    const LONG ex_style = ::GetWindowLong(window, GWL_EXSTYLE);
    return ex_style & WS_EX_LAYERED;
}

MsgRouting AwtWindow::WmTimer(UINT_PTR timerID)
{
    return mrPassAlong;
}

void AwtWindow::InitType(JNIEnv *env, jobject peer)
{
    jobject type = env->GetObjectField(peer, windowTypeID);
    if (type == NULL) {
        return;
    }

    jstring value = (jstring)env->CallObjectMethod(type, windowTypeNameMID);
    if (value == NULL) {
        env->DeleteLocalRef(type);
        return;
    }

    const char* valueNative = env->GetStringUTFChars(value, 0);
    if (valueNative == NULL) {
        env->DeleteLocalRef(value);
        env->DeleteLocalRef(type);
        return;
    }

    if (strcmp(valueNative, "UTILITY") == 0) {
        m_windowType = UTILITY;
    } else if (strcmp(valueNative, "POPUP") == 0) {
        m_windowType = POPUP;
    }

    env->ReleaseStringUTFChars(value, valueNative);
    env->DeleteLocalRef(value);
    env->DeleteLocalRef(type);
}

void AwtWindow::TweakStyle(DWORD & style, DWORD & exStyle)
{
    switch (GetType()) {
        case UTILITY:
            exStyle |= WS_EX_TOOLWINDOW;
            break;
        case POPUP:
            style &= ~WS_OVERLAPPED;
            style |= WS_POPUP;
            break;
    }
}

/* Create a new AwtWindow object and window.   */
AwtWindow* AwtWindow::Create(jobject self, jobject parent)
{
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    jobject target = NULL;
    AwtWindow* window = NULL;

    try {
        if (env->EnsureLocalCapacity(1) < 0) {
            return NULL;
        }

        AwtWindow* awtParent = NULL;

        PDATA pData;
        if (parent != NULL) {
            JNI_CHECK_PEER_GOTO(parent, done);
            awtParent = (AwtWindow *)pData;
        }

        target = env->GetObjectField(self, AwtObject::targetID);
        JNI_CHECK_NULL_GOTO(target, "null target", done);

        window = new AwtWindow();

        {
            if (JNU_IsInstanceOfByName(env, target, "javax/swing/Popup$HeavyWeightWindow") > 0) {
                window->m_isRetainingHierarchyZOrder = TRUE;
            }
            if (env->ExceptionCheck()) goto done;
            DWORD style = WS_CLIPCHILDREN | WS_POPUP;
            DWORD exStyle = WS_EX_NOACTIVATE;
            if (JNU_CallMethodByName(env, NULL, target, "isIgnoreMouseEvents", "()Z").z) {
                exStyle |= WS_EX_LAYERED | WS_EX_TRANSPARENT;
                window->m_isIgnoringMouseEvents = TRUE;
            }
            if (GetRTL()) {
                exStyle |= WS_EX_RIGHT | WS_EX_LEFTSCROLLBAR;
                if (GetRTLReadingOrder())
                    exStyle |= WS_EX_RTLREADING;
            }
            if (awtParent != NULL) {
                window->InitOwner(awtParent);
            } else {
                // specify WS_EX_TOOLWINDOW to remove parentless windows from taskbar
                exStyle |= WS_EX_TOOLWINDOW;
            }
            jint x = env->GetIntField(target, AwtComponent::xID);
            jint y = env->GetIntField(target, AwtComponent::yID);
            jint width = env->GetIntField(target, AwtComponent::widthID);
            jint height = env->GetIntField(target, AwtComponent::heightID);

            window->CreateHWnd(env, L"",
                               style, exStyle,
                               x, y, width, height,
                               (awtParent != NULL) ? awtParent->GetHWnd() : NULL,
                               NULL,
                               ::GetSysColor(COLOR_WINDOWTEXT),
                               ::GetSysColor(COLOR_WINDOW),
                               self);
            /*
             * Initialize icon as inherited from parent if it exists
             */
            if (parent != NULL) {
                window->m_hIcon = awtParent->GetHIcon();
                window->m_hIconSm = awtParent->GetHIconSm();
                window->m_iconInherited = TRUE;
            }
            window->DoUpdateIcon();
            window->RecalcNonClient();
        }
    } catch (...) {
        env->DeleteLocalRef(target);
        throw;
    }

done:
    env->DeleteLocalRef(target);
    return window;
}

BOOL AwtWindow::IsOneOfOwnersOf(AwtWindow * wnd) {
    while (wnd != NULL) {
        if (wnd == this || wnd->GetOwningFrameOrDialog() == this) return TRUE;
        wnd = (AwtWindow*)GetComponent(::GetWindow(wnd->GetHWnd(), GW_OWNER));
    }
    return FALSE;
}

void AwtWindow::InitOwner(AwtWindow *owner)
{
    DASSERT(owner != NULL);
    AwtWindow *initialOwner = owner;
    while (owner != NULL && owner->IsSimpleWindow()) {

        HWND ownerOwnerHWND = ::GetWindow(owner->GetHWnd(), GW_OWNER);
        if (ownerOwnerHWND == NULL) {
            owner = NULL;
            break;
        }
        owner = (AwtWindow *)AwtComponent::GetComponent(ownerOwnerHWND);
    }
    if (!owner) {
        owner = initialOwner->GetOwningFrameOrDialog();
    }
    m_owningFrameDialog = (AwtFrame *)owner;
}

void AwtWindow::moveToDefaultLocation() {
    HWND boggy = ::CreateWindow(GetClassName(), L"BOGGY", WS_OVERLAPPED, CW_USEDEFAULT, 0 ,0, 0,
        NULL, NULL, NULL, NULL);
    RECT defLoc;

    // Fixed 6477497: Windows drawn off-screen on Win98, even when java.awt.Window.locationByPlatform is set
    //    Win9x does not position a window until the window is shown.
    //    The behavior is slightly opposite to the WinNT (and up), where
    //    Windows will position the window upon creation of the window.
    //    That's why we have to manually set the left & top values of
    //    the defLoc to 0 if the GetWindowRect function returns FALSE.
    BOOL result = ::GetWindowRect(boggy, &defLoc);
    if (!result) {
        defLoc.left = defLoc.top = 0;
    }
    VERIFY(::DestroyWindow(boggy));
    VERIFY(::SetWindowPos(GetHWnd(), NULL, defLoc.left, defLoc.top, 0, 0, SWP_NOSIZE | SWP_NOZORDER));
}

/**
 * Override AwtComponent::Reshape() to handle absolute screen coordinates used
 * by the top-level windows.
 */
void AwtWindow::Reshape(int x, int y, int w, int h) {
    if (IsEmbeddedFrame()) {
        // Not the "real" top level window
        return AwtComponent::Reshape(x, y, w, h);
    }
    // Yes, use x,y in user's space to find the nearest monitor in device space.
    POINT pt = {x + w / 2, y + h / 2};
    Devices::InstanceAccess devices;
    HMONITOR monitor = MonitorFromPoint(pt, MONITOR_DEFAULTTONEAREST);
    int screen = AwtWin32GraphicsDevice::GetScreenFromHMONITOR(monitor);
    AwtWin32GraphicsDevice *device = devices->GetDevice(screen);
    // Try to set the correct size and jump to the correct location, even if it is
    // on the different monitor. Note that for the "size" we use the current
    // monitor, so the WM_DPICHANGED will adjust it for the "target" monitor.
    int scaleUpAbsX = device == NULL ? x : device->ScaleUpAbsX(x);
    int scaleUpAbsY = device == NULL ? y : device->ScaleUpAbsY(y);

    int usrX = x;
    int usrY = y;

    // [tav] Handle the fact that an owned window is most likely positioned relative to its owner, and it may
    // require pixel-perfect alignment. For that, compensate rounding errors (caused by converting from the device
    // space to the integer user space and back) for the owner's origin and for the owner's client area origin
    // (see Window::GetAlignedInsets).
    AwtComponent* parent = GetParent();
    if (parent != NULL && (device->GetScaleX() > 1 || device->GetScaleY() > 1)) {
        RECT parentInsets;
        parent->GetInsets(&parentInsets);
        // Convert the owner's client area origin to user space
        int parentInsetsUsrX = device->ScaleDownX(parentInsets.left);
        int parentInsetsUsrY = device->ScaleDownY(parentInsets.top);

        RECT parentRect;
        VERIFY(::GetWindowRect(parent->GetHWnd(), &parentRect));
        // Convert the owner's origin to user space
        int parentUsrX = device->ScaleDownAbsX(parentRect.left);
        int parentUsrY = device->ScaleDownAbsY(parentRect.top);

        // Calc the offset from the owner's client area in user space
        int offsetUsrX = usrX - parentUsrX - parentInsetsUsrX;
        int offsetUsrY = usrY - parentUsrY - parentInsetsUsrY;

        // Convert the offset to device space
        int offsetDevX = device->ScaleUpX(offsetUsrX);
        int offsetDevY = device->ScaleUpY(offsetUsrY);

        // Finally calc the window's location based on the frame's and its insets system numbers.
        int devX = parentRect.left + parentInsets.left + offsetDevX;
        int devY = parentRect.top + parentInsets.top + offsetDevY;

        // Check the toplevel is not going to be moved to another screen.
        ::SetRect(&parentRect, devX, devY, devX + w, devY + h);
        HMONITOR hmon = ::MonitorFromRect(&parentRect, MONITOR_DEFAULTTONEAREST);
        if (hmon != NULL && AwtWin32GraphicsDevice::GetScreenFromHMONITOR(hmon) == device->GetDeviceIndex()) {
            scaleUpAbsX = devX;
            scaleUpAbsY = devY;
        }
    }

    ReshapeNoScale(scaleUpAbsX, scaleUpAbsY, ScaleUpX(w), ScaleUpY(h));
    // The window manager may tweak the size for different reasons, so try
    // to make sure our window has the correct size in the user's space.
    // NOOP if the size was changed already or changing is in progress.
    RECT rc;
    ::GetWindowRect(GetHWnd(), &rc);
    ReshapeNoScale(rc.left, rc.top, ScaleUpX(w), ScaleUpY(h));
    // the window manager may ignore our "SetWindowPos" request, in this,
    // case the WmMove/WmSize will not come and we need to manually resync
    // the "java.awt.Window" locations, because "java.awt.Window" already
    // uses location ignored by the window manager.
    ::GetWindowRect(GetHWnd(), &rc);
    if (x != ScaleDownAbsX(rc.left) || y != ScaleDownAbsY(rc.top)) {
        WmMove(rc.left, rc.top);
    }
    int userW = ScaleDownX(rc.right - rc.left);
    int userH = ScaleDownY(rc.bottom - rc.top);
    if (w != userW || h != userH) {
        WmSize(SIZENORMAL, rc.right - rc.left, rc.bottom - rc.top);
    }
}

void AwtWindow::Show()
{
    m_visible = true;
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);
    BOOL  done = false;
    HWND hWnd = GetHWnd();

    if (env->EnsureLocalCapacity(2) < 0) {
        return;
    }
    jobject target = GetTarget(env);
    INT nCmdShow;

    AwtFrame* owningFrame = GetOwningFrameOrDialog();
    if (IsFocusableWindow() && IsAutoRequestFocus() && owningFrame != NULL &&
        ::GetForegroundWindow() == owningFrame->GetHWnd())
    {
        nCmdShow = SW_SHOW;
    } else {
        nCmdShow = SW_SHOWNA;
    }

    BOOL locationByPlatform = env->GetBooleanField(GetTarget(env), AwtWindow::locationByPlatformID);

    if (locationByPlatform) {
         moveToDefaultLocation();
    }

    EnableTranslucency(TRUE);

    // The following block exists to support Menu/Tooltip animation for
    // Swing programs in a way which avoids introducing any new public api into
    // AWT or Swing.
    // This code should eventually be replaced by a better longterm solution
    // which might involve tagging java.awt.Window instances with a semantic
    // property so platforms can animate/decorate/etc accordingly.
    //
    if (JNU_IsInstanceOfByName(env, target, "com/sun/java/swing/plaf/windows/WindowsPopupWindow") > 0)
    {
        // need this global ref to make the class unloadable (see 6500204)
        static jclass windowsPopupWindowCls;
        static jfieldID windowTypeFID = NULL;
        jint windowType = 0;
        BOOL  animateflag = FALSE;
        BOOL  fadeflag = FALSE;
        DWORD animateStyle = 0;

        if (windowTypeFID == NULL) {
            // Initialize Window type constants ONCE...

            jfieldID windowTYPESFID[TYPES_COUNT];
            jclass cls = env->GetObjectClass(target);
            windowTypeFID = env->GetFieldID(cls, "windowType", "I");

            windowTYPESFID[UNSPECIFIED] = env->GetStaticFieldID(cls, "UNDEFINED_WINDOW_TYPE", "I");
            windowTYPESFID[TOOLTIP] = env->GetStaticFieldID(cls, "TOOLTIP_WINDOW_TYPE", "I");
            windowTYPESFID[MENU] = env->GetStaticFieldID(cls, "MENU_WINDOW_TYPE", "I");
            windowTYPESFID[SUBMENU] = env->GetStaticFieldID(cls, "SUBMENU_WINDOW_TYPE", "I");
            windowTYPESFID[POPUPMENU] = env->GetStaticFieldID(cls, "POPUPMENU_WINDOW_TYPE", "I");
            windowTYPESFID[COMBOBOX_POPUP] = env->GetStaticFieldID(cls, "COMBOBOX_POPUP_WINDOW_TYPE", "I");

            for (int i=0; i < 6; i++) {
                windowTYPES[i] = env->GetStaticIntField(cls, windowTYPESFID[i]);
            }
            windowsPopupWindowCls = (jclass) env->NewGlobalRef(cls);
            env->DeleteLocalRef(cls);
        }
        windowType = env->GetIntField(target, windowTypeFID);

        if (windowType == windowTYPES[TOOLTIP]) {
            SystemParametersInfo(SPI_GETTOOLTIPANIMATION, 0, &animateflag, 0);
            SystemParametersInfo(SPI_GETTOOLTIPFADE, 0, &fadeflag, 0);
            if (animateflag) {
              // AW_BLEND currently produces runtime parameter error
              // animateStyle = fadeflag? AW_BLEND : AW_SLIDE | AW_VER_POSITIVE;
                 animateStyle = fadeflag? 0 : AW_SLIDE | AW_VER_POSITIVE;
            }
        } else if (windowType == windowTYPES[MENU] || windowType == windowTYPES[SUBMENU] ||
                   windowType == windowTYPES[POPUPMENU]) {
            SystemParametersInfo(SPI_GETMENUANIMATION, 0, &animateflag, 0);
            if (animateflag) {
                SystemParametersInfo(SPI_GETMENUFADE, 0, &fadeflag, 0);
                if (fadeflag) {
                    // AW_BLEND currently produces runtime parameter error
                    //animateStyle = AW_BLEND;
                }
                if (animateStyle == 0 && !fadeflag) {
                    animateStyle = AW_SLIDE;
                    if (windowType == windowTYPES[MENU]) {
                      animateStyle |= AW_VER_POSITIVE;
                    } else if (windowType == windowTYPES[SUBMENU]) {
                      animateStyle |= AW_HOR_POSITIVE;
                    } else { /* POPUPMENU */
                      animateStyle |= (AW_VER_POSITIVE | AW_HOR_POSITIVE);
                    }
                }
            }
        } else if (windowType == windowTYPES[COMBOBOX_POPUP]) {
            SystemParametersInfo(SPI_GETCOMBOBOXANIMATION, 0, &animateflag, 0);
            if (animateflag) {
                 animateStyle = AW_SLIDE | AW_VER_POSITIVE;
            }
        }

        if (animateStyle != 0) {
            BOOL result = ::AnimateWindow(hWnd, (DWORD)200, animateStyle);
            if (!result) {
                // TODO: log message
            } else {
                // WM_PAINT is not automatically sent when invoking AnimateWindow,
                // so force an expose event
                RECT rect;
                ::GetWindowRect(hWnd,&rect);
                ::ScreenToClient(hWnd, (LPPOINT)&rect);
                ::InvalidateRect(hWnd, &rect, TRUE);
                ::UpdateWindow(hWnd);
                done = TRUE;
            }
        }
    }
    if (!done) {
        // transient windows shouldn't change the owner window's position in the z-order
        if (IsRetainingHierarchyZOrder() || m_isIgnoringMouseEvents) {
            UINT flags = SWP_NOSIZE | SWP_NOMOVE | SWP_SHOWWINDOW | SWP_NOOWNERZORDER;
            if (nCmdShow == SW_SHOWNA || m_isIgnoringMouseEvents) {
                flags |= SWP_NOACTIVATE;
            }
            HWND hInsertAfter = HWND_TOP;
            if (m_isIgnoringMouseEvents) {
                HWND hFgWindow = ::GetForegroundWindow();
                HWND hOwner = ::GetWindow(GetHWnd(), GW_OWNER);
                if (hFgWindow != NULL && hOwner != hFgWindow) {
                    hInsertAfter = ::GetWindow(hOwner, GW_HWNDPREV); // insert below the wnd above the owner
                }
            }
            ::SetWindowPos(GetHWnd(), hInsertAfter, 0, 0, 0, 0, flags);
        } else {
            ::ShowWindow(GetHWnd(), nCmdShow);
        }
    }
    env->DeleteLocalRef(target);
}

/*
 * Get and return the insets for this window (container, really).
 * Calculate & cache them while we're at it, for use by AwtGraphics
 */
BOOL AwtWindow::UpdateInsets(jobject insets)
{
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);
    DASSERT(GetPeer(env) != NULL);
    if (env->EnsureLocalCapacity(2) < 0) {
        return FALSE;
    }

    // fix 4167248 : don't update insets when frame is iconified
    // to avoid bizarre window/client rectangles
    if (::IsIconic(GetHWnd())) {
        return FALSE;
    }

    /*
     * Code to calculate insets. Stores results in frame's data
     * members, and in the peer's Inset object.
     */
    RECT outside;
    RECT inside;

    // extra padded border for captioned windows
    int extraPaddedBorderInsets = ::GetSystemMetrics(SM_CXPADDEDBORDER);

    ::GetClientRect(GetHWnd(), &inside);
    ::GetWindowRect(GetHWnd(), &outside);

    /* Update our inset member */
    if (outside.right - outside.left > 0 && outside.bottom - outside.top > 0) {
        ::MapWindowPoints(GetHWnd(), 0, (LPPOINT)&inside, 2);
        m_insets.top = inside.top - outside.top;
        m_insets.bottom = outside.bottom - inside.bottom;
        m_insets.left = inside.left - outside.left;
        m_insets.right = outside.right - inside.right;
    } else {
        m_insets.top = -1;
    }

    if (m_insets.left < 0 || m_insets.top < 0 ||
        m_insets.right < 0 || m_insets.bottom < 0)
    {
        /* This window hasn't been sized yet -- use system metrics. */
        jobject target = GetTarget(env);
        if (IsUndecorated() == FALSE) {
            /* Get outer frame sizes. */
            // System metrics are same for resizable & non-resizable frame.
            m_insets.left = m_insets.right =
                ::GetSystemMetrics(SM_CXFRAME) + extraPaddedBorderInsets;
            m_insets.top = m_insets.bottom =
                ::GetSystemMetrics(SM_CYFRAME) + extraPaddedBorderInsets;
            /* Add in title. */
            m_insets.top += ::GetSystemMetrics(SM_CYCAPTION);
        }
        else {
            /* fix for 4418125: Undecorated frames are off by one */
            /* undo the -1 set above */
            /* Additional fix for 5059656 */
            /* Also, 5089312: Window insets should be 0. */
            ::memset(&m_insets, 0, sizeof(m_insets));
        }

        /* Add in menuBar, if any. */
        if (JNU_IsInstanceOfByName(env, target, "java/awt/Frame") > 0 &&
            ((AwtFrame*)this)->GetMenuBar()) {
            m_insets.top += ::GetSystemMetrics(SM_CYMENU);
        }
        if (env->ExceptionCheck()) {
            env->DeleteLocalRef(target);
            return FALSE;
        }
        env->DeleteLocalRef(target);
    }

    BOOL insetsChanged = FALSE;

    jobject peer = GetPeer(env);
    /* Get insets into our peer directly */
    jobject peerInsets = (env)->GetObjectField(peer, AwtPanel::insets_ID);
    DASSERT(!safe_ExceptionOccurred(env));

    jobject peerSysInsets = (env)->GetObjectField(peer, AwtWindow::sysInsetsID);
    DASSERT(!safe_ExceptionOccurred(env));

    // Floor resulting insets
    int screen = GetScreenImOn();
    Devices::InstanceAccess devices;
    AwtWin32GraphicsDevice* device = devices->GetDevice(screen);
    float scaleX = device == NULL ? 1.0f : device->GetScaleX();
    float scaleY = device == NULL ? 1.0f : device->GetScaleY();
    RECT result;
    result.top = (LONG) floor(m_insets.top / scaleY);
    result.bottom = (LONG) floor(m_insets.bottom / scaleY);
    result.left = (LONG) floor(m_insets.left / scaleX);
    result.right = (LONG) floor(m_insets.right / scaleX);

    if (peerInsets != NULL) { // may have been called during creation
        (env)->SetIntField(peerInsets, AwtInsets::topID, result.top);
        (env)->SetIntField(peerInsets, AwtInsets::bottomID, result.bottom);
        (env)->SetIntField(peerInsets, AwtInsets::leftID, result.left);
        (env)->SetIntField(peerInsets, AwtInsets::rightID, result.right);
    }
    if (peerSysInsets != NULL) {
        (env)->SetIntField(peerSysInsets, AwtInsets::topID, m_insets.top);
        (env)->SetIntField(peerSysInsets, AwtInsets::bottomID, m_insets.bottom);
        (env)->SetIntField(peerSysInsets, AwtInsets::leftID, m_insets.left);
        (env)->SetIntField(peerSysInsets, AwtInsets::rightID, m_insets.right);
    }
    /* Get insets into the Inset object (if any) that was passed */
    if (insets != NULL) {
        (env)->SetIntField(insets, AwtInsets::topID, result.top);
        (env)->SetIntField(insets, AwtInsets::bottomID, result.bottom);
        (env)->SetIntField(insets, AwtInsets::leftID, result.left);
        (env)->SetIntField(insets, AwtInsets::rightID, result.right);
    }
    env->DeleteLocalRef(peerInsets);

    insetsChanged = !::EqualRect( &m_old_insets, &m_insets );
    ::CopyRect( &m_old_insets, &m_insets );

    if (insetsChanged) {
        // Since insets are changed we need to update the surfaceData object
        // to reflect that change
        env->CallVoidMethod(peer, AwtComponent::replaceSurfaceDataLaterMID);
    }

    return insetsChanged;
}

/**
 * Sometimes we need the hWnd that actually owns this Window's hWnd (if
 * there is an owner).
 */
HWND AwtWindow::GetTopLevelHWnd()
{
    return m_owningFrameDialog ? m_owningFrameDialog->GetHWnd() :
                                 GetHWnd();
}

/*
 * Although this function sends ComponentEvents, it needs to be defined
 * here because only top-level windows need to have move and resize
 * events fired from native code.  All contained windows have these events
 * fired from common Java code.
 */
void AwtWindow::SendComponentEvent(jint eventId)
{
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    static jclass classEvent = NULL;
    if (classEvent == NULL) {
        if (env->PushLocalFrame(1) < 0)
            return;
        classEvent = env->FindClass("java/awt/event/ComponentEvent");
        if (classEvent != NULL) {
            classEvent = (jclass)env->NewGlobalRef(classEvent);
        }
        env->PopLocalFrame(0);
        CHECK_NULL(classEvent);
    }
    static jmethodID eventInitMID = NULL;
    if (eventInitMID == NULL) {
        eventInitMID = env->GetMethodID(classEvent, "<init>",
                                        "(Ljava/awt/Component;I)V");
        CHECK_NULL(eventInitMID);
    }
    if (env->EnsureLocalCapacity(2) < 0) {
        return;
    }
    jobject target = GetTarget(env);
    jobject event = env->NewObject(classEvent, eventInitMID,
                                   target, eventId);
    DASSERT(!safe_ExceptionOccurred(env));
    DASSERT(event != NULL);
    if (event == NULL) {
        env->DeleteLocalRef(target);
        return;
    }
    SendEvent(event);

    env->DeleteLocalRef(target);
    env->DeleteLocalRef(event);
}

static void SendPriorityEvent(jobject event) {
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    static jclass toolkitClass;
    if (toolkitClass == NULL) {
        toolkitClass = env->FindClass("sun/awt/SunToolkit");
        if (toolkitClass != NULL) {
            toolkitClass = (jclass)env->NewGlobalRef(toolkitClass);
        }
        if (toolkitClass == NULL) {
            return;
        }
    }

    static jmethodID postPriorityEventMID;
    if (postPriorityEventMID == NULL) {
        postPriorityEventMID =
            env->GetStaticMethodID(toolkitClass, "postPriorityEvent",
                             "(Ljava/awt/AWTEvent;)V");
        DASSERT(postPriorityEventMID);
        if (postPriorityEventMID == NULL) {
            return;
        }
    }

    env->CallStaticVoidMethod(toolkitClass, postPriorityEventMID, event);
}

void AwtWindow::SendWindowEvent(jint id, HWND opposite,
                                jint oldState, jint newState)
{
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    static jclass wClassEvent;
    if (wClassEvent == NULL) {
        if (env->PushLocalFrame(1) < 0)
            return;
        wClassEvent = env->FindClass("sun/awt/TimedWindowEvent");
        if (wClassEvent != NULL) {
            wClassEvent = (jclass)env->NewGlobalRef(wClassEvent);
        }
        env->PopLocalFrame(0);
        if (wClassEvent == NULL) {
            return;
        }
    }

    static jmethodID wEventInitMID;
    if (wEventInitMID == NULL) {
        wEventInitMID =
            env->GetMethodID(wClassEvent, "<init>",
                             "(Ljava/awt/Window;ILjava/awt/Window;IIJ)V");
        DASSERT(wEventInitMID);
        if (wEventInitMID == NULL) {
            return;
        }
    }

    static jclass windowCls = NULL;
    if (windowCls == NULL) {
        jclass windowClsLocal = env->FindClass("java/awt/Window");
        CHECK_NULL(windowClsLocal);
        windowCls = (jclass)env->NewGlobalRef(windowClsLocal);
        env->DeleteLocalRef(windowClsLocal);
        CHECK_NULL(windowCls);
    }

    if (env->EnsureLocalCapacity(3) < 0) {
        return;
    }

    jobject target = GetTarget(env);
    jobject jOpposite = NULL;
    if (opposite != NULL) {
        AwtComponent *awtOpposite = AwtComponent::GetComponent(opposite);
        if (awtOpposite != NULL) {
            jOpposite = awtOpposite->GetTarget(env);
            if ((jOpposite != NULL) &&
                !env->IsInstanceOf(jOpposite, windowCls)) {
                env->DeleteLocalRef(jOpposite);
                jOpposite = NULL;

                HWND parent = AwtComponent::GetTopLevelParentForWindow(opposite);
                if ((parent != NULL) && (parent != opposite)) {
                    if (parent == GetHWnd()) {
                        jOpposite = env->NewLocalRef(target);
                    } else {
                        AwtComponent* awtParent = AwtComponent::GetComponent(parent);
                        if (awtParent != NULL) {
                            jOpposite = awtParent->GetTarget(env);
                            if ((jOpposite != NULL) &&
                                !env->IsInstanceOf(jOpposite, windowCls)) {
                                env->DeleteLocalRef(jOpposite);
                                jOpposite = NULL;
                            }
                        }
                    }
                }
            }
        }
    }
    jobject event = env->NewObject(wClassEvent, wEventInitMID, target, id,
                                   jOpposite, oldState, newState, ::JVM_CurrentTimeMillis(NULL, 0));
    DASSERT(!safe_ExceptionOccurred(env));
    DASSERT(event != NULL);
    if (jOpposite != NULL) {
        env->DeleteLocalRef(jOpposite); jOpposite = NULL;
    }
    env->DeleteLocalRef(target); target = NULL;
    CHECK_NULL(event);

    if (id == java_awt_event_WindowEvent_WINDOW_GAINED_FOCUS ||
        id == java_awt_event_WindowEvent_WINDOW_LOST_FOCUS)
    {
        SendPriorityEvent(event);
    } else {
        SendEvent(event);
    }

    env->DeleteLocalRef(event);
}

void AwtWindow::NotifyWindowStateChanged(jint oldState, jint newState)
{
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);
    jobject peer = GetPeer(env);
    if (peer != NULL) {
        env->CallVoidMethod(peer, AwtWindow::notifyWindowStateChangedMID,
            oldState, newState);
    }
}

BOOL AwtWindow::AwtSetActiveWindow(BOOL isMouseEventCause, UINT hittest)
{
    // We used to reject non mouse window activation if our app wasn't active.
    // This code since has been removed as the fix for 7185280

    HWND proxyContainerHWnd = GetProxyToplevelContainer();
    HWND proxyHWnd = GetProxyFocusOwner();

    if (proxyContainerHWnd == NULL || proxyHWnd == NULL) {
        return FALSE;
    }

    // Activate the proxy toplevel container
    if (::GetActiveWindow() != proxyContainerHWnd) {
        sm_suppressFocusAndActivation = TRUE;
        ::BringWindowToTop(proxyContainerHWnd);
        ::SetForegroundWindow(proxyContainerHWnd);
        sm_suppressFocusAndActivation = FALSE;

        if (::GetActiveWindow() != proxyContainerHWnd) {
            return FALSE; // activation has been rejected
        }
    }

    // Focus the proxy itself
    if (::GetFocus() != proxyHWnd) {
        sm_suppressFocusAndActivation = TRUE;
        ::SetFocus(proxyHWnd);
        sm_suppressFocusAndActivation = FALSE;

        if (::GetFocus() != proxyHWnd) {
            return FALSE; // focus has been rejected (that is unlikely)
        }
    }

    const HWND focusedWindow = AwtComponent::GetFocusedWindow();
    if (focusedWindow != GetHWnd()) {
        if (focusedWindow != NULL) {
            // Deactivate the old focused window
            AwtWindow::SynthesizeWmActivate(FALSE, focusedWindow, GetHWnd());
        }
        // Activate the new focused window.
        AwtWindow::SynthesizeWmActivate(TRUE, GetHWnd(), focusedWindow);
    }
    return TRUE;
}

MsgRouting AwtWindow::WmActivate(UINT nState, BOOL fMinimized, HWND opposite)
{
    jint type;

    if (nState != WA_INACTIVE) {
        type = java_awt_event_WindowEvent_WINDOW_GAINED_FOCUS;
        AwtComponent::SetFocusedWindow(GetHWnd());
    } else {
        // The owner is not necassarily getting WM_ACTIVATE(WA_INACTIVE).
        // So, initiate retaining the actualFocusedWindow.
        AwtFrame *owner = GetOwningFrameOrDialog();
        if (owner) {
            owner->CheckRetainActualFocusedWindow(opposite);
        }

        if (m_grabbedWindow != NULL && !m_grabbedWindow->IsOneOfOwnersOf(this)) {
            m_grabbedWindow->Ungrab();
        }
        type = java_awt_event_WindowEvent_WINDOW_LOST_FOCUS;
        AwtComponent::SetFocusedWindow(NULL);
        sm_focusOwner = NULL;
    }

    SendWindowEvent(type, opposite);
    return mrConsume;
}

MsgRouting AwtWindow::WmCreate()
{
    return mrDoDefault;
}

MsgRouting AwtWindow::WmClose()
{
    SendWindowEvent(java_awt_event_WindowEvent_WINDOW_CLOSING);

    /* Rely on above notification to handle quitting as needed */
    return mrConsume;
}

MsgRouting AwtWindow::WmDestroy()
{
    SendWindowEvent(java_awt_event_WindowEvent_WINDOW_CLOSED);
    return AwtComponent::WmDestroy();
}

MsgRouting AwtWindow::WmShowWindow(BOOL show, UINT status)
{
    /*
     * Original fix for 4810575. Modified for 6386592.
     * If a simple window gets disposed we should synthesize
     * WM_ACTIVATE for its nearest focusable owner. This is not performed by default because
     * the owner frame/dialog is natively active.
     */
    HWND hwndSelf = GetHWnd();
    HWND hwndOwner = ::GetParent(hwndSelf);

    if (!show && IsSimpleWindow() && hwndSelf == AwtComponent::GetFocusedWindow()) {
        while (hwndOwner != NULL && ::IsWindowVisible(hwndOwner)) {
            AwtWindow *owner = (AwtWindow*)AwtComponent::GetComponent(hwndOwner);
            if (owner != NULL && owner->IsFocusableWindow()) {
                owner->AwtSetActiveWindow();
                break;
            }
            hwndOwner = ::GetParent(hwndOwner);
        }
    }

    //Fixed 4842599: REGRESSION: JPopupMenu not Hidden Properly After Iconified and Deiconified
    if (show && (status == SW_PARENTOPENING)) {
        if (!IsVisible()) {
            return mrConsume;
        }
    }
    return AwtCanvas::WmShowWindow(show, status);
}

void AwtWindow::WmDPIChanged(const LPARAM &lParam) {
    // need to update the scales now, otherwise the ReshapeNoScale() will
    // calculate the bounds wrongly
    AwtWin32GraphicsDevice::ResetAllDesktopScales();
    RECT *r = (RECT *) lParam;
    ReshapeNoScale(r->left, r->top, r->right - r->left, r->bottom - r->top);
    CheckIfOnNewScreen(true);
    WmSize(GetCurrentWmSizeType(), r->right - r->left, r->bottom - r->top);
}

MsgRouting AwtWindow::WmEraseBkgnd(HDC hDC, BOOL& didErase)
{
    if (!IsUndecorated()) {
        // [tav] When an undecorated window is shown nothing is actually displayed
        // until something is drawn in it. In order to prevent blinking, the background
        // is not erased for such windows.
        RECT     rc;
        ::GetClipBox(hDC, &rc);
        ::FillRect(hDC, &rc, this->GetBackgroundBrush());
    }
    didErase = TRUE;
    return mrConsume;
}

/*
 * Override AwtComponent's move handling to first update the
 * java AWT target's position fields directly, since Windows
 * and below can be resized from outside of java (by user)
 */
MsgRouting AwtWindow::WmMove(int x, int y)
{
    if ( ::IsIconic(GetHWnd()) ) {
    // fixes 4065534 (robi.khan@eng)
    // if a window is iconified we don't want to update
    // it's target's position since minimized Win32 windows
    // move to -32000, -32000 for whatever reason
    // NOTE: See also AwtWindow::Reshape
        return mrDoDefault;
    }

    if (CheckIfOnNewScreen(false)) DoUpdateIcon();

    /* Update the java AWT target component's fields directly */
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);
    if (env->EnsureLocalCapacity(1) < 0) {
        return mrConsume;
    }
    jobject target = GetTarget(env);

    RECT rect;
    ::GetWindowRect(GetHWnd(), &rect);

    // [tav] Convert x/y to user space, asymmetrically to AwtWindow::Reshape()
    POINT pt = {rect.left + (rect.right - rect.left) / 2, rect.top + (rect.bottom - rect.top) / 2};
    Devices::InstanceAccess devices;
    HMONITOR monitor = MonitorFromPoint(pt, MONITOR_DEFAULTTONEAREST);
    int screen = AwtWin32GraphicsDevice::GetScreenFromHMONITOR(monitor);
    AwtWin32GraphicsDevice *device = devices->GetDevice(screen);

    int usrX = ScaleDownAbsX(rect.left);
    int usrY = ScaleDownAbsY(rect.top);

    AwtComponent* parent = GetParent();
    if (parent != NULL && (device->GetScaleX() > 1 || device->GetScaleY() > 1)) {

        RECT parentInsets;
        parent->GetInsets(&parentInsets);
        // Convert the owner's client area origin to user space
        int parentInsetsUsrX = device->ScaleDownX(parentInsets.left);
        int parentInsetsUsrY = device->ScaleDownY(parentInsets.top);

        RECT parentRect;
        VERIFY(::GetWindowRect(parent->GetHWnd(), &parentRect));
        // Convert the owner's origin to user space
        int parentUsrX = device->ScaleDownAbsX(parentRect.left);
        int parentUsrY = device->ScaleDownAbsY(parentRect.top);

        // Calc the offset from the owner's client area in device space
        int offsetDevX = rect.left - parentRect.left - parentInsets.left;
        int offsetDevY = rect.top - parentRect.top - parentInsets.top;

        // Convert the offset to user space
        int offsetUsrX = device->ScaleDownX(offsetDevX);
        int offsetUsrY = device->ScaleDownY(offsetDevY);

        // Finally calc the window's location based on the frame's and its insets user space values.
        usrX = parentUsrX + parentInsetsUsrX + offsetUsrX;
        usrY = parentUsrY + parentInsetsUsrY + offsetUsrY;
    }

    (env)->SetIntField(target, AwtComponent::xID, usrX);
    (env)->SetIntField(target, AwtComponent::yID, usrY);
    SendComponentEvent(java_awt_event_ComponentEvent_COMPONENT_MOVED);

    env->DeleteLocalRef(target);
    return AwtComponent::WmMove(x, y);
}

MsgRouting AwtWindow::WmGetMinMaxInfo(LPMINMAXINFO lpmmi)
{
    MsgRouting r = AwtCanvas::WmGetMinMaxInfo(lpmmi);
    if ((m_minSize.x == 0) && (m_minSize.y == 0)) {
        return r;
    }
    lpmmi->ptMinTrackSize.x = ScaleUpX(m_minSize.x);
    lpmmi->ptMinTrackSize.y = ScaleUpY(m_minSize.y);
    return mrConsume;
}

MsgRouting AwtWindow::WmSizing()
{
    if (!AwtToolkit::GetInstance().IsDynamicLayoutActive()) {
        return mrDoDefault;
    }

    DTRACE_PRINTLN("AwtWindow::WmSizing  fullWindowDragEnabled");

    SendComponentEvent(java_awt_event_ComponentEvent_COMPONENT_RESIZED);

    HWND thisHwnd = GetHWnd();
    if (thisHwnd == NULL) {
        return mrDoDefault;
    }

    // Call WComponentPeer::dynamicallyLayoutContainer()
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);
    jobject peer = GetPeer(env);
    JNU_CallMethodByName(env, NULL, peer, "dynamicallyLayoutContainer", "()V");
    DASSERT(!safe_ExceptionOccurred(env));

    return mrDoDefault;
}

MsgRouting AwtWindow::WmEnterSizeMove()
{
    m_winSizeMove = TRUE;
    // Below is a workaround, see CheckWindowDPIChange
    Devices::InstanceAccess devices;
    AwtWin32GraphicsDevice* device = devices->GetDevice(m_screenNum);
    if (device) {
        prevScaleRec.screen = m_screenNum;
        prevScaleRec.scaleX = device->GetScaleX();
        prevScaleRec.scaleY = device->GetScaleY();
    }
    // Above is a workaround
    return mrDoDefault;
}

MsgRouting AwtWindow::WmExitSizeMove()
{
    m_winSizeMove = FALSE;
    CheckWindowDPIChange(); // workaround
    return mrDoDefault;
}

/*
 * Override AwtComponent's size handling to first update the
 * java AWT target's dimension fields directly, since Windows
 * and below can be resized from outside of java (by user)
 */
MsgRouting AwtWindow::WmSize(UINT type, int w, int h)
{
    currentWmSizeState = type;

    if (type == SIZE_MINIMIZED) {
        return mrDoDefault;
    }

    if (CheckIfOnNewScreenWithDifferentScale()) { // postpone if different DPI
        return mrDoDefault;
    }

    // Check for the new screen and update the java peer
    CheckIfOnNewScreen(false);

    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);
    if (env->EnsureLocalCapacity(1) < 0)
        return mrDoDefault;
    jobject target = GetTarget(env);
    // fix 4167248 : ensure the insets are up-to-date before using
    BOOL insetsChanged = UpdateInsets(NULL);
    (env)->SetIntField(target, AwtComponent::widthID, ScaleDownX(w));
    (env)->SetIntField(target, AwtComponent::heightID, ScaleDownY(h));

    if (!AwtWindow::IsResizing()) {
        WindowResized();
    }

    env->DeleteLocalRef(target);
    return AwtComponent::WmSize(type, w, h);
}

MsgRouting AwtWindow::WmPaint(HDC)
{
    PaintUpdateRgn(&m_insets);
    return mrConsume;
}

MsgRouting AwtWindow::WmSettingChange(UINT wFlag, LPCTSTR pszSection)
{
    if (wFlag == SPI_SETNONCLIENTMETRICS) {
    // user changed window metrics in
    // Control Panel->Display->Appearance
    // which may cause window insets to change
        UpdateInsets(NULL);

    // [rray] fix for 4407329 - Changing Active Window Border width in display
    //  settings causes problems
        WindowResized();
        Invalidate(NULL);

        return mrConsume;
    }
    return mrDoDefault;
}

MsgRouting AwtWindow::WmNcCalcSize(BOOL fCalcValidRects,
                                   LPNCCALCSIZE_PARAMS lpncsp, LRESULT& retVal)
{
    MsgRouting mrRetVal = mrDoDefault;

    if (fCalcValidRects == FALSE) {
        return mrDoDefault;
    }
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);
    if (env->EnsureLocalCapacity(2) < 0) {
        return mrConsume;
    }
    // WM_NCCALCSIZE is usually in response to a resize, but
    // also can be triggered by SetWindowPos(SWP_FRAMECHANGED),
    // which means the insets will have changed - rnk 4/7/1998
    retVal = static_cast<UINT>(DefWindowProc(
                WM_NCCALCSIZE, fCalcValidRects, reinterpret_cast<LPARAM>(lpncsp)));
    if (HasValidRect()) {
        UpdateInsets(NULL);
    }
    mrRetVal = mrConsume;
    return mrRetVal;
}

MsgRouting AwtWindow::WmNcHitTest(int x, int y, LRESULT& retVal)
{
    // If this window is blocked by modal dialog, return HTCLIENT for any point of it.
    // That prevents it to be moved or resized using the mouse. Disabling these
    // actions to be launched from sysmenu is implemented by ignoring WM_SYSCOMMAND
    if (::IsWindow(GetModalBlocker(GetHWnd()))) {
        retVal = HTCLIENT;
    } else {
        retVal = DefWindowProc(WM_NCHITTEST, 0, MAKELPARAM(x, y));
    }
    return mrConsume;
}

MsgRouting AwtWindow::WmGetIcon(WPARAM iconType, LRESULT& retValue)
{
    return mrDoDefault;
}

LRESULT AwtWindow::WindowProc(UINT message, WPARAM wParam, LPARAM lParam)
{
    MsgRouting mr = mrDoDefault;
    LRESULT retValue = 0L;

    switch(message) {
        case WM_DPICHANGED: {
            WmDPIChanged(lParam);
            mr = mrConsume;
            break;
        }
        case WM_GETICON:
            mr = WmGetIcon(wParam, retValue);
            break;
        case WM_SYSCOMMAND:
            //Fixed 6355340: Contents of frame are not laid out properly on maximize
            if ((wParam & 0xFFF0) == SC_SIZE) {
                AwtWindow::sm_resizing = TRUE;
                mr = WmSysCommand(wParam, GET_X_LPARAM(lParam), GET_Y_LPARAM(lParam));
                if (mr != mrConsume) {
                    // Perform size-move loop here
                    AwtWindow::DefWindowProc(message, wParam, lParam);
                }
                AwtWindow::sm_resizing = FALSE;
                if (!AwtToolkit::GetInstance().IsDynamicLayoutActive()) {
                    WindowResized();
                } else {
                    /*
                     * 8016356: check whether window snapping occurred after
                     * resizing, i.e. GetWindowRect() returns the real
                     * (snapped) window rectangle, e.g. (179, 0)-(483, 1040),
                     * but GetWindowPlacement() returns the rectangle of
                     * normal window position, e.g. (179, 189)-(483, 445) and
                     * they are different. If so, send ComponentResized event.
                     */
                    WINDOWPLACEMENT wp;
                    ::GetWindowPlacement(GetHWnd(), &wp);
                    RECT rc;
                    ::GetWindowRect(GetHWnd(), &rc);
                    if (!::EqualRect(&rc, &wp.rcNormalPosition)) {
                        WindowResized();
                    }
                }
                mr = mrConsume;
            }
            break;
    }

    if (mr != mrConsume) {
        retValue = AwtCanvas::WindowProc(message, wParam, lParam);
    }
    return retValue;
}

/*
 * Fix for BugTraq ID 4041703: keyDown not being invoked.
 * This method overrides AwtCanvas::HandleEvent() since
 * an empty Window always receives the focus on the activation
 * so we don't have to modify the behavior.
 */
MsgRouting AwtWindow::HandleEvent(MSG *msg, BOOL synthetic)
{
    return AwtComponent::HandleEvent(msg, synthetic);
}

void AwtWindow::WindowResized()
{
    SendComponentEvent(java_awt_event_ComponentEvent_COMPONENT_RESIZED);
    // Need to replace surfaceData on resize to catch changes to
    // various component-related values, such as insets
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);
    env->CallVoidMethod(m_peerObject, AwtComponent::replaceSurfaceDataLaterMID);
}

BOOL CALLBACK InvalidateChildRect(HWND hWnd, LPARAM)
{
    TRY;

    ::InvalidateRect(hWnd, NULL, TRUE);
    return TRUE;

    CATCH_BAD_ALLOC_RET(FALSE);
}

void AwtWindow::Invalidate(RECT* r)
{
    ::InvalidateRect(GetHWnd(), NULL, TRUE);
    ::EnumChildWindows(GetHWnd(), (WNDENUMPROC)InvalidateChildRect, 0);
}

BOOL AwtWindow::IsResizable() {
    return m_isResizable;
}

void AwtWindow::SetResizable(BOOL isResizable)
{
    m_isResizable = isResizable;
    if (IsEmbeddedFrame()) {
        return;
    }
    LONG style = GetStyle();
    LONG resizeStyle = WS_MAXIMIZEBOX;

    if (IsUndecorated() == FALSE) {
        resizeStyle |= WS_THICKFRAME;
    }

    if (isResizable) {
        style |= resizeStyle;
    } else {
        style &= ~resizeStyle;
    }
    SetStyle(style);
    RedrawNonClient();
}

//
// Forces WM_NCCALCSIZE to be called to recalculate
// window border (updates insets) without redrawing it
//
void AwtWindow::RecalcNonClient()
{
    ::SetWindowPos(GetHWnd(), (HWND) NULL, 0, 0, 0, 0, SwpFrameChangeFlags|SWP_NOREDRAW);
}

//
// Forces WM_NCCALCSIZE to be called to recalculate
// window border (updates insets) and redraws border to match
//
void AwtWindow::RedrawNonClient()
{
    ::SetWindowPos(GetHWnd(), (HWND) NULL, 0, 0, 0, 0, SwpFrameChangeFlags|SWP_ASYNCWINDOWPOS);
}

int AwtWindow::GetScreenImOn() {
    HMONITOR hmon;
    int scrnNum;

    hmon = ::MonitorFromWindow(GetHWnd(), MONITOR_DEFAULTTOPRIMARY);
    DASSERT(hmon != NULL);

    scrnNum = AwtWin32GraphicsDevice::GetScreenFromHMONITOR(hmon);
    DASSERT(scrnNum > -1);

    return scrnNum;
}

/*
 * Check to see if we've been moved onto another screen with different scale.
 */
BOOL AwtWindow::CheckIfOnNewScreenWithDifferentScale() {
    int curScrn = GetScreenImOn();

    if (curScrn != m_screenNum) {  // we've been moved
        // if moved from one monitor to another with different DPI, we should
        // update the m_screenNum only if the size was updated as well in the
        // WM_DPICHANGED.
        Devices::InstanceAccess devices;
        AwtWin32GraphicsDevice* oldDevice = devices->GetDevice(m_screenNum);
        AwtWin32GraphicsDevice* newDevice = devices->GetDevice(curScrn);
        if (m_winSizeMove && oldDevice && newDevice) {
            if (oldDevice->GetScaleX() != newDevice->GetScaleX()
                || oldDevice->GetScaleY() != newDevice->GetScaleY()) {
                // scales are different, wait for WM_DPICHANGED
                return TRUE;
            }
        }
    }
    return FALSE;
}

UINT AwtWindow::GetCurrentWmSizeType() {
    return SIZENORMAL;
}

/*
 * Check to see if we've been moved onto another screen.
 * If so, update internal data, surfaces, etc.
 */
BOOL AwtWindow::CheckIfOnNewScreen(BOOL force) {
    int curScrn = GetScreenImOn();

    if (curScrn != m_screenNum) {  // we've been moved
        if (!force && CheckIfOnNewScreenWithDifferentScale()) {
            // scales are different, wait for WM_DPICHANGED
            return TRUE;
        }

        JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

        jclass peerCls = env->GetObjectClass(m_peerObject);
        DASSERT(peerCls);
        CHECK_NULL_RETURN(peerCls, TRUE);

        jmethodID draggedID = env->GetMethodID(peerCls, "draggedToNewScreen",
                                               "()V");
        DASSERT(draggedID);
        if (draggedID == NULL) {
            env->DeleteLocalRef(peerCls);
            return TRUE;
        }

        env->CallVoidMethod(m_peerObject, draggedID);
        m_screenNum = curScrn;

        env->DeleteLocalRef(peerCls);
        return TRUE;
    }
    return FALSE;
}

// The shared code is not ready to the top-level window which crosses a few
// monitors with different DPI. Popup windows will start to use wrong screen,
// will be placed in the wrong place and will use the wrong size, see 8249164
// So we will "JUMP TO" the new screen.
void AwtWindow::CheckWindowDPIChange() {
    if (prevScaleRec.screen != -1 && prevScaleRec.screen != m_screenNum) {
        Devices::InstanceAccess devices;
        AwtWin32GraphicsDevice *device = devices->GetDevice(m_screenNum);
        if (device) {
            if (prevScaleRec.scaleX != device->GetScaleX()
                    || prevScaleRec.scaleY != device->GetScaleY()) {
                RECT rect;
                ::GetWindowRect(GetHWnd(), &rect);
                int x = rect.left;
                int y = rect.top;
                int w = rect.right - rect.left;
                int h = rect.bottom - rect.top;
                RECT bounds;
                if (MonitorBounds(device->GetMonitor(), &bounds)) {
                    x = x < bounds.left ? bounds.left : x;
                    y = y < bounds.top ? bounds.top : y;
                    x = (x + w > bounds.right) ? bounds.right - w : x;
                    y = (y + h > bounds.bottom) ? bounds.bottom - h : y;
                }
                ReshapeNoScale(x, y, w, h);
            }
        }
        prevScaleRec.screen = -1;
        prevScaleRec.scaleX = -1.0f;
        prevScaleRec.scaleY = -1.0f;
    }
}

BOOL AwtWindow::IsFocusableWindow() {
    /*
     * For Window/Frame/Dialog to accept focus it should:
     * - be focusable;
     * - be not blocked by any modal blocker.
     */
    BOOL focusable = m_isFocusableWindow && !::IsWindow(AwtWindow::GetModalBlocker(GetHWnd()));
    AwtFrame *owner = GetOwningFrameOrDialog(); // NULL for Frame and Dialog

    if (owner != NULL) {
        /*
         * Also for Window (not Frame/Dialog) to accept focus:
         * - its decorated parent should accept focus;
         */
        focusable = focusable && owner->IsFocusableWindow();
    }
    return focusable;
}

void AwtWindow::SetModalBlocker(HWND window, HWND blocker) {
    if (!::IsWindow(window)) {
        return;
    }

    if (::IsWindow(blocker)) {
        ::SetProp(window, ModalBlockerProp, reinterpret_cast<HANDLE>(blocker));
        ::EnableWindow(window, FALSE);
    } else {
        ::RemoveProp(window, ModalBlockerProp);
         AwtComponent *comp = AwtComponent::GetComponent(window);
         // we don't expect to be called with non-java HWNDs
         DASSERT(comp && comp->IsTopLevel());
         // we should not unblock disabled toplevels
         ::EnableWindow(window, comp->isEnabled());
    }
}

void AwtWindow::SetAndActivateModalBlocker(HWND window, HWND blocker) {
    if (!::IsWindow(window)) {
        return;
    }
    AwtWindow::SetModalBlocker(window, blocker);
    if (::IsWindow(blocker)) {
        // We must check for visibility. Otherwise invisible dialog will receive WM_ACTIVATE.
        if (::IsWindowVisible(blocker)) {
            ::BringWindowToTop(blocker);
            ::SetForegroundWindow(blocker);
        }
    }
}

HWND AwtWindow::GetTopmostModalBlocker(HWND window)
{
    HWND ret, blocker = NULL;

    do {
        ret = blocker;
        blocker = AwtWindow::GetModalBlocker(window);
        window = blocker;
    } while (::IsWindow(blocker));

    return ret;
}

void AwtWindow::FlashWindowEx(HWND hWnd, UINT count, DWORD timeout, DWORD flags) {
    FLASHWINFO fi;
    fi.cbSize = sizeof(fi);
    fi.hwnd = hWnd;
    fi.dwFlags = flags;
    fi.uCount = count;
    fi.dwTimeout = timeout;
    ::FlashWindowEx(&fi);
}

jboolean
AwtWindow::_RequestWindowFocus(void *param)
{
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    RequestWindowFocusStruct *rfs = (RequestWindowFocusStruct *)param;
    jobject self = rfs->component;
    jboolean isMouseEventCause = rfs->isMouseEventCause;

    jboolean result = JNI_FALSE;
    AwtWindow *window = NULL;

    PDATA pData;
    JNI_CHECK_NULL_GOTO(self, "peer", ret);
    pData = JNI_GET_PDATA(self);
    if (pData == NULL) {
        // do nothing just return false
        goto ret;
    }

    window = (AwtWindow *)pData;
    if (::IsWindow(window->GetHWnd())) {
        result = (jboolean)window->SendMessage(WM_AWT_WINDOW_SETACTIVE, (WPARAM)isMouseEventCause, 0);
    }
ret:
    env->DeleteGlobalRef(self);

    delete rfs;

    return result;
}

void AwtWindow::ToFront() {
    if (::IsWindow(GetHWnd())) {
        UINT flags = SWP_NOMOVE|SWP_NOSIZE;
        BOOL focusable = IsFocusableWindow();
        BOOL autoRequestFocus = IsAutoRequestFocus();

        if (!focusable || !autoRequestFocus)
        {
            flags = flags|SWP_NOACTIVATE;
        }
        ::SetWindowPos(GetHWnd(), HWND_TOP, 0, 0, 0, 0, flags);
        if (focusable && autoRequestFocus)
        {
            ::SetForegroundWindow(GetHWnd());
        }
    }
}

void AwtWindow::_ToFront(void *param, BOOL wait)
{
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    jobject self = (jobject)param;

    AwtWindow *w = NULL;

    PDATA pData;
    JNI_CHECK_PEER_GOTO(self, ret);
    w = (AwtWindow *)pData;
    if (::IsWindow(w->GetHWnd()))
    {
        if (wait) {
            w->SendMessage(WM_AWT_WINDOW_TOFRONT, 0, 0);
        } else {
            w->ToFront();
        }
    }
ret:
    env->DeleteGlobalRef(self);
}

static void _ToFrontWait(void *param) {
    AwtWindow::_ToFront(param, TRUE);
}

void AwtWindow::_ToBack(void *param)
{
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    jobject self = (jobject)param;

    AwtWindow *w = NULL;

    PDATA pData;
    JNI_CHECK_PEER_GOTO(self, ret);
    w = (AwtWindow *)pData;
    if (::IsWindow(w->GetHWnd()))
    {
        HWND hwnd = w->GetHWnd();
//        if (AwtComponent::GetComponent(hwnd) == NULL) {
//            // Window was disposed. Don't bother.
//            return;
//        }

        ::SetWindowPos(hwnd, HWND_BOTTOM, 0, 0 ,0, 0,
            SWP_NOMOVE|SWP_NOSIZE|SWP_NOACTIVATE);

        // If hwnd is the foreground window or if *any* of its owners are, then
        // we have to reset the foreground window. The reason is that when we send
        // hwnd to back, all of its owners are sent to back as well. If any one of
        // them is the foreground window, then it's possible that we could end up
        // with a foreground window behind a window of another application.
        HWND foregroundWindow = ::GetForegroundWindow();
        BOOL adjustForegroundWindow;
        HWND toTest = hwnd;
        do
        {
            adjustForegroundWindow = (toTest == foregroundWindow);
            if (adjustForegroundWindow)
            {
                break;
            }
            toTest = ::GetWindow(toTest, GW_OWNER);
        }
        while (toTest != NULL);

        if (adjustForegroundWindow)
        {
            HWND foregroundSearch = hwnd, newForegroundWindow = NULL;
                while (1)
                {
                foregroundSearch = ::GetNextWindow(foregroundSearch, GW_HWNDPREV);
                if (foregroundSearch == NULL)
                {
                    break;
                }
                LONG style = static_cast<LONG>(::GetWindowLongPtr(foregroundSearch, GWL_STYLE));
                if ((style & WS_CHILD) || !(style & WS_VISIBLE))
                {
                    continue;
                }

                AwtComponent *c = AwtComponent::GetComponent(foregroundSearch);
                if ((c != NULL) && !::IsWindow(AwtWindow::GetModalBlocker(c->GetHWnd())))
                {
                    newForegroundWindow = foregroundSearch;
                }
            }
            if (newForegroundWindow != NULL)
            {
                ::SetWindowPos(newForegroundWindow, HWND_TOP, 0, 0, 0, 0,
                    SWP_NOMOVE|SWP_NOSIZE|SWP_NOACTIVATE);
                if (((AwtWindow*)AwtComponent::GetComponent(newForegroundWindow))->IsFocusableWindow())
                {
                    ::SetForegroundWindow(newForegroundWindow);
                }
            }
            else
            {
                // We *have* to set the active HWND to something new. We simply
                // cannot risk having an active Java HWND which is behind an HWND
                // of a native application. This really violates the Windows user
                // experience.
                //
                // Windows won't allow us to set the foreground window to NULL,
                // so we use the desktop window instead. To the user, it appears
                // that there is no foreground window system-wide.
                ::SetForegroundWindow(::GetDesktopWindow());
            }
        }
    }
ret:
    env->DeleteGlobalRef(self);
}

void AwtWindow::_SetAlwaysOnTop(void *param)
{
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    SetAlwaysOnTopStruct *sas = (SetAlwaysOnTopStruct *)param;
    jobject self = sas->window;
    jboolean value = sas->value;

    AwtWindow *w = NULL;

    PDATA pData;
    JNI_CHECK_PEER_GOTO(self, ret);
    w = (AwtWindow *)pData;
    if (::IsWindow(w->GetHWnd()))
    {
        w->SendMessage(WM_AWT_SETALWAYSONTOP, (WPARAM)value, (LPARAM)w);
        w->m_alwaysOnTop = (bool)value;
    }
ret:
    env->DeleteGlobalRef(self);

    delete sas;
}

void AwtWindow::_SetTitle(void *param)
{
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    SetTitleStruct *sts = (SetTitleStruct *)param;
    jobject self = sts->window;
    jstring title = sts->title;

    AwtWindow *w = NULL;

    PDATA pData;
    JNI_CHECK_PEER_GOTO(self, ret);
    JNI_CHECK_NULL_GOTO(title, "null title", ret);

    w = (AwtWindow *)pData;
    if (::IsWindow(w->GetHWnd()))
    {
        int length = env->GetStringLength(title);
        TCHAR *buffer = new TCHAR[length + 1];
        env->GetStringRegion(title, 0, length, reinterpret_cast<jchar*>(buffer));
        buffer[length] = L'\0';
        VERIFY(::SetWindowText(w->GetHWnd(), buffer));
        delete[] buffer;
    }
ret:
    env->DeleteGlobalRef(self);
    if (title != NULL) {
        env->DeleteGlobalRef(title);
    }

    delete sts;
}

void AwtWindow::_SetResizable(void *param)
{
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    SetResizableStruct *srs = (SetResizableStruct *)param;
    jobject self = srs->window;
    jboolean resizable = srs->resizable;

    AwtWindow *w = NULL;

    PDATA pData;
    JNI_CHECK_PEER_GOTO(self, ret);
    w = (AwtWindow *)pData;
    if (::IsWindow(w->GetHWnd()))
    {
        DASSERT(!IsBadReadPtr(w, sizeof(AwtWindow)));
        w->SetResizable(resizable != 0);
    }
ret:
    env->DeleteGlobalRef(self);

    delete srs;
}

void AwtWindow::_UpdateInsets(void *param)
{
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    UpdateInsetsStruct *uis = (UpdateInsetsStruct *)param;
    jobject self = uis->window;
    jobject insets = uis->insets;

    AwtWindow *w = NULL;

    PDATA pData;
    JNI_CHECK_PEER_GOTO(self, ret);
    JNI_CHECK_NULL_GOTO(insets, "null insets", ret);
    w = (AwtWindow *)pData;
    if (::IsWindow(w->GetHWnd()))
    {
        w->UpdateInsets(insets);
    }
ret:
    env->DeleteGlobalRef(self);
    env->DeleteGlobalRef(insets);

    delete uis;
}

void AwtWindow::_ReshapeFrame(void *param)
{
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    ReshapeFrameStruct *rfs = (ReshapeFrameStruct *)param;
    jobject self = rfs->frame;
    jint x = rfs->x;
    jint y = rfs->y;
    jint w = rfs->w;
    jint h = rfs->h;

    if (env->EnsureLocalCapacity(1) < 0)
    {
        env->DeleteGlobalRef(self);
        delete rfs;
        return;
    }

    AwtFrame *p = NULL;

    PDATA pData;
    JNI_CHECK_PEER_GOTO(self, ret);
    p = (AwtFrame *)pData;
    if (::IsWindow(p->GetHWnd()))
    {
        jobject target = env->GetObjectField(self, AwtObject::targetID);
        if (target != NULL)
        {
            // enforce tresholds before sending the event
            // Fix for 4459064 : do not enforce thresholds for embedded frames
            if (!p->IsEmbeddedFrame())
            {
                jobject peer = p->GetPeer(env);
                int minWidth = p->ScaleDownX(::GetSystemMetrics(SM_CXMIN));
                int minHeight = p->ScaleDownY(::GetSystemMetrics(SM_CYMIN));
                if (w < minWidth)
                {
                    env->SetIntField(target, AwtComponent::widthID,
                        w = minWidth);
                }
                if (h < minHeight)
                {
                    env->SetIntField(target, AwtComponent::heightID,
                        h = minHeight);
                }
            }
            env->DeleteLocalRef(target);

            RECT *r = new RECT;
            ::SetRect(r, x, y, x + w, y + h);
            p->SendMessage(WM_AWT_RESHAPE_COMPONENT, 0, (LPARAM)r);
            // r is deleted in message handler

            // After the input method window shown, the dimension & position may not
            // be valid until this method is called. So we need to adjust the
            // IME candidate window position for the same reason as commented on
            // awt_Frame.cpp Show() method.
            if (p->isInputMethodWindow() && ::IsWindowVisible(p->GetHWnd())) {
              p->AdjustCandidateWindowPos();
            }
        }
        else
        {
            JNU_ThrowNullPointerException(env, "null target");
        }
    }
ret:
   env->DeleteGlobalRef(self);

   delete rfs;
}

void AwtWindow::_OverrideHandle(void *param)
{
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    OverrideHandle* oh = (OverrideHandle *) param;
    jobject self = oh->frame;
    AwtWindow *f = NULL;

    PDATA pData;
    JNI_CHECK_PEER_GOTO(self, ret);
    f = (AwtWindow *)pData;
    f->OverrideHWnd(oh->handle);
ret:
    env->DeleteGlobalRef(self);

    delete oh;
}

/*
 * This is AwtWindow-specific function that is not intended for reusing
 */
HICON CreateIconFromRaster(JNIEnv* env, jintArray iconRaster, jint w, jint h)
{
    HBITMAP mask = NULL;
    HBITMAP image = NULL;
    HICON icon = NULL;
    if (iconRaster != NULL) {
        int* iconRasterBuffer = NULL;
        try {
            iconRasterBuffer = (int *)env->GetPrimitiveArrayCritical(iconRaster, 0);

            JNI_CHECK_NULL_GOTO(iconRasterBuffer, "iconRaster data", done);

            mask = BitmapUtil::CreateTransparencyMaskFromARGB(w, h, iconRasterBuffer);
            image = BitmapUtil::CreateV4BitmapFromARGB(w, h, iconRasterBuffer);
        } catch (...) {
            if (iconRasterBuffer != NULL) {
                env->ReleasePrimitiveArrayCritical(iconRaster, iconRasterBuffer, 0);
            }
            throw;
        }
        if (iconRasterBuffer != NULL) {
            env->ReleasePrimitiveArrayCritical(iconRaster, iconRasterBuffer, 0);
        }
    }
    if (mask && image) {
        ICONINFO icnInfo;
        memset(&icnInfo, 0, sizeof(ICONINFO));
        icnInfo.hbmMask = mask;
        icnInfo.hbmColor = image;
        icnInfo.fIcon = TRUE;
        icon = ::CreateIconIndirect(&icnInfo);
    }
    if (image) {
        destroy_BMP(image);
    }
    if (mask) {
        destroy_BMP(mask);
    }
done:
    return icon;
}

void AwtWindow::SetIconData(JNIEnv* env, jintArray iconRaster, jint w, jint h,
                             jintArray smallIconRaster, jint smw, jint smh)
{
    HICON hNewIcon = NULL;
    HICON hNewIconSm = NULL;

    try {
        hNewIcon = CreateIconFromRaster(env, iconRaster, w, h);
        if (env->ExceptionCheck()) {
            if (hNewIcon != NULL) {
                DestroyIcon(hNewIcon);
            }
            return;
        }

        hNewIconSm = CreateIconFromRaster(env, smallIconRaster, smw, smh);
        if (env->ExceptionCheck()) {
            if (hNewIcon != NULL) {
                DestroyIcon(hNewIcon);
            }
            if (hNewIconSm != NULL) {
                DestroyIcon(hNewIconSm);
            }
            return;
        }
    } catch (...) {
        if (hNewIcon != NULL) {
            DestroyIcon(hNewIcon);
        }
        if (hNewIconSm != NULL) {
            DestroyIcon(hNewIconSm);
        }
        return;
    }

    HICON hOldIcon = NULL;
    HICON hOldIconSm = NULL;
    if ((m_hIcon != NULL) && !m_iconInherited) {
        hOldIcon = m_hIcon;
    }
    if ((m_hIconSm != NULL) && !m_iconInherited) {
        hOldIconSm = m_hIconSm;
    }

    m_hIcon = hNewIcon;
    m_hIconSm = hNewIconSm;

    m_iconInherited = (m_hIcon == NULL);
    if (m_iconInherited) {
        HWND hOwner = ::GetWindow(GetHWnd(), GW_OWNER);
        AwtWindow* owner = (AwtWindow *)AwtComponent::GetComponent(hOwner);
        if (owner != NULL) {
            m_hIcon = owner->GetHIcon();
            m_hIconSm = owner->GetHIconSm();
        } else {
            m_iconInherited = FALSE;
        }
    }

    DoUpdateIcon();
    EnumThreadWindows(AwtToolkit::MainThread(), UpdateOwnedIconCallback, (LPARAM)this);

    // Destroy previous icons if they were not inherited
    if (hOldIcon != NULL) {
        DestroyIcon(hOldIcon);
    }
    if (hOldIconSm != NULL) {
        DestroyIcon(hOldIconSm);
    }
}

BOOL AwtWindow::UpdateOwnedIconCallback(HWND hWndOwned, LPARAM lParam)
{
    HWND hWndOwner = ::GetWindow(hWndOwned, GW_OWNER);
    AwtWindow* owner = (AwtWindow*)lParam;
    if (hWndOwner == owner->GetHWnd()) {
        AwtComponent* comp = AwtComponent::GetComponent(hWndOwned);
        if (comp != NULL && comp->IsTopLevel()) {
            AwtWindow* owned = (AwtWindow *)comp;
            if (owned->m_iconInherited) {
                owned->m_hIcon = owner->m_hIcon;
                owned->m_hIconSm = owner->m_hIconSm;
                owned->DoUpdateIcon();
                EnumThreadWindows(AwtToolkit::MainThread(), UpdateOwnedIconCallback, (LPARAM)owned);
            }
        }
    }
    return TRUE;
}

void AwtWindow::DoUpdateIcon()
{
    //Does nothing for windows, is overridden for frames and dialogs
}

void AwtWindow::RedrawWindow()
{
    if (isOpaque()) {
        ::RedrawWindow(GetHWnd(), NULL, NULL,
                RDW_ERASE | RDW_INVALIDATE | RDW_FRAME | RDW_ALLCHILDREN);
    } else {
        ::EnterCriticalSection(&contentBitmapCS);
        if (hContentBitmap != NULL) {
            UpdateWindowImpl(contentWidth, contentHeight, hContentBitmap);
        }
        ::LeaveCriticalSection(&contentBitmapCS);
    }
}

// Deletes the hContentBitmap if it is non-null
void AwtWindow::DeleteContentBitmap()
{
    ::EnterCriticalSection(&contentBitmapCS);
    if (hContentBitmap != NULL) {
        ::DeleteObject(hContentBitmap);
        hContentBitmap = NULL;
    }
    ::LeaveCriticalSection(&contentBitmapCS);
}

// The effects are enabled only upon showing the window.
// See 6780496 for details.
void AwtWindow::EnableTranslucency(BOOL enable)
{
    if (enable) {
        SetTranslucency(getOpacity(), isOpaque(), FALSE, TRUE);
    } else {
        SetTranslucency(0xFF, TRUE, FALSE);
    }
}

/**
 * Sets the translucency effects.
 *
 * This method is used to:
 *
 * 1. Apply the translucency effects upon showing the window
 *    (setValues == FALSE, useDefaultForOldValues == TRUE);
 * 2. Turn off the effects upon hiding the window
 *    (setValues == FALSE, useDefaultForOldValues == FALSE);
 * 3. Set the effects per user's request
 *    (setValues == TRUE, useDefaultForOldValues == FALSE);
 *
 * In case #3 the effects may or may not be applied immediately depending on
 * the current visibility status of the window.
 *
 * The setValues argument indicates if we need to preserve the passed values
 * in local fields for further use.
 * The useDefaultForOldValues argument indicates whether we should consider
 * the window as if it has not any effects applied at the moment.
 */
void AwtWindow::SetTranslucency(BYTE opacity, BOOL opaque, BOOL setValues,
        BOOL useDefaultForOldValues)
{
    BYTE old_opacity = useDefaultForOldValues ? 0xFF : getOpacity();
    BOOL old_opaque = useDefaultForOldValues ? TRUE : isOpaque();

    if (opacity == old_opacity && opaque == old_opaque) {
        return;
    }

    if (setValues) {
       m_opacity = opacity;
       m_opaque = opaque;
    }

    // If we're invisible and are storing the values, return
    // Otherwise, apply the effects immediately
    if (!IsVisible() && setValues) {
        return;
    }

    HWND hwnd = GetHWnd();

    if (opaque != old_opaque) {
        DeleteContentBitmap();
    }

    if (opaque && opacity == 0xff) {
        // Turn off all the effects
        AwtWindow::SetLayered(hwnd, false);

        // Ask the window to repaint itself and all the children
        RedrawWindow();
    } else {
        // We're going to enable some effects
        if (!AwtWindow::IsLayered(hwnd)) {
            AwtWindow::SetLayered(hwnd, true);
        } else {
            if ((opaque && opacity < 0xff) ^ (old_opaque && old_opacity < 0xff)) {
                // _One_ of the modes uses the SetLayeredWindowAttributes.
                // Need to reset the style in this case.
                // If both modes are simple (i.e. just changing the opacity level),
                // no need to reset the style.
                AwtWindow::SetLayered(hwnd, false);
                AwtWindow::SetLayered(hwnd, true);
            }
        }

        if (opaque) {
            // Simple opacity mode
            ::SetLayeredWindowAttributes(hwnd, RGB(0, 0, 0), opacity, LWA_ALPHA);
        }
    }
}

static HBITMAP CreateBitmapFromRaster(JNIEnv* env, jintArray raster, jint w, jint h)
{
    HBITMAP image = NULL;
    if (raster != NULL) {
        int* rasterBuffer = NULL;
        try {
            rasterBuffer = (int *)env->GetPrimitiveArrayCritical(raster, 0);
            JNI_CHECK_NULL_GOTO(rasterBuffer, "raster data", done);
            image = BitmapUtil::CreateBitmapFromARGBPre(w, h, w*4, rasterBuffer);
        } catch (...) {
            if (rasterBuffer != NULL) {
                env->ReleasePrimitiveArrayCritical(raster, rasterBuffer, 0);
            }
            throw;
        }
        if (rasterBuffer != NULL) {
            env->ReleasePrimitiveArrayCritical(raster, rasterBuffer, 0);
        }
    }
done:
    return image;
}

void AwtWindow::UpdateWindowImpl(int width, int height, HBITMAP hBitmap)
{
    if (isOpaque()) {
        return;
    }

    HWND hWnd = GetHWnd();
    HDC hdcDst = ::GetDC(NULL);
    HDC hdcSrc = ::CreateCompatibleDC(NULL);
    HBITMAP hOldBitmap = (HBITMAP)::SelectObject(hdcSrc, hBitmap);

    //XXX: this code doesn't paint the children (say, the java.awt.Button)!
    //So, if we ever want to support HWs here, we need to repaint them
    //in some other way...
    //::SendMessage(hWnd, WM_PRINT, (WPARAM)hdcSrc, /*PRF_CHECKVISIBLE |*/
    //      PRF_CHILDREN /*| PRF_CLIENT | PRF_NONCLIENT*/);

    POINT ptSrc;
    ptSrc.x = ptSrc.y = 0;

    RECT rect;
    POINT ptDst;
    SIZE size;

    ::GetWindowRect(hWnd, &rect);
    ptDst.x = rect.left;
    ptDst.y = rect.top;
    size.cx = width;
    size.cy = height;

    BLENDFUNCTION bf;

    bf.SourceConstantAlpha = getOpacity();
    bf.AlphaFormat = AC_SRC_ALPHA;
    bf.BlendOp = AC_SRC_OVER;
    bf.BlendFlags = 0;

    ::UpdateLayeredWindow(hWnd, hdcDst, &ptDst, &size, hdcSrc, &ptSrc,
            RGB(0, 0, 0), &bf, ULW_ALPHA);

    ::ReleaseDC(NULL, hdcDst);
    ::SelectObject(hdcSrc, hOldBitmap);
    ::DeleteDC(hdcSrc);
}

void AwtWindow::UpdateWindow(JNIEnv* env, jintArray data, int width, int height,
                             HBITMAP hNewBitmap)
{
    if (isOpaque()) {
        return;
    }

    HBITMAP hBitmap;
    if (hNewBitmap == NULL) {
        if (data == NULL) {
            return;
        }
        hBitmap = CreateBitmapFromRaster(env, data, width, height);
        if (hBitmap == NULL) {
            return;
        }
    } else {
        hBitmap = hNewBitmap;
    }

    ::EnterCriticalSection(&contentBitmapCS);
    DeleteContentBitmap();
    hContentBitmap = hBitmap;
    contentWidth = width;
    contentHeight = height;
    UpdateWindowImpl(width, height, hBitmap);
    ::LeaveCriticalSection(&contentBitmapCS);
}

/*
 * Fixed 6353381: it's improved fix for 4792958
 * which was backed-out to avoid 5059656
 */
BOOL AwtWindow::HasValidRect()
{
    RECT inside;
    RECT outside;

    if (::IsIconic(GetHWnd())) {
        return FALSE;
    }

    ::GetClientRect(GetHWnd(), &inside);
    ::GetWindowRect(GetHWnd(), &outside);

    BOOL isZeroClientArea = (inside.right == 0 && inside.bottom == 0);
    BOOL isInvalidLocation = ((outside.left == -32000 && outside.top == -32000) || // Win2k && WinXP
                              (outside.left == 32000 && outside.top == 32000) || // Win95 && Win98
                              (outside.left == 3000 && outside.top == 3000)); // Win95 && Win98

    // the bounds correspond to iconic state
    if (isZeroClientArea && isInvalidLocation)
    {
        return FALSE;
    }

    return TRUE;
}


void AwtWindow::_SetIconImagesData(void * param)
{
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    SetIconImagesDataStruct* s = (SetIconImagesDataStruct*)param;
    jobject self = s->window;

    jintArray iconRaster = s->iconRaster;
    jintArray smallIconRaster = s->smallIconRaster;

    AwtWindow *window = NULL;

    PDATA pData;
    JNI_CHECK_PEER_GOTO(self, ret);
    // ok to pass null raster: default AWT icon

    window = (AwtWindow*)pData;
    if (::IsWindow(window->GetHWnd()))
    {
        window->SetIconData(env, iconRaster, s->w, s->h, smallIconRaster, s->smw, s->smh);

    }

ret:
    env->DeleteGlobalRef(self);
    env->DeleteGlobalRef(iconRaster);
    env->DeleteGlobalRef(smallIconRaster);
    delete s;
}

void AwtWindow::_SetMinSize(void* param)
{
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    SizeStruct *ss = (SizeStruct *)param;
    jobject self = ss->window;
    jint w = ss->w;
    jint h = ss->h;
    //Perform size setting
    AwtWindow *window = NULL;

    PDATA pData;
    JNI_CHECK_PEER_GOTO(self, ret);
    window = (AwtWindow *)pData;
    window->m_minSize.x = w;
    window->m_minSize.y = h;
  ret:
    env->DeleteGlobalRef(self);
    delete ss;
}

jint AwtWindow::_GetScreenImOn(void *param)
{
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    jobject self = (jobject)param;

    jint result = -1;
    AwtWindow* window = NULL;

    // Our native resources may have been destroyed before the Java peer,
    // e.g., if dispose() was called. In that case, return the default screen.
    PDATA pData;
    JNI_CHECK_PEER_GOTO(self, ret);
    window = (AwtWindow *)pData;
    if (::IsWindow(window->GetHWnd()))
    {
        result = (jint)window->GetScreenImOn();
    }

  ret:
    env->DeleteGlobalRef(self);
    return (result != -1) ? result : AwtWin32GraphicsDevice::GetDefaultDeviceIndex();
}

void AwtWindow::_SetFocusableWindow(void *param)
{
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    SetFocusableWindowStruct *sfws = (SetFocusableWindowStruct *)param;
    jobject self = sfws->window;
    jboolean isFocusableWindow = sfws->isFocusableWindow;

    AwtWindow *window = NULL;

    PDATA pData;
    JNI_CHECK_PEER_GOTO(self, ret);
    window = (AwtWindow *)pData;

    window->m_isFocusableWindow = isFocusableWindow;

    // A simple window is permanently set to WS_EX_NOACTIVATE
    if (!window->IsSimpleWindow()) {
        if (!window->m_isFocusableWindow) {
            LONG isPopup = window->GetStyle() & WS_POPUP;
            window->SetStyleEx(window->GetStyleEx() | (isPopup ? 0 : WS_EX_APPWINDOW) | WS_EX_NOACTIVATE);
        } else {
            window->SetStyleEx(window->GetStyleEx() & ~WS_EX_APPWINDOW & ~WS_EX_NOACTIVATE);
        }
    }

  ret:
    env->DeleteGlobalRef(self);
    delete sfws;
}

void AwtWindow::_ModalDisable(void *param)
{
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    ModalDisableStruct *mds = static_cast<ModalDisableStruct *>(param);
    jobject self = mds->window;
    HWND blockerHWnd = (HWND)mds->blockerHWnd;

    AwtWindow *window = NULL;
    HWND windowHWnd = 0;

    if (self == NULL) {
        env->ExceptionClear();
        JNU_ThrowNullPointerException(env, "self");
        delete mds;
        return;
    } else {
        window = (AwtWindow *)JNI_GET_PDATA(self);
        if (window == NULL) {
            env->DeleteGlobalRef(self);
            delete mds;
            return;
        }
    }

    windowHWnd = window->GetHWnd();
    if (::IsWindow(windowHWnd)) {
        AwtWindow::SetAndActivateModalBlocker(windowHWnd, blockerHWnd);
    }

    env->DeleteGlobalRef(self);

    delete mds;
}

void AwtWindow::_ModalEnable(void *param)
{
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    jobject self = static_cast<jobject>(param);

    AwtWindow *window = NULL;
    HWND windowHWnd = 0;

    if (self == NULL) {
        env->ExceptionClear();
        JNU_ThrowNullPointerException(env, "self");
        return;
    } else {
        window = (AwtWindow *)JNI_GET_PDATA(self);
        if (window == NULL) {
            env->DeleteGlobalRef(self);
            return;
        }
    }

    windowHWnd = window->GetHWnd();
    if (::IsWindow(windowHWnd)) {
        AwtWindow::SetModalBlocker(windowHWnd, NULL);
    }

    env->DeleteGlobalRef(self);
}

void AwtWindow::_SetOpacity(void* param)
{
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    OpacityStruct *os = static_cast<OpacityStruct *>(param);
    jobject self = os->window;
    BYTE iOpacity = (BYTE)os->iOpacity;

    AwtWindow *window = NULL;

    if (self == NULL) {
        env->ExceptionClear();
        JNU_ThrowNullPointerException(env, "self");
        delete os;
        return;
    } else {
        window = (AwtWindow *)JNI_GET_PDATA(self);
        if (window == NULL) {
            THROW_NULL_PDATA_IF_NOT_DESTROYED(self);
            env->DeleteGlobalRef(self);
            delete os;
            return;
        }
    }

    window->SetTranslucency(iOpacity, window->isOpaque());

    env->DeleteGlobalRef(self);
    delete os;
}

void AwtWindow::_SetOpaque(void* param)
{
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    OpaqueStruct *os = static_cast<OpaqueStruct *>(param);
    jobject self = os->window;
    BOOL isOpaque = (BOOL)os->isOpaque;

    AwtWindow *window = NULL;

    if (self == NULL) {
        env->ExceptionClear();
        JNU_ThrowNullPointerException(env, "self");
        delete os;
        return;
    } else {
        window = (AwtWindow *)JNI_GET_PDATA(self);
        if (window == NULL) {
            THROW_NULL_PDATA_IF_NOT_DESTROYED(self);
            env->DeleteGlobalRef(self);
            delete os;
            return;
        }
    }

    window->SetTranslucency(window->getOpacity(), isOpaque);

    env->DeleteGlobalRef(self);
    delete os;
}

void AwtWindow::_SetRoundedCorners(void *param) {
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    RoundedCornersStruct *rcs = (RoundedCornersStruct *)param;
    jobject self = rcs->window;

    PDATA pData;
    AwtWindow *window;
    JNI_CHECK_PEER_GOTO(self, ret);
    window = (AwtWindow *)pData;

    DwmSetWindowAttribute(window->GetHWnd(), DWMWA_WINDOW_CORNER_PREFERENCE, &rcs->type, sizeof(DWM_WINDOW_CORNER_PREFERENCE));

    if (rcs->isBorderColor) {
        jint red = (rcs->borderColor >> 16) & 0xff;
        jint green = (rcs->borderColor >> 8) & 0xff;
        jint blue  = (rcs->borderColor >> 0) & 0xff;
        COLORREF borderColor = RGB(red, green, blue);
        DwmSetWindowAttribute(window->GetHWnd(), DWMWA_BORDER_COLOR, &borderColor, sizeof(COLORREF));
    }
  ret:
    env->DeleteGlobalRef(self);
    delete rcs;
}

void AwtWindow::_UpdateWindow(void* param)
{
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    UpdateWindowStruct *uws = static_cast<UpdateWindowStruct *>(param);
    jobject self = uws->window;
    jintArray data = uws->data;

    AwtWindow *window = NULL;

    if (self == NULL) {
        env->ExceptionClear();
        JNU_ThrowNullPointerException(env, "self");
        if (data != NULL) {
            env->DeleteGlobalRef(data);
        }
        delete uws;
        return;
    } else {
        window = (AwtWindow *)JNI_GET_PDATA(self);
        if (window == NULL) {
            THROW_NULL_PDATA_IF_NOT_DESTROYED(self);
            env->DeleteGlobalRef(self);
            if (data != NULL) {
                env->DeleteGlobalRef(data);
            }
            delete uws;
            return;
        }
    }

    window->UpdateWindow(env, data, (int)uws->width, (int)uws->height,
                         uws->hBitmap);

    env->DeleteGlobalRef(self);
    if (data != NULL) {
        env->DeleteGlobalRef(data);
    }
    delete uws;
}

void AwtWindow::_SetFullScreenExclusiveModeState(void *param)
{
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    SetFullScreenExclusiveModeStateStruct * data =
        (SetFullScreenExclusiveModeStateStruct*)param;
    jobject self = data->window;
    jboolean state = data->isFSEMState;

    AwtWindow *window = NULL;

    if (self == NULL) {
        env->ExceptionClear();
        JNU_ThrowNullPointerException(env, "self");
        delete data;
        return;
    } else {
        window = (AwtWindow *)JNI_GET_PDATA(self);
        if (window == NULL) {
            THROW_NULL_PDATA_IF_NOT_DESTROYED(self);
            env->DeleteGlobalRef(self);
            delete data;
            return;
        }
    }

    window->setFullScreenExclusiveModeState(state != 0);

    env->DeleteGlobalRef(self);
    delete data;
}

void AwtWindow::_GetNativeWindowSize(void* param) {

    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    SizeStruct *ss = (SizeStruct *)param;
    jobject self = ss->window;
    AwtWindow *window = NULL;
    PDATA pData;
    JNI_CHECK_PEER_RETURN(self);
    window = (AwtWindow *)pData;

    RECT rc;
    ::GetWindowRect(window->GetHWnd(), &rc);
    ss->w = rc.right - rc.left;
    ss->h = rc.bottom - rc.top;

    env->DeleteGlobalRef(self);
}


extern "C" int getSystemMetricValue(int msgType);
extern "C" {

/*
 * Class:     java_awt_Window
 * Method:    initIDs
 * Signature: ()V
 */
JNIEXPORT void JNICALL
Java_java_awt_Window_initIDs(JNIEnv *env, jclass cls)
{
    TRY;

    CHECK_NULL(AwtWindow::locationByPlatformID =
        env->GetFieldID(cls, "locationByPlatform", "Z"));
    CHECK_NULL(AwtWindow::customTitleBarHitTestID =
        env->GetFieldID(cls, "customTitleBarHitTest", "I"));
    CHECK_NULL(AwtWindow::customTitleBarHitTestQueryID =
        env->GetFieldID(cls, "customTitleBarHitTestQuery", "I"));
    CHECK_NULL(AwtWindow::autoRequestFocusID =
        env->GetFieldID(cls, "autoRequestFocus", "Z"));
    CHECK_NULL(AwtWindow::internalCustomTitleBarHeightMID =
        env->GetMethodID(cls, "internalCustomTitleBarHeight", "()F"));

    jclass windowTypeClass = env->FindClass("java/awt/Window$Type");
    CHECK_NULL(windowTypeClass);
    AwtWindow::windowTypeNameMID =
        env->GetMethodID(windowTypeClass, "name", "()Ljava/lang/String;");
    env->DeleteLocalRef(windowTypeClass);

    CATCH_BAD_ALLOC;
}

} /* extern "C" */


/************************************************************************
 * WindowPeer native methods
 */

extern "C" {

/*
 * Class:     sun_awt_windows_WWindowPeer
 * Method:    initIDs
 * Signature: ()V
 */
JNIEXPORT void JNICALL
Java_sun_awt_windows_WWindowPeer_initIDs(JNIEnv *env, jclass cls)
{
    TRY;

    CHECK_NULL(AwtWindow::sysInsetsID = env->GetFieldID(cls, "sysInsets", "Ljava/awt/Insets;"));

    AwtWindow::windowTypeID = env->GetFieldID(cls, "windowType",
            "Ljava/awt/Window$Type;");

    AwtWindow::notifyWindowStateChangedMID =
        env->GetMethodID(cls, "notifyWindowStateChanged", "(II)V");
    DASSERT(AwtWindow::notifyWindowStateChangedMID);
    CHECK_NULL(AwtWindow::notifyWindowStateChangedMID);

    CATCH_BAD_ALLOC;
}

/*
 * Class:     sun_awt_windows_WWindowPeer
 * Method:    toFront
 * Signature: ()V
 */
JNIEXPORT void JNICALL
Java_sun_awt_windows_WWindowPeer__1toFront(JNIEnv *env, jobject self)
{
    TRY;

    AwtToolkit::GetInstance().SyncCall(_ToFrontWait,
        env->NewGlobalRef(self));
    // global ref is deleted in _ToFront()

    CATCH_BAD_ALLOC;
}

/*
 * Class:     sun_awt_windows_WWindowPeer
 * Method:    toBack
 * Signature: ()V
 */
JNIEXPORT void JNICALL
Java_sun_awt_windows_WWindowPeer_toBack(JNIEnv *env, jobject self)
{
    TRY;

    AwtToolkit::GetInstance().SyncCall(AwtWindow::_ToBack,
        env->NewGlobalRef(self));
    // global ref is deleted in _ToBack()

    CATCH_BAD_ALLOC;
}

/*
 * Class:     sun_awt_windows_WWindowPeer
 * Method:    setAlwaysOnTop
 * Signature: (Z)V
 */
JNIEXPORT void JNICALL
Java_sun_awt_windows_WWindowPeer_setAlwaysOnTopNative(JNIEnv *env, jobject self,
                                                jboolean value)
{
    TRY;

    SetAlwaysOnTopStruct *sas = new SetAlwaysOnTopStruct;
    sas->window = env->NewGlobalRef(self);
    sas->value = value;

    AwtToolkit::GetInstance().SyncCall(AwtWindow::_SetAlwaysOnTop, sas);
    // global ref and sas are deleted in _SetAlwaysOnTop

    CATCH_BAD_ALLOC;
}

/*
 * Class:     sun_awt_windows_WWindowPeer
 * Method:    _setTitle
 * Signature: (Ljava/lang/String;)V
 */
JNIEXPORT void JNICALL
Java_sun_awt_windows_WWindowPeer__1setTitle(JNIEnv *env, jobject self,
                                            jstring title)
{
    TRY;

    SetTitleStruct *sts = new SetTitleStruct;
    sts->window = env->NewGlobalRef(self);
    sts->title = (jstring)env->NewGlobalRef(title);

    AwtToolkit::GetInstance().SyncCall(AwtWindow::_SetTitle, sts);
    /// global refs and sts are deleted in _SetTitle()

    CATCH_BAD_ALLOC;
}

/*
 * Class:     sun_awt_windows_WWindowPeer
 * Method:    _setResizable
 * Signature: (Z)V
 */
JNIEXPORT void JNICALL
Java_sun_awt_windows_WWindowPeer__1setResizable(JNIEnv *env, jobject self,
                                                jboolean resizable)
{
    TRY;

    SetResizableStruct *srs = new SetResizableStruct;
    srs->window = env->NewGlobalRef(self);
    srs->resizable = resizable;

    AwtToolkit::GetInstance().SyncCall(AwtWindow::_SetResizable, srs);
    // global ref and srs are deleted in _SetResizable

    CATCH_BAD_ALLOC;
}

/*
 * Class:     sun_awt_windows_WWindowPeer
 * Method:    create
 * Signature: (Lsun/awt/windows/WComponentPeer;)V
 */
JNIEXPORT void JNICALL
Java_sun_awt_windows_WWindowPeer_createAwtWindow(JNIEnv *env, jobject self,
                                                 jobject parent)
{
    TRY;

    AwtToolkit::CreateComponent(self, parent,
                                (AwtToolkit::ComponentFactory)
                                AwtWindow::Create);

    CATCH_BAD_ALLOC;
}

/*
 * Class:     sun_awt_windows_WWindowPeer
 * Method:    updateInsets
 * Signature: (Ljava/awt/Insets;)V
 */
JNIEXPORT void JNICALL
Java_sun_awt_windows_WWindowPeer_updateInsets(JNIEnv *env, jobject self,
                                              jobject insets)
{
    TRY;

    UpdateInsetsStruct *uis = new UpdateInsetsStruct;
    uis->window = env->NewGlobalRef(self);
    uis->insets = env->NewGlobalRef(insets);

    AwtToolkit::GetInstance().SyncCall(AwtWindow::_UpdateInsets, uis);
    // global refs and uis are deleted in _UpdateInsets()

    CATCH_BAD_ALLOC;
}

/*
 * Class:     sun_awt_windows_WWindowPeer
 * Method:    reshapeFrame
 * Signature: (IIII)V
 */
JNIEXPORT void JNICALL
Java_sun_awt_windows_WWindowPeer_reshapeFrame(JNIEnv *env, jobject self,
                                        jint x, jint y, jint w, jint h)
{
    TRY;

    ReshapeFrameStruct *rfs = new ReshapeFrameStruct;
    rfs->frame = env->NewGlobalRef(self);
    rfs->x = x;
    rfs->y = y;
    rfs->w = w;
    rfs->h = h;

    AwtToolkit::GetInstance().SyncCall(AwtWindow::_ReshapeFrame, rfs);
    // global ref and rfs are deleted in _ReshapeFrame()

    CATCH_BAD_ALLOC;
}

/*
 * Class:     sun_awt_windows_WWindowPeer
* Method:    getNativeWindowSize
* Signature: ()Ljava/awt/Dimension;
*/
JNIEXPORT jobject JNICALL Java_sun_awt_windows_WWindowPeer_getNativeWindowSize
(JNIEnv *env, jobject self) {

    jobject res = NULL;
    TRY;
    SizeStruct *ss = new SizeStruct;
    ss->window = env->NewGlobalRef(self);

    AwtToolkit::GetInstance().SyncCall(AwtWindow::_GetNativeWindowSize, ss);

    int w = ss->w;
    int h = ss->h;

    delete ss;
    // global ref is deleted in _GetNativeWindowSize()

    static jmethodID dimMID = NULL;
    static jclass dimClassID = NULL;
    if (dimClassID == NULL) {
        jclass dimClassIDLocal = env->FindClass("java/awt/Dimension");
        CHECK_NULL_RETURN(dimClassIDLocal, NULL);
        dimClassID = (jclass)env->NewGlobalRef(dimClassIDLocal);
        env->DeleteLocalRef(dimClassIDLocal);
    }

    if (dimMID == NULL) {
        dimMID = env->GetMethodID(dimClassID, "<init>", "(II)V");
        CHECK_NULL_RETURN(dimMID, NULL);
    }

    return env->NewObject(dimClassID, dimMID, w, h);

    CATCH_BAD_ALLOC_RET(NULL);
}

/*
 * Class:     sun_awt_windows_WWindowPeer
 * Method:    getSysMinWidth
 * Signature: ()I
 */
JNIEXPORT jint JNICALL
Java_sun_awt_windows_WWindowPeer_getSysMinWidth(JNIEnv *env, jclass self)
{
    TRY;

    return ::GetSystemMetrics(SM_CXMIN);

    CATCH_BAD_ALLOC_RET(0);
}

/*
 * Class:     sun_awt_windows_WWindowPeer
 * Method:    getSysMinHeight
 * Signature: ()I
 */
JNIEXPORT jint JNICALL
Java_sun_awt_windows_WWindowPeer_getSysMinHeight(JNIEnv *env, jclass self)
{
    TRY;

    return ::GetSystemMetrics(SM_CYMIN);

    CATCH_BAD_ALLOC_RET(0);
}

/*
 * Class:     sun_awt_windows_WWindowPeer
 * Method:    getSysIconHeight
 * Signature: ()I
 */
JNIEXPORT jint JNICALL
Java_sun_awt_windows_WWindowPeer_getSysIconHeight(JNIEnv *env, jclass self)
{
    TRY;

    return getSystemMetricValue(SM_CYICON);

    CATCH_BAD_ALLOC_RET(0);
}

/*
 * Class:     sun_awt_windows_WWindowPeer
 * Method:    getSysIconWidth
 * Signature: ()I
 */
JNIEXPORT jint JNICALL
Java_sun_awt_windows_WWindowPeer_getSysIconWidth(JNIEnv *env, jclass self)
{
    TRY;

    return getSystemMetricValue(SM_CXICON);

    CATCH_BAD_ALLOC_RET(0);
}

/*
 * Class:     sun_awt_windows_WWindowPeer
 * Method:    getSysSmIconHeight
 * Signature: ()I
 */
JNIEXPORT jint JNICALL
Java_sun_awt_windows_WWindowPeer_getSysSmIconHeight(JNIEnv *env, jclass self)
{
    TRY;

    return getSystemMetricValue(SM_CYSMICON);

    CATCH_BAD_ALLOC_RET(0);
}

/*
 * Class:     sun_awt_windows_WWindowPeer
 * Method:    getSysSmIconWidth
 * Signature: ()I
 */
JNIEXPORT jint JNICALL
Java_sun_awt_windows_WWindowPeer_getSysSmIconWidth(JNIEnv *env, jclass self)
{
    TRY;

    return getSystemMetricValue(SM_CXSMICON);

    CATCH_BAD_ALLOC_RET(0);
}

int getSystemMetricValue(int msgType) {
    int value = 1;
    int logPixels = LOGPIXELSX;
    switch (msgType) {
        case SM_CXICON:
            value = ::GetSystemMetrics(SM_CXICON);
            break;
        case SM_CYICON:
            value = ::GetSystemMetrics(SM_CYICON);
            logPixels = LOGPIXELSY;
            break;
        case SM_CXSMICON:
            value = ::GetSystemMetrics(SM_CXSMICON);
            break;
        case SM_CYSMICON:
            value = ::GetSystemMetrics(SM_CYSMICON);
            logPixels = LOGPIXELSY;
            break;
    }
    static int dpi = -1;
    if (dpi == -1) {
        HWND hWnd = ::GetDesktopWindow();
        HDC hDC = ::GetDC(hWnd);
        dpi = GetDeviceCaps(hDC, logPixels);
        ::ReleaseDC(hWnd, hDC);
    }
    if(dpi != 0 && dpi != 96) {
        float invScaleX = 96.0f / dpi;
        value = (int) round(value * invScaleX);
    }
    return value;
}

/*
 * Class:     sun_awt_windows_WWindowPeer
 * Method:    setIconImagesData
 * Signature: ([I)V
 */
JNIEXPORT void JNICALL
Java_sun_awt_windows_WWindowPeer_setIconImagesData(JNIEnv *env, jobject self,
    jintArray iconRaster, jint w, jint h,
    jintArray smallIconRaster, jint smw, jint smh)
{
    TRY;

    SetIconImagesDataStruct *sims = new SetIconImagesDataStruct;

    sims->window = env->NewGlobalRef(self);
    sims->iconRaster = (jintArray)env->NewGlobalRef(iconRaster);
    sims->w = w;
    sims->h = h;
    sims->smallIconRaster = (jintArray)env->NewGlobalRef(smallIconRaster);
    sims->smw = smw;
    sims->smh = smh;

    AwtToolkit::GetInstance().SyncCall(AwtWindow::_SetIconImagesData, sims);
    // global refs and sims are deleted in _SetIconImagesData()

    CATCH_BAD_ALLOC;
}

/*
 * Class:     sun_awt_windows_WWindowPeer
 * Method:    setMinSize
 * Signature: (Lsun/awt/windows/WWindowPeer;)V
 */
JNIEXPORT void JNICALL
Java_sun_awt_windows_WWindowPeer_setMinSize(JNIEnv *env, jobject self,
                                              jint w, jint h)
{
    TRY;

    SizeStruct *ss = new SizeStruct;
    ss->window = env->NewGlobalRef(self);
    ss->w = w;
    ss->h = h;

    AwtToolkit::GetInstance().SyncCall(AwtWindow::_SetMinSize, ss);
    // global refs and mds are deleted in _SetMinSize

    CATCH_BAD_ALLOC;
}

/*
 * Class:     sun_awt_windows_WWindowPeer
 * Method:    getScreenImOn
 * Signature: ()I
 */
JNIEXPORT jint JNICALL
Java_sun_awt_windows_WWindowPeer_getScreenImOn(JNIEnv *env, jobject self)
{
    TRY;

    return static_cast<jint>(reinterpret_cast<INT_PTR>(AwtToolkit::GetInstance().SyncCall(
        (void *(*)(void *))AwtWindow::_GetScreenImOn,
        env->NewGlobalRef(self))));
    // global ref is deleted in _GetScreenImOn()

    CATCH_BAD_ALLOC_RET(-1);
}

/*
 * Class:     sun_awt_windows_WWindowPeer
 * Method:    setFullScreenExclusiveModeState
 * Signature: (Z)V
 */
JNIEXPORT void JNICALL
Java_sun_awt_windows_WWindowPeer_setFullScreenExclusiveModeState(JNIEnv *env,
        jobject self, jboolean state)
{
    TRY;

    SetFullScreenExclusiveModeStateStruct *data =
        new SetFullScreenExclusiveModeStateStruct;
    data->window = env->NewGlobalRef(self);
    data->isFSEMState = state;

    AwtToolkit::GetInstance().SyncCall(
            AwtWindow::_SetFullScreenExclusiveModeState, data);
    // global ref and data are deleted in the invoked method

    CATCH_BAD_ALLOC;
}

/*
 * Class:     sun_awt_windows_WWindowPeer
 * Method:    modalDisable
 * Signature: (J)V
 */
JNIEXPORT void JNICALL
Java_sun_awt_windows_WWindowPeer_modalDisable(JNIEnv *env, jobject self,
                                              jobject blocker, jlong blockerHWnd)
{
    TRY;

    ModalDisableStruct *mds = new ModalDisableStruct;
    mds->window = env->NewGlobalRef(self);
    mds->blockerHWnd = blockerHWnd;

    AwtToolkit::GetInstance().SyncCall(AwtWindow::_ModalDisable, mds);
    // global ref and mds are deleted in _ModalDisable

    CATCH_BAD_ALLOC;
}

/*
 * Class:     sun_awt_windows_WWindowPeer
 * Method:    modalEnable
 * Signature: ()V
 */
JNIEXPORT void JNICALL
Java_sun_awt_windows_WWindowPeer_modalEnable(JNIEnv *env, jobject self, jobject blocker)
{
    TRY;

    AwtToolkit::GetInstance().SyncCall(AwtWindow::_ModalEnable,
        env->NewGlobalRef(self));
    // global ref is deleted in _ModalEnable

    CATCH_BAD_ALLOC;
}

/*
 * Class:     sun_awt_windows_WWindowPeer
 * Method:    setFocusableWindow
 * Signature: (Z)V
 */
JNIEXPORT void JNICALL
Java_sun_awt_windows_WWindowPeer_setFocusableWindow(JNIEnv *env, jobject self, jboolean isFocusableWindow)
{
    TRY;

    SetFocusableWindowStruct *sfws = new SetFocusableWindowStruct;
    sfws->window = env->NewGlobalRef(self);
    sfws->isFocusableWindow = isFocusableWindow;

    AwtToolkit::GetInstance().SyncCall(AwtWindow::_SetFocusableWindow, sfws);
    // global ref and sfws are deleted in _SetFocusableWindow()

    CATCH_BAD_ALLOC;
}

JNIEXPORT void JNICALL
Java_sun_awt_windows_WWindowPeer_nativeGrab(JNIEnv *env, jobject self)
{
    TRY;

    AwtToolkit::GetInstance().SyncCall(AwtWindow::_Grab, env->NewGlobalRef(self));
    // global ref is deleted in _Grab()

    CATCH_BAD_ALLOC;
}

JNIEXPORT void JNICALL
Java_sun_awt_windows_WWindowPeer_nativeUngrab(JNIEnv *env, jobject self)
{
    TRY;

    AwtToolkit::GetInstance().SyncCall(AwtWindow::_Ungrab, env->NewGlobalRef(self));
    // global ref is deleted in _Ungrab()

    CATCH_BAD_ALLOC;
}

/*
 * Class:     sun_awt_windows_WWindowPeer
 * Method:    setOpacity
 * Signature: (I)V
 */
JNIEXPORT void JNICALL
Java_sun_awt_windows_WWindowPeer_setOpacity(JNIEnv *env, jobject self,
                                              jint iOpacity)
{
    TRY;

    OpacityStruct *os = new OpacityStruct;
    os->window = env->NewGlobalRef(self);
    os->iOpacity = iOpacity;

    AwtToolkit::GetInstance().SyncCall(AwtWindow::_SetOpacity, os);
    // global refs and mds are deleted in _SetOpacity

    CATCH_BAD_ALLOC;
}

/*
 * Class:     sun_awt_windows_WWindowPeer
 * Method:    setOpaqueImpl
 * Signature: (Z)V
 */
JNIEXPORT void JNICALL
Java_sun_awt_windows_WWindowPeer_setOpaqueImpl(JNIEnv *env, jobject self,
                                              jboolean isOpaque)
{
    TRY;

    OpaqueStruct *os = new OpaqueStruct;
    os->window = env->NewGlobalRef(self);
    os->isOpaque = isOpaque;

    AwtToolkit::GetInstance().SyncCall(AwtWindow::_SetOpaque, os);
    // global refs and mds are deleted in _SetOpaque

    CATCH_BAD_ALLOC;
}

/*
 * Class:     sun_awt_windows_WWindowPeer
 * Method:    updateWindowImpl
 * Signature: ([III)V
 */
JNIEXPORT void JNICALL
Java_sun_awt_windows_WWindowPeer_updateWindowImpl(JNIEnv *env, jobject self,
                                                  jintArray data,
                                                  jint width, jint height)
{
    TRY;

    UpdateWindowStruct *uws = new UpdateWindowStruct;
    uws->window = env->NewGlobalRef(self);
    uws->data = (jintArray)env->NewGlobalRef(data);
    uws->hBitmap = NULL;
    uws->width = width;
    uws->height = height;

    AwtToolkit::GetInstance().InvokeFunction(AwtWindow::_UpdateWindow, uws);
    // global refs and mds are deleted in _UpdateWindow

    CATCH_BAD_ALLOC;
}

/**
 * This method is called from the WGL pipeline when it needs to update
 * the layered window WindowPeer's C++ level object.
 */
void AwtWindow_UpdateWindow(JNIEnv *env, jobject peer,
                            jint width, jint height, HBITMAP hBitmap)
{
    TRY;

    UpdateWindowStruct *uws = new UpdateWindowStruct;
    uws->window = env->NewGlobalRef(peer);
    uws->data = NULL;
    uws->hBitmap = hBitmap;
    uws->width = width;
    uws->height = height;

    AwtToolkit::GetInstance().InvokeFunction(AwtWindow::_UpdateWindow, uws);
    // global refs and mds are deleted in _UpdateWindow

    CATCH_BAD_ALLOC;
}

/*
 * Class:     sun_awt_windows_WComponentPeer
 * Method:    requestFocus
 * Signature: (Z)Z
 */
JNIEXPORT jboolean JNICALL Java_sun_awt_windows_WWindowPeer_requestWindowFocus
    (JNIEnv *env, jobject self, jboolean isMouseEventCause)
{
    TRY;

    jobject selfGlobalRef = env->NewGlobalRef(self);

    RequestWindowFocusStruct *rfs = new RequestWindowFocusStruct;
    rfs->component = selfGlobalRef;
    rfs->isMouseEventCause = isMouseEventCause;

    return (jboolean)((intptr_t)AwtToolkit::GetInstance().SyncCall(
        (void*(*)(void*))AwtWindow::_RequestWindowFocus, rfs));
    // global refs and rfs are deleted in _RequestWindowFocus

    CATCH_BAD_ALLOC_RET(JNI_FALSE);
}

/*
 * Class:     sun_awt_windows_WWindowPeer
 * Method:    setRoundedCorners
 * Signature: (IZI)V
 */
JNIEXPORT void JNICALL
Java_sun_awt_windows_WWindowPeer_setRoundedCorners(JNIEnv *env, jobject self, jint type, jboolean isBorderColor, jint borderColor)
{
    TRY;

    RoundedCornersStruct *rcs = new RoundedCornersStruct;
    rcs->window = env->NewGlobalRef(self);
    rcs->type = (DWM_WINDOW_CORNER_PREFERENCE)type;
    rcs->isBorderColor = isBorderColor;
    rcs->borderColor = borderColor;

    AwtToolkit::GetInstance().SyncCall(AwtWindow::_SetRoundedCorners, rcs);
    // global refs and rcs are deleted in _SetRoundedCorners

    CATCH_BAD_ALLOC;
}

/*
 * Class:     sun_awt_windows_WLightweightFramePeer
 * Method:    overrideNativeHandle
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_sun_awt_windows_WLightweightFramePeer_overrideNativeHandle
  (JNIEnv *env, jobject self, jlong hwnd)
{
    TRY;

    OverrideHandle *oh = new OverrideHandle;
    oh->frame = env->NewGlobalRef(self);
    oh->handle = (HWND) hwnd;

    AwtToolkit::GetInstance().SyncCall(AwtFrame::_OverrideHandle, oh);
    // global ref and oh are deleted in _OverrideHandle()

    CATCH_BAD_ALLOC;
}

} /* extern "C" */
