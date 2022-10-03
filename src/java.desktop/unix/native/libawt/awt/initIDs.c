/*
 * Copyright (c) 1997, 2023, Oracle and/or its affiliates. All rights reserved.
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

#include "java_awt_Dimension.h"
#include "java_awt_MenuBar.h"
#include "java_awt_FontMetrics.h"
#include "java_awt_event_MouseEvent.h"
#include "java_awt_ScrollPaneAdjustable.h"
#include "java_awt_Toolkit.h"
#include "java_awt_CheckboxMenuItem.h"

#include "jni_util.h"

#include <stdlib.h>
#include <string.h>
#include <link.h>

/*
 * This file contains stubs for JNI field and method id initializers
 * which are used in the win32 awt.
 */

JNIEXPORT void JNICALL
Java_java_awt_MenuBar_initIDs
  (JNIEnv *env, jclass clazz)
{
}

JNIEXPORT void JNICALL
Java_java_awt_Label_initIDs
  (JNIEnv *env, jclass clazz)
{
}

JNIEXPORT void JNICALL
Java_java_awt_FontMetrics_initIDs
  (JNIEnv *env, jclass clazz)
{
}

JNIEXPORT void JNICALL
Java_java_awt_Toolkit_initIDs
  (JNIEnv *env, jclass clazz)
{
}

JNIEXPORT void JNICALL
Java_java_awt_ScrollPaneAdjustable_initIDs
  (JNIEnv *env, jclass clazz)
{
}

JNIEXPORT void JNICALL
Java_java_awt_CheckboxMenuItem_initIDs
  (JNIEnv *env, jclass clazz)
{
}

JNIEXPORT void JNICALL
Java_java_awt_Choice_initIDs
  (JNIEnv *env, jclass clazz)
{
}

JNIEXPORT void JNICALL
Java_java_awt_Dimension_initIDs
  (JNIEnv *env, jclass clazz)
{
}

JNIEXPORT void JNICALL
Java_java_awt_event_MouseEvent_initIDs
  (JNIEnv *env, jclass clazz)
{
}

struct shared_libs {
    int      count;
    int      index;
    char **  names;
};

static int
dl_iterate_callback
    (struct dl_phdr_info *info, size_t size, void *data)
{
    struct shared_libs *libs = (struct shared_libs*)data;
    if (libs->names == NULL) {
        libs->count++;
    } else {
        // The number of libraries may have grown since the last time we asked.
        if (libs->index < libs->count) {
            libs->names[libs->index++] = strdup(info->dlpi_name);
        }
    }

    return 0;
}

static jarray
convert_to_java_array
    (JNIEnv *env, struct shared_libs* libs)
{
    if ((*env)->EnsureLocalCapacity(env, libs->count + 2) != JNI_OK) {
        return NULL; // OOME has been thrown already
    }


    jclass stringClazz = (*env)->FindClass(env, "java/lang/String");
    CHECK_NULL_RETURN(stringClazz, NULL);
    jarray libsArray = (*env)->NewObjectArray(env, libs->count, stringClazz, NULL);
    JNU_CHECK_EXCEPTION_RETURN(env, NULL);

    for (uint32_t i = 0; i < libs->count; i++) {
        const char * name = libs->names[i];
        if (name) {
            jstring jName = (*env)->NewStringUTF(env, name);
            JNU_CHECK_EXCEPTION_RETURN(env, NULL);
            (*env)->SetObjectArrayElement(env, libsArray, i, jName);
            JNU_CHECK_EXCEPTION_RETURN(env, NULL);
        }
    }

    return libsArray;
}

JNIEXPORT jarray JNICALL
Java_sun_font_FontManagerNativeLibrary_loadedLibraries
    (JNIEnv *env, jclass cls)
{
    struct shared_libs libs = {0, 0, NULL};
    dl_iterate_phdr(&dl_iterate_callback, &libs);
    if (libs.count <= 0) {
        return NULL;
    }

    libs.names = (char **) calloc(libs.count, sizeof(libs.names[0]));
    CHECK_NULL_RETURN(libs.names, NULL);
    dl_iterate_phdr(&dl_iterate_callback, &libs);

    jarray libsArray = convert_to_java_array(env, &libs);

    for (uint32_t i = 0; i < libs.count; i++) {
        free(libs.names[i]);
    }
    free(libs.names);

    return libsArray;
}
