/*
 * Java ATK Wrapper for GNOME
 * Copyright (C) 2009 Sun Microsystems Inc.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301 USA
 */

#include "jawhyperlink.h"
#include "jawimpl.h"
#include "jawutil.h"
#include <atk/atk.h>
#include <glib.h>

/**
 * (From Atk documentation)
 *
 * AtkHypertext:
 *
 * The ATK interface which provides standard mechanism for manipulating
 * hyperlinks.
 *
 * An interface used for objects which implement linking between
 * multiple resource or content locations, or multiple 'markers'
 * within a single document.  A Hypertext instance is associated with
 * one or more Hyperlinks, which are associated with particular
 * offsets within the Hypertext's included content.  While this
 * interface is derived from Text, there is no requirement that
 * Hypertext instances have textual content; they may implement Image
 * as well, and Hyperlinks need not have non-zero text offsets.
 */

static jclass cachedAtkHypertextClass = NULL;
static jmethodID cachedCreateAtkHypertextMethod = NULL;
static jmethodID cachedGetLinkMethod = NULL;
static jmethodID cachedGetNLinksMethod = NULL;
static jmethodID cachedGetLinkIndexMethod = NULL;

static GMutex cache_init_mutex;
static gboolean cache_initialized = FALSE;

static gboolean jaw_hypertext_init_jni_cache(JNIEnv *jniEnv);

static AtkHyperlink *jaw_hypertext_get_link(AtkHypertext *hypertext,
                                            gint link_index);
static gint jaw_hypertext_get_n_links(AtkHypertext *hypertext);
static gint jaw_hypertext_get_link_index(AtkHypertext *hypertext,
                                         gint char_index);

typedef struct _HypertextData {
    jobject atk_hypertext;
} HypertextData;

#define JAW_GET_HYPERTEXT(hypertext, def_ret)                                  \
    JAW_GET_OBJ_IFACE(                                                         \
        hypertext, org_GNOME_Accessibility_AtkInterface_INTERFACE_HYPERTEXT,   \
        HypertextData, atk_hypertext, jniEnv, atk_hypertext, def_ret)

/**
 * AtkHypertextIface:
 * @get_link:
 * @get_n_links:
 * @get_link_index:
 **/
void jaw_hypertext_interface_init(AtkHypertextIface *iface, gpointer data) {
    if (iface == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    iface->get_link = jaw_hypertext_get_link;
    iface->get_n_links = jaw_hypertext_get_n_links;
    iface->get_link_index = jaw_hypertext_get_link_index;
}

gpointer jaw_hypertext_data_init(jobject ac) {
    JAW_DEBUG_ALL("%p", ac);

    if (ac == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    JAW_CHECK_NULL(jniEnv, NULL);

    if (!jaw_hypertext_init_jni_cache(jniEnv)) {
        g_warning("%s: Failed to initialize JNI cache", G_STRFUNC);
        return NULL;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jobject jatk_hypertext =
        (*jniEnv)->CallStaticObjectMethod(jniEnv, cachedAtkHypertextClass, cachedCreateAtkHypertextMethod, ac);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jatk_hypertext == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to create jatk_hypertext using create_atk_hypertext method", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    HypertextData *data = g_new0(HypertextData, 1);
    data->atk_hypertext = (*jniEnv)->NewGlobalRef(jniEnv, jatk_hypertext);
    if (data->atk_hypertext == NULL) {
        g_warning("%s: Failed to create global ref for atk_hypertext", G_STRFUNC);
        g_free(data);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return data;
}

void jaw_hypertext_data_finalize(gpointer p) {
    JAW_DEBUG_ALL("%p", p);

    if (p == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    HypertextData *data = (HypertextData *)p;
    if (data == NULL) {
        g_warning("%s: data is null after cast", G_STRFUNC);
        return;
    }

    JNIEnv *jniEnv = jaw_util_get_jni_env();

    if (jniEnv == NULL) {
        g_warning("%s: JNIEnv is NULL in finalize", G_STRFUNC);
    } else {
        if (data->atk_hypertext != NULL) {
            (*jniEnv)->DeleteGlobalRef(jniEnv, data->atk_hypertext);
            data->atk_hypertext = NULL;
        }
    }

    g_free(data);
}

/**
 * jaw_hypertext_get_link:
 * @hypertext: an #AtkHypertext
 * @link_index: an integer specifying the desired link
 *
 * Gets the link in this hypertext document at index
 * @link_index
 *
 * Returns: (transfer none): the link in this hypertext document at
 * index @link_index
 **/
static AtkHyperlink *jaw_hypertext_get_link(AtkHypertext *hypertext,
                                            gint link_index) {
    JAW_DEBUG_C("%p, %d", hypertext, link_index);

    if (hypertext == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    JAW_GET_HYPERTEXT(hypertext, NULL); // create global JNI reference `jobject atk_hypertext`

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_hypertext); // deleting ref that was created in
                            // JAW_GET_HYPERTEXT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jobject jhyperlink = (*jniEnv)->CallObjectMethod(jniEnv, atk_hypertext,
                                                     cachedGetLinkMethod, (jint)link_index);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jhyperlink == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to create jhyperlink using get_link method", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_hypertext);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    JawHyperlink *jaw_hyperlink = jaw_hyperlink_new(jhyperlink);
    if (jaw_hyperlink == NULL) {
        g_warning("%s: Failed to create JawHyperlink object for link_index %d", G_STRFUNC, link_index);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_hypertext);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_hypertext);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return ATK_HYPERLINK(jaw_hyperlink);
}

/**
 * jaw_hypertext_get_n_links:
 * @hypertext: an #AtkHypertext
 *
 * Gets the number of links within this hypertext document.
 *
 * Returns: the number of links within this hypertext document
 **/
static gint jaw_hypertext_get_n_links(AtkHypertext *hypertext) {
    JAW_DEBUG_C("%p", hypertext);

    if (hypertext == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return 0;
    }

    JAW_GET_HYPERTEXT(hypertext, 0); // create global JNI reference `jobject atk_hypertext`

    gint ret = (gint)(*jniEnv)->CallIntMethod(jniEnv, atk_hypertext, cachedGetNLinksMethod);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_hypertext);
        return 0;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_hypertext);

    return ret;
}

/**
 * jaw_hypertext_get_link_index:
 * @hypertext: an #AtkHypertext
 * @char_index: a character index
 *
 * Gets the index into the array of hyperlinks that is associated with
 * the character specified by @char_index.
 *
 * Returns: an index into the array of hyperlinks in @hypertext,
 * or -1 if there is no hyperlink associated with this character.
 **/
static gint jaw_hypertext_get_link_index(AtkHypertext *hypertext,
                                         gint char_index) {
    JAW_DEBUG_C("%p, %d", hypertext, char_index);

    if (hypertext == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return -1;
    }

    JAW_GET_HYPERTEXT(hypertext, -1); // create global JNI reference `jobject atk_hypertext`

    gint ret = (gint)(*jniEnv)->CallIntMethod(jniEnv, atk_hypertext, cachedGetLinkIndexMethod,
                                              (jint)char_index);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_hypertext);
        return -1;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_hypertext);

    return ret;
}

