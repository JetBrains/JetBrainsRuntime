/*
 * Copyright 2026 JetBrains s.r.o.
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

#include "jvm_io.h"         // jio_fprintf
#include <libspeechd.h>
#include <stdbool.h>        // bool, true, false
#include <pthread.h>        // pthread_*
#include <dlfcn.h>          // dlopen, dlclose, dlsym, dlerror


// =============================================== libspeechd RESOURCES ===============================================

// Below are all handlers associated with libspeechd.
// Reading any of them MUST be performed under at least read-locked libspeechd_rwlock.
// Writing is only allowed under write-locked libspeechd_rwlock.

// dlopen/dlclose
static void *libspeechd_libhandle = NULL;

static void                  (*libspeechd_funcptr_SPDConnectionAddress__free)         (SPDConnectionAddress*) = NULL;
static SPDConnectionAddress* (*libspeechd_funcptr_spd_get_default_address)            (char**) = NULL;
static SPDConnection*        (*libspeechd_funcptr_spd_open)                           (const char*, const char*, const char*, SPDConnectionMode) = NULL;
static SPDConnection*        (*libspeechd_funcptr_spd_open2)                          (const char*, const char*, const char*, SPDConnectionMode, const SPDConnectionAddress*, int, char**) = NULL;
static int                   (*libspeechd_funcptr_spd_fd)                             (SPDConnection*) = NULL;
static int                   (*libspeechd_funcptr_spd_get_client_id)                  (SPDConnection*) = NULL;
static void                  (*libspeechd_funcptr_spd_close)                          (SPDConnection*) = NULL;
static int                   (*libspeechd_funcptr_spd_say)                            (SPDConnection*, SPDPriority, const char*) = NULL;
static int                   (*libspeechd_funcptr_spd_sayf)                           (SPDConnection*, SPDPriority, const char*, ...) = NULL;
static int                   (*libspeechd_funcptr_spd_stop)                           (SPDConnection*) = NULL;
static int                   (*libspeechd_funcptr_spd_stop_all)                       (SPDConnection*) = NULL;
static int                   (*libspeechd_funcptr_spd_stop_uid)                       (SPDConnection*, int) = NULL;
static int                   (*libspeechd_funcptr_spd_cancel)                         (SPDConnection*) = NULL;
static int                   (*libspeechd_funcptr_spd_cancel_all)                     (SPDConnection*) = NULL;
static int                   (*libspeechd_funcptr_spd_cancel_uid)                     (SPDConnection*, int) = NULL;
static int                   (*libspeechd_funcptr_spd_pause)                          (SPDConnection*) = NULL;
static int                   (*libspeechd_funcptr_spd_pause_all)                      (SPDConnection*) = NULL;
static int                   (*libspeechd_funcptr_spd_pause_uid)                      (SPDConnection*, int) = NULL;
static int                   (*libspeechd_funcptr_spd_resume)                         (SPDConnection*) = NULL;
static int                   (*libspeechd_funcptr_spd_resume_all)                     (SPDConnection*) = NULL;
static int                   (*libspeechd_funcptr_spd_resume_uid)                     (SPDConnection*, int) = NULL;
static int                   (*libspeechd_funcptr_spd_key)                            (SPDConnection*, SPDPriority, const char*) = NULL;
static int                   (*libspeechd_funcptr_spd_char)                           (SPDConnection*, SPDPriority, const char*) = NULL;
static int                   (*libspeechd_funcptr_spd_wchar)                          (SPDConnection*, SPDPriority, wchar_t) = NULL;
static int                   (*libspeechd_funcptr_spd_sound_icon)                     (SPDConnection*, SPDPriority, const char*) = NULL;
static int                   (*libspeechd_funcptr_spd_set_voice_type)                 (SPDConnection*, SPDVoiceType) = NULL;
static int                   (*libspeechd_funcptr_spd_set_voice_type_all)             (SPDConnection*, SPDVoiceType) = NULL;
static int                   (*libspeechd_funcptr_spd_set_voice_type_uid)             (SPDConnection*, SPDVoiceType, unsigned int) = NULL;
static SPDVoiceType          (*libspeechd_funcptr_spd_get_voice_type)                 (SPDConnection*) = NULL;
static int                   (*libspeechd_funcptr_spd_set_synthesis_voice)            (SPDConnection*, const char*) = NULL;
static int                   (*libspeechd_funcptr_spd_set_synthesis_voice_all)        (SPDConnection*, const char*) = NULL;
static int                   (*libspeechd_funcptr_spd_set_synthesis_voice_uid)        (SPDConnection*, const char*, unsigned int) = NULL;
static int                   (*libspeechd_funcptr_spd_set_data_mode)                  (SPDConnection*, SPDDataMode) = NULL;
static int                   (*libspeechd_funcptr_spd_set_notification_on)            (SPDConnection*, SPDNotification) = NULL;
static int                   (*libspeechd_funcptr_spd_set_notification_off)           (SPDConnection*, SPDNotification) = NULL;
static int                   (*libspeechd_funcptr_spd_set_notification)               (SPDConnection*, SPDNotification, const char*) = NULL;
static int                   (*libspeechd_funcptr_spd_set_voice_rate)                 (SPDConnection*, signed int) = NULL;
static int                   (*libspeechd_funcptr_spd_set_voice_rate_all)             (SPDConnection*, signed int) = NULL;
static int                   (*libspeechd_funcptr_spd_set_voice_rate_uid)             (SPDConnection*, signed int, unsigned int) = NULL;
static int                   (*libspeechd_funcptr_spd_get_voice_rate)                 (SPDConnection*) = NULL;
static int                   (*libspeechd_funcptr_spd_set_voice_pitch)                (SPDConnection*, signed int) = NULL;
static int                   (*libspeechd_funcptr_spd_set_voice_pitch_all)            (SPDConnection*, signed int) = NULL;
static int                   (*libspeechd_funcptr_spd_set_voice_pitch_uid)            (SPDConnection*, signed int, unsigned int) = NULL;
static int                   (*libspeechd_funcptr_spd_get_voice_pitch)                (SPDConnection*) = NULL;
static int                   (*libspeechd_funcptr_spd_set_voice_pitch_range)          (SPDConnection*, signed int) = NULL;
static int                   (*libspeechd_funcptr_spd_set_voice_pitch_range_all)      (SPDConnection*, signed int) = NULL;
static int                   (*libspeechd_funcptr_spd_set_voice_pitch_range_uid)      (SPDConnection*, signed int, unsigned int) = NULL;
static int                   (*libspeechd_funcptr_spd_set_volume)                     (SPDConnection*, signed int) = NULL;
static int                   (*libspeechd_funcptr_spd_set_volume_all)                 (SPDConnection*, signed int) = NULL;
static int                   (*libspeechd_funcptr_spd_set_volume_uid)                 (SPDConnection*, signed int, unsigned int) = NULL;
static int                   (*libspeechd_funcptr_spd_get_volume)                     (SPDConnection*) = NULL;
static int                   (*libspeechd_funcptr_spd_set_punctuation)                (SPDConnection*, SPDPunctuation) = NULL;
static int                   (*libspeechd_funcptr_spd_set_punctuation_all)            (SPDConnection*, SPDPunctuation) = NULL;
static int                   (*libspeechd_funcptr_spd_set_punctuation_uid)            (SPDConnection*, SPDPunctuation, unsigned int) = NULL;
static int                   (*libspeechd_funcptr_spd_set_capital_letters)            (SPDConnection*, SPDCapitalLetters) = NULL;
static int                   (*libspeechd_funcptr_spd_set_capital_letters_all)        (SPDConnection*, SPDCapitalLetters) = NULL;
static int                   (*libspeechd_funcptr_spd_set_capital_letters_uid)        (SPDConnection*, SPDCapitalLetters, unsigned int) = NULL;
static int                   (*libspeechd_funcptr_spd_set_spelling)                   (SPDConnection*, SPDSpelling) = NULL;
static int                   (*libspeechd_funcptr_spd_set_spelling_all)               (SPDConnection*, SPDSpelling) = NULL;
static int                   (*libspeechd_funcptr_spd_set_spelling_uid)               (SPDConnection*, SPDSpelling, unsigned int) = NULL;
static int                   (*libspeechd_funcptr_spd_set_language)                   (SPDConnection*, const char*) = NULL;
static int                   (*libspeechd_funcptr_spd_set_language_all)               (SPDConnection*, const char*) = NULL;
static int                   (*libspeechd_funcptr_spd_set_language_uid)               (SPDConnection*, const char*, unsigned int) = NULL;
static char*                 (*libspeechd_funcptr_spd_get_language)                   (SPDConnection*) = NULL;
static int                   (*libspeechd_funcptr_spd_set_output_module)              (SPDConnection*, const char*) = NULL;
static int                   (*libspeechd_funcptr_spd_set_output_module_all)          (SPDConnection*, const char*) = NULL;
static int                   (*libspeechd_funcptr_spd_set_output_module_uid)          (SPDConnection*, const char*, unsigned int) = NULL;
static int                   (*libspeechd_funcptr_spd_get_client_list)                (SPDConnection*, char**, int*, int*) = NULL;
static int                   (*libspeechd_funcptr_spd_get_message_list_fd)            (SPDConnection*, int, int*, char**) = NULL;
static char**                (*libspeechd_funcptr_spd_list_modules)                   (SPDConnection*) = NULL;
static void                  (*libspeechd_funcptr_free_spd_modules)                   (char**) = NULL;
static char*                 (*libspeechd_funcptr_spd_get_output_module)              (SPDConnection*) = NULL;
static char**                (*libspeechd_funcptr_spd_list_voices)                    (SPDConnection*) = NULL;
static void                  (*libspeechd_funcptr_free_spd_symbolic_voices)           (char**) = NULL;
static SPDVoice**            (*libspeechd_funcptr_spd_list_synthesis_voices)          (SPDConnection*) = NULL;
static SPDVoice**            (*libspeechd_funcptr_spd_list_synthesis_voices2)         (SPDConnection*, const char*, const char*) = NULL;
static void                  (*libspeechd_funcptr_free_spd_voices)                    (SPDVoice**) = NULL;
static char**                (*libspeechd_funcptr_spd_execute_command_with_list_reply)(SPDConnection*, const char*) = NULL;
static int                   (*libspeechd_funcptr_spd_execute_command)                (SPDConnection*, const char*) = NULL;
static int                   (*libspeechd_funcptr_spd_execute_command_with_reply)     (SPDConnection*, const char*, char**) = NULL;
static int                   (*libspeechd_funcptr_spd_execute_command_wo_mutex)       (SPDConnection*, const char*) = NULL;
static char*                 (*libspeechd_funcptr_spd_send_data)                      (SPDConnection*, const char*, int) = NULL;
static char*                 (*libspeechd_funcptr_spd_send_data_wo_mutex)             (SPDConnection*, const char*, int) = NULL;

// ======================================== End of libspeechd RESOURCES section =======================================


// =========================================== libspeechd LOADING/UNLOADING ===========================================

static pthread_once_t libspeechd_loadOnceGuard = PTHREAD_ONCE_INIT;

static void LoadSpeechdOnceImpl(void);


/**
 * @return \c true if and only if all the identifiers listed in the "libspeechd RESOURCES" are loaded successfully.
 *         This is done on purpose to avoid a situation when some libspeechd call allocates some resource which then
 *         can't be disposed because the corresponding function couldn't get loaded.
 */
