/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
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

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <jni.h>
#include <jni_util.h>

#include "gtk_interface.h"
#include "sun_awt_wl_GtkFileDialogPeer.h"
#include "java_awt_FileDialog.h"
#include "debug_assert.h"

typedef void GtkWidget;
static JavaVM *jvm;

static jmethodID filenameFilterCallbackMethodID = NULL;
static jmethodID setFileInternalMethodID = NULL;
static jfieldID  widgetFieldID = NULL;
static jmethodID setWindowMethodID = NULL;
static jmethodID onCloseMethodID = NULL;

JNIEXPORT void JNICALL Java_sun_awt_wl_GtkFileDialogPeer_initIDs
(JNIEnv *env, jclass cx)
{
    filenameFilterCallbackMethodID = (*env)->GetMethodID(env, cx,
            "filenameFilterCallback", "(Ljava/lang/String;)Z");
    DASSERT(filenameFilterCallbackMethodID != NULL);
    CHECK_NULL(filenameFilterCallbackMethodID);

    setFileInternalMethodID = (*env)->GetMethodID(env, cx,
            "setFileInternal", "(Ljava/lang/String;[Ljava/lang/String;)V");
    DASSERT(setFileInternalMethodID != NULL);
    CHECK_NULL(setFileInternalMethodID);

    widgetFieldID = (*env)->GetFieldID(env, cx, "widget", "J");
    DASSERT(widgetFieldID != NULL);
    CHECK_NULL(widgetFieldID);

    setWindowMethodID = (*env)->GetMethodID(env, cx, "setWindow", "()Z");
    DASSERT(setWindowMethodID != NULL);

    onCloseMethodID = (*env)->GetMethodID(env, cx, "onClose", "()V");
    DASSERT(onCloseMethodID != NULL);
}

static gboolean filenameFilterCallback(const GtkFileFilterInfo * filter_info, gpointer obj)
{
    JNIEnv *env;
    jstring filename;

    env = (JNIEnv *) JNU_GetEnv(jvm, JNI_VERSION_1_2);

    filename = (*env)->NewStringUTF(env, filter_info->filename);
    JNU_CHECK_EXCEPTION_RETURN(env, FALSE);

    return (*env)->CallBooleanMethod(env, obj, filenameFilterCallbackMethodID, filename);
}

static void quit(JNIEnv * env, jobject jpeer, gboolean isSignalHandler)
{
    jthrowable pendingException;
    if ((pendingException = (*env)->ExceptionOccurred(env)) != NULL) {
         (*env)->ExceptionClear(env);
    }

    GtkWidget * dialog = (GtkWidget*)jlong_to_ptr(
            (*env)->GetLongField(env, jpeer, widgetFieldID));

    (*env)->CallVoidMethod(env, jpeer, onCloseMethodID);

    if (dialog != NULL) {
        // Callbacks from GTK signals are made within the GTK lock
        // So, within a signal handler there is no need to call
        // gdk_threads_enter() / gtk->gdk_threads_leave()
        if (!isSignalHandler) {
            gtk->gdk_threads_enter();
        }

        gtk->gtk_widget_hide (dialog);
        gtk->gtk_widget_destroy (dialog);

        gtk->gtk_main_quit ();

        if (!isSignalHandler) {
            gtk->gdk_threads_leave();
        }
    }

    if (pendingException) {
         (*env)->Throw(env, pendingException);
    }
}

JNIEXPORT void JNICALL Java_sun_awt_wl_GtkFileDialogPeer_quit
(JNIEnv * env, jobject jpeer)
{
    quit(env, jpeer, FALSE);
}

JNIEXPORT void JNICALL Java_sun_awt_wl_GtkFileDialogPeer_toFrontImpl
(JNIEnv * env, jobject jpeer, jlong timestamp)
{
    gtk->gdk_threads_enter();

    GtkWidget * dialog = jlong_to_ptr((*env)->GetLongField(env, jpeer, widgetFieldID));
    if (dialog != NULL) {
        gtk->gtk_window_present_with_time((GtkWindow*)dialog, (guint32)timestamp);
    }

    gtk->gdk_threads_leave();
}

