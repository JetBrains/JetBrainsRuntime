/*
 * Copyright (c) 2000, 2021, Oracle and/or its affiliates. All rights reserved.
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

#include <stdio.h>
#include <dlfcn.h>
#include <string.h>
#include <stdlib.h>
#include <jni.h>
#include <jni_util.h>
#include <jvm.h>
#include "gdefs.h"
#include "sun_awt_PlatformGraphicsInfo.h"
#include <sys/param.h>
#include <sys/utsname.h>

#ifdef AIX
#include "porting_aix.h" /* For the 'dladdr' function. */
#endif

#ifndef MACOSX
#ifndef STATIC_BUILD

#define CHECK_EXCEPTION_FATAL(env, message) \
    if ((*env)->ExceptionCheck(env)) { \
        (*env)->ExceptionClear(env); \
        (*env)->FatalError(env, message); \
    }

static void *fontmanagerHandle = NULL;

JNIEXPORT jboolean JNICALL AWTIsHeadless();
JNIEXPORT jint JNICALL AWTGetToolkitID();

jint
Fontmanager_OnLoad(JavaVM *vm, void *reserved)
{
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(vm, JNI_VERSION_1_2);

    if (!AWTIsHeadless() && AWTGetToolkitID() == sun_awt_PlatformGraphicsInfo_TK_X11) {

        Dl_info dlinfo;
        char buf[MAXPATHLEN];
        int32_t len;
        char *p, *tk;

        tk = "/libfontmanager_xawt.so";

        /* Get address of this library and the directory containing it. */
        dladdr((void *)Fontmanager_OnLoad, &dlinfo);
        realpath((char *)dlinfo.dli_fname, buf);
        len = strlen(buf);
        p = strrchr(buf, '/');
        /* Calculate library name to load */
        strncpy(p, tk, MAXPATHLEN-len-1);

        jstring jbuf = JNU_NewStringPlatform(env, buf);
        CHECK_EXCEPTION_FATAL(env, "Could not allocate library name");
        JNU_CallStaticMethodByName(env, NULL, "java/lang/System", "load",
                                   "(Ljava/lang/String;)V",
                                   jbuf);
        fontmanagerHandle = dlopen(buf, RTLD_LAZY | RTLD_GLOBAL);
    }

    return JNI_VERSION_1_2;
}

JNIEXPORT jint JNICALL
DEF_JNI_OnLoad(JavaVM *vm, void *reserved)
{
    return Fontmanager_OnLoad(vm, reserved);
}

#endif
#endif
