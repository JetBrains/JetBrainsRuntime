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
#include "jawcache.h"
#include "jawimpl.h"
#include "jawutil.h"
#include <glib.h>

/**
 * (From Atk documentation)
 *
 * AtkHyperlink:
 *
 * An ATK object which encapsulates a link or set of links in a hypertext
 * document.
 *
 * An ATK object which encapsulates a link or set of links (for
 * instance in the case of client-side image maps) in a hypertext
 * document.  It may implement the AtkAction interface.  AtkHyperlink
 * may also be used to refer to inline embedded content, since it
 * allows specification of a start and end offset within the host
 * AtkHypertext object.
 */

jclass cachedHyperlinkAtkHyperlinkClass = NULL;
jmethodID cachedHyperlinkGetUriMethod = NULL;
jmethodID cachedHyperlinkGetObjectMethod = NULL;
jmethodID cachedHyperlinkGetEndIndexMethod = NULL;
jmethodID cachedHyperlinkGetStartIndexMethod = NULL;
jmethodID cachedHyperlinkIsValidMethod = NULL;
jmethodID cachedHyperlinkGetNAnchorsMethod = NULL;

static GMutex cache_mutex;
static gboolean cache_initialized = FALSE;

static gboolean jaw_hyperlink_init_jni_cache(JNIEnv *jniEnv);

static void jaw_hyperlink_dispose(GObject *gobject);
static void jaw_hyperlink_finalize(GObject *gobject);

static gchar *jaw_hyperlink_get_uri(AtkHyperlink *atk_hyperlink, gint i);
static AtkObject *jaw_hyperlink_get_object(AtkHyperlink *atk_hyperlink, gint i);
static gint jaw_hyperlink_get_end_index(AtkHyperlink *atk_hyperlink);
static gint jaw_hyperlink_get_start_index(AtkHyperlink *atk_hyperlink);
static gboolean jaw_hyperlink_is_valid(AtkHyperlink *atk_hyperlink);
static gint jaw_hyperlink_get_n_anchors(AtkHyperlink *atk_hyperlink);

G_DEFINE_TYPE(JawHyperlink, jaw_hyperlink, ATK_TYPE_HYPERLINK)

#define JAW_GET_HYPERLINK(atk_hyperlink, def_ret)                              \
    JAW_GET_OBJ(atk_hyperlink, JAW_HYPERLINK, JawHyperlink, jaw_hyperlink,     \
                jhyperlink, jniEnv, jhyperlink, def_ret)

JawHyperlink *jaw_hyperlink_new(jobject jhyperlink) {
    JAW_DEBUG_ALL("%p", jhyperlink);

    if (jhyperlink == NULL) {
        g_warning("%s: NULL jhyperlink parameter", G_STRFUNC);
        return NULL;
    }

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    if (jniEnv == NULL) {
        g_warning("%s: Failed to get JNI environment", G_STRFUNC);
        return NULL;
    }

    JawHyperlink *jaw_hyperlink = g_object_new(JAW_TYPE_HYPERLINK, NULL);
    if (jaw_hyperlink == NULL) {
        g_warning("%s: Failed to create JawHyperlink object", G_STRFUNC);
        return NULL;
    }

    jaw_hyperlink->jhyperlink = (*jniEnv)->NewGlobalRef(jniEnv, jhyperlink);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jaw_hyperlink->jhyperlink == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to create global reference", G_STRFUNC);
        g_object_unref(jaw_hyperlink);
        return NULL;
    }

    return jaw_hyperlink;
}

/**
 * _AtkHyperlinkClass:
 * @get_uri:
 * @get_object:
 * @get_end_index:
 * @get_start_index:
 * @is_valid:
 * @get_n_anchors:
 * @link_state:
 * @is_selected_link:
 * @link_activated -- The signal link-activated is emitted when a link is
 *activated.
 **/
