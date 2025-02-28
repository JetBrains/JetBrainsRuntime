/*
* Copyright (c) 2024, JetBrains s.r.o.. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

#include "AccessibleCaret.h"

/**
 * This class implements Win32 IAccessible interface in a similar way to the system text caret.
*/
AccessibleCaret::AccessibleCaret()
    : m_refCount(1), m_x(0), m_y(0), m_width(0), m_height(0) {
}

AccessibleCaret *AccessibleCaret::instance = NULL;

bool AccessibleCaret::isCaretUsed = false;

// IUnknown methods
IFACEMETHODIMP_(ULONG) AccessibleCaret::AddRef() {
    return InterlockedIncrement(&m_refCount);
}

IFACEMETHODIMP_(ULONG) AccessibleCaret::Release() {
    ULONG count = InterlockedDecrement(&m_refCount);
    if (count == 0) {
        delete this;
    }
    return count;
}

IFACEMETHODIMP AccessibleCaret::QueryInterface(REFIID riid, void **ppInterface) {
    if (ppInterface == NULL) {
        return E_POINTER;
    }

    if (riid == IID_IUnknown || riid == IID_IDispatch || riid == IID_IAccessible) {
        *ppInterface = static_cast<IAccessible *>(this);
        AddRef();
        return S_OK;
    }

    *ppInterface = NULL;
    return E_NOINTERFACE;
}

// IDispatch methods
IFACEMETHODIMP AccessibleCaret::GetTypeInfoCount(UINT *pctinfo) {
    return E_NOTIMPL;
}

IFACEMETHODIMP AccessibleCaret::GetTypeInfo(UINT itinfo, LCID lcid, ITypeInfo **pptinfo) {
    return E_NOTIMPL;
}

IFACEMETHODIMP AccessibleCaret::GetIDsOfNames(REFIID riid, OLECHAR **rgszNames, UINT cNames, LCID lcid,
                                              DISPID *rgdispid) {
    return E_NOTIMPL;
}

IFACEMETHODIMP AccessibleCaret::Invoke(DISPID dispidMember, REFIID riid, LCID lcid, WORD wFlags,
                                       DISPPARAMS *pdispparams, VARIANT *pvarResult, EXCEPINFO *pexcepinfo,
                                       UINT *puArgErr) {
    return E_NOTIMPL;
}

// IAccessible methods
IFACEMETHODIMP AccessibleCaret::get_accParent(IDispatch **ppdispParent) {
    if (ppdispParent == NULL) {
        return E_POINTER;
    }
    *ppdispParent = NULL;
    return S_OK;
}

IFACEMETHODIMP AccessibleCaret::get_accChildCount(long *pcountChildren) {
    if (pcountChildren == NULL) {
        return E_POINTER;
    }
    *pcountChildren = 0;
    return S_OK;
}

IFACEMETHODIMP AccessibleCaret::get_accChild(VARIANT varChild, IDispatch **ppdispChild) {
    *ppdispChild = NULL;
    return S_FALSE;
}

IFACEMETHODIMP AccessibleCaret::get_accName(VARIANT varChild, BSTR *pszName) {
    if (pszName == NULL) {
        return E_POINTER;
    }
    *pszName = SysAllocString(L"Edit"); // Same name as the system caret.
    return S_OK;
}

IFACEMETHODIMP AccessibleCaret::get_accValue(VARIANT varChild, BSTR *pszValue) {
    return E_NOTIMPL;
}

IFACEMETHODIMP AccessibleCaret::get_accDescription(VARIANT varChild, BSTR *pszDescription) {
    return E_NOTIMPL;
}

IFACEMETHODIMP AccessibleCaret::get_accRole(VARIANT varChild, VARIANT *pvarRole) {
    if (pvarRole == NULL) {
        return E_POINTER;
    }

    pvarRole->vt = VT_I4;
    pvarRole->lVal = ROLE_SYSTEM_CARET;
    return S_OK;
}

IFACEMETHODIMP AccessibleCaret::get_accState(VARIANT varChild, VARIANT *pvarState) {
    if (pvarState == NULL) {
        return E_POINTER;
    }

    pvarState->vt = VT_I4;
    pvarState->lVal = 0; // The state without any flags, corresponds to "normal".
    return S_OK;
}

IFACEMETHODIMP AccessibleCaret::get_accHelp(VARIANT varChild, BSTR *pszHelp) {
    return E_NOTIMPL;
}

IFACEMETHODIMP AccessibleCaret::get_accHelpTopic(BSTR *pszHelpFile, VARIANT varChild, long *pidTopic) {
    return E_NOTIMPL;
}

IFACEMETHODIMP AccessibleCaret::get_accKeyboardShortcut(VARIANT varChild, BSTR *pszKeyboardShortcut) {
    return E_NOTIMPL;
}

IFACEMETHODIMP AccessibleCaret::get_accFocus(VARIANT *pvarChild) {
    pvarChild->vt = VT_I4;
    pvarChild->lVal = CHILDID_SELF;
    return S_OK;
}

