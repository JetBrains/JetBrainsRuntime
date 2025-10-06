/*
 * Copyright 2025 JetBrains s.r.o.
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

#include "sun_awt_wl_im_text_input_unstable_v3_WLInputMethodDescriptorZwpTextInputV3.h"
#include "sun_awt_wl_im_text_input_unstable_v3_WLInputMethodZwpTextInputV3.h"
#include "WLToolkit.h"      // wl_seat, zwp_text_input_*, uint[...]_t, int[...]_t
#include "JNIUtilities.h"

#include <stdbool.h>        // bool, true, false
#include <string.h>         // memset, strlen
#include <stdlib.h>         // malloc, free
#include <assert.h>         // assert


static bool checkIfTheImplementationIsAvailable() {
    return (zwp_text_input_manager == NULL) ? false : true;
}

static struct {
    jclass wlInputMethodClass;

    /// `sun.awt.wl.im.text_input_unstable_v3.WLInputMethodZwpTextInputV3#zwp_text_input_v3_onEnter`
    jmethodID mID_tiOnEnter;
    /// `sun.awt.wl.im.text_input_unstable_v3.WLInputMethodZwpTextInputV3#zwp_text_input_v3_onLeave`
    jmethodID mID_tiOnLeave;
    /// `sun.awt.wl.im.text_input_unstable_v3.WLInputMethodZwpTextInputV3#zwp_text_input_v3_onPreeditString`
    jmethodID mID_tiOnPreeditString;
    /// `sun.awt.wl.im.text_input_unstable_v3.WLInputMethodZwpTextInputV3#zwp_text_input_v3_onCommitString`
    jmethodID mID_tiOnCommitString;
    /// `sun.awt.wl.im.text_input_unstable_v3.WLInputMethodZwpTextInputV3#zwp_text_input_v3_onDeleteSurroundingText`
    jmethodID mID_tiOnDeleteSurroundingText;
    /// `sun.awt.wl.im.text_input_unstable_v3.WLInputMethodZwpTextInputV3#zwp_text_input_v3_onDone`
    jmethodID mID_tiOnDone;
} jniIDs = {0};


// ================================================= IMContext section ================================================
static void IMContext_zwp_text_input_v3_onEnter(void*, struct zwp_text_input_v3*, struct wl_surface*);
static void IMContext_zwp_text_input_v3_onLeave(void*, struct zwp_text_input_v3*, struct wl_surface*);
static void IMContext_zwp_text_input_v3_onPreeditString(void*, struct zwp_text_input_v3*, const char*, int32_t, int32_t);
static void IMContext_zwp_text_input_v3_onCommitString(void*, struct zwp_text_input_v3*, const char*);
static void IMContext_zwp_text_input_v3_onDeleteSurroundingText(void*, struct zwp_text_input_v3*, uint32_t, uint32_t);
static void IMContext_zwp_text_input_v3_onDone(void*, struct zwp_text_input_v3*, uint32_t);

static const struct zwp_text_input_v3_listener IMContext_zwp_text_input_v3_listener = {
    .enter = &IMContext_zwp_text_input_v3_onEnter,
    .leave = &IMContext_zwp_text_input_v3_onLeave,
    .preedit_string = &IMContext_zwp_text_input_v3_onPreeditString,
    .commit_string = &IMContext_zwp_text_input_v3_onCommitString,
    .delete_surrounding_text = &IMContext_zwp_text_input_v3_onDeleteSurroundingText,
    .done = &IMContext_zwp_text_input_v3_onDone,
};

/**
 * The native-side counterpart of `sun.awt.wl.im.text_input_unstable_v3.WLInputMethodZwpTextInputV3`.
 * On Java side these contexts are created and destroyed through
 * `WLInputMethod#createNativeContext` and `WLInputMethod#destroyNativeContext` respectively.
 *
 * `IMContext` and `WLInputMethodZwpTextInputV3` are related in a 1:1 ratio - an instance of `WLInputMethodZwpTextInputV3` holds no more than 1
 * instance of `IMContext` and an instance of `IMContext` only belongs to 1 instance of `WLInputMethodZwpTextInputV3`.
 */
struct IMContext {
    /// A global reference to the instance of `sun.awt.wl.im.text_input_unstable_v3.WLInputMethodZwpTextInputV3` owning this instance of `IMContext`.
    jobject wlInputMethodOwner;

