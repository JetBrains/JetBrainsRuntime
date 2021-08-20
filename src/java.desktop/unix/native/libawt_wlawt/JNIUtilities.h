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
#ifndef JNIUTILITIES_H_
#define JNIUTILITIES_H_

#include "jni.h"
#include "jni_util.h"

#define CHECK_NULL_THROW_OOME(env, x, msg)      \
    do {                                        \
        if ((x) == NULL) {                      \
           JNU_ThrowOutOfMemoryError((env), (msg));\
           return;                              \
        }                                       \
    } while(0)                                  \

#define CHECK_NULL_THROW_OOME_RETURN(env, x, msg, z)\
    do {                                        \
        if ((x) == NULL) {                      \
           JNU_ThrowOutOfMemoryError((env), (msg));\
           return (z);                          \
        }                                       \
    } while(0)                                  \

#define CHECK_NULL_THROW_IE(env, x, msg)        \
    do {                                        \
        if ((x) == NULL) {                      \
           JNU_ThrowInternalError((env), (msg));\
           return;                              \
        }                                       \
    } while(0)                                  \

/********        GET CLASS SUPPORT    *********/

#define GET_CLASS(dst_var, cls) \
     if (dst_var == NULL) { \
         dst_var = (*env)->FindClass(env, cls); \
         if (dst_var != NULL) dst_var = (*env)->NewGlobalRef(env, dst_var); \
     } \
     CHECK_NULL(dst_var);

#define DECLARE_CLASS(dst_var, cls) \
    static jclass dst_var = NULL; \
    GET_CLASS(dst_var, cls);

#define GET_CLASS_RETURN(dst_var, cls, ret) \
     if (dst_var == NULL) { \
         dst_var = (*env)->FindClass(env, cls); \
         if (dst_var != NULL) dst_var = (*env)->NewGlobalRef(env, dst_var); \
     } \
     CHECK_NULL_RETURN(dst_var, ret);

#define DECLARE_CLASS_RETURN(dst_var, cls, ret) \
    static jclass dst_var = NULL; \
    GET_CLASS_RETURN(dst_var, cls, ret);


/********        GET METHOD SUPPORT    *********/

#define GET_METHOD(dst_var, cls, name, signature) \
     if (dst_var == NULL) { \
         dst_var = (*env)->GetMethodID(env, cls, name, signature); \
     } \
     CHECK_NULL(dst_var);

#define DECLARE_METHOD(dst_var, cls, name, signature) \
     static jmethodID dst_var = NULL; \
     GET_METHOD(dst_var, cls, name, signature);

#define GET_METHOD_RETURN(dst_var, cls, name, signature, ret) \
     if (dst_var == NULL) { \
         dst_var = (*env)->GetMethodID(env, cls, name, signature); \
     } \
     CHECK_NULL_RETURN(dst_var, ret);

#define DECLARE_METHOD_RETURN(dst_var, cls, name, signature, ret) \
     static jmethodID dst_var = NULL; \
     GET_METHOD_RETURN(dst_var, cls, name, signature, ret);

#define GET_STATIC_METHOD(dst_var, cls, name, signature) \
     if (dst_var == NULL) { \
         dst_var = (*env)->GetStaticMethodID(env, cls, name, signature); \
     } \
     CHECK_NULL(dst_var);

#define DECLARE_STATIC_METHOD(dst_var, cls, name, signature) \
     static jmethodID dst_var = NULL; \
     GET_STATIC_METHOD(dst_var, cls, name, signature);

#define GET_STATIC_METHOD_RETURN(dst_var, cls, name, signature, ret) \
     if (dst_var == NULL) { \
         dst_var = (*env)->GetStaticMethodID(env, cls, name, signature); \
     } \
     CHECK_NULL_RETURN(dst_var, ret);

#define DECLARE_STATIC_METHOD_RETURN(dst_var, cls, name, signature, ret) \
     static jmethodID dst_var = NULL; \
     GET_STATIC_METHOD_RETURN(dst_var, cls, name, signature, ret);

/********        GET FIELD SUPPORT    *********/


#define GET_FIELD(dst_var, cls, name, signature) \
     if (dst_var == NULL) { \
         dst_var = (*env)->GetFieldID(env, cls, name, signature); \
     } \
     CHECK_NULL(dst_var);

#define DECLARE_FIELD(dst_var, cls, name, signature) \
     static jfieldID dst_var = NULL; \
     GET_FIELD(dst_var, cls, name, signature);

#define GET_FIELD_RETURN(dst_var, cls, name, signature, ret) \
     if (dst_var == NULL) { \
         dst_var = (*env)->GetFieldID(env, cls, name, signature); \
     } \
     CHECK_NULL_RETURN(dst_var, ret);

#define DECLARE_FIELD_RETURN(dst_var, cls, name, signature, ret) \
     static jfieldID dst_var = NULL; \
     GET_FIELD_RETURN(dst_var, cls, name, signature, ret);

#define GET_STATIC_FIELD_RETURN(dst_var, cls, name, signature, ret) \
     if (dst_var == NULL) { \
         dst_var = (*env)->GetStaticFieldID(env, cls, name, signature); \
     } \
     CHECK_NULL_RETURN(dst_var, ret);

#define DECLARE_STATIC_FIELD_RETURN(dst_var, cls, name, signature, ret) \
     static jfieldID dst_var = NULL; \
     GET_STATIC_FIELD_RETURN(dst_var, cls, name, signature, ret);

/********        EXCEPTIONS SUPPORT    *********/

#define EXCEPTION_CLEAR(env) \
    if ((*env)->ExceptionCheck(env)) { \
        (*env)->ExceptionClear(env); \
    }

#endif // JNIUTILITIES_H_
