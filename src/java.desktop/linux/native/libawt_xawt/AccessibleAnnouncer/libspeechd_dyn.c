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
#include "debug_assert.h"   // DASSERT
#include <libspeechd.h>
#include <stdbool.h>        // bool, true, false
#include <pthread.h>        // pthread_*
#include <dlfcn.h>          // dlopen, dlclose, dlsym, dlerror
#include <string.h>         // strdup
#include <stdarg.h>         // va_list, va_start, va_end


// =============================================== libspeechd RESOURCES ===============================================

// Below are all handlers associated with libspeechd.
// Reading any of them MUST be performed under at least read-locked libspeechd_rwlock.
// Writing is only allowed under write-locked libspeechd_rwlock.

// dlopen/dlclose
static void *libspeechd_libhandle = NULL;

static SPDConnection*        (*libspeechd_funcptr_spd_open)                           (const char*, const char*, const char*, SPDConnectionMode) = NULL;
static void                  (*libspeechd_funcptr_spd_close)                          (SPDConnection*) = NULL;
static int                   (*libspeechd_funcptr_spd_say)                            (SPDConnection*, SPDPriority, const char*) = NULL;
static int                   (*libspeechd_funcptr_spd_set_synthesis_voice)            (SPDConnection*, const char*) = NULL;
static int                   (*libspeechd_funcptr_spd_set_voice_rate)                 (SPDConnection*, signed int) = NULL;
static int                   (*libspeechd_funcptr_spd_set_voice_pitch)                (SPDConnection*, signed int) = NULL;
static int                   (*libspeechd_funcptr_spd_set_volume)                     (SPDConnection*, signed int) = NULL;
static int                   (*libspeechd_funcptr_spd_set_punctuation)                (SPDConnection*, SPDPunctuation) = NULL;
static int                   (*libspeechd_funcptr_spd_set_language)                   (SPDConnection*, const char*) = NULL;
static int                   (*libspeechd_funcptr_spd_set_output_module)              (SPDConnection*, const char*) = NULL;

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
           (libspeechd_funcptr_spd_open != NULL)                            &&
           (libspeechd_funcptr_spd_close != NULL)                           &&
           (libspeechd_funcptr_spd_say != NULL)                             &&
           (libspeechd_funcptr_spd_set_synthesis_voice != NULL)             &&
           (libspeechd_funcptr_spd_set_voice_rate != NULL)                  &&
           (libspeechd_funcptr_spd_set_voice_pitch != NULL)                 &&
           (libspeechd_funcptr_spd_set_volume != NULL)                      &&
           (libspeechd_funcptr_spd_set_punctuation != NULL)                 &&
           (libspeechd_funcptr_spd_set_language != NULL)                    &&
           (libspeechd_funcptr_spd_set_output_module != NULL);
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

    LOAD_FUNC_OR_FAIL(spd_open);
    LOAD_FUNC_OR_FAIL(spd_close);
    LOAD_FUNC_OR_FAIL(spd_say);
    LOAD_FUNC_OR_FAIL(spd_set_synthesis_voice);
    LOAD_FUNC_OR_FAIL(spd_set_voice_rate);
    LOAD_FUNC_OR_FAIL(spd_set_voice_pitch);
    LOAD_FUNC_OR_FAIL(spd_set_volume);
    LOAD_FUNC_OR_FAIL(spd_set_punctuation);
    LOAD_FUNC_OR_FAIL(spd_set_language);
    LOAD_FUNC_OR_FAIL(spd_set_output_module);

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

    libspeechd_funcptr_spd_open = NULL;
    libspeechd_funcptr_spd_close = NULL;
    libspeechd_funcptr_spd_say = NULL;
    libspeechd_funcptr_spd_set_synthesis_voice = NULL;
    libspeechd_funcptr_spd_set_voice_rate = NULL;
    libspeechd_funcptr_spd_set_voice_pitch = NULL;
    libspeechd_funcptr_spd_set_volume = NULL;
    libspeechd_funcptr_spd_set_punctuation = NULL;
    libspeechd_funcptr_spd_set_language = NULL;
    libspeechd_funcptr_spd_set_output_module = NULL;
}