    /// Represents an input context for the entire `text-input-unstable-v3` protocol.
    struct zwp_text_input_v3 *textInput;
};

/**
 * Creates and fully initializes an instance of `struct IMContext`.
 *
 * @param wlInputMethodOwnerRefToCopy a reference to the owning instance of `sun.awt.wl.im.text_input_unstable_v3.WLInputMethodZwpTextInputV3`.
 *                                    NB: the reference will be copied via `NewGlobalRef` and no longer used.
 * @return `null` if it has failed to create or completely initialize a new instance.
 *         In this case a corresponding exception of class `java.awt.AWTException` or of an unchecked exception class
 *         will be raised in @p env
 */
static struct IMContext* IMContext_Create(JNIEnv * const env, jobject wlInputMethodOwnerRefToCopy) {
    struct wl_seat * const wlSeat = wl_seat;
    struct zwp_text_input_manager_v3 * const textInputManager = zwp_text_input_manager;

    struct IMContext *result = NULL;
    jobject wlInputMethodOwner = NULL;
    struct zwp_text_input_v3 *textInput = NULL;

    if (wlSeat == NULL) {
        JNU_ThrowByName(env, "java/awt/AWTException", "IMContext_Create: no wl_seat is available");
        goto failure;
    }

    if (textInputManager == NULL) {
        JNU_ThrowNullPointerException(env, "IMContext_Create: textInputManager is NULL");
        goto failure;
    }

    wlInputMethodOwner = (*env)->NewGlobalRef(env, wlInputMethodOwnerRefToCopy);
    if (wlInputMethodOwner == NULL) {
        if ((*env)->ExceptionCheck(env) == JNI_FALSE) {
            JNU_ThrowOutOfMemoryError(env, "IMContext_Create: NewGlobalRef(wlInputMethodOwnerRefToCopy) failed");
        }
        goto failure;
    }

    wlInputMethodOwnerRefToCopy = NULL; // To avoid misusages

    result = malloc(sizeof(struct IMContext));
    if (result == NULL) {
        JNU_ThrowOutOfMemoryError(env, "IMContext_Create: malloc(sizeof(struct IMContext)) failed");
        goto failure;
    }

    textInput = zwp_text_input_manager_v3_get_text_input(textInputManager, wlSeat);
    if (textInput == NULL) {
        JNU_ThrowByName(env, "java/awt/AWTException", "IMContext_Create: failed to obtain a new instance of zwp_text_input_v3");
        goto failure;
    }

    // WLToolkit dispatches (almost) all native Wayland events on EDT, not on its thread.
    // If it didn't, the callbacks being set here might be called even before this function finishes, hence even before
    //   the constructor of `sun.awt.wl.im.text_input_unstable_v3.WLInputMethodZwpTextInputV3` finishes (because the constructor gets called on the
    //   EDT rather than on the toolkit thread).
    // In that case we would have to take it into account while implementing the callbacks and
    //   `WLInputMethodZwpTextInputV3` class in general.
    zwp_text_input_v3_add_listener(textInput, &IMContext_zwp_text_input_v3_listener, result);

    (void)memset(result, 0, sizeof(struct IMContext));

    result->wlInputMethodOwner = wlInputMethodOwner;
    result->textInput = textInput;

    return result;

failure:
    if (textInput != NULL) {
        zwp_text_input_v3_destroy(textInput);
        textInput = NULL;
    }

    if (result != NULL) {
        free(result);
        result = NULL;
    }

    if (wlInputMethodOwner != NULL) {
        (*env)->DeleteGlobalRef(env, wlInputMethodOwner);
        wlInputMethodOwner = NULL;
    }

    return NULL;
}

/// Destroys the context previously created by IMContext_Create
static void IMContext_Destroy(JNIEnv * const env, struct IMContext * const imContext) {
    assert(env != NULL);
    assert(imContext != NULL);

    if (imContext->textInput != NULL) {
        zwp_text_input_v3_destroy(imContext->textInput);
        imContext->textInput = NULL;
    }

    if (imContext->wlInputMethodOwner != NULL) {
        (*env)->DeleteGlobalRef(env, imContext->wlInputMethodOwner);
        imContext->wlInputMethodOwner = NULL;
    }

    free(imContext);
}


