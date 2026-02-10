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
#include "jvm_md.h"         // VERSIONED_JNI_LIB_NAME
#include "debug_assert.h"   // DASSERT
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
    // Currently we're only supporting version 2
    static const char *soName = VERSIONED_JNI_LIB_NAME("speechd", "2");

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


SPDConnection* spd_open(const char *client_name, const char *connection_name, const char *user_name, SPDConnectionMode mode)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(NULL, libspeechd_funcptr_spd_open, client_name, connection_name, user_name, mode);
}

void spd_close(SPDConnection *connection)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(, libspeechd_funcptr_spd_close, connection);
}


int spd_say(SPDConnection *connection, SPDPriority priority, const char *text)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_say, connection, priority, text);
}


int spd_set_synthesis_voice(SPDConnection *connection, const char *voice_name)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_synthesis_voice, connection, voice_name);
}


int spd_set_voice_rate(SPDConnection *connection, signed int rate)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_voice_rate, connection, rate);
}


int spd_set_voice_pitch(SPDConnection *connection, signed int pitch)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_voice_pitch, connection, pitch);
}


int spd_set_volume(SPDConnection *connection, signed int volume)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_volume, connection, volume);
}


int spd_set_punctuation(SPDConnection *connection, SPDPunctuation type)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_punctuation, connection, type);
}


int spd_set_language(SPDConnection *connection, const char *language)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_language, connection, language);
}


int spd_set_output_module(SPDConnection *connection, const char *output_module)
{
    INVOKE_SPEECHD_OR_RETURN_VALUE(-1, libspeechd_funcptr_spd_set_output_module, connection, output_module);
}


#undef INVOKE_SPEECHD_OR_RETURN_VALUE

// ================================ End of libspeechd.h FUNCTIONS INTERCEPTORS section ================================


#endif // ndef NO_A11Y_SPEECHD_ANNOUNCING
