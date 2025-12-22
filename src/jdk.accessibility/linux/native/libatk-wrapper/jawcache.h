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

#ifndef _JAW_CACHE_H_
#define _JAW_CACHE_H_

#include <jni.h>
#include <glib.h>

#ifdef __cplusplus
extern "C" {
#endif

void atk_wrapper_cache_cleanup(JNIEnv *jniEnv);
void jaw_action_cache_cleanup(JNIEnv *jniEnv);
void jaw_component_cache_cleanup(JNIEnv *jniEnv);
void jaw_editable_text_cache_cleanup(JNIEnv *jniEnv);
void jaw_hyperlink_cache_cleanup(JNIEnv *jniEnv);
void jaw_hypertext_cache_cleanup(JNIEnv *jniEnv);
void jaw_image_cache_cleanup(JNIEnv *jniEnv);
void jaw_impl_cache_cleanup(JNIEnv *jniEnv);
void jaw_object_cache_cleanup(JNIEnv *jniEnv);
void jaw_selection_cache_cleanup(JNIEnv *jniEnv);
void jaw_table_cache_cleanup(JNIEnv *jniEnv);
void jaw_tablecell_cache_cleanup(JNIEnv *jniEnv);
void jaw_text_cache_cleanup(JNIEnv *jniEnv);
void jaw_value_cache_cleanup(JNIEnv *jniEnv);
void jaw_util_cache_cleanup(JNIEnv *jniEnv);

// TODO: currently leaking
static inline void jaw_cache_cleanup(JNIEnv *jniEnv) {
    if (jniEnv == NULL) {
        g_warning("%s: Null argument jniEnv passed to the function", G_STRFUNC);
        return;
    }

    atk_wrapper_cache_cleanup(jniEnv);
    jaw_action_cache_cleanup(jniEnv);
    jaw_component_cache_cleanup(jniEnv);
    jaw_editable_text_cache_cleanup(jniEnv);
    jaw_hyperlink_cache_cleanup(jniEnv);
    jaw_hypertext_cache_cleanup(jniEnv);
    jaw_image_cache_cleanup(jniEnv);
    jaw_impl_cache_cleanup(jniEnv);
    jaw_object_cache_cleanup(jniEnv);
    jaw_selection_cache_cleanup(jniEnv);
    jaw_table_cache_cleanup(jniEnv);
    jaw_tablecell_cache_cleanup(jniEnv);
    jaw_text_cache_cleanup(jniEnv);
    jaw_value_cache_cleanup(jniEnv);
    jaw_util_cache_cleanup(jniEnv);
}

#ifdef __cplusplus
}
#endif

#endif