// The IMContext_zwp_text_input_v3_on* callbacks are supposed to be as a thin bridge to
// `WLInputMethodZwpTextInputV3`'s JNI upcalls as possible in terms of contained logic.
// Generally they should only invoke the corresponding upcalls with the received parameters.
//
// Exceptions after making JNI upcalls to WLInputMethodZwpTextInputV3 are checked (via JNU_CHECK_EXCEPTION),
//   but not suppressed on a purpose: all the corresponding Java methods already handles any java.lang.Exception.
//   So if an exception leaves any of those methods, it's something really strange and it's better to let WLToolkit
//   get to know about it rather than log and try to continue the normal path.
//   The checks are just made to suppress -Xcheck:jni warnings.

static void IMContext_zwp_text_input_v3_onEnter(
    void * const ctx,
    struct zwp_text_input_v3 * const textInput,
    struct wl_surface * const surface
) {
    const struct IMContext * const imContext = ctx;
    JNIEnv *env = NULL;

    if (imContext == NULL) {
        return;
    }

    env = getEnv();
    if (env == NULL) {
        return;
    }

    (*env)->CallVoidMethod(env, imContext->wlInputMethodOwner, jniIDs.mID_tiOnEnter, ptr_to_jlong(surface));
    JNU_CHECK_EXCEPTION(env);
}

static void IMContext_zwp_text_input_v3_onLeave(
    void * const ctx,
    struct zwp_text_input_v3 * const textInput,
    struct wl_surface * const surface
) {
    const struct IMContext * const imContext = ctx;
    JNIEnv *env = NULL;

    if (imContext == NULL) {
        return;
    }

    env = getEnv();
    if (env == NULL) {
        return;
    }

    (*env)->CallVoidMethod(env, imContext->wlInputMethodOwner, jniIDs.mID_tiOnLeave, ptr_to_jlong(surface));
    JNU_CHECK_EXCEPTION(env);
}


/**
 * Converts a UTF-8 string to a Java byte array, throwing an OutOfMemoryError if the allocation fails. Another kind
 * of exceptions may also appear, according to the implementation of 'NewByteArray' and 'SetByteArrayRegion'.
 * Regardless of the returned value, always check 'env->ExceptionCheck()' after each call of this function.
 *
 * @param utf8Str UTF-8 string to convert.
 * @param utf8StrSizeInBytes size of the UTF-8 string in bytes, or a negative value to ask the function to
 *                           calculate it manually.
 * @param env JNI environment. Mustn't be NULL.
 * @param oomMessage message to use in the OutOfMemoryError exception if it appears. Mustn't be NULL.
 *
 * @return a local JNI reference to a Java byte array or NULL if 'utf8Str' is NULL,
 *                                                            an exception occurred,
 *                                                            or 'NewByteArray' returned NULL for some other reason.
 */
static jbyteArray utf8StrToJByteArrayOrThrowOOM(
    const char * const utf8Str,
    ssize_t utf8StrSizeInBytes,
    JNIEnv * const env,
    const char * const oomMessage
) {
    jbyteArray result = NULL;

    assert(env != NULL);
    assert(oomMessage != NULL);

    if (utf8Str == NULL) {
        return NULL;
    }

    if (utf8StrSizeInBytes < 0) {
        // Let's believe there's a trailing NUL codepoint (otherwise we can't calculate the string's size), and
        //   there are no NUL codepoints in the middle, though it's possible in general for UTF-8.
        utf8StrSizeInBytes = (ssize_t)(strlen(utf8Str) + 1);
    }

    result = (*env)->NewByteArray(env, (jsize)utf8StrSizeInBytes);
    if (result == NULL) {
        if ((*env)->ExceptionCheck(env) == JNI_FALSE) {
            JNU_ThrowOutOfMemoryError(env, oomMessage);
        }
    } else {
        (*env)->SetByteArrayRegion(
            env,
            result,
            0,
            (jsize)utf8StrSizeInBytes,
            (const jbyte*)utf8Str
        );
    }

    return result;
}


