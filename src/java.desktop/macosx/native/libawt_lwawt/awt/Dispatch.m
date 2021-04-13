/*
 * Copyright (c) 2011, 2015, Oracle and/or its affiliates. All rights reserved.
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

/*
 * Must include this before JavaNativeFoundation.h to get jni.h from build
 */
#include "jni.h"
#include "jni_util.h"

#import "sun_lwawt_macosx_concurrent_LibDispatchNative.h"

#import <dispatch/dispatch.h>
#import <ThreadUtilities.h>
#import "JNIUtilities.h"

/*
 * Declare library specific JNI_Onload entry if static build
 */
DEF_STATIC_JNI_OnLoad

/*
 * Class:     sun_lwawt_macosx_concurrent_LibDispatchNative
 * Method:    nativeIsDispatchSupported
 * Signature: ()Z
 */
JNIEXPORT jboolean JNICALL Java_sun_lwawt_macosx_concurrent_LibDispatchNative_nativeIsDispatchSupported
(JNIEnv *env, jclass clazz)
{
        return JNI_TRUE;
}


/*
 * Class:     sun_lwawt_macosx_concurrent_LibDispatchNative
 * Method:    nativeGetMainQueue
 * Signature: ()J
 */
JNIEXPORT jlong JNICALL Java_sun_lwawt_macosx_concurrent_LibDispatchNative_nativeGetMainQueue
(JNIEnv *env, jclass clazz)
{
        dispatch_queue_t queue = dispatch_get_main_queue();
        return ptr_to_jlong(queue);
}


/*
 * Class:     sun_lwawt_macosx_concurrent_LibDispatchNative
 * Method:    nativeCreateConcurrentQueue
 * Signature: (I)J
 */
JNIEXPORT jlong JNICALL Java_sun_lwawt_macosx_concurrent_LibDispatchNative_nativeCreateConcurrentQueue
(JNIEnv *env, jclass clazz, jint priority)
{
        dispatch_queue_t queue = dispatch_get_global_queue((long)priority, 0);
        return ptr_to_jlong(queue);
}


/*
 * Class:     sun_lwawt_macosx_concurrent_LibDispatchNative
 * Method:    nativeCreateSerialQueue
 * Signature: (Ljava/lang/String;)J
 */
JNIEXPORT jlong JNICALL Java_sun_lwawt_macosx_concurrent_LibDispatchNative_nativeCreateSerialQueue
(JNIEnv *env, jclass clazz, jstring name)
{
        if (name == NULL) return 0L;

        jboolean isCopy;
        const char *queue_name = (*env)->GetStringUTFChars(env, name, &isCopy);
        dispatch_queue_t queue = dispatch_queue_create(queue_name, NULL);
        (*env)->ReleaseStringUTFChars(env, name, queue_name);

        return ptr_to_jlong(queue);
}


/*
 * Class:     sun_lwawt_macosx_concurrent_LibDispatchNative
 * Method:    nativeReleaseQueue
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_concurrent_LibDispatchNative_nativeReleaseQueue
(JNIEnv *env, jclass clazz, jlong nativeQueue)
{
        if (nativeQueue == 0L) return;
        dispatch_release((dispatch_queue_t)jlong_to_ptr(nativeQueue));
}



static void perform_dispatch(JNIEnv *env, jlong nativeQueue, jobject runnable, void (*dispatch_fxn)(dispatch_queue_t, dispatch_block_t))
{
JNI_COCOA_ENTER(env);
        DECLARE_CLASS(jc_Runnable, "java/lang/Runnable");
        DECLARE_METHOD(jm_run, jc_Runnable, "run", "()V");

        dispatch_queue_t queue = (dispatch_queue_t)jlong_to_ptr(nativeQueue);
        if (queue == NULL) return; // shouldn't happen

        // create a global-ref around the Runnable, so it can be safely passed to the dispatch thread
        jobject runnableRef = (*env)->NewGlobalRef(env, runnable);
        dispatch_fxn(queue, ^{
            // attach the dispatch thread to the JVM if necessary, and get an env
            JNIEnv*      blockEnv = [ThreadUtilities getJNIEnvUncached];

            JNI_COCOA_ENTER(blockEnv);
                // call the user's runnable
                (*blockEnv)->CallVoidMethod(blockEnv, runnableRef, jm_run);
                (*blockEnv)->DeleteGlobalRef(blockEnv, runnableRef);
            JNI_COCOA_EXIT(blockEnv);
        });

JNI_COCOA_EXIT(env);
}


/*
 * Class:     sun_lwawt_macosx_concurrent_LibDispatchNative
 * Method:    nativeExecuteAsync
 * Signature: (JLjava/lang/Runnable;)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_concurrent_LibDispatchNative_nativeExecuteAsync
(JNIEnv *env, jclass clazz, jlong nativeQueue, jobject runnable)
{
        // enqueues and returns
        perform_dispatch(env, nativeQueue, runnable, dispatch_async);
}


/*
 * Class:     sun_lwawt_macosx_concurrent_LibDispatchNative
 * Method:    nativeExecuteSync
 * Signature: (JLjava/lang/Runnable;)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_concurrent_LibDispatchNative_nativeExecuteSync
(JNIEnv *env, jclass clazz, jlong nativeQueue, jobject runnable)
{
        // blocks until the Runnable completes
        perform_dispatch(env, nativeQueue, runnable, dispatch_sync);
}