// ==================================== End of libspeechd LOADING/UNLOADING section ===================================


// ======================================== libspeechd.h FUNCTIONS INTERCEPTORS =======================================

// If something is missing and the AccessibleAnnouncer tries to use it, it's expected the AWT will fail to build at
//   link-time instead of blowing up at run-time/load-time.

#define INVOKE_SPEECHD_OR_RETURN_VALUE(retVal, funcPtr, ...)  \
    do {                                                      \
        if (!TryLoadSpeechd()) {                              \
            return retVal;                                    \
        }                                                     \
        DASSERT(funcPtr != NULL);                             \
        return funcPtr(__VA_ARGS__);                          \
    } while (0)

static const char* NOT_LOADED_ERR_MSG = "libspeechd not loaded";

void SPDConnectionAddress__free(SPDConnectionAddress *address)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(, libspeechd_funcptr_SPDConnectionAddress__free, address);
}

SPDConnectionAddress* spd_get_default_address(char **error)
{
    if (!TryLoadSpeechd())
    {
        if (error != NULL)
        {
            // The original implementation also dynamically allocates the error string
            *error = strdup(NOT_LOADED_ERR_MSG);
        }
        return NULL;
    }
    DASSERT(libspeechd_funcptr_spd_get_default_address != NULL);

    return libspeechd_funcptr_spd_get_default_address(error);
}

SPDConnection* spd_open(const char *client_name, const char *connection_name, const char *user_name, SPDConnectionMode mode)
{
    if (!TryLoadSpeechd())
    {
        return NULL;
    }
    DASSERT(libspeechd_funcptr_spd_open != NULL);

    return libspeechd_funcptr_spd_open(client_name, connection_name, user_name, mode);
}

SPDConnection* spd_open2(
    const char *client_name,
    const char *connection_name,
    const char *user_name,
    SPDConnectionMode mode,
    const SPDConnectionAddress *address,
    int autospawn,
    char **error_result
) {
    if (!TryLoadSpeechd())
    {
        if (error_result != NULL)
        {
            *error_result = strdup(NOT_LOADED_ERR_MSG);
        }
        return NULL;
    }
    DASSERT(libspeechd_funcptr_spd_open2 != NULL);

    return libspeechd_funcptr_spd_open2(client_name, connection_name, user_name, mode, address, autospawn, error_result);
}

int spd_fd(SPDConnection *connection)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_fd, connection);
}

int spd_get_client_id(SPDConnection *connection)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(0, libspeechd_funcptr_spd_get_client_id, connection);
}

void spd_close(SPDConnection *connection)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(, libspeechd_funcptr_spd_close, connection);
}


int spd_say(SPDConnection *connection, SPDPriority priority, const char *text)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_say, connection, priority, text);
}

int spd_sayf(SPDConnection *connection, SPDPriority priority, const char *format, ...)
{
    int result;
    va_list args;

    if (!TryLoadSpeechd())
    {
        return -1;
    }
    DASSERT(libspeechd_funcptr_spd_sayf != NULL);

    va_start(args, format);
    result = libspeechd_funcptr_spd_sayf(connection, priority, format, args);
    va_end(args);

    return result;
}


int spd_stop(SPDConnection *connection)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_stop, connection);
}

int spd_stop_all(SPDConnection *connection)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_stop_all, connection);
}

int spd_stop_uid(SPDConnection *connection, int target_uid)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_stop_uid, connection, target_uid);
}


int spd_cancel(SPDConnection *connection)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_cancel, connection);
}

int spd_cancel_all(SPDConnection *connection)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_cancel_all, connection);
}

int spd_cancel_uid(SPDConnection *connection, int target_uid)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_cancel_uid, connection, target_uid);
}


int spd_pause(SPDConnection *connection)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_pause, connection);
}

