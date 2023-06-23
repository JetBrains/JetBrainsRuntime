/*
 * Copyright (c) 2021, 2022, JetBrains s.r.o.. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

#include "jni.h"
#include "jni_util.h"
#include "nio_util.h"

#include <stdarg.h>

#include <CoreFoundation/CoreFoundation.h>
#include <CoreServices/CoreServices.h>

#if defined(__GNUC__) || defined(__clang__)
#  ifndef ATTRIBUTE_PRINTF
#    define ATTRIBUTE_PRINTF(fmt,vargs)  __attribute__((format(printf, fmt, vargs)))
#  endif
#endif

static void
traceLine(JNIEnv* env, const char* fmt, ...) ATTRIBUTE_PRINTF(2, 3);

// Controls exception stack trace output and debug trace.
// Set by raising the logging level of sun.nio.fs.MacOSXWatchService to or above FINEST.
static jboolean tracingEnabled;

static jmethodID callbackMID;  // MacOSXWatchService.callback()

static void* watchServiceKey = &watchServiceKey;

JNIEXPORT void JNICALL
Java_sun_nio_fs_MacOSXWatchService_initIDs(JNIEnv *env, __unused jclass clazz)
{
    jfieldID tracingEnabledFieldID = (*env)->GetStaticFieldID(env, clazz, "tracingEnabled", "Z");
    CHECK_NULL(tracingEnabledFieldID);
    tracingEnabled = (*env)->GetStaticBooleanField(env, clazz, tracingEnabledFieldID);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
    }

    callbackMID = (*env)->GetMethodID(env, clazz, "callback", "(J[Ljava/lang/String;J)V");
}

extern CFStringRef toCFString(JNIEnv *env, jstring javaString);

static void
traceLine(JNIEnv* env, const char* fmt, ...)
{
    if (tracingEnabled) {
        va_list vargs;
        va_start(vargs, fmt);
        char* buf = (char*)malloc(1024);
        vsnprintf(buf, 1024, fmt, vargs);
        const jstring text = JNU_NewStringPlatform(env, buf);
        free(buf);
        va_end(vargs);

        jboolean ignoreException;
        JNU_CallStaticMethodByName(env, &ignoreException, "sun/nio/fs/MacOSXWatchService", "traceLine", "(Ljava/lang/String;)V", text);
    }
}

static jboolean
convertToJavaStringArray(JNIEnv* env,
                         char **eventPaths,
                         const jsize numEventsToReport,
                         jobjectArray javaEventPathsArray)
{
    for (jsize i = 0; i < numEventsToReport; i++) {
        const jstring path = JNU_NewStringPlatform(env, eventPaths[i]);
        CHECK_NULL_RETURN(path, FALSE);
        (*env)->SetObjectArrayElement(env, javaEventPathsArray, i, path);
    }

    return JNI_TRUE;
}

static void
callJavaCallback(JNIEnv* env,
                 jobject watchService,
                 jlong nativeDataPtr,
                 jobjectArray javaEventPathsArray,
                 jlong eventFlags)
{
    if (callbackMID != NULL && watchService != NULL) {
        (*env)->CallVoidMethod(env, watchService, callbackMID, nativeDataPtr, javaEventPathsArray, eventFlags);
    }
}

/**
 * Callback that is invoked on the dispatch queue and informs of new file-system events from an FSEventStream.
 */
static void
callback(__unused ConstFSEventStreamRef streamRef,
         void *clientCallBackInfo,
         size_t numEventsTotal,
         void *eventPaths,
         const FSEventStreamEventFlags eventFlags[],
         __unused const FSEventStreamEventId eventIds[])
{
    JNIEnv* env = NULL;
    JavaVMAttachArgs args;
    args.version = JNI_VERSION_1_4;
    args.name = "FSEvents";
    args.group = NULL;
    jint rc = (*jvm)->AttachCurrentThreadAsDaemon(jvm, (void**)&env, &args);

    if (!env || rc != JNI_OK) {
        traceLine(env, "Couldn't attach dispatch queue thread to JVM");
        return;
    }

    jobject watchServiceObj = dispatch_get_specific(watchServiceKey);
    jlong nativeDataPtr = ptr_to_jlong(streamRef);

    // We can get more events at once than the number of Java array elements,
    // so report them in chunks.
    const size_t MAX_EVENTS_TO_REPORT_AT_ONCE = (INT_MAX - 2);

    jboolean success = JNI_TRUE;
    for(size_t eventIndex = 0; success && (eventIndex < numEventsTotal); ) {
        const size_t numEventsRemaining = (numEventsTotal - eventIndex);
        const jsize  numEventsToReport  = (numEventsRemaining > MAX_EVENTS_TO_REPORT_AT_ONCE)
                                        ? MAX_EVENTS_TO_REPORT_AT_ONCE
                                        : numEventsRemaining;

        const jboolean localFramePushed = ((*env)->PushLocalFrame(env, numEventsToReport + 5) == JNI_OK);
        success = localFramePushed;

        jobjectArray javaEventPathsArray = NULL;
        if (success) {
            javaEventPathsArray = (*env)->NewObjectArray(env, (jsize)numEventsToReport, JNU_ClassString(env), NULL);
            success = (javaEventPathsArray != NULL);
        }

        if (success) {
            success = convertToJavaStringArray(env, &((char**)eventPaths)[eventIndex], numEventsToReport, javaEventPathsArray);
        }

        callJavaCallback(env,
                         watchServiceObj,
                         nativeDataPtr,
                         javaEventPathsArray,
                         ptr_to_jlong(&eventFlags[eventIndex]));

        if ((*env)->ExceptionCheck(env)) {
            if (tracingEnabled) {
                (*env)->ExceptionDescribe(env);
            }
            else {
                (*env)->ExceptionClear(env);
            }
        }

        if (localFramePushed) {
            (*env)->PopLocalFrame(env, NULL);
        }

        eventIndex += numEventsToReport;
    }
}