static void jaw_hyperlink_class_init(JawHyperlinkClass *klass) {
    JAW_DEBUG_ALL("%p", klass);

    if (klass == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    GObjectClass *gobject_class = G_OBJECT_CLASS(klass);
    gobject_class->dispose = jaw_hyperlink_dispose;
    gobject_class->finalize = jaw_hyperlink_finalize;

    AtkHyperlinkClass *atk_hyperlink_class = ATK_HYPERLINK_CLASS(klass);
    atk_hyperlink_class->get_uri = jaw_hyperlink_get_uri;
    atk_hyperlink_class->get_object = jaw_hyperlink_get_object;
    atk_hyperlink_class->get_end_index = jaw_hyperlink_get_end_index;
    atk_hyperlink_class->get_start_index = jaw_hyperlink_get_start_index;
    atk_hyperlink_class->is_valid = jaw_hyperlink_is_valid;
    atk_hyperlink_class->get_n_anchors = jaw_hyperlink_get_n_anchors;
    atk_hyperlink_class->link_state =
        NULL; // missing java support for atk_hyperlink_class->link_state
    atk_hyperlink_class->is_selected_link =
        NULL; // missing java support for
              // atk_hyperlink_class->is_selected_link
}

static void jaw_hyperlink_init(JawHyperlink *link) {
    JAW_DEBUG_ALL("%p", link);
}

static void jaw_hyperlink_dispose(GObject *gobject) {
    JAW_DEBUG_ALL("%p", gobject);

    if (gobject == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    /* Chain up to parent's dispose */
    G_OBJECT_CLASS(jaw_hyperlink_parent_class)->dispose(gobject);
}

static void jaw_hyperlink_finalize(GObject *gobject) {
    JAW_DEBUG_ALL("%p", gobject);

    if (gobject == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    JawHyperlink *jaw_hyperlink = JAW_HYPERLINK(gobject);
    if (jaw_hyperlink == NULL) {
        g_debug("%s: jaw_hyperlink is NULL", G_STRFUNC);
        G_OBJECT_CLASS(jaw_hyperlink_parent_class)->finalize(gobject);
        return;
    }

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    if (jniEnv == NULL) {
        g_debug("%s: jniEnv is NULL", G_STRFUNC);
        G_OBJECT_CLASS(jaw_hyperlink_parent_class)->finalize(gobject);
        return;
    }

    if (jaw_hyperlink->jstrUri != NULL) {
        if (jaw_hyperlink->uri != NULL) {
            (*jniEnv)->ReleaseStringUTFChars(jniEnv, jaw_hyperlink->jstrUri,
                                             jaw_hyperlink->uri);
            jaw_hyperlink->uri = NULL;
        }
        (*jniEnv)->DeleteGlobalRef(jniEnv, jaw_hyperlink->jstrUri);
        jaw_hyperlink->jstrUri = NULL;
    }

    if (jaw_hyperlink->jhyperlink != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, jaw_hyperlink->jhyperlink);
        jaw_hyperlink->jhyperlink = NULL;
    }

    /* Chain up to parent's finalize */
    G_OBJECT_CLASS(jaw_hyperlink_parent_class)->finalize(gobject);
}

/**
 * jaw_hyperlink_get_uri:
 * @link_: an #AtkHyperlink
 * @i: a (zero-index) integer specifying the desired anchor
 *
 * Get a the URI associated with the anchor specified
 * by @i of @link_.
 *
 * Multiple anchors are primarily used by client-side image maps.
 *
 * Returns: a string specifying the URI
 **/
static gchar *jaw_hyperlink_get_uri(AtkHyperlink *atk_hyperlink, gint i) {
    JAW_DEBUG_C("%p, %d", atk_hyperlink, i);

    if (atk_hyperlink == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    JAW_GET_HYPERLINK(atk_hyperlink, NULL); // create global JNI reference `jobject jhyperlink`

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            jhyperlink); // deleting ref that was created in JAW_GET_HYPERLINK
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    if (!jaw_hyperlink_init_jni_cache(jniEnv)) {
        g_warning("%s: Failed to initialize JNI cache", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, jhyperlink);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    jstring jstr =
        (*jniEnv)->CallObjectMethod(jniEnv, jhyperlink, cachedHyperlinkGetUriMethod, (jint)i);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jstr == NULL) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteGlobalRef(jniEnv, jhyperlink);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    if (jaw_hyperlink->jstrUri != NULL) {
        if (jaw_hyperlink->uri != NULL) {
            (*jniEnv)->ReleaseStringUTFChars(jniEnv, jaw_hyperlink->jstrUri,
                                             jaw_hyperlink->uri);
            jaw_hyperlink->uri = NULL;
        }
        (*jniEnv)->DeleteGlobalRef(jniEnv, jaw_hyperlink->jstrUri);
        jaw_hyperlink->jstrUri = NULL;
    }

    jaw_hyperlink->jstrUri = (*jniEnv)->NewGlobalRef(jniEnv, jstr);
    jaw_hyperlink->uri = (gchar *)(*jniEnv)->GetStringUTFChars(
        jniEnv, jaw_hyperlink->jstrUri, NULL);

    (*jniEnv)->DeleteGlobalRef(jniEnv, jhyperlink);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jaw_hyperlink->uri;
}

/**
 * jaw_hyperlink_get_object:
 * @atk_hyperlink: an #AtkHyperlink
 * @i: a (zero-index) integer specifying the desired anchor
 *
 * Returns the item associated with this hyperlinks nth anchor.
 *
 * Returns: (transfer none): an #AtkObject associated with this hyperlinks
 * i-th anchor
 **/
static AtkObject *jaw_hyperlink_get_object(AtkHyperlink *atk_hyperlink,
                                           gint i) {
    JAW_DEBUG_C("%p, %d", atk_hyperlink, i);

    if (atk_hyperlink == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    JAW_GET_HYPERLINK(atk_hyperlink, NULL);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            jhyperlink); // deleting ref that was created in JAW_GET_HYPERLINK
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    if (!jaw_hyperlink_init_jni_cache(jniEnv)) {
        g_warning("%s: Failed to initialize JNI cache", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, jhyperlink);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    jobject ac = (*jniEnv)->CallObjectMethod(jniEnv, jhyperlink, cachedHyperlinkGetObjectMethod, (jint)i);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || ac == NULL) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteGlobalRef(jniEnv, jhyperlink);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    AtkObject *obj = (AtkObject *)jaw_impl_find_instance(jniEnv, ac);
    if (obj == NULL) {
        g_warning("%s: No AtkObject found for AccessibleContext", G_STRFUNC);
    }

    // From documentation of the `atk_hyperlink_get_object`:
    // The returned data is owned by the instance (transfer none annotation), so
    // we don't ref the obj before returning it

    (*jniEnv)->DeleteGlobalRef(jniEnv, jhyperlink);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return obj;
}

/**
 * atk_hyperlink_get_end_index:
 * @link_: an #AtkHyperlink
 *
 * Gets the index with the hypertext document at which this link ends.
 *
 * Returns: the index with the hypertext document at which this link ends, 0 if
 *an error happened.
 **/
static gint jaw_hyperlink_get_end_index(AtkHyperlink *atk_hyperlink) {
    JAW_DEBUG_C("%p", atk_hyperlink);

    if (atk_hyperlink == NULL) {
        g_warning("%s: Null argument atk_hyperlink passed to the function", G_STRFUNC);
        return 0;
    }

    JAW_GET_HYPERLINK(atk_hyperlink, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            jhyperlink); // deleting ref that was created in JAW_GET_HYPERLINK
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return 0;
    }

    if (!jaw_hyperlink_init_jni_cache(jniEnv)) {
        g_warning("%s: Failed to initialize JNI cache", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, jhyperlink);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    jint jindex = (*jniEnv)->CallIntMethod(jniEnv, jhyperlink, cachedHyperlinkGetEndIndexMethod);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
       jaw_jni_clear_exception(jniEnv);
       (*jniEnv)->DeleteGlobalRef(jniEnv, jhyperlink);
       (*jniEnv)->PopLocalFrame(jniEnv, NULL);
       return 0;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, jhyperlink);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jindex;
}

/**
 * jaw_hyperlink_get_start_index:
 * @link_: an #AtkHyperlink
 *
 * Gets the index with the hypertext document at which this link begins.
 *
 * Returns: the index with the hypertext document at which this link begins, 0
 * if an error happened
 **/
static gint jaw_hyperlink_get_start_index(AtkHyperlink *atk_hyperlink) {
    JAW_DEBUG_C("%p", atk_hyperlink);

    if (atk_hyperlink == NULL) {
        g_warning("%s: Null argument atk_hyperlink passed to the function", G_STRFUNC);
        return 0;
    }

    JAW_GET_HYPERLINK(atk_hyperlink, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            jhyperlink); // deleting ref that was created in JAW_GET_HYPERLINK
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return 0;
    }

    if (!jaw_hyperlink_init_jni_cache(jniEnv)) {
        g_warning("%s: Failed to initialize JNI cache", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, jhyperlink);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    jint jindex = (*jniEnv)->CallIntMethod(jniEnv, jhyperlink, cachedHyperlinkGetStartIndexMethod);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
       jaw_jni_clear_exception(jniEnv);
       (*jniEnv)->DeleteGlobalRef(jniEnv, jhyperlink);
       (*jniEnv)->PopLocalFrame(jniEnv, NULL);
       return 0;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, jhyperlink);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jindex;
}