JNIEXPORT void JNICALL Java_sun_awt_wl_GtkFileDialogPeer_setBounds
(JNIEnv * env, jobject jpeer, jint x, jint y, jint width, jint height, jint op)
{
    gtk->gdk_threads_enter();

    GtkWindow* dialog = jlong_to_ptr((*env)->GetLongField(env, jpeer, widgetFieldID));
    if (dialog != NULL) {
        // Note: gtk_window_move() will not work in Wayland anyway, so the coordinates
        //       are ignored intentionally here.
        if (width > 0 && height > 0) {
            gtk->gtk_window_resize(dialog, (gint)width, (gint)height);
        }
    }

    gtk->gdk_threads_leave();
}

/*
 * baseDir should be freed by user.
 */
static gboolean isFromSameDirectory(GSList* list, gchar** baseDir) {

    GSList *it = list;
    gchar* prevDir = NULL;
    gboolean isAllDirsSame = TRUE;

    while (it) {
        gchar* dir = gtk->g_path_get_dirname((gchar*) it->data);

        if (prevDir && strcmp(prevDir, dir) != 0) {
            isAllDirsSame = FALSE;
            gtk->g_free(dir);
            break;
        }

        if (!prevDir) {
            prevDir = strdup(dir);
        }
        gtk->g_free(dir);

        it = it->next;
    }

    if (isAllDirsSame) {
        *baseDir = prevDir;
    } else {
        free(prevDir);
        *baseDir = strdup("/");
    }

    return isAllDirsSame;
}

/**
 * Convert a GSList to an array of filenames
 */
static jobjectArray toFilenamesArray(JNIEnv *env, GSList* list, jstring* jcurrent_folder)
{
    jstring str;
    jclass stringCls;
    GSList *iterator;
    jobjectArray array;
    int i;
    gchar* entry;
    gchar * baseDir;
    gboolean isFromSameDir;

    if (list == NULL) {
        return NULL;
    }

    stringCls = (*env)->FindClass(env, "java/lang/String");
    if (stringCls == NULL) {
        (*env)->ExceptionClear(env);
        JNU_ThrowInternalError(env, "Could not get java.lang.String class");
        return NULL;
    }

    array = (*env)->NewObjectArray(env, gtk->gtk_g_slist_length(list), stringCls, NULL);
    if (array == NULL) {
        (*env)->ExceptionClear(env);
        JNU_ThrowInternalError(env, "Could not instantiate array files array");
        return NULL;
    }

    isFromSameDir = isFromSameDirectory(list, &baseDir);

    *jcurrent_folder = (*env)->NewStringUTF(env, baseDir);
    if (*jcurrent_folder == NULL) {
        free(baseDir);
        return NULL;
    }

    for (iterator = list, i=0; iterator; iterator = iterator->next, i++) {
        entry = (gchar*) iterator->data;
        if (isFromSameDir) {
            entry = strrchr(entry, '/') + 1;
        } else if (entry[0] == '/') {
            entry++;
        }

        str = (*env)->NewStringUTF(env, entry);
        if((*env)->ExceptionCheck(env)){
            break;
        }
        if (str) {
            (*env)->SetObjectArrayElement(env, array, i, str);
            if((*env)->ExceptionCheck(env)){
                break;
            }
        }
    }

    free(baseDir);
    return array;
}

