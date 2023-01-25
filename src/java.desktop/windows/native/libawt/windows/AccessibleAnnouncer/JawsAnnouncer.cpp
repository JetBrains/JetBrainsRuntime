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

#include "JawsAnnouncer.h"

#ifndef NO_A11Y_JAWS_ANNOUNCING
#include "IJawsApi.h"
#include "sun_swing_AccessibleAnnouncer.h"
#include "jni_util.h"                           // JNU_ThrowOutOfMemoryError
#include "debug_assert.h"                       // DASSERT
#include <windows.h>                            // GetCurrentThreadId
#include <initguid.h>                           // DEFINE_GUID


/* {CCE5B1E5-B2ED-45D5-B09F-8EC54B75ABF4} */
DEFINE_GUID(CLSID_JAWSCLASS,
    0xCCE5B1E5, 0xB2ED, 0x45D5, 0xB0, 0x9F, 0x8E, 0xC5, 0x4B, 0x75, 0xAB, 0xF4);

/* {123DEDB4-2CF6-429C-A2AB-CC809E5516CE} */
DEFINE_GUID(IID_IJAWSAPI,
    0x123DEDB4, 0x2CF6, 0x429C, 0xA2, 0xAB, 0xCC, 0x80, 0x9E, 0x55, 0x16, 0xCE);


class ComInitializationWrapper final {
public: // ctors
    ComInitializationWrapper() = default;
    ComInitializationWrapper(const ComInitializationWrapper&) = delete;
    ComInitializationWrapper(ComInitializationWrapper&&) = delete;

public: // assignments
    ComInitializationWrapper& operator=(const ComInitializationWrapper&) = delete;
    ComInitializationWrapper& operator=(ComInitializationWrapper&&) = delete;

public:
    HRESULT tryInitialize() {
        if (!isInitialized()) {
            m_initializeResult = CoInitialize(nullptr);
        }
        return m_initializeResult;
    }

public: // dtor
    ~ComInitializationWrapper() {
        // MSDN: To close the COM library gracefully, each successful call to CoInitialize or CoInitializeEx,
        //       including those that return S_FALSE, must be balanced by a corresponding call to CoUninitialize
        if ((m_initializeResult == S_OK) || (m_initializeResult == S_FALSE)) {
            m_initializeResult = CO_E_NOTINITIALIZED;
            CoUninitialize();
        }
    }

public: // getters
    HRESULT getInitializeResult() const noexcept { return m_initializeResult; }

    bool isInitialized() const noexcept {
        if ( (m_initializeResult == S_OK) ||
             (m_initializeResult == S_FALSE) ||             // Is already initialized
             (m_initializeResult == RPC_E_CHANGED_MODE) ) { // Is already initialized but with different threading mode
            return true;
        }
        return false;
    }

private:
    HRESULT m_initializeResult = CO_E_NOTINITIALIZED;
};


template<typename T>
struct ComObjectWrapper final {
    T* objPtr;

    ~ComObjectWrapper() {
        T* const localObjPtr = objPtr;
        objPtr = nullptr;

        if (localObjPtr != nullptr) {
            localObjPtr->Release();
        }
    }
};


bool JawsAnnounce(JNIEnv *env, jstring str, jint priority)
{
    DASSERT(env != nullptr);
    DASSERT(str != nullptr);

    static const DWORD comInitThreadId = ::GetCurrentThreadId();

    const DWORD currThread = ::GetCurrentThreadId();
    if (currThread != comInitThreadId) {
#ifdef DEBUG
        fprintf(stderr, "JawsAnnounce: currThread != comInitThreadId.\n");
#endif
        return false;
    }

    static ComInitializationWrapper comInitializer;
    comInitializer.tryInitialize();
    if (!comInitializer.isInitialized()) {
#ifdef DEBUG
        fprintf(stderr, "JawsAnnounce: CoInitialize failed ; HRESULT=0x%llX.\n",
                static_cast<unsigned long long>(comInitializer.getInitializeResult()));
#endif
        return false;
    }

    static ComObjectWrapper<IJawsApi> pJawsApi{ nullptr };
    if (pJawsApi.objPtr == nullptr) {
        HRESULT hr = CoCreateInstance(CLSID_JAWSCLASS, nullptr, CLSCTX_INPROC_SERVER, IID_IJAWSAPI, reinterpret_cast<void**>(&pJawsApi.objPtr));
        if ((hr != S_OK) || (pJawsApi.objPtr == nullptr)) {
#ifdef DEBUG
            fprintf(stderr, "JawsAnnounce: CoCreateInstance failed ; HRESULT=0x%llX.\n", static_cast<unsigned long long>(hr));
#endif
            // just in case
            if (pJawsApi.objPtr != nullptr) {
                pJawsApi.objPtr->Release();
                pJawsApi.objPtr = nullptr;
            }
            return false;
        }
    }

    VARIANT_BOOL jawsInterruptCurrentOutput = VARIANT_TRUE;
    if (priority == sun_swing_AccessibleAnnouncer_ANNOUNCE_WITHOUT_INTERRUPTING_CURRENT_OUTPUT) {
        jawsInterruptCurrentOutput = VARIANT_FALSE;
    }

    const jchar* jStringToSpeak = env->GetStringChars(str, nullptr);
    if (jStringToSpeak == nullptr) {
        if (env->ExceptionCheck() == JNI_FALSE) {
            JNU_ThrowOutOfMemoryError(env, "JawsAnnounce: failed to obtain chars from the announcing string");
        }
        return false;
    }

    BSTR stringToSpeak = SysAllocString(jStringToSpeak);

    env->ReleaseStringChars(str, jStringToSpeak);
    jStringToSpeak = nullptr;

    if (stringToSpeak == nullptr) {
        if (env->ExceptionCheck() == JNI_FALSE) {
            JNU_ThrowOutOfMemoryError(env, "JawsAnnounce: failed to allocate memory for the announcing string");
        }
        return false;
    }

    VARIANT_BOOL jawsSucceeded = VARIANT_FALSE;

    HRESULT comCallResult = pJawsApi.objPtr->SayString(stringToSpeak, jawsInterruptCurrentOutput, &jawsSucceeded);

    SysFreeString(stringToSpeak);
    stringToSpeak = nullptr;

    if (FAILED(comCallResult)) {
#ifdef DEBUG
        fprintf(stderr, "JawsAnnounce: failed to invoke COM function to say string ; HRESULT=0x%llX.\n", static_cast<unsigned long long>(comCallResult));
#endif
        return false;
    }
    if (jawsSucceeded != VARIANT_TRUE) {
#ifdef DEBUG
        fprintf(stderr, "JawsAnnounce: failed to say string ; code = %d.\n", static_cast<int>(jawsSucceeded));
#endif
        return false;
    }

    return true;
}

#endif // ndef NO_A11Y_JAWS_ANNOUNCING