static void IMContext_zwp_text_input_v3_onPreeditString(
    void * const ctx,
    struct zwp_text_input_v3 * const textInput,
    const char * const preeditStringUtf8,
    const int32_t cursorBeginUtf8Byte,
    const int32_t cursorEndUtf8Byte
) {
    const struct IMContext * const imContext = ctx;
    JNIEnv *env = NULL;
    jbyteArray preeditStringUtf8Bytes = NULL;

    if (imContext == NULL) {
        return;
    }

    env = getEnv();
    if (env == NULL) {
        return;
    }

    // may still be NULL
    preeditStringUtf8Bytes = utf8StrToJByteArrayOrThrowOOM(
        preeditStringUtf8,
        // the zwp_text_input_v3::preedit_string event doesn't provide the length or size of the string separately,
        //   asking the function to manually calculate it.
        -1,
        env,
        "IMContext_zwp_text_input_v3_onPreeditString: failed to allocate a new Java byte array"
    );

    if ((*env)->ExceptionCheck(env) == JNI_TRUE) {
        return;
    }

    (*env)->CallVoidMethod(
        env,
        imContext->wlInputMethodOwner,
        jniIDs.mID_tiOnPreeditString,
        preeditStringUtf8Bytes,
        (jint)cursorBeginUtf8Byte,
        (jint)cursorEndUtf8Byte
    );
    JNU_CHECK_EXCEPTION(env);
}

static void IMContext_zwp_text_input_v3_onCommitString(
    void * const ctx,
    struct zwp_text_input_v3 * const textInput,
    const char * const commitStringUtf8
) {
    const struct IMContext * const imContext = ctx;
    JNIEnv *env = NULL;
    jbyteArray commitStringUtf8Bytes = NULL;

    if (imContext == NULL) {
        return;
    }

    env = getEnv();
    if (env == NULL) {
        return;
    }

    // may still be NULL
    commitStringUtf8Bytes = utf8StrToJByteArrayOrThrowOOM(
        commitStringUtf8,
        // the zwp_text_input_v3::commit_string event doesn't provide the length or size of the string separately,
        //   asking the function to manually calculate it.
        -1,
        env,
        "IMContext_zwp_text_input_v3_onCommitString: failed to allocate a new Java byte array"
    );

    if ((*env)->ExceptionCheck(env) == JNI_TRUE) {
        return;
    }

    (*env)->CallVoidMethod(env, imContext->wlInputMethodOwner, jniIDs.mID_tiOnCommitString, commitStringUtf8Bytes);
    JNU_CHECK_EXCEPTION(env);
}

static void IMContext_zwp_text_input_v3_onDeleteSurroundingText(
    void * const ctx,
    struct zwp_text_input_v3 * const textInput,
    const uint32_t numberOfUtf8BytesBeforeToDelete,
    const uint32_t numberOfUtf8BytesAfterToDelete
) {
    const struct IMContext * const imContext = ctx;
    JNIEnv *env = NULL;

    if (imContext == NULL) {
        return;
    }

    env = getEnv();
    if (env == NULL) {
        return;
    }

    if ((*env)->ExceptionCheck(env) == JNI_TRUE) {
        return;
    }

    (*env)->CallVoidMethod(
       env,
       imContext->wlInputMethodOwner,
       jniIDs.mID_tiOnDeleteSurroundingText,
       (jlong)numberOfUtf8BytesBeforeToDelete,
       (jlong)numberOfUtf8BytesAfterToDelete
    );
    JNU_CHECK_EXCEPTION(env);
}

static void IMContext_zwp_text_input_v3_onDone(
    void *const ctx,
    struct zwp_text_input_v3 * const textInput,
    const uint32_t serial
) {
    const struct IMContext * const imContext = ctx;
    JNIEnv *env = NULL;

    if (imContext == NULL) {
        return;
    }

    env = getEnv();
    if (env == NULL) {
        return;
    }

    (*env)->CallVoidMethod(env, imContext->wlInputMethodOwner, jniIDs.mID_tiOnDone, (jlong)serial);
    JNU_CHECK_EXCEPTION(env);
}
// ============================================= END of IMContext section =============================================


// =============================================== JNI downcalls section ==============================================

JNIEXPORT jboolean JNICALL
Java_sun_awt_wl_im_text_1input_1unstable_1v3_WLInputMethodDescriptorZwpTextInputV3_checkIfAvailableOnPlatform(JNIEnv * const env, const jclass clazz) {
    return checkIfTheImplementationIsAvailable() ? JNI_TRUE : JNI_FALSE;
}