int spd_pause_all(SPDConnection *connection)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_pause_all, connection);
}

int spd_pause_uid(SPDConnection *connection, int target_uid)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_pause_uid, connection, target_uid);
}


int spd_resume(SPDConnection *connection)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_resume, connection);
}

int spd_resume_all(SPDConnection *connection)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_resume_all, connection);
}

int spd_resume_uid(SPDConnection *connection, int target_uid)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_resume_uid, connection, target_uid);
}



int spd_key(SPDConnection *connection, SPDPriority priority, const char *key_name)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_key, connection, priority, key_name);
}

int spd_char(SPDConnection *connection, SPDPriority priority, const char *character)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_char, connection, priority, character);
}

int spd_wchar(SPDConnection *connection, SPDPriority priority, wchar_t wcharacter)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_wchar, connection, priority, wcharacter);
}


int spd_sound_icon(SPDConnection *connection, SPDPriority priority, const char *icon_name)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_sound_icon, connection, priority, icon_name);
}


int spd_set_voice_type(SPDConnection *connection, SPDVoiceType type)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_voice_type, connection, type);
}

int spd_set_voice_type_all(SPDConnection *connection, SPDVoiceType type)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_voice_type_all, connection, type);
}

int spd_set_voice_type_uid(SPDConnection *connection, SPDVoiceType type, unsigned int uid)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_voice_type_uid, connection, type, uid);
}

SPDVoiceType spd_get_voice_type(SPDConnection *connection)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(0, libspeechd_funcptr_spd_get_voice_type, connection);
}


int spd_set_synthesis_voice(SPDConnection *connection, const char *voice_name)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_synthesis_voice, connection, voice_name);
}

int spd_set_synthesis_voice_all(SPDConnection *connection, const char *voice_name)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_synthesis_voice_all, connection, voice_name);
}

int spd_set_synthesis_voice_uid(SPDConnection *connection, const char *voice_name, unsigned int uid)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_synthesis_voice_uid, connection, voice_name, uid);
}


int spd_set_data_mode(SPDConnection *connection, SPDDataMode mode)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_data_mode, connection, mode);
}


int spd_set_notification_on(SPDConnection *connection, SPDNotification notification)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_notification_on, connection, notification);
}

int spd_set_notification_off(SPDConnection *connection, SPDNotification notification)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_notification_off, connection, notification);
}

int spd_set_notification(SPDConnection *connection, SPDNotification notification, const char *state)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_notification, connection, notification, state);
}


int spd_set_voice_rate(SPDConnection *connection, signed int rate)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_voice_rate, connection, rate);
}

int spd_set_voice_rate_all(SPDConnection *connection, signed int rate)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_voice_rate_all, connection, rate);
}

int spd_set_voice_rate_uid(SPDConnection *connection, signed int rate, unsigned int uid)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_voice_rate_uid, connection, rate, uid);
}

int spd_get_voice_rate(SPDConnection *connection)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(0, libspeechd_funcptr_spd_get_voice_rate, connection);
}


int spd_set_voice_pitch(SPDConnection *connection, signed int pitch)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_voice_pitch, connection, pitch);
}

int spd_set_voice_pitch_all(SPDConnection *connection, signed int pitch)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_voice_pitch_all, connection, pitch);
}

int spd_set_voice_pitch_uid(SPDConnection *connection, signed int pitch, unsigned int uid)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_voice_pitch_uid, connection, pitch, uid);
}

int spd_get_voice_pitch(SPDConnection *connection)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_get_voice_pitch, connection);
}


int spd_set_voice_pitch_range(SPDConnection *connection, signed int pitch_range)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_voice_pitch_range, connection, pitch_range);
}

int spd_set_voice_pitch_range_all(SPDConnection *connection, signed int pitch_range)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_voice_pitch_range_all, connection, pitch_range);
}

int spd_set_voice_pitch_range_uid(SPDConnection *connection, signed int pitch_range, unsigned int uid)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_voice_pitch_range_uid, connection, pitch_range, uid);
}