static bool TryLoadSpeechd(void)
{
    int errCode = pthread_once(&libspeechd_loadOnceGuard, &LoadSpeechdOnceImpl);
    if (errCode != 0)
    {
        jio_fprintf(stderr, "%s: pthread_once(%p, %p) failed ; error code=%d\n",
                    __func__, &libspeechd_loadOnceGuard, &LoadSpeechdOnceImpl, errCode);
        return false;
    }

    return (libspeechd_libhandle != NULL)                                   &&
           (libspeechd_funcptr_SPDConnectionAddress__free != NULL)          &&
           (libspeechd_funcptr_spd_get_default_address != NULL)             &&
           (libspeechd_funcptr_spd_open != NULL)                            &&
           (libspeechd_funcptr_spd_open2 != NULL)                           &&
           (libspeechd_funcptr_spd_fd != NULL)                              &&
           (libspeechd_funcptr_spd_get_client_id != NULL)                   &&
           (libspeechd_funcptr_spd_close != NULL)                           &&
           (libspeechd_funcptr_spd_say != NULL)                             &&
           (libspeechd_funcptr_spd_sayf != NULL)                            &&
           (libspeechd_funcptr_spd_stop != NULL)                            &&
           (libspeechd_funcptr_spd_stop_all != NULL)                        &&
           (libspeechd_funcptr_spd_stop_uid != NULL)                        &&
           (libspeechd_funcptr_spd_cancel != NULL)                          &&
           (libspeechd_funcptr_spd_cancel_all != NULL)                      &&
           (libspeechd_funcptr_spd_cancel_uid != NULL)                      &&
           (libspeechd_funcptr_spd_pause != NULL)                           &&
           (libspeechd_funcptr_spd_pause_all != NULL)                       &&
           (libspeechd_funcptr_spd_pause_uid != NULL)                       &&
           (libspeechd_funcptr_spd_resume != NULL)                          &&
           (libspeechd_funcptr_spd_resume_all != NULL)                      &&
           (libspeechd_funcptr_spd_resume_uid != NULL)                      &&
           (libspeechd_funcptr_spd_key != NULL)                             &&
           (libspeechd_funcptr_spd_char != NULL)                            &&
           (libspeechd_funcptr_spd_wchar != NULL)                           &&
           (libspeechd_funcptr_spd_sound_icon != NULL)                      &&
           (libspeechd_funcptr_spd_set_voice_type != NULL)                  &&
           (libspeechd_funcptr_spd_set_voice_type_all != NULL)              &&
           (libspeechd_funcptr_spd_set_voice_type_uid != NULL)              &&
           (libspeechd_funcptr_spd_get_voice_type != NULL)                  &&
           (libspeechd_funcptr_spd_set_synthesis_voice != NULL)             &&
           (libspeechd_funcptr_spd_set_synthesis_voice_all != NULL)         &&
           (libspeechd_funcptr_spd_set_synthesis_voice_uid != NULL)         &&
           (libspeechd_funcptr_spd_set_data_mode != NULL)                   &&
           (libspeechd_funcptr_spd_set_notification_on != NULL)             &&
           (libspeechd_funcptr_spd_set_notification_off != NULL)            &&
           (libspeechd_funcptr_spd_set_notification != NULL)                &&
           (libspeechd_funcptr_spd_set_voice_rate != NULL)                  &&
           (libspeechd_funcptr_spd_set_voice_rate_all != NULL)              &&
           (libspeechd_funcptr_spd_set_voice_rate_uid != NULL)              &&
           (libspeechd_funcptr_spd_get_voice_rate != NULL)                  &&
           (libspeechd_funcptr_spd_set_voice_pitch != NULL)                 &&
           (libspeechd_funcptr_spd_set_voice_pitch_all != NULL)             &&
           (libspeechd_funcptr_spd_set_voice_pitch_uid != NULL)             &&
           (libspeechd_funcptr_spd_get_voice_pitch != NULL)                 &&
           (libspeechd_funcptr_spd_set_voice_pitch_range != NULL)           &&
           (libspeechd_funcptr_spd_set_voice_pitch_range_all != NULL)       &&
           (libspeechd_funcptr_spd_set_voice_pitch_range_uid != NULL)       &&
           (libspeechd_funcptr_spd_set_volume != NULL)                      &&
           (libspeechd_funcptr_spd_set_volume_all != NULL)                  &&
           (libspeechd_funcptr_spd_set_volume_uid != NULL)                  &&
           (libspeechd_funcptr_spd_get_volume != NULL)                      &&
           (libspeechd_funcptr_spd_set_punctuation != NULL)                 &&
           (libspeechd_funcptr_spd_set_punctuation_all != NULL)             &&
           (libspeechd_funcptr_spd_set_punctuation_uid != NULL)             &&
           (libspeechd_funcptr_spd_set_capital_letters != NULL)             &&
           (libspeechd_funcptr_spd_set_capital_letters_all != NULL)         &&
           (libspeechd_funcptr_spd_set_capital_letters_uid != NULL)         &&
           (libspeechd_funcptr_spd_set_spelling != NULL)                    &&
           (libspeechd_funcptr_spd_set_spelling_all != NULL)                &&
           (libspeechd_funcptr_spd_set_spelling_uid != NULL)                &&
           (libspeechd_funcptr_spd_set_language != NULL)                    &&
           (libspeechd_funcptr_spd_set_language_all != NULL)                &&
           (libspeechd_funcptr_spd_set_language_uid != NULL)                &&
           (libspeechd_funcptr_spd_get_language != NULL)                    &&
           (libspeechd_funcptr_spd_set_output_module != NULL)               &&
           (libspeechd_funcptr_spd_set_output_module_all != NULL)           &&
           (libspeechd_funcptr_spd_set_output_module_uid != NULL)           &&
           (libspeechd_funcptr_spd_get_client_list != NULL)                 &&
           (libspeechd_funcptr_spd_get_message_list_fd != NULL)             &&
           (libspeechd_funcptr_spd_list_modules != NULL)                    &&
           (libspeechd_funcptr_free_spd_modules != NULL)                    &&
           (libspeechd_funcptr_spd_get_output_module != NULL)               &&
           (libspeechd_funcptr_spd_list_voices != NULL)                     &&
           (libspeechd_funcptr_free_spd_symbolic_voices != NULL)            &&
           (libspeechd_funcptr_spd_list_synthesis_voices != NULL)           &&
           (libspeechd_funcptr_spd_list_synthesis_voices2 != NULL)          &&
           (libspeechd_funcptr_free_spd_voices != NULL)                     &&
           (libspeechd_funcptr_spd_execute_command_with_list_reply != NULL) &&
           (libspeechd_funcptr_spd_execute_command != NULL)                 &&
           (libspeechd_funcptr_spd_execute_command_with_reply != NULL)      &&
           (libspeechd_funcptr_spd_execute_command_wo_mutex != NULL)        &&
           (libspeechd_funcptr_spd_send_data != NULL)                       &&
           (libspeechd_funcptr_spd_send_data_wo_mutex != NULL);
}


