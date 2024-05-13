/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2024, Azul Systems, Inc. All rights reserved.
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

#include <jvmti.h>
#include <string.h>

void JNICALL callbackClassUnload(jvmtiEnv* jvmti_env, ...) {
    printf("callbackClassUnload called\n");
    fflush(NULL);
}

void JNICALL callbackClassLoad(jvmtiEnv* jvmti_env, JNIEnv* jni_env, jthread thread, jclass klass) {
    // nothing to do, just a stub
}

JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM* jvm, char* options, void* reserved) {
    printf("%s:%d : %s : JVMTI agent loading...\n", __FILE__, __LINE__, __FUNCTION__);

    jvmtiEnv* jvmti = NULL;
    jint extensionEventCount = 0;
    jvmtiExtensionEventInfo* extensionEvents = NULL;
    (*jvm)->GetEnv(jvm, (void**)&jvmti, JVMTI_VERSION_1_0);

    // Set extension event callback
    (*jvmti)->GetExtensionEvents(jvmti, &extensionEventCount, &extensionEvents);
    for (int i = 0; i < extensionEventCount; ++i) {
        if (0 == strcmp("com.sun.hotspot.events.ClassUnload", extensionEvents[i].id)) {
            (*jvmti)->SetExtensionEventCallback(jvmti, extensionEvents[i].extension_event_index, &callbackClassUnload);
        }
    }

    {
        // Set some event callbacks
        jvmtiEventCallbacks callbacks;
        memset(&callbacks, 0, sizeof(callbacks));
        callbacks.ClassLoad = &callbackClassLoad;
        (*jvmti)->SetEventCallbacks(jvmti, &callbacks, sizeof(callbacks));

        // Clear event callbacks
        memset(&callbacks, 0, sizeof(callbacks));
        (*jvmti)->SetEventCallbacks(jvmti, &callbacks, sizeof(callbacks));
    }

    return JNI_OK;
}

JNIEXPORT void JNICALL Agent_OnUnload(JavaVM* jvm) {
    printf("%s:%d : %s : JVMTI agent unloading...\n", __FILE__, __LINE__, __FUNCTION__);
}