/**
 * jaw_hyperlink_is_valid:
 * @link_: an #AtkHyperlink
 *
 * Since the document that a link is associated with may have changed
 * this method returns %TRUE if the link is still valid (with
 * respect to the document it references) and %FALSE otherwise.
 *
 * Returns: whether or not this link is still valid
 **/
static gboolean jaw_hyperlink_is_valid(AtkHyperlink *atk_hyperlink) {
    JAW_DEBUG_C("%p", atk_hyperlink);

    if (atk_hyperlink == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return FALSE;
    }

    JAW_GET_HYPERLINK(atk_hyperlink, FALSE);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            jhyperlink); // deleting ref that was created in JAW_GET_HYPERLINK
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return FALSE;
    }

    if (!jaw_hyperlink_init_jni_cache(jniEnv)) {
        g_warning("%s: Failed to initialize JNI cache", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, jhyperlink);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }

    jboolean jvalid = (*jniEnv)->CallBooleanMethod(jniEnv, jhyperlink, cachedHyperlinkIsValidMethod);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
       jaw_jni_clear_exception(jniEnv);
       (*jniEnv)->DeleteGlobalRef(jniEnv, jhyperlink);
       (*jniEnv)->PopLocalFrame(jniEnv, NULL);
       return FALSE;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, jhyperlink);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jvalid;
}

