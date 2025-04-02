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

static AtkHyperlink *jaw_hypertext_get_link(AtkHypertext *hypertext,
                                            gint link_index);
static gint jaw_hypertext_get_n_links(AtkHypertext *hypertext);
static gint jaw_hypertext_get_link_index(AtkHypertext *hypertext,
                                         gint char_index);

typedef struct _HypertextData {
    jobject atk_hypertext;
} HypertextData;

#define JAW_GET_HYPERTEXT(hypertext, def_ret)                                  \
    JAW_GET_OBJ_IFACE(hypertext, INTERFACE_HYPERTEXT, HypertextData,           \
                      atk_hypertext, jniEnv, atk_hypertext, def_ret)

void jaw_hypertext_interface_init(AtkHypertextIface *iface, gpointer data) {
    if (!iface) {
        g_warning(
            "Null argument passed to function jaw_hypertext_interface_init");
        return;
    }

    iface->get_link = jaw_hypertext_get_link;
    iface->get_n_links = jaw_hypertext_get_n_links;
    iface->get_link_index = jaw_hypertext_get_link_index;
}

gpointer jaw_hypertext_data_init(jobject ac) {
    JAW_DEBUG_ALL("%p", ac);

    if (!ac) {
        g_warning("Null argument passed to function jaw_hypertext_data_init");
        return NULL;
    }

    HypertextData *data = g_new0(HypertextData, 1);

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    JAW_CHECK_NULL(jniEnv, NULL);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("Failed to create a new local reference frame");
        return NULL;
    }

    jclass classHypertext =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkHypertext");
    if (!classHypertext) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, classHypertext, "create_atk_hypertext",
        "(Ljavax/accessibility/AccessibleContext;)Lorg/GNOME/Accessibility/"
        "AtkHypertext;");
    if (!jmid) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jobject jatk_hypertext =
        (*jniEnv)->CallStaticObjectMethod(jniEnv, classHypertext, jmid, ac);
    if (!jatk_hypertext) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    data->atk_hypertext = (*jniEnv)->NewGlobalRef(jniEnv, jatk_hypertext);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
    return data;
}

void jaw_hypertext_data_finalize(gpointer p) {
    JAW_DEBUG_ALL("%p", p);

    if (!p) {
        g_warning(
            "Null argument passed to function jaw_hypertext_data_finalize");
        return;
    }

    HypertextData *data = (HypertextData *)p;
    JAW_CHECK_NULL(data, );

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    JAW_CHECK_NULL(jniEnv, );

    if (data->atk_hypertext) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, data->atk_hypertext);
        data->atk_hypertext = NULL;
    }
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

    if (!hypertext) {
        g_warning("Null argument passed to function jaw_hypertext_get_link");
        return NULL;
    }

    JAW_GET_HYPERTEXT(hypertext, NULL);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_hypertext); // deleting ref that was created in
                            // JAW_GET_HYPERTEXT
        g_warning("Failed to create a new local reference frame");
        return NULL;
    }

    jclass classAtkHypertext =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkHypertext");
    if (!classAtkHypertext) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_hypertext);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkHypertext, "get_link",
                               "(I)Lorg/GNOME/Accessibility/AtkHyperlink;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_hypertext);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    jobject jhyperlink = (*jniEnv)->CallObjectMethod(jniEnv, atk_hypertext,
                                                     jmid, (jint)link_index);
    if (!jhyperlink) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_hypertext);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    JawHyperlink *jaw_hyperlink = jaw_hyperlink_new(jhyperlink);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_hypertext);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return ATK_HYPERLINK(jaw_hyperlink);
}

/*
 * Gets the number of links within this hypertext document.
 */
static gint jaw_hypertext_get_n_links(AtkHypertext *hypertext) {
    JAW_DEBUG_C("%p", hypertext);

    if (!hypertext) {
        g_warning("Null argument passed to function jaw_hypertext_get_n_links");
        return 0;
    }

    JAW_GET_HYPERTEXT(hypertext, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_hypertext); // deleting ref that was created in
                            // JAW_GET_HYPERTEXT
        g_warning("Failed to create a new local reference frame");
        return 0;
    }

    jclass classAtkHypertext =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkHypertext");
    if (!classAtkHypertext) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_hypertext);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkHypertext, "get_n_links", "()I");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_hypertext);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    gint ret = (gint)(*jniEnv)->CallIntMethod(jniEnv, atk_hypertext, jmid);
    if (!ret) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_hypertext);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_hypertext);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return ret;
}

/*
 * Gets the index into the array of hyperlinks that is associated with the
 * character specified by char-index.
 *
 * Returns -1 if an error occurred.
 */
static gint jaw_hypertext_get_link_index(AtkHypertext *hypertext,
                                         gint char_index) {
    JAW_DEBUG_C("%p, %d", hypertext, char_index);

    if (!hypertext) {
        g_warning(
            "Null argument passed to function jaw_hypertext_get_link_index");
        return -1;
    }

    JAW_GET_HYPERTEXT(hypertext, -1);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_hypertext); // deleting ref that was created in
                            // JAW_GET_HYPERTEXT
        g_warning("Failed to create a new local reference frame");
        return -1;
    }

    jclass classAtkHypertext =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkHypertext");
    if (!classAtkHypertext) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_hypertext);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return -1;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkHypertext,
                                            "get_link_index", "(I)I");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_hypertext);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return -1;
    }
    gint ret = (gint)(*jniEnv)->CallIntMethod(jniEnv, atk_hypertext, jmid,
                                              (jint)char_index);
    if (!ret) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_hypertext);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return -1;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_hypertext);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return ret;
}