JNIEXPORT void JNICALL
Java_sun_awt_wl_im_text_1input_1unstable_1v3_WLInputMethodZwpTextInputV3_initIDs(JNIEnv * const env, const jclass clazz) {
    CHECK_NULL_THROW_OOME(
        env,
        jniIDs.wlInputMethodClass = (*env)->NewGlobalRef(env, clazz),
        "Allocation of a global reference to sun.awt.wl.im.text_input_unstable_v3.WLInputMethodZwpTextInputV3 class failed"
    );

    jniIDs.mID_tiOnEnter =
        (*env)->GetMethodID(env, jniIDs.wlInputMethodClass, "zwp_text_input_v3_onEnter", "(J)V");
    if (jniIDs.mID_tiOnEnter == NULL) {
        // DeleteGlobalRef is one of the few JNI functions that are safe to call while there's a pending exception
        (*env)->DeleteGlobalRef(env, jniIDs.wlInputMethodClass);
        (void)memset(&jniIDs, 0, sizeof(jniIDs));
        return;
    }

    jniIDs.mID_tiOnLeave =
        (*env)->GetMethodID(env, jniIDs.wlInputMethodClass, "zwp_text_input_v3_onLeave", "(J)V");
    if (jniIDs.mID_tiOnLeave == NULL) {
        (*env)->DeleteGlobalRef(env, jniIDs.wlInputMethodClass);
        (void)memset(&jniIDs, 0, sizeof(jniIDs));
        return;
    }

    jniIDs.mID_tiOnPreeditString =
        (*env)->GetMethodID(env, jniIDs.wlInputMethodClass, "zwp_text_input_v3_onPreeditString", "([BII)V");
    if (jniIDs.mID_tiOnPreeditString == NULL) {
        (*env)->DeleteGlobalRef(env, jniIDs.wlInputMethodClass);
        (void)memset(&jniIDs, 0, sizeof(jniIDs));
        return;
    }

    jniIDs.mID_tiOnCommitString =
        (*env)->GetMethodID(env, jniIDs.wlInputMethodClass, "zwp_text_input_v3_onCommitString", "([B)V");
    if (jniIDs.mID_tiOnCommitString == NULL) {
        (*env)->DeleteGlobalRef(env, jniIDs.wlInputMethodClass);
        (void)memset(&jniIDs, 0, sizeof(jniIDs));
        return;
    }

    jniIDs.mID_tiOnDeleteSurroundingText =
        (*env)->GetMethodID(env, jniIDs.wlInputMethodClass, "zwp_text_input_v3_onDeleteSurroundingText", "(JJ)V");
    if (jniIDs.mID_tiOnDeleteSurroundingText == NULL) {
        (*env)->DeleteGlobalRef(env, jniIDs.wlInputMethodClass);
        (void)memset(&jniIDs, 0, sizeof(jniIDs));
        return;
    }

    jniIDs.mID_tiOnDone =
        (*env)->GetMethodID(env, jniIDs.wlInputMethodClass, "zwp_text_input_v3_onDone", "(J)V");
    if (jniIDs.mID_tiOnDone == NULL) {
        (*env)->DeleteGlobalRef(env, jniIDs.wlInputMethodClass);
        (void)memset(&jniIDs, 0, sizeof(jniIDs));
        return;
    }
}