/**
 * jaw_hyperlink_get_n_anchors:
 * @link_: an #AtkHyperlink
 *
 * Gets the number of anchors associated with this hyperlink.
 *
 * Returns: the number of anchors associated with this hyperlink
 **/
static gint jaw_hyperlink_get_n_anchors(AtkHyperlink *atk_hyperlink) {
    JAW_DEBUG_C("%p", atk_hyperlink);

    if (atk_hyperlink == NULL) {
        g_warning("%s: Null argument atk_hyperlink passed to the function", G_STRFUNC);
        return 0;
    }

    JAW_GET_HYPERLINK(atk_hyperlink, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            jhyperlink); // deleting ref that was created in JAW_GET_HYPERLINK
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return 0;
    }

    if (!jaw_hyperlink_init_jni_cache(jniEnv)) {
        g_warning("%s: Failed to initialize JNI cache", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, jhyperlink);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    jint janchors = (*jniEnv)->CallIntMethod(jniEnv, jhyperlink, cachedHyperlinkGetNAnchorsMethod);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
       jaw_jni_clear_exception(jniEnv);
       (*jniEnv)->DeleteGlobalRef(jniEnv, jhyperlink);
       (*jniEnv)->PopLocalFrame(jniEnv, NULL);
       return 0;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, jhyperlink);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return janchors;
}

