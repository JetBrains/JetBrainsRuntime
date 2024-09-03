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

#ifndef ACCESSIBLECARET_H
#define ACCESSIBLECARET_H
#include "jni.h"
#include <oleacc.h>
#include <atomic>

class AccessibleCaret : public IAccessible {
public:
    static AccessibleCaret *createInstance();
    static std::atomic<AccessibleCaret *> instance;

    // IUnknown methods.
    IFACEMETHODIMP_(ULONG) AddRef();
    IFACEMETHODIMP_(ULONG) Release();
    IFACEMETHODIMP QueryInterface(REFIID riid, void **ppInterface);

    // IDispatch methods.
    IFACEMETHODIMP GetTypeInfoCount(UINT *pctinfo);
    IFACEMETHODIMP GetTypeInfo(UINT itinfo, LCID lcid, ITypeInfo **pptinfo);
    IFACEMETHODIMP GetIDsOfNames(REFIID riid, __in_ecount(cNames)
                                 OLECHAR **rgszNames, UINT cNames, LCID lcid, DISPID *rgdispid);
    IFACEMETHODIMP Invoke(DISPID dispidMember, REFIID riid, LCID lcid,
                          WORD wFlags, DISPPARAMS *pdispparams, VARIANT *pvarResult,
                          EXCEPINFO *pexcepinfo, UINT *puArgErr);

    // IAccessible methods
    IFACEMETHODIMP get_accParent(IDispatch **ppdispParent);
    IFACEMETHODIMP get_accChildCount(long *pcountChildren);
    IFACEMETHODIMP get_accChild(VARIANT varChild, IDispatch **ppdispChild);
    IFACEMETHODIMP get_accName(VARIANT varChild, BSTR *pszName);
    IFACEMETHODIMP get_accValue(VARIANT varChild, BSTR *pszValue);
    IFACEMETHODIMP get_accDescription(VARIANT varChild, BSTR *pszDescription);
    IFACEMETHODIMP get_accRole(VARIANT varChild, VARIANT *pvarRole);
    IFACEMETHODIMP get_accState(VARIANT varChild, VARIANT *pvarState);
    IFACEMETHODIMP get_accHelp(VARIANT varChild, BSTR *pszHelp);
    IFACEMETHODIMP get_accHelpTopic(BSTR *pszHelpFile, VARIANT varChild, long *pidTopic);
    IFACEMETHODIMP get_accKeyboardShortcut(VARIANT varChild, BSTR *pszKeyboardShortcut);
    IFACEMETHODIMP get_accFocus(VARIANT *pvarChild);
    IFACEMETHODIMP get_accSelection(VARIANT *pvarChildren);
    IFACEMETHODIMP get_accDefaultAction(VARIANT varChild, BSTR *pszDefaultAction);
    IFACEMETHODIMP accSelect(long flagsSelect, VARIANT varChild);
    IFACEMETHODIMP accLocation(long *pxLeft, long *pyTop, long *pcxWidth, long *pcyHeight, VARIANT varChild);
    IFACEMETHODIMP accNavigate(long navDir, VARIANT varStart, VARIANT *pvarEndUpAt);
    IFACEMETHODIMP accHitTest(long xLeft, long yTop, VARIANT *pvarChild);
    IFACEMETHODIMP accDoDefaultAction(VARIANT varChild);
    IFACEMETHODIMP put_accName(VARIANT varChild, BSTR szName);
    IFACEMETHODIMP put_accValue(VARIANT varChild, BSTR szValue);

    void setLocation(long x, long y, long width, long height);

private:
    AccessibleCaret();
    ~AccessibleCaret();

    ULONG m_refCount;
    int m_x, m_y, m_width, m_height;
    CRITICAL_SECTION m_caretLocationLock;
};

#endif //ACCESSIBLECARET_H