int spd_set_volume(SPDConnection *connection, signed int volume)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_volume, connection, volume);
}

int spd_set_volume_all(SPDConnection *connection, signed int volume)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_volume_all, connection, volume);
}

int spd_set_volume_uid(SPDConnection *connection, signed int volume, unsigned int uid)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_volume_uid, connection, volume, uid);
}

int spd_get_volume(SPDConnection *connection)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(0, libspeechd_funcptr_spd_get_volume, connection);
}


int spd_set_punctuation(SPDConnection *connection, SPDPunctuation type)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_punctuation, connection, type);
}

int spd_set_punctuation_all(SPDConnection *connection, SPDPunctuation type)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_punctuation_all, connection, type);
}

int spd_set_punctuation_uid(SPDConnection *connection, SPDPunctuation type, unsigned int uid)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_punctuation_uid, connection, type, uid);
}


int spd_set_capital_letters(SPDConnection *connection, SPDCapitalLetters type)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_capital_letters, connection, type);
}

int spd_set_capital_letters_all(SPDConnection *connection, SPDCapitalLetters type)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_capital_letters_all, connection, type);
}

int spd_set_capital_letters_uid(SPDConnection *connection, SPDCapitalLetters type, unsigned int uid)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_capital_letters_uid, connection, type, uid);
}


int spd_set_spelling(SPDConnection *connection, SPDSpelling type)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_spelling, connection, type);
}

int spd_set_spelling_all(SPDConnection *connection, SPDSpelling type)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_spelling_all, connection, type);
}

int spd_set_spelling_uid(SPDConnection *connection, SPDSpelling type, unsigned int uid)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_spelling_uid, connection, type, uid);
}


int spd_set_language(SPDConnection *connection, const char *language)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_language, connection, language);
}

int spd_set_language_all(SPDConnection *connection, const char *language)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_language_all, connection, language);
}

int spd_set_language_uid(SPDConnection *connection, const char *language, unsigned int uid)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_language_uid, connection, language, uid);
}

char* spd_get_language(SPDConnection *connection)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(NULL, libspeechd_funcptr_spd_get_language, connection);
}


int spd_set_output_module(SPDConnection *connection, const char *output_module)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_output_module, connection, output_module);
}

int spd_set_output_module_all(SPDConnection *connection, const char *output_module)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_output_module_all, connection, output_module);
}

int spd_set_output_module_uid(SPDConnection *connection, const char *output_module, unsigned int uid)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_output_module_uid, connection, output_module, uid);
}


int spd_get_client_list(SPDConnection *connection, char **client_names, int *client_ids, int *active);
int spd_get_message_list_fd(SPDConnection *connection, int target, int *msg_ids, char **client_names);

char** spd_list_modules(SPDConnection *connection);
void free_spd_modules(char **);
char* spd_get_output_module(SPDConnection *connection);

char** spd_list_voices(SPDConnection *connection);
void free_spd_symbolic_voices(char **voices);
SPDVoice** spd_list_synthesis_voices(SPDConnection *connection);
SPDVoice** spd_list_synthesis_voices2(SPDConnection *connection, const char *language, const char *variant);
void free_spd_voices(SPDVoice **voices);
char** spd_execute_command_with_list_reply(SPDConnection *connection, const char *command);

int spd_execute_command(SPDConnection *connection, const char *command);
int spd_execute_command_with_reply(SPDConnection *connection, const char *command, char **reply);
int spd_execute_command_wo_mutex(SPDConnection *connection, const char *command);
char* spd_send_data(SPDConnection *connection, const char *message, int wfr);
char* spd_send_data_wo_mutex(SPDConnection *connection, const char *message, int wfr);


#undef INVOKE_SPEECHD_OR_RETURN_VALUE

// ================================ End of libspeechd.h FUNCTIONS INTERCEPTORS section ================================


#endif // ndef NO_A11Y_SPEECHD_ANNOUNCING