IFACEMETHODIMP AccessibleCaret::get_accSelection(VARIANT *pvarChildren) {
    return E_NOTIMPL;
}

IFACEMETHODIMP AccessibleCaret::get_accDefaultAction(VARIANT varChild, BSTR *pszDefaultAction) {
    return E_NOTIMPL;
}

IFACEMETHODIMP AccessibleCaret::accSelect(long flagsSelect, VARIANT varChild) {
    return E_NOTIMPL;
}

IFACEMETHODIMP AccessibleCaret::accLocation(long *pxLeft, long *pyTop, long *pcxWidth, long *pcyHeight,
                                            VARIANT varChild) {
    if (pxLeft == NULL || pyTop == NULL || pcxWidth == NULL || pcyHeight == NULL) {
        return E_POINTER;
    }
    isCaretUsed = true;

    *pxLeft = m_x;
    *pyTop = m_y;
    *pcxWidth = m_width;
    *pcyHeight = m_height;
    return S_OK;
}

IFACEMETHODIMP AccessibleCaret::accNavigate(long navDir, VARIANT varStart, VARIANT *pvarEndUpAt) {
    return E_NOTIMPL;
}

IFACEMETHODIMP AccessibleCaret::accHitTest(long xLeft, long yTop, VARIANT *pvarChild) {
    return E_NOTIMPL;
}

IFACEMETHODIMP AccessibleCaret::accDoDefaultAction(VARIANT varChild) {
    return E_NOTIMPL;
}

IFACEMETHODIMP AccessibleCaret::put_accName(VARIANT varChild, BSTR szName) {
    return E_NOTIMPL;
}

IFACEMETHODIMP AccessibleCaret::put_accValue(VARIANT varChild, BSTR szValue) {
    return E_NOTIMPL;
}

void AccessibleCaret::setLocation(long x, long y, long width, long height) {
    m_x = x;
    m_y = y;
    m_width = width;
    m_height = height;
}


extern "C" {
/*
 * Class:     sun_awt_windows_AccessibleCaretLocationNotifier
 * Method:    updateNativeCaretLocation
 * Signature: (JIIII)V
 */
JNIEXPORT void JNICALL Java_sun_awt_windows_AccessibleCaretLocationNotifier_updateNativeCaretLocation(
    JNIEnv *env, jclass jClass,
    jlong jHwnd, jint x, jint y, jint width, jint height) {
    HWND hwnd = reinterpret_cast<HWND>(jHwnd);
    if (AccessibleCaret::instance == NULL) {
        AccessibleCaret::instance = new AccessibleCaret();
        // Notify with Object ID "OBJID_CARET".
        // After that, an assistive tool will send a WM_GETOBJECT message with this ID,
        // and we can return the caret instance.
        NotifyWinEvent(EVENT_OBJECT_CREATE, hwnd, OBJID_CARET, CHILDID_SELF);
        NotifyWinEvent(EVENT_OBJECT_SHOW, hwnd, OBJID_CARET, CHILDID_SELF);
    }
    AccessibleCaret::instance->setLocation(x, y, width, height);
    NotifyWinEvent(EVENT_OBJECT_LOCATIONCHANGE, hwnd, OBJID_CARET, CHILDID_SELF);
}

/*
 * Class:     sun_awt_windows_AccessibleCaretLocationNotifier
 * Method:    releaseNativeCaret
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_sun_awt_windows_AccessibleCaretLocationNotifier_releaseNativeCaret(
    JNIEnv *env, jclass jClass, jlong jHwnd) {
    if (AccessibleCaret::instance != NULL) {
        HWND hwnd = reinterpret_cast<HWND>(jHwnd);
        AccessibleCaret::instance->Release();
        AccessibleCaret::instance = NULL;
        NotifyWinEvent(EVENT_OBJECT_HIDE, hwnd, OBJID_CARET, CHILDID_SELF);
        NotifyWinEvent(EVENT_OBJECT_DESTROY, hwnd, OBJID_CARET, CHILDID_SELF);
    }
}

/*
 * Class:     sun_awt_windows_AccessibleCaretLocationNotifier
 * Method:    notifyFocusedWindowChanged
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_sun_awt_windows_AccessibleCaretLocationNotifier_notifyFocusedWindowChanged(
    JNIEnv *env, jclass jClass, jlong jHwnd) {
    HWND hwnd = reinterpret_cast<HWND>(jHwnd);
    // This is a workaround to make sure the foreground window is set to the actual focused window.
    // Otherwise, in some cases, e.g., when opening a popup,
    // the root frame can still stay as the foreground window instead of the popup,
    // and Magnifier will be focused on it instead of the popup.
    // We only do it if the caret object is actually used to minimize risks.
    if (AccessibleCaret::isCaretUsed && ::GetForegroundWindow() != hwnd) {
        ::SetForegroundWindow(hwnd);
    }
}
} /* extern "C" */
