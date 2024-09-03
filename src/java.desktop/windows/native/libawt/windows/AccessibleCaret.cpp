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
    InitializeCriticalSection(&m_caretLocationLock);
}

AccessibleCaret* AccessibleCaret::createInstance() {
    return new AccessibleCaret();
}

AccessibleCaret::~AccessibleCaret() {
    DeleteCriticalSection(&m_caretLocationLock);
}

std::atomic<AccessibleCaret *> AccessibleCaret::instance{nullptr};


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
    if (ppInterface == nullptr) {
        return E_POINTER;
    }

    if (riid == IID_IUnknown || riid == IID_IDispatch || riid == IID_IAccessible) {
        *ppInterface = static_cast<IAccessible *>(this);
        AddRef();
        return S_OK;
    }

    *ppInterface = nullptr;
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
    if (ppdispParent == nullptr) {
        return E_POINTER;
    }
    *ppdispParent = nullptr;
    return S_FALSE;
}

IFACEMETHODIMP AccessibleCaret::get_accChildCount(long *pcountChildren) {
    if (pcountChildren == nullptr) {
        return E_POINTER;
    }
    *pcountChildren = 0;
    return S_OK;
}

IFACEMETHODIMP AccessibleCaret::get_accChild(VARIANT varChild, IDispatch **ppdispChild) {
    if (ppdispChild == nullptr) {
        return E_POINTER;
    }
    *ppdispChild = nullptr;
    return S_FALSE;
}

IFACEMETHODIMP AccessibleCaret::get_accName(VARIANT varChild, BSTR *pszName) {
    if (pszName == nullptr) {
        return E_POINTER;
    }
    *pszName = SysAllocString(L"Edit"); // Same name as the system caret.
    return S_OK;
}

IFACEMETHODIMP AccessibleCaret::get_accValue(VARIANT varChild, BSTR *pszValue) {
    return DISP_E_MEMBERNOTFOUND;
}

IFACEMETHODIMP AccessibleCaret::get_accDescription(VARIANT varChild, BSTR *pszDescription) {
    return S_FALSE;
}

IFACEMETHODIMP AccessibleCaret::get_accRole(VARIANT varChild, VARIANT *pvarRole) {
    if (pvarRole == nullptr) {
        return E_POINTER;
    }

    pvarRole->vt = VT_I4;
    pvarRole->lVal = ROLE_SYSTEM_CARET;
    return S_OK;
}

IFACEMETHODIMP AccessibleCaret::get_accState(VARIANT varChild, VARIANT *pvarState) {
    if (pvarState == nullptr) {
        return E_POINTER;
    }

    pvarState->vt = VT_I4;
    pvarState->lVal = 0; // The state without any flags, corresponds to "normal".
    return S_OK;
}

IFACEMETHODIMP AccessibleCaret::get_accHelp(VARIANT varChild, BSTR *pszHelp) {
    return S_FALSE;
}

IFACEMETHODIMP AccessibleCaret::get_accHelpTopic(BSTR *pszHelpFile, VARIANT varChild, long *pidTopic) {
    return S_FALSE;
}

IFACEMETHODIMP AccessibleCaret::get_accKeyboardShortcut(VARIANT varChild, BSTR *pszKeyboardShortcut) {
    return S_FALSE;
}

IFACEMETHODIMP AccessibleCaret::get_accFocus(VARIANT *pvarChild) {
    if (pvarChild == nullptr) {
        return E_POINTER;
    }

    pvarChild->vt = VT_EMPTY;
    return S_OK;
}

IFACEMETHODIMP AccessibleCaret::get_accSelection(VARIANT *pvarChildren) {
    return DISP_E_MEMBERNOTFOUND;
}

IFACEMETHODIMP AccessibleCaret::get_accDefaultAction(VARIANT varChild, BSTR *pszDefaultAction) {
    return S_FALSE;
}

IFACEMETHODIMP AccessibleCaret::accSelect(long flagsSelect, VARIANT varChild) {
    return DISP_E_MEMBERNOTFOUND;
}

IFACEMETHODIMP AccessibleCaret::accLocation(long *pxLeft, long *pyTop, long *pcxWidth, long *pcyHeight,
                                            VARIANT varChild) {
    if (pxLeft == nullptr || pyTop == nullptr || pcxWidth == nullptr || pcyHeight == nullptr) {
        return E_POINTER;
    }

    EnterCriticalSection(&m_caretLocationLock);
    *pxLeft = m_x;
    *pyTop = m_y;
    *pcxWidth = m_width;
    *pcyHeight = m_height;
    LeaveCriticalSection(&m_caretLocationLock);

    return S_OK;
}

IFACEMETHODIMP AccessibleCaret::accNavigate(long navDir, VARIANT varStart, VARIANT *pvarEndUpAt) {
    return DISP_E_MEMBERNOTFOUND;
}

IFACEMETHODIMP AccessibleCaret::accHitTest(long xLeft, long yTop, VARIANT *pvarChild) {
    return DISP_E_MEMBERNOTFOUND;
}

IFACEMETHODIMP AccessibleCaret::accDoDefaultAction(VARIANT varChild) {
    return DISP_E_MEMBERNOTFOUND;
}

IFACEMETHODIMP AccessibleCaret::put_accName(VARIANT varChild, BSTR szName) {
    return E_NOTIMPL;
}

IFACEMETHODIMP AccessibleCaret::put_accValue(VARIANT varChild, BSTR szValue) {
    return DISP_E_MEMBERNOTFOUND;
}

void AccessibleCaret::setLocation(long x, long y, long width, long height) {
    EnterCriticalSection(&m_caretLocationLock);
    m_x = x;
    m_y = y;
    m_width = width;
    m_height = height;
    LeaveCriticalSection(&m_caretLocationLock);
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
    AccessibleCaret *caret = AccessibleCaret::instance.load(std::memory_order_acquire);
    if (caret == nullptr) {
        caret = AccessibleCaret::createInstance();
        AccessibleCaret::instance.store(caret, std::memory_order_release);
        // Notify with Object ID "OBJID_CARET".
        // After that, an assistive tool will send a WM_GETOBJECT message with this ID,
        // and we can return the caret instance.
        NotifyWinEvent(EVENT_OBJECT_CREATE, hwnd, OBJID_CARET, CHILDID_SELF);
        NotifyWinEvent(EVENT_OBJECT_SHOW, hwnd, OBJID_CARET, CHILDID_SELF);
    }
    caret->setLocation(x, y, width, height);
    NotifyWinEvent(EVENT_OBJECT_LOCATIONCHANGE, hwnd, OBJID_CARET, CHILDID_SELF);
}

/*
 * Class:     sun_awt_windows_AccessibleCaretLocationNotifier
 * Method:    releaseNativeCaret
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_sun_awt_windows_AccessibleCaretLocationNotifier_releaseNativeCaret(
    JNIEnv *env, jclass jClass, jlong jHwnd) {
    AccessibleCaret *caret = AccessibleCaret::instance.exchange(nullptr, std::memory_order_acq_rel);
    if (caret != nullptr) {
        caret->Release();
        HWND hwnd = reinterpret_cast<HWND>(jHwnd);
        NotifyWinEvent(EVENT_OBJECT_HIDE, hwnd, OBJID_CARET, CHILDID_SELF);
        NotifyWinEvent(EVENT_OBJECT_DESTROY, hwnd, OBJID_CARET, CHILDID_SELF);
    }
}
} /* extern "C" */