/**
 * Creates a new FSEventStream and returns an opaque pointer to the corresponding native data for it.
 */
JNIEXPORT jlong JNICALL
Java_sun_nio_fs_MacOSXWatchService_eventStreamCreate(JNIEnv* env, __unused jclass clazz,
                                                     jstring dir,
                                                     jdouble latencyInSeconds,
                                                     jint flags)
{
    const CFStringRef path = toCFString(env, dir);
    CHECK_NULL_RETURN(path, 0);
    const CFArrayRef pathsToWatch = CFArrayCreate(NULL, (const void **) &path, 1, NULL);
    CHECK_NULL_RETURN(pathsToWatch, 0);

    FSEventStreamRef stream = FSEventStreamCreate(
            NULL,
            &callback,
            NULL,
            pathsToWatch,
            kFSEventStreamEventIdSinceNow,
            (CFAbsoluteTime) latencyInSeconds,
            flags
    );

    traceLine(env, "created event stream 0x%p", stream);
    return ptr_to_jlong(stream);
}

/**
 * Creates a dispatch queue and schedules the given FSEventStream on it.
 * Starts the stream so that events from the stream can come and get handled.
 */
JNIEXPORT jboolean JNICALL
Java_sun_nio_fs_MacOSXWatchService_eventStreamSchedule(__unused JNIEnv* env,  __unused jclass clazz,
                                                       jlong nativeDataPtr,
                                                       jlong dispatchQueuePtr)
{
    FSEventStreamRef stream = jlong_to_ptr(nativeDataPtr);
    dispatch_queue_t queue = jlong_to_ptr(dispatchQueuePtr);

    FSEventStreamSetDispatchQueue(stream, queue);
    if (!FSEventStreamStart(stream)) {
        // "FSEventStreamInvalidate() can only be called on the stream after you have called
        //  FSEventStreamScheduleWithRunLoop() or FSEventStreamSetDispatchQueue()."
        FSEventStreamInvalidate(stream);
        FSEventStreamRelease(stream);
        return JNI_FALSE;
    }

    traceLine(env, "scheduled stream 0x%p on queue %p", stream, queue);
    return JNI_TRUE;
}

/**
 * Performs the steps necessary to dispose of the given stream and the
 * associated dispatch queue.
 * The native pointer is no longer valid after return from this method.
 */
JNIEXPORT void JNICALL
Java_sun_nio_fs_MacOSXWatchService_eventStreamDestroy(__unused JNIEnv* env, __unused jclass clazz,
                                                      jlong nativeDataPtr)
{
    FSEventStreamRef stream = jlong_to_ptr(nativeDataPtr);

    // "You must eventually call FSEventStreamInvalidate and it’s an error to call FSEventStreamInvalidate
    //  without having the stream either scheduled on a runloop or a dispatch queue"

    // Unregister with the FS Events service. No more callbacks from this stream.
    FSEventStreamStop(stream);
    FSEventStreamInvalidate(stream); // Unschedule from any queues
    FSEventStreamRelease(stream);    // Decrement the stream's refcount
}

static void
dispatchQueueDestructor(void * context)
{
    JNIEnv* env = NULL;
    JavaVMAttachArgs args;
    args.version = JNI_VERSION_1_4;
    args.name = "FSEvents";
    args.group = NULL;
    jint rc = (*jvm)->AttachCurrentThreadAsDaemon(jvm, (void**)&env, &args);

    if (env && rc == JNI_OK) {
        jobject watchServiceGlobal = (jobject)context;
        (*env)->DeleteGlobalRef(env, watchServiceGlobal);
    }
}

JNIEXPORT jlong JNICALL
Java_sun_nio_fs_MacOSXWatchService_dispatchQueueCreate(JNIEnv* env, jobject watchService)
{
    jobject watchServiceGlobal = (*env)->NewGlobalRef(env, watchService);
    if (watchServiceGlobal == NULL) {
        return 0;
    }

    dispatch_queue_t queue = dispatch_queue_create("FSEvents", NULL);
    if (queue) {
        dispatch_queue_set_specific(queue, watchServiceKey, (void*)watchServiceGlobal, dispatchQueueDestructor);
    }
    return ptr_to_jlong(queue);
}

JNIEXPORT void JNICALL
Java_sun_nio_fs_MacOSXWatchService_dispatchQueueDestroy(__unused JNIEnv* env, __unused jclass clazz,
                                                        jlong dispatchQueuePtr)
{
    dispatch_queue_t queue = jlong_to_ptr(dispatchQueuePtr);
    dispatch_release(queue); // allow the queue to get deallocated
    // NB: the global reference to watchService stored with the queue will be deleted
    // by dispatchQueueDestructor()
}