JNIEXPORT jlong JNICALL
Java_sun_awt_wl_im_text_1input_1unstable_1v3_WLInputMethodZwpTextInputV3_createNativeContext(JNIEnv * const env, const jobject self) {
    struct IMContext *result = NULL;

    if (!checkIfTheImplementationIsAvailable()) {
        JNU_ThrowByName(env, "java/awt/AWTException", "sun.awt.wl.im.text_input_unstable_v3.WLInputMethodZwpTextInputV3 is not supported on this system");
        return 0;
    }

    result = IMContext_Create(env, self);

    return ptr_to_jlong(result);
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_im_text_1input_1unstable_1v3_WLInputMethodZwpTextInputV3_disposeNativeContext(
    JNIEnv * const env,
    const jclass clazz,
    const jlong contextPtr
) {
    struct IMContext *imContext = jlong_to_ptr(contextPtr);

    if (imContext == NULL) {
        JNU_ThrowNullPointerException(env, "contextPtr");
        return;
    }

    IMContext_Destroy(env, imContext);
    imContext = NULL;
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_im_text_1input_1unstable_1v3_WLInputMethodZwpTextInputV3_zwp_1text_1input_1v3_1enable(
    JNIEnv * const env,
    const jobject self,
    const jlong contextPtr
) {
    const struct IMContext * const imContext = jlong_to_ptr(contextPtr);
    struct zwp_text_input_v3 *textInput = NULL;

    if (imContext == NULL) {
        JNU_ThrowNullPointerException(env, "contextPtr");
        return;
    }

    textInput = imContext->textInput;
    if (textInput == NULL) {
        JNU_ThrowByName(env, "java/lang/IllegalStateException", "textInput == NULL");
        return;
    }

    zwp_text_input_v3_enable(textInput);
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_im_text_1input_1unstable_1v3_WLInputMethodZwpTextInputV3_zwp_1text_1input_1v3_1disable(
    JNIEnv * const env,
    const jobject self,
    const jlong contextPtr
) {
    const struct IMContext * const imContext = jlong_to_ptr(contextPtr);
    struct zwp_text_input_v3 *textInput = NULL;

    if (imContext == NULL) {
        JNU_ThrowNullPointerException(env, "contextPtr");
        return;
    }

    textInput = imContext->textInput;
    if (textInput == NULL) {
        JNU_ThrowByName(env, "java/lang/IllegalStateException", "textInput == NULL");
        return;
    }

    zwp_text_input_v3_disable(textInput);
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_im_text_1input_1unstable_1v3_WLInputMethodZwpTextInputV3_zwp_1text_1input_1v3_1set_1cursor_1rectangle(
    JNIEnv * const env,
    const jobject self,
    const jlong contextPtr,
    const jint surfaceLocalX,
    const jint surfaceLocalY,
    const jint width,
    const jint height
) {
    const struct IMContext * const imContext = jlong_to_ptr(contextPtr);
    struct zwp_text_input_v3 *textInput = NULL;

    if (imContext == NULL) {
        JNU_ThrowNullPointerException(env, "contextPtr");
        return;
    }

    textInput = imContext->textInput;
    if (textInput == NULL) {
        JNU_ThrowByName(env, "java/lang/IllegalStateException", "textInput == NULL");
        return;
    }

    zwp_text_input_v3_set_cursor_rectangle(
        textInput,
        (int32_t)surfaceLocalX,
        (int32_t)surfaceLocalY,
        (int32_t)width,
        (int32_t)height
    );
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_im_text_1input_1unstable_1v3_WLInputMethodZwpTextInputV3_zwp_1text_1input_1v3_1set_1content_1type(
    JNIEnv * const env,
    const jobject self,
    const jlong contextPtr,
    const jint hint,
    const jint purpose
) {
    const struct IMContext * const imContext = jlong_to_ptr(contextPtr);
    struct zwp_text_input_v3 *textInput = NULL;

    if (imContext == NULL) {
        JNU_ThrowNullPointerException(env, "contextPtr");
        return;
    }

    textInput = imContext->textInput;
    if (textInput == NULL) {
        JNU_ThrowByName(env, "java/lang/IllegalStateException", "textInput == NULL");
        return;
    }

    zwp_text_input_v3_set_content_type(textInput, (uint32_t)hint, (uint32_t)purpose);
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_im_text_1input_1unstable_1v3_WLInputMethodZwpTextInputV3_zwp_1text_1input_1v3_1set_1text_1change_1cause(
    JNIEnv * const env,
    const jobject self,
    const jlong contextPtr,
    const jint changeCause
) {
    const struct IMContext * const imContext = jlong_to_ptr(contextPtr);
    struct zwp_text_input_v3 *textInput = NULL;

    if (imContext == NULL) {
        JNU_ThrowNullPointerException(env, "contextPtr");
        return;
    }

    textInput = imContext->textInput;
    if (textInput == NULL) {
        JNU_ThrowByName(env, "java/lang/IllegalStateException", "textInput == NULL");
        return;
    }

    zwp_text_input_v3_set_text_change_cause(textInput, (uint32_t)changeCause);
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_im_text_1input_1unstable_1v3_WLInputMethodZwpTextInputV3_zwp_1text_1input_1v3_1commit(
    JNIEnv * const env,
    const jobject self,
    const jlong contextPtr
) {
    const struct IMContext * const imContext = jlong_to_ptr(contextPtr);
    struct zwp_text_input_v3 *textInput = NULL;

    if (imContext == NULL) {
        JNU_ThrowNullPointerException(env, "contextPtr");
        return;
    }

    textInput = imContext->textInput;
    if (textInput == NULL) {
        JNU_ThrowByName(env, "java/lang/IllegalStateException", "textInput == NULL");
        return;
    }

    zwp_text_input_v3_commit(textInput);
}

// =========================================== END of JNI downcalls section ===========================================
