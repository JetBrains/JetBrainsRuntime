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

#ifndef NO_A11Y_SPEECHD_ANNOUNCING

#include "sun_swing_AccessibleAnnouncer.h"
#include "OrcaAnnouncer.h"
#include "AccessibleAnnouncerJNIUtils.h"
#include "jni_util.h"
#include "debug_assert.h"

static jclass jc_AccessibleAnnouncerUtilities = NULL;
static jmethodID jsm_getOrcaConf = NULL;
static jmethodID jsm_getSpeechServerInfo = NULL;
static jmethodID jsm_getGain = NULL;
static jmethodID jsm_getVariant = NULL;
static jmethodID jsm_getDialect = NULL;
static jmethodID jsm_getLang = NULL;
static jmethodID jsm_getName = NULL;
static jmethodID jsm_getAveragePitch = NULL;
static jmethodID jsm_getRate = NULL;
static jmethodID jsm_getEstablished = NULL;
static jmethodID jsm_getActiveProfile = NULL;
static jmethodID jsm_getVerbalizePunctuationStyle = NULL;
static jmethodID jsm_getOnlySpeakDisplayedText = NULL;
static jmethodID jsm_getEnableSpeech = NULL;

int OrcaAnnounce(JNIEnv *env, jstring str, jint priority)
{
    DASSERT(env != NULL);
    DASSERT(str != NULL)

    jobject conf = OrcaGetConf(env);
    if (conf == NULL)
    {
#ifdef DEBUG
        fprintf(stderr, "Failed to read Orca configuration file\n");
#endif
        return -1;
    }

    if (OrcaGetEnableSpeech(env, conf) <= 0)
    {
#ifdef DEBUG
        fprintf(stderr, "Speech is disable\n");
#endif
        (*env)->DeleteLocalRef(env, conf);
        return -1;
    }

    SPDConnection *connection = spd_open("Java announcer", NULL, NULL, SPD_MODE_SINGLE);
    if (connection == NULL)
    {
#ifdef DEBUG
        fprintf(stderr, "Speech dispatcher connection is null\n");
#endif
        (*env)->DeleteLocalRef(env, conf);
        return -1;
    }

    const char *msg = JNU_GetStringPlatformChars(env, str, NULL);
    if (msg == NULL)
    {
        if ((*env)->ExceptionCheck(env) == JNI_FALSE)
        {
            JNU_ThrowOutOfMemoryError(env, "OrcaAnnounce: failed to obtain chars from the announcing string");
        }

        spd_close(connection);
        (*env)->DeleteLocalRef(env, conf);
        return -1;
    }

    OrcaSetSpeechConf(env, connection, conf);
    (*env)->DeleteLocalRef(env, conf);
    int p = SPD_TEXT;
    if (priority == sun_swing_AccessibleAnnouncer_ANNOUNCE_WITH_INTERRUPTING_CURRENT_OUTPUT)
    {
        p = SPD_MESSAGE;
    }
    int err = spd_say(connection, p, msg);
    spd_close(connection);
    JNU_ReleaseStringPlatformChars(env, str, msg);

    if (err < 0)
    {
#ifdef DEBUG
        fprintf(stderr, "Failed to say message\n");
#endif
        return -1;
    }

    return 0;
}

void OrcaSetSpeechConf(JNIEnv *env, SPDConnection *connection, jobject conf)
{
    OrcaSetOutputModule(env, connection, conf);
    OrcaSetSynthesisVoice(env, connection, conf);
    OrcaSetLanguage(env, connection, conf);
    OrcaSetPunctuation(env, connection, conf);
    OrcaSetVoiceRate(env, connection, conf);
    OrcaSetVoicePitch(env, connection, conf);
    OrcaSetVolume(env, connection, conf);
}

void OrcaSetVolume(JNIEnv *env, SPDConnection *connection, jobject conf)
{
    GET_getGain();
    jdouble gain = (*env)->CallStaticDoubleMethod(env, jc_AccessibleAnnouncerUtilities, jsm_getGain, conf);
    JNU_CHECK_EXCEPTION(env);
    if (gain < 0)
    {
#ifdef DEBUG
        fprintf(stderr, "Failed to read value of gain from config\n");
#endif
        return;
    }

    int volume = (int)((gain - 5) * 20);
    spd_set_volume(connection, volume);
}

void OrcaSetVoiceRate(JNIEnv *env, SPDConnection *connection, jobject conf)
{
    GET_getRate();
    jdouble rate = (*env)->CallStaticDoubleMethod(env, jc_AccessibleAnnouncerUtilities, jsm_getRate, conf);
    JNU_CHECK_EXCEPTION(env);
    if (rate < 0)
    {
#ifdef DEBUG
        fprintf(stderr, "Failed to read value of rate from config\n");
#endif
        return;
    }

    int iRate = (int)((rate - 50) * 2);
    spd_set_voice_rate(connection, iRate);
}