static gboolean jaw_hyperlink_init_jni_cache(JNIEnv *jniEnv) {
    JAW_CHECK_NULL(jniEnv, FALSE);

    g_mutex_lock(&cache_mutex);

    if (cache_initialized) {
        g_mutex_unlock(&cache_mutex);
        return TRUE;
    }

    jclass localClass = (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkHyperlink");
    if ((*jniEnv)->ExceptionCheck(jniEnv) || localClass == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to find AtkHyperlink class", G_STRFUNC);
        goto cleanup_and_fail;
    }

    cachedHyperlinkAtkHyperlinkClass = (*jniEnv)->NewGlobalRef(jniEnv, localClass);
    (*jniEnv)->DeleteLocalRef(jniEnv, localClass);

    if (cachedHyperlinkAtkHyperlinkClass == NULL) {
        g_warning("%s: Failed to create global reference for AtkHyperlink class", G_STRFUNC);
        goto cleanup_and_fail;
    }

    cachedHyperlinkGetUriMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedHyperlinkAtkHyperlinkClass, "get_uri", "(I)Ljava/lang/String;");

    cachedHyperlinkGetObjectMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedHyperlinkAtkHyperlinkClass, "get_object",
        "(I)Ljavax/accessibility/AccessibleContext;");

    cachedHyperlinkGetEndIndexMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedHyperlinkAtkHyperlinkClass, "get_end_index", "()I");

    cachedHyperlinkGetStartIndexMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedHyperlinkAtkHyperlinkClass, "get_start_index", "()I");

    cachedHyperlinkIsValidMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedHyperlinkAtkHyperlinkClass, "is_valid", "()Z");

    cachedHyperlinkGetNAnchorsMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedHyperlinkAtkHyperlinkClass, "get_n_anchors", "()I");

    if ((*jniEnv)->ExceptionCheck(jniEnv) ||
        cachedHyperlinkGetUriMethod == NULL ||
        cachedHyperlinkGetObjectMethod == NULL ||
        cachedHyperlinkGetEndIndexMethod == NULL ||
        cachedHyperlinkGetStartIndexMethod == NULL ||
        cachedHyperlinkIsValidMethod == NULL ||
        cachedHyperlinkGetNAnchorsMethod == NULL) {

        jaw_jni_clear_exception(jniEnv);

        g_warning("%s: Failed to cache one or more AtkHyperlink method IDs",
                  G_STRFUNC);
        goto cleanup_and_fail;
    }

    cache_initialized = TRUE;
    g_mutex_unlock(&cache_mutex);
    return TRUE;

cleanup_and_fail:
    if (cachedHyperlinkAtkHyperlinkClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedHyperlinkAtkHyperlinkClass);
        cachedHyperlinkAtkHyperlinkClass = NULL;
    }
    cachedHyperlinkGetUriMethod = NULL;
    cachedHyperlinkGetObjectMethod = NULL;
    cachedHyperlinkGetEndIndexMethod = NULL;
    cachedHyperlinkGetStartIndexMethod = NULL;
    cachedHyperlinkIsValidMethod = NULL;
    cachedHyperlinkGetNAnchorsMethod = NULL;

    g_mutex_unlock(&cache_mutex);
    return FALSE;
    g_mutex_unlock(&cache_mutex);
    return TRUE;
}

void jaw_hyperlink_cache_cleanup(JNIEnv *jniEnv) {
    if (jniEnv == NULL) {
        return;
    }

    g_mutex_lock(&cache_mutex);

    if (cachedHyperlinkAtkHyperlinkClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedHyperlinkAtkHyperlinkClass);
        cachedHyperlinkAtkHyperlinkClass = NULL;
    }
    cachedHyperlinkGetUriMethod = NULL;
    cachedHyperlinkGetObjectMethod = NULL;
    cachedHyperlinkGetEndIndexMethod = NULL;
    cachedHyperlinkGetStartIndexMethod = NULL;
    cachedHyperlinkIsValidMethod = NULL;
    cachedHyperlinkGetNAnchorsMethod = NULL;
    cache_initialized = FALSE;

    g_mutex_unlock(&cache_mutex);
}
