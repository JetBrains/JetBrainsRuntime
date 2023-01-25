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


#include "NVDAAnnouncer.h"

#ifndef NO_A11Y_NVDA_ANNOUNCING
#include "sun_swing_AccessibleAnnouncer.h"
#include "jni_util.h"                           // JNU_ThrowOutOfMemoryError
#include "debug_assert.h"                       // DASSERT
#include <nvdaController.h>                     // nvdaController_*, error_status_t

bool NVDAAnnounce(JNIEnv* const env, const jstring str, const jint priority)
{
    DASSERT(env != nullptr);
    DASSERT(str != nullptr);

    error_status_t nvdaStatus;

    if ( (nvdaStatus = nvdaController_testIfRunning()) != 0 ) {
#ifdef DEBUG
        fprintf(stderr, "NVDA isn't running or an RPC error occurred code = %d\n", nvdaStatus);
#endif
        return false;
    }

    if (priority == sun_swing_AccessibleAnnouncer_ANNOUNCE_WITH_INTERRUPTING_CURRENT_OUTPUT) {
        if ( (nvdaStatus = nvdaController_cancelSpeech()) != 0 ) {
#ifdef DEBUG
            fprintf(stderr, "Failed to interrupt current output. code = %d\n", nvdaStatus);
#endif
        }
    }

    const jchar* jchars = env->GetStringChars(str, nullptr);
    if (jchars == nullptr) {
        if (env->ExceptionCheck() == JNI_FALSE) {
            JNU_ThrowOutOfMemoryError(env, "NVDAAnnounce: failed to obtain chars from the announcing string");
        }
        return false;
    }

    static_assert(sizeof(*jchars) == sizeof(wchar_t), "Can't cast jchar* to wchar_t*");
    const wchar_t* announceText = reinterpret_cast<const wchar_t*>(jchars);

    nvdaStatus = nvdaController_speakText(announceText);

    env->ReleaseStringChars(str, jchars);
    jchars = nullptr;
    announceText = nullptr;

    if (nvdaStatus != 0) {
#ifdef DEBUG
        fprintf(stderr, "nvdaController_speakText failed code = %d\n", nvdaStatus);
#endif
        return false;
    }

    return true;
}

#endif // ndef NO_A11Y_NVDA_ANNOUNCING
