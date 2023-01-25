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

#ifndef ACCESSIBLEANNOUNCERJNIUTILS_H
#define ACCESSIBLEANNOUNCERJNIUTILS_H

#ifndef NO_A11Y_SPEECHD_ANNOUNCING

#include "jni.h"

#define GET_AccessibleAnnouncerUtilities()\
if (jc_AccessibleAnnouncerUtilities == NULL) {\
jclass cls = (*env)->FindClass(env, "sun/awt/AccessibleAnnouncerUtilities");\
if (cls == NULL) {\
return;\
}\
jc_AccessibleAnnouncerUtilities =  (*env)->NewGlobalRef(env, cls);\
(*env)->DeleteLocalRef(env, cls);\
}\

#define GET_AccessibleAnnouncerUtilitiesReturn(ret)\
if (jc_AccessibleAnnouncerUtilities == NULL) {\
jclass cls = (*env)->FindClass(env, "sun/awt/AccessibleAnnouncerUtilities");\
if (cls == NULL) {\
return ret;\
}\
jc_AccessibleAnnouncerUtilities =  (*env)->NewGlobalRef(env, cls);\
(*env)->DeleteLocalRef(env, cls);\
}\

#define GET_getOrcaConf()\
GET_AccessibleAnnouncerUtilities();\
if (jsm_getOrcaConf == NULL) {\
jsm_getOrcaConf = (*env)->GetStaticMethodID(env, jc_AccessibleAnnouncerUtilities, "getOrcaConf", "()Ljava/lang/Object;");\
if (jsm_getOrcaConf == NULL) {\
return;\
}\
}\

#define GET_getOrcaConfReturn(ret)\
GET_AccessibleAnnouncerUtilitiesReturn(ret);\
if (jsm_getOrcaConf == NULL) {\
jsm_getOrcaConf = (*env)->GetStaticMethodID(env, jc_AccessibleAnnouncerUtilities, "getOrcaConf", "()Ljava/lang/Object;");\
if (jsm_getOrcaConf == NULL) {\
return ret;\
}\
}\

#define GET_getSpeechServerInfo()\
GET_AccessibleAnnouncerUtilities();\
if (jsm_getSpeechServerInfo == NULL) {\
jsm_getSpeechServerInfo = (*env)->GetStaticMethodID(env, jc_AccessibleAnnouncerUtilities, "getSpeechServerInfo", "(Ljava/lang/Object;)Ljava/lang/String;");\
if (jsm_getSpeechServerInfo == NULL) {\
return;\
}\
}\

#define GET_getGain()\
GET_AccessibleAnnouncerUtilities();\
if (jsm_getGain == NULL) {\
jsm_getGain = (*env)->GetStaticMethodID(env, jc_AccessibleAnnouncerUtilities, "getGain",  "(Ljava/lang/Object;)D");\
if (jsm_getGain == NULL) {\
return;\
}\
}\

#define GET_getVariant()\
GET_AccessibleAnnouncerUtilities();\
if (jsm_getVariant == NULL) {\
jsm_getVariant = (*env)->GetStaticMethodID(env, jc_AccessibleAnnouncerUtilities, "getVariant", "(Ljava/lang/Object;)Ljava/lang/String;");\
if (jsm_getVariant == NULL) {\
return;\
}\
}\

#define GET_getDialect()\
GET_AccessibleAnnouncerUtilities();\
if (jsm_getDialect == NULL) {\
jsm_getDialect = (*env)->GetStaticMethodID(env, jc_AccessibleAnnouncerUtilities, "getDialect", "(Ljava/lang/Object;)Ljava/lang/String;");\
if (jsm_getDialect == NULL) {\
return;\
}\
}\

#define GET_getLang()\
GET_AccessibleAnnouncerUtilities();\
if (jsm_getLang == NULL) {\
jsm_getLang = (*env)->GetStaticMethodID(env, jc_AccessibleAnnouncerUtilities, "getLang", "(Ljava/lang/Object;)Ljava/lang/String;");\
if (jsm_getLang == NULL) {\
return;\
}\
}\

#define GET_getName()\
GET_AccessibleAnnouncerUtilities();\
if (jsm_getName == NULL) {\
jsm_getName = (*env)->GetStaticMethodID(env, jc_AccessibleAnnouncerUtilities, "getName", "(Ljava/lang/Object;)Ljava/lang/String;");\
if (jsm_getName == NULL) {\
return;\
}\
}\

#define GET_getAveragePitch()\
GET_AccessibleAnnouncerUtilities();\
if (jsm_getAveragePitch == NULL) {\
jsm_getAveragePitch = (*env)->GetStaticMethodID(env, jc_AccessibleAnnouncerUtilities, "getAveragePitch", "(Ljava/lang/Object;)D");\
if (jsm_getAveragePitch == NULL) {\
return;\
}\
}\

#define GET_getRate()\
GET_AccessibleAnnouncerUtilities();\
if (jsm_getRate == NULL) {\
jsm_getRate = (*env)->GetStaticMethodID(env, jc_AccessibleAnnouncerUtilities, "getRate", "(Ljava/lang/Object;)D");\
if (jsm_getRate == NULL) {\
return;\
}\
}\

#define GET_getActiveProfile()\
GET_AccessibleAnnouncerUtilities();\
if (jsm_getActiveProfile == NULL) {\
jsm_getActiveProfile = (*env)->GetStaticMethodID(env, jc_AccessibleAnnouncerUtilities, "getActiveProfile", "(Ljava/lang/Object;)Ljava/lang/String;");\
if (jsm_getActiveProfile == NULL) {\
return;\
}\
}\

#define GET_getVerbalizePunctuationStyle()\
GET_AccessibleAnnouncerUtilities();\
if (jsm_getVerbalizePunctuationStyle == NULL) {\
jsm_getVerbalizePunctuationStyle = (*env)->GetStaticMethodID(env, jc_AccessibleAnnouncerUtilities, "getVerbalizePunctuationStyle", "(Ljava/lang/Object;)I");\
if (jsm_getVerbalizePunctuationStyle == NULL) {\
return;\
}\
}\

#define GET_getEnableSpeech(ret)\
GET_AccessibleAnnouncerUtilitiesReturn(ret);\
if (jsm_getEnableSpeech == NULL) {\
jsm_getEnableSpeech = (*env)->GetStaticMethodID(env, jc_AccessibleAnnouncerUtilities, "getEnableSpeech", "(Ljava/lang/Object;)Z");\
if (jsm_getEnableSpeech == NULL) {\
return ret;\
}\
}\

#endif // #ifndef NO_A11Y_SPEECHD_ANNOUNCING


#endif //ACCESSIBLEANNOUNCERJNIUTILS_H

