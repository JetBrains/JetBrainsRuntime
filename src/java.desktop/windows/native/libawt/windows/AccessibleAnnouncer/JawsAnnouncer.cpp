/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2022, JetBrains s.r.o.. All rights reserved.
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

extern const CLSID CLSID_JAWSCLASS;
extern const IID IID_IJAWSAPI;

bool JawsAnnounce(JNIEnv *env, jstring str, jint priority)
{
    DASSERT(env != nullptr);
    DASSERT(str != nullptr);

    IJawsApi* pJawsApi = NULL;
    CoInitialize(NULL);
    HRESULT hr = CoCreateInstance(CLSID_JAWSCLASS, NULL, CLSCTX_INPROC_SERVER, IID_IJAWSAPI, reinterpret_cast<void**>(&pJawsApi));
    if (!(SUCCEEDED(hr) && pJawsApi)) {
#ifdef DEBUG
        fprintf(stderr, "Failed to get instans of Jaws API with code = %d\n", hr);
#endif
        CoUninitialize();
        return false;
    }

    const jchar *jchars = env->GetStringChars(str, NULL);
    if (jchars == nullptr) {
    JNU_ThrowOutOfMemoryError(env, "JawsAnnounce: failed to obtain chars from the announcing string"                    );
        CoUninitialize();
        return false;
    }

    VARIANT_BOOL retval;
    BSTR param = SysAllocString(jchars);
    int jawsPriority = -1;
    if (priority == sun_swing_AccessibleAnnouncer_ANNOUNCE_WITHOUT_INTERRUPTING_CURRENT_OUTPUT) {
        jawsPriority = 0;
    }
    pJawsApi->SayString(param, jawsPriority, &retval);
    SysFreeString(param);
    env->ReleaseStringChars(str, jchars);
    CoUninitialize();
    if(retval != -1) {
#ifdef DEBUG
        fprintf(stderr, "Failed say string with jaws with code = %d\n", retval);
#endif
        return false;
    }

    return true;
}

#include <initguid.h>

/* {CCE5B1E5-B2ED-45D5-B09F-8EC54B75ABF4} */
DEFINE_GUID(CLSID_JAWSCLASS ,
0x0CCE5B1E5, 0x0B2ED, 0x45D5,0xB0, 0x9F, 0x8E, 0x0C5, 0x4B, 0x75, 0x0AB, 0x0F4);

/* {123DEDB4-2CF6-429C-A2AB-CC809E5516CE} */
DEFINE_GUID(IID_IJAWSAPI ,
0x123DEDB4,0x2CF6, 0x429C, 0x0A2, 0x0AB, 0x0CC, 0x80, 0x9E, 0x55, 0x16, 0x0CE);

#endif // ndef NO_A11Y_JAWS_ANNOUNCING
