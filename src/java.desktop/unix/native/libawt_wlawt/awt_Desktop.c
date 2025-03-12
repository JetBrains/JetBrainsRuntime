/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2025, JetBrains s.r.o.. All rights reserved.
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

#ifdef HEADLESS
    #error This file should not be included in headless library
#endif

#include <dlfcn.h>

#include <jni.h>
#include <jvm_md.h>

#include "Trace.h"
#include "jni_util.h"
#include "gtk_interface.h"

typedef gboolean (GNOME_URL_SHOW_TYPE)(const char *, void **);
typedef gboolean (GNOME_VFS_INIT_TYPE)(void);

static GNOME_URL_SHOW_TYPE *gnome_url_show = NULL;

static gboolean gnome_load(void) {
     void *vfs_handle;
     void *gnome_handle;
     const char *errmsg;
     GNOME_VFS_INIT_TYPE *gnome_vfs_init;

     vfs_handle = dlopen(VERSIONED_JNI_LIB_NAME("gnomevfs-2", "0"), RTLD_LAZY);
     if (vfs_handle == NULL) {
         vfs_handle = dlopen(JNI_LIB_NAME("gnomevfs-2"), RTLD_LAZY);
         if (vfs_handle == NULL) {
             J2dTraceLn(J2D_TRACE_ERROR, "can not load libgnomevfs-2.so");
             return FALSE;
         }
     }
     dlerror(); /* Clear errors */
     gnome_vfs_init = dlsym(vfs_handle, "gnome_vfs_init");
     if (gnome_vfs_init == NULL){
         J2dTraceLn(J2D_TRACE_ERROR, "dlsym( gnome_vfs_init) returned NULL");
         return FALSE;
     }
     if ((errmsg = dlerror()) != NULL) {
         J2dTraceLn(J2D_TRACE_ERROR, "can not find symbol gnome_vfs_init %s", errmsg);
         return FALSE;
     }
     // call gonme_vfs_init()
     (*gnome_vfs_init)();

     gnome_handle = dlopen(VERSIONED_JNI_LIB_NAME("gnome-2", "0"), RTLD_LAZY);
     if (gnome_handle == NULL) {
         gnome_handle = dlopen(JNI_LIB_NAME("gnome-2"), RTLD_LAZY);
         if (gnome_handle == NULL) {
             J2dTraceLn(J2D_TRACE_ERROR, "can not load libgnome-2.so");
             return FALSE;
         }
     }
     dlerror(); /* Clear errors */
     gnome_url_show = dlsym(gnome_handle, "gnome_url_show");
     if ((errmsg = dlerror()) != NULL) {
         J2dTraceLn(J2D_TRACE_ERROR, "can not find symble gnome_url_show");
         return FALSE;
     }
     return TRUE;
}

static gboolean gtk_has_been_loaded = FALSE;
static gboolean gnome_has_been_loaded = FALSE;

JNIEXPORT jboolean JNICALL Java_sun_awt_wl_WLDesktopPeer_init
  (JNIEnv *env, jclass cls, jint version, jboolean verbose)
{
    if (gtk_has_been_loaded || gnome_has_been_loaded) {
        return JNI_TRUE;
    }

    if (gtk_load(env, version, verbose) && gtk->show_uri_load(env)) {
        gtk_has_been_loaded = TRUE;
        return JNI_TRUE;
    } else if (gnome_load()) {
        gnome_has_been_loaded = TRUE;
        return JNI_TRUE;
    }

    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_sun_awt_wl_WLDesktopPeer_gnome_1url_1show
  (JNIEnv *env, jobject obj, jbyteArray url_j)
{
    gboolean success = FALSE;
    const gchar* url_c = (gchar*)(*env)->GetByteArrayElements(env, url_j, NULL);
    if (url_c == NULL) {
        JNU_ThrowOutOfMemoryError(env, 0);
        return JNI_FALSE;
    }

    if (gtk_has_been_loaded) {
        gtk->gdk_threads_enter();
        success = gtk->gtk_show_uri_on_window(NULL, url_c, GDK_CURRENT_TIME, NULL);
        gtk->gdk_threads_leave();
    } else if (gnome_has_been_loaded) {
        success = (*gnome_url_show)(url_c, NULL);
    }

    (*env)->ReleaseByteArrayElements(env, url_j, (jbyte*)url_c, 0);

    return success ? JNI_TRUE : JNI_FALSE;
}