static void LoadSpeechdOnceImpl(void)
{
    // Currently we're only supporting .so.2
    static const char *soName = "libspeechd.so.2";

    libspeechd_libhandle = dlopen(soName, RTLD_LAZY | RTLD_LOCAL);
    if (libspeechd_libhandle == NULL)
    {
        const char *errStr = dlerror();
        jio_fprintf(stderr, "%s: dlopen(\"%s\", RTLD_LAZY | RTLD_LOCAL) failed ; error message=\"%s\".\n",
                    __func__, soName, (errStr == NULL) ? "" : errStr);
        goto fail;
    }

    #define LOAD_FUNC_OR_FAIL(funcId)                                                               \
    do {                                                                                            \
        libspeechd_funcptr_ ## funcId = dlsym(libspeechd_libhandle, (#funcId));                     \
        if ( (libspeechd_funcptr_ ## funcId) == NULL ) {                                            \
            const char *errStr = dlerror();                                                         \
            jio_fprintf(stderr, "%s: dlsym(%p, \"%s\") failed ; error message=\"%s\".\n",           \
                        __func__, libspeechd_libhandle, (#funcId), (errStr == NULL) ? "" : errStr); \
            goto fail;                                                                              \
        }                                                                                           \
    } while (0)

    LOAD_FUNC_OR_FAIL(SPDConnectionAddress__free);
    LOAD_FUNC_OR_FAIL(spd_get_default_address);
    LOAD_FUNC_OR_FAIL(spd_open);
    LOAD_FUNC_OR_FAIL(spd_open2);
    LOAD_FUNC_OR_FAIL(spd_fd);
    LOAD_FUNC_OR_FAIL(spd_get_client_id);
    LOAD_FUNC_OR_FAIL(spd_close);
    LOAD_FUNC_OR_FAIL(spd_say);
    LOAD_FUNC_OR_FAIL(spd_sayf);
    LOAD_FUNC_OR_FAIL(spd_stop);
    LOAD_FUNC_OR_FAIL(spd_stop_all);
    LOAD_FUNC_OR_FAIL(spd_stop_uid);
    LOAD_FUNC_OR_FAIL(spd_cancel);
    LOAD_FUNC_OR_FAIL(spd_cancel_all);
    LOAD_FUNC_OR_FAIL(spd_cancel_uid);
    LOAD_FUNC_OR_FAIL(spd_pause);
    LOAD_FUNC_OR_FAIL(spd_pause_all);
    LOAD_FUNC_OR_FAIL(spd_pause_uid);
    LOAD_FUNC_OR_FAIL(spd_resume);
    LOAD_FUNC_OR_FAIL(spd_resume_all);
    LOAD_FUNC_OR_FAIL(spd_resume_uid);
    LOAD_FUNC_OR_FAIL(spd_key);
    LOAD_FUNC_OR_FAIL(spd_char);
    LOAD_FUNC_OR_FAIL(spd_wchar);
    LOAD_FUNC_OR_FAIL(spd_sound_icon);
    LOAD_FUNC_OR_FAIL(spd_set_voice_type);
    LOAD_FUNC_OR_FAIL(spd_set_voice_type_all);
    LOAD_FUNC_OR_FAIL(spd_set_voice_type_uid);
    LOAD_FUNC_OR_FAIL(spd_get_voice_type);
    LOAD_FUNC_OR_FAIL(spd_set_synthesis_voice);
    LOAD_FUNC_OR_FAIL(spd_set_synthesis_voice_all);
    LOAD_FUNC_OR_FAIL(spd_set_synthesis_voice_uid);
    LOAD_FUNC_OR_FAIL(spd_set_data_mode);
    LOAD_FUNC_OR_FAIL(spd_set_notification_on);
    LOAD_FUNC_OR_FAIL(spd_set_notification_off);
    LOAD_FUNC_OR_FAIL(spd_set_notification);
    LOAD_FUNC_OR_FAIL(spd_set_voice_rate);
    LOAD_FUNC_OR_FAIL(spd_set_voice_rate_all);
    LOAD_FUNC_OR_FAIL(spd_set_voice_rate_uid);
    LOAD_FUNC_OR_FAIL(spd_get_voice_rate);
    LOAD_FUNC_OR_FAIL(spd_set_voice_pitch);
    LOAD_FUNC_OR_FAIL(spd_set_voice_pitch_all);
    LOAD_FUNC_OR_FAIL(spd_set_voice_pitch_uid);
    LOAD_FUNC_OR_FAIL(spd_get_voice_pitch);
    LOAD_FUNC_OR_FAIL(spd_set_voice_pitch_range);
    LOAD_FUNC_OR_FAIL(spd_set_voice_pitch_range_all);
    LOAD_FUNC_OR_FAIL(spd_set_voice_pitch_range_uid);
    LOAD_FUNC_OR_FAIL(spd_set_volume);
    LOAD_FUNC_OR_FAIL(spd_set_volume_all);
    LOAD_FUNC_OR_FAIL(spd_set_volume_uid);
    LOAD_FUNC_OR_FAIL(spd_get_volume);
    LOAD_FUNC_OR_FAIL(spd_set_punctuation);
    LOAD_FUNC_OR_FAIL(spd_set_punctuation_all);
    LOAD_FUNC_OR_FAIL(spd_set_punctuation_uid);
    LOAD_FUNC_OR_FAIL(spd_set_capital_letters);
    LOAD_FUNC_OR_FAIL(spd_set_capital_letters_all);
    LOAD_FUNC_OR_FAIL(spd_set_capital_letters_uid);
    LOAD_FUNC_OR_FAIL(spd_set_spelling);
    LOAD_FUNC_OR_FAIL(spd_set_spelling_all);
    LOAD_FUNC_OR_FAIL(spd_set_spelling_uid);
    LOAD_FUNC_OR_FAIL(spd_set_language);
    LOAD_FUNC_OR_FAIL(spd_set_language_all);
    LOAD_FUNC_OR_FAIL(spd_set_language_uid);
    LOAD_FUNC_OR_FAIL(spd_get_language);
    LOAD_FUNC_OR_FAIL(spd_set_output_module);
    LOAD_FUNC_OR_FAIL(spd_set_output_module_all);
    LOAD_FUNC_OR_FAIL(spd_set_output_module_uid);
    LOAD_FUNC_OR_FAIL(spd_get_client_list);
    LOAD_FUNC_OR_FAIL(spd_get_message_list_fd);
    LOAD_FUNC_OR_FAIL(spd_list_modules);
    LOAD_FUNC_OR_FAIL(free_spd_modules);
    LOAD_FUNC_OR_FAIL(spd_get_output_module);
    LOAD_FUNC_OR_FAIL(spd_list_voices);
    LOAD_FUNC_OR_FAIL(free_spd_symbolic_voices);
    LOAD_FUNC_OR_FAIL(spd_list_synthesis_voices);
    LOAD_FUNC_OR_FAIL(spd_list_synthesis_voices2);
    LOAD_FUNC_OR_FAIL(free_spd_voices);
    LOAD_FUNC_OR_FAIL(spd_execute_command_with_list_reply);
    LOAD_FUNC_OR_FAIL(spd_execute_command);
    LOAD_FUNC_OR_FAIL(spd_execute_command_with_reply);
    LOAD_FUNC_OR_FAIL(spd_execute_command_wo_mutex);
    LOAD_FUNC_OR_FAIL(spd_send_data);
    LOAD_FUNC_OR_FAIL(spd_send_data_wo_mutex);

    #undef LOAD_FUNC_OR_FAIL

    return;

fail:
    if (libspeechd_libhandle != NULL)
    {
        int errCode = dlclose(libspeechd_libhandle);
        if (errCode != 0)
        {
            const char *errStr = dlerror();
            jio_fprintf(stderr, "%s: dlclose(%p) failed ; error code=%d , error message=\"%s\".\n",
                        __func__, libspeechd_libhandle, errCode, (errStr == NULL) ? "" : errStr);
        }

        libspeechd_libhandle = NULL;
    }

    libspeechd_funcptr_SPDConnectionAddress__free = NULL;
    libspeechd_funcptr_spd_get_default_address = NULL;
    libspeechd_funcptr_spd_open = NULL;
    libspeechd_funcptr_spd_open2 = NULL;
    libspeechd_funcptr_spd_fd = NULL;
    libspeechd_funcptr_spd_get_client_id = NULL;
    libspeechd_funcptr_spd_close = NULL;
    libspeechd_funcptr_spd_say = NULL;
    libspeechd_funcptr_spd_sayf = NULL;
    libspeechd_funcptr_spd_stop = NULL;
    libspeechd_funcptr_spd_stop_all = NULL;
    libspeechd_funcptr_spd_stop_uid = NULL;
    libspeechd_funcptr_spd_cancel = NULL;
    libspeechd_funcptr_spd_cancel_all = NULL;
    libspeechd_funcptr_spd_cancel_uid = NULL;
    libspeechd_funcptr_spd_pause = NULL;
    libspeechd_funcptr_spd_pause_all = NULL;
    libspeechd_funcptr_spd_pause_uid = NULL;
    libspeechd_funcptr_spd_resume = NULL;
    libspeechd_funcptr_spd_resume_all = NULL;
    libspeechd_funcptr_spd_resume_uid = NULL;
    libspeechd_funcptr_spd_key = NULL;
    libspeechd_funcptr_spd_char = NULL;
    libspeechd_funcptr_spd_wchar = NULL;
    libspeechd_funcptr_spd_sound_icon = NULL;
    libspeechd_funcptr_spd_set_voice_type = NULL;
    libspeechd_funcptr_spd_set_voice_type_all = NULL;
    libspeechd_funcptr_spd_set_voice_type_uid = NULL;
    libspeechd_funcptr_spd_get_voice_type = NULL;
    libspeechd_funcptr_spd_set_synthesis_voice = NULL;
    libspeechd_funcptr_spd_set_synthesis_voice_all = NULL;
    libspeechd_funcptr_spd_set_synthesis_voice_uid = NULL;
    libspeechd_funcptr_spd_set_data_mode = NULL;
    libspeechd_funcptr_spd_set_notification_on = NULL;
    libspeechd_funcptr_spd_set_notification_off = NULL;
    libspeechd_funcptr_spd_set_notification = NULL;
    libspeechd_funcptr_spd_set_voice_rate = NULL;
    libspeechd_funcptr_spd_set_voice_rate_all = NULL;
    libspeechd_funcptr_spd_set_voice_rate_uid = NULL;
    libspeechd_funcptr_spd_get_voice_rate = NULL;
    libspeechd_funcptr_spd_set_voice_pitch = NULL;
    libspeechd_funcptr_spd_set_voice_pitch_all = NULL;
    libspeechd_funcptr_spd_set_voice_pitch_uid = NULL;
    libspeechd_funcptr_spd_get_voice_pitch = NULL;
    libspeechd_funcptr_spd_set_voice_pitch_range = NULL;
    libspeechd_funcptr_spd_set_voice_pitch_range_all = NULL;
    libspeechd_funcptr_spd_set_voice_pitch_range_uid = NULL;
    libspeechd_funcptr_spd_set_volume = NULL;
    libspeechd_funcptr_spd_set_volume_all = NULL;
    libspeechd_funcptr_spd_set_volume_uid = NULL;
    libspeechd_funcptr_spd_get_volume = NULL;
    libspeechd_funcptr_spd_set_punctuation = NULL;
    libspeechd_funcptr_spd_set_punctuation_all = NULL;
    libspeechd_funcptr_spd_set_punctuation_uid = NULL;
    libspeechd_funcptr_spd_set_capital_letters = NULL;
    libspeechd_funcptr_spd_set_capital_letters_all = NULL;
    libspeechd_funcptr_spd_set_capital_letters_uid = NULL;
    libspeechd_funcptr_spd_set_spelling = NULL;
    libspeechd_funcptr_spd_set_spelling_all = NULL;
    libspeechd_funcptr_spd_set_spelling_uid = NULL;
    libspeechd_funcptr_spd_set_language = NULL;
    libspeechd_funcptr_spd_set_language_all = NULL;
    libspeechd_funcptr_spd_set_language_uid = NULL;
    libspeechd_funcptr_spd_get_language = NULL;
    libspeechd_funcptr_spd_set_output_module = NULL;
    libspeechd_funcptr_spd_set_output_module_all = NULL;
    libspeechd_funcptr_spd_set_output_module_uid = NULL;
    libspeechd_funcptr_spd_get_client_list = NULL;
    libspeechd_funcptr_spd_get_message_list_fd = NULL;
    libspeechd_funcptr_spd_list_modules = NULL;
    libspeechd_funcptr_free_spd_modules = NULL;
    libspeechd_funcptr_spd_get_output_module = NULL;
    libspeechd_funcptr_spd_list_voices = NULL;
    libspeechd_funcptr_free_spd_symbolic_voices = NULL;
    libspeechd_funcptr_spd_list_synthesis_voices = NULL;
    libspeechd_funcptr_spd_list_synthesis_voices2 = NULL;
    libspeechd_funcptr_free_spd_voices = NULL;
    libspeechd_funcptr_spd_execute_command_with_list_reply = NULL;
    libspeechd_funcptr_spd_execute_command = NULL;
    libspeechd_funcptr_spd_execute_command_with_reply = NULL;
    libspeechd_funcptr_spd_execute_command_wo_mutex = NULL;
    libspeechd_funcptr_spd_send_data = NULL;
    libspeechd_funcptr_spd_send_data_wo_mutex = NULL;
}

// ==================================== End of libspeechd LOADING/UNLOADING section ===================================


#endif // ndef NO_A11Y_SPEECHD_ANNOUNCING