static gboolean jaw_hypertext_init_jni_cache(JNIEnv *jniEnv) {
    JAW_CHECK_NULL(jniEnv, FALSE);

    g_mutex_lock(&cache_init_mutex);

    if (cache_initialized) {
        g_mutex_unlock(&cache_init_mutex);
        return TRUE;
    }

    jclass localClass = (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkHypertext");
    if ((*jniEnv)->ExceptionCheck(jniEnv) || localClass == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to find AtkHypertext class", G_STRFUNC);
        goto cleanup_and_fail;
    }

    cachedAtkHypertextClass = (*jniEnv)->NewGlobalRef(jniEnv, localClass);
    (*jniEnv)->DeleteLocalRef(jniEnv, localClass);

    if ((*jniEnv)->ExceptionCheck(jniEnv) || cachedAtkHypertextClass == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to create global reference for AtkHypertext class", G_STRFUNC);
        goto cleanup_and_fail;
    }

    cachedCreateAtkHypertextMethod = (*jniEnv)->GetStaticMethodID(
        jniEnv, cachedAtkHypertextClass, "create_atk_hypertext",
        "(Ljavax/accessibility/AccessibleContext;)Lorg/GNOME/Accessibility/AtkHypertext;");

    cachedGetLinkMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedAtkHypertextClass, "get_link",
        "(I)Lorg/GNOME/Accessibility/AtkHyperlink;");

    cachedGetNLinksMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedAtkHypertextClass, "get_n_links", "()I");

    cachedGetLinkIndexMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedAtkHypertextClass, "get_link_index", "(I)I");

    if ((*jniEnv)->ExceptionCheck(jniEnv) ||
        cachedCreateAtkHypertextMethod == NULL ||
        cachedGetLinkMethod == NULL ||
        cachedGetNLinksMethod == NULL ||
        cachedGetLinkIndexMethod == NULL) {

        jaw_jni_clear_exception(jniEnv);

        g_warning("%s: Failed to cache one or more AtkHypertext method IDs",
                  G_STRFUNC);
        goto cleanup_and_fail;
    }

    cache_initialized = TRUE;
    g_mutex_unlock(&cache_init_mutex);
    return TRUE;

cleanup_and_fail:
    if (cachedAtkHypertextClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedAtkHypertextClass);
        cachedAtkHypertextClass = NULL;
    }
    cachedCreateAtkHypertextMethod = NULL;
    cachedGetLinkMethod = NULL;
    cachedGetNLinksMethod = NULL;
    cachedGetLinkIndexMethod = NULL;

    g_mutex_unlock(&cache_init_mutex);
    return FALSE;
}