void OrcaSetPunctuation(JNIEnv *env, SPDConnection *connection, jobject conf)
{
    GET_getVerbalizePunctuationStyle();
    jint punctuation = (*env)->CallStaticIntMethod(env, jc_AccessibleAnnouncerUtilities, jsm_getVerbalizePunctuationStyle, conf);
    JNU_CHECK_EXCEPTION(env);
    if (punctuation < 0)
    {
#ifdef DEBUG
        fprintf(stderr, "Failed to read value of punctuation from config\n");
#endif
        return;
    }

    spd_set_punctuation(connection, punctuation);
}

void OrcaSetVoicePitch(JNIEnv *env, SPDConnection *connection, jobject conf)
{
    GET_getAveragePitch();
    jdouble pitch = (*env)->CallStaticDoubleMethod(env, jc_AccessibleAnnouncerUtilities, jsm_getAveragePitch, conf);
    JNU_CHECK_EXCEPTION(env);
    if (pitch < 0)
    {
#ifdef DEBUG
        fprintf(stderr, "Failed to read value of pitch from config\n");
#endif
        return;
    }

    int iPitch = (int)((pitch - 5) * 20);
    spd_set_voice_pitch(connection, iPitch);
}

void OrcaSetOutputModule(JNIEnv *env, SPDConnection *connection, jobject conf)
{
    GET_getSpeechServerInfo();
    jobject jStr = (*env)->CallStaticObjectMethod(env, jc_AccessibleAnnouncerUtilities, jsm_getSpeechServerInfo, conf);
    JNU_CHECK_EXCEPTION(env);
    if (jStr == NULL)
    {
#ifdef DEBUG
        fprintf(stderr, "Failed to read value of speech server info from config\n");
#endif
        return;
    }

    const char *sintName = JNU_GetStringPlatformChars(env, jStr, NULL);
    if (sintName == NULL)
    {
        if ((*env)->ExceptionCheck(env) == JNI_FALSE)
        {
            JNU_ThrowOutOfMemoryError(env, "OrcaAnnounce: failed to obtain chars from the sintName string");
        }

        (*env)->DeleteLocalRef(env, jStr);
        return;
    }

    spd_set_output_module(connection, sintName);
    JNU_ReleaseStringPlatformChars(env, jStr, sintName);
    (*env)->DeleteLocalRef(env, jStr);
}

void OrcaSetLanguage(JNIEnv *env, SPDConnection *connection, jobject conf)
{
    GET_getLang();
    jobject jStr = (*env)->CallStaticObjectMethod(env, jc_AccessibleAnnouncerUtilities, jsm_getLang, conf);
    JNU_CHECK_EXCEPTION(env);
    if (jStr == NULL)
    {
#ifdef DEBUG
        fprintf(stderr, "Failed to read value of lang from config\n");
#endif
        return;
    }

    const char *lang = JNU_GetStringPlatformChars(env, jStr, NULL);
    if (lang == NULL)
    {
        if ((*env)->ExceptionCheck(env) == JNI_FALSE)
        {
            JNU_ThrowOutOfMemoryError(env, "OrcaAnnounce: failed to obtain chars from the lang string");
        }

        (*env)->DeleteLocalRef(env, jStr);
        return;
    }

    spd_set_language(connection, lang);
    JNU_ReleaseStringPlatformChars(env, jStr, lang);
    (*env)->DeleteLocalRef(env, jStr);
}

int OrcaGetEnableSpeech(JNIEnv *env, jobject conf)
{
    GET_getEnableSpeech(-1);
    int es = (*env)->CallStaticBooleanMethod(env, jc_AccessibleAnnouncerUtilities, jsm_getEnableSpeech, conf);
    JNU_CHECK_EXCEPTION_RETURN(env, -1);
    return es;
}

void OrcaSetSynthesisVoice(JNIEnv *env, SPDConnection *connection, jobject conf)
{
    GET_getName();
    jobject jStr = (*env)->CallStaticObjectMethod(env, jc_AccessibleAnnouncerUtilities, jsm_getName, conf);
    JNU_CHECK_EXCEPTION(env);
    if (jStr == NULL)
    {
#ifdef DEBUG
        fprintf(stderr, "Failed to read value of voice name from config\n");
#endif
        return;
    }

    const char *voiceName = JNU_GetStringPlatformChars(env, jStr, NULL);
    if (voiceName == NULL)
    {
        if ((*env)->ExceptionCheck(env) == JNI_FALSE)
        {
            JNU_ThrowOutOfMemoryError(env, "OrcaAnnounce: failed to obtain chars from the voiceName string");
        }

        (*env)->DeleteLocalRef(env, jStr);
        return;
    }

    spd_set_synthesis_voice(connection, voiceName);
    JNU_ReleaseStringPlatformChars(env, jStr, voiceName);
    (*env)->DeleteLocalRef(env, jStr);
}

jobject OrcaGetConf(JNIEnv *env)
{
    GET_getOrcaConfReturn(NULL);
    jobject o = (*env)->CallStaticObjectMethod(env, jc_AccessibleAnnouncerUtilities, jsm_getOrcaConf);
    JNU_CHECK_EXCEPTION_RETURN(env, NULL);
    return o;
}

#endif // #ifndef NO_A11Y_SPEECHD_ANNOUNCING