static void handle_response(GtkWidget* aDialog, gint responseId, gpointer obj)
{
    JNIEnv *env;
    GSList *filenames;
    jstring jcurrent_folder = NULL;
    jobjectArray jfilenames;

    env = (JNIEnv *) JNU_GetEnv(jvm, JNI_VERSION_1_2);
    filenames = NULL;

    if (responseId == GTK_RESPONSE_ACCEPT) {
        filenames = gtk->gtk_file_chooser_get_filenames(GTK_FILE_CHOOSER(aDialog));
    }

    jfilenames = toFilenamesArray(env, filenames, &jcurrent_folder);

    if (!(*env)->ExceptionCheck(env)) {
        (*env)->CallVoidMethod(env, obj, setFileInternalMethodID, jcurrent_folder, jfilenames);
    }

    quit(env, (jobject)obj, TRUE);
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_GtkFileDialogPeer_run(JNIEnv * env, jobject jpeer,
        jstring jtitle, jint mode, jstring jdir, jstring jfile,
        jobject jfilter, jboolean multiple)
{
    GtkWidget *dialog = NULL;
    GtkFileFilter *filter;

    if (jvm == NULL) {
        (*env)->GetJavaVM(env, &jvm);
        JNU_CHECK_EXCEPTION(env);
    }

    gtk->gdk_threads_enter();

    const char *title = jtitle == NULL? "": (*env)->GetStringUTFChars(env, jtitle, 0);
    if (title == NULL) {
        (*env)->ExceptionClear(env);
        JNU_ThrowOutOfMemoryError(env, "Could not get title");
        return;
    }

    if (mode == java_awt_FileDialog_SAVE) {
        dialog = gtk->gtk_file_chooser_dialog_new(title, NULL,
                GTK_FILE_CHOOSER_ACTION_SAVE, GTK_STOCK_CANCEL,
                GTK_RESPONSE_CANCEL, GTK_STOCK_SAVE, GTK_RESPONSE_ACCEPT, NULL);
    }
    else {
        dialog = gtk->gtk_file_chooser_dialog_new(title, NULL,
                GTK_FILE_CHOOSER_ACTION_OPEN, GTK_STOCK_CANCEL,
                GTK_RESPONSE_CANCEL, GTK_STOCK_OPEN, GTK_RESPONSE_ACCEPT, NULL);

        /* Set multiple selection mode, that is allowed only in OPEN action */
        if (multiple) {
            gtk->gtk_file_chooser_set_select_multiple(GTK_FILE_CHOOSER(dialog), multiple);
        }
    }

    if (jtitle != NULL) {
        (*env)->ReleaseStringUTFChars(env, jtitle, title);
    }

    /* Set the directory */
    if (jdir != NULL) {
        const char *dir = (*env)->GetStringUTFChars(env, jdir, 0);
        if (dir == NULL) {
            (*env)->ExceptionClear(env);
            JNU_ThrowOutOfMemoryError(env, "Could not get dir");
            return;
        }
        gtk->gtk_file_chooser_set_current_folder(GTK_FILE_CHOOSER(dialog), dir);
        (*env)->ReleaseStringUTFChars(env, jdir, dir);
    }

    /* Set the filename */
    if (jfile != NULL) {
        const char *filename = (*env)->GetStringUTFChars(env, jfile, 0);
        if (filename == NULL) {
            (*env)->ExceptionClear(env);
            JNU_ThrowOutOfMemoryError(env, "Could not get filename");
            return;
        }
        if (mode == java_awt_FileDialog_SAVE) {
            gtk->gtk_file_chooser_set_current_name(GTK_FILE_CHOOSER(dialog), filename);
        } else {
            gtk->gtk_file_chooser_set_filename(GTK_FILE_CHOOSER(dialog), filename);
        }
        (*env)->ReleaseStringUTFChars(env, jfile, filename);
    }

    /* Set the file filter */
    if (jfilter != NULL) {
        filter = gtk->gtk_file_filter_new();
        gtk->gtk_file_filter_add_custom(filter, GTK_FILE_FILTER_FILENAME,
                filenameFilterCallback, jpeer, NULL);
        gtk->gtk_file_chooser_set_filter(GTK_FILE_CHOOSER(dialog), filter);
    }

    /* Other Properties */
    gtk->gtk_file_chooser_set_do_overwrite_confirmation(GTK_FILE_CHOOSER(dialog), TRUE);

    // Note: the initial location will be ignored by the Wayland server anyway, so
    //       we are not doing gtk_window_move() here

    gtk->g_signal_connect_data(dialog, "response", G_CALLBACK( handle_response), jpeer, 0, 0);

    (*env)->SetLongField(env, jpeer, widgetFieldID, ptr_to_jlong(dialog));

    gtk->gtk_widget_show(dialog);

    if ((*env)->CallBooleanMethod(env, jpeer, setWindowMethodID, 0)) {
        gtk->gtk_main();
    }

    gtk->gdk_threads_leave();
}

