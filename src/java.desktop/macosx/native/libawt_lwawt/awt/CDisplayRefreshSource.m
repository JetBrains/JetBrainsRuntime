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

#import <Cocoa/Cocoa.h>
#import <CoreVideo/CVDisplayLink.h>
#import <dispatch/dispatch.h>
#import <mach/mach_time.h>
#import <stdatomic.h>
#import <stdlib.h>

#import "ThreadUtilities.h"
#include "JNIUtilities.h"

#define NO_REFRESH ((jlong) 0x8000000000000000LL)

typedef struct {
    CVDisplayLinkRef displayLink;
    // the callback signals it once per refresh
    dispatch_semaphore_t refreshes;
    // the values of the last refresh
    _Atomic uint64_t outputHostTime;
    _Atomic int64_t refreshPeriodNanos;
} DisplayRefreshLink;

static mach_timebase_info_data_t timebase;

static int64_t hostTimeToNanos(int64_t hostTime) {
    return hostTime * (int64_t) timebase.numer / (int64_t) timebase.denom;
}

// It runs on the thread of the link and makes no JNI call, so that thread never attaches to the JVM.
static CVReturn displayRefreshCallback(CVDisplayLinkRef displayLink, const CVTimeStamp *now,
                                       const CVTimeStamp *outputTime, CVOptionFlags flagsIn,
                                       CVOptionFlags *flagsOut, void *context) {
    DisplayRefreshLink *link = (DisplayRefreshLink *) context;
    const int64_t refreshPeriodNanos = outputTime->videoTimeScale > 0
            ? outputTime->videoRefreshPeriod * 1000000000LL / outputTime->videoTimeScale
            : 0;
    atomic_store(&link->outputHostTime, outputTime->hostTime);
    atomic_store(&link->refreshPeriodNanos, refreshPeriodNanos);
    dispatch_semaphore_signal(link->refreshes);
    return kCVReturnSuccess;
}

/*
 * Class:     sun_awt_CDisplayRefreshSource
 * Method:    nativeObserveWake
 * Signature: ()V
 *
 * A link that ran before the sleep of the system or of the displays can stay silent after it,
 * so the Java side starts each link anew. The class initialization calls it once.
 */
JNIEXPORT void JNICALL
Java_sun_awt_CDisplayRefreshSource_nativeObserveWake(JNIEnv *env, jclass cls) {
    jmethodID onWake = (*env)->GetStaticMethodID(env, cls, "onWake", "()V");
    if (onWake == NULL) {
        return;
    }
    jclass globalCls = (*env)->NewGlobalRef(env, cls);
    if (globalCls == NULL) {
        return;
    }
JNI_COCOA_ENTER(env);
    NSNotificationCenter *center = [[NSWorkspace sharedWorkspace] notificationCenter];
    for (NSNotificationName name in @[NSWorkspaceDidWakeNotification, NSWorkspaceScreensDidWakeNotification]) {
        [center addObserverForName:name object:nil queue:[NSOperationQueue mainQueue]
                        usingBlock:^(NSNotification *notification) {
            JNIEnv *mainEnv = [ThreadUtilities getJNIEnvUncached];
            if (mainEnv == NULL) {
                return;
            }
            (*mainEnv)->CallStaticVoidMethod(mainEnv, globalCls, onWake);
            if ((*mainEnv)->ExceptionCheck(mainEnv)) {
                (*mainEnv)->ExceptionDescribe(mainEnv);
                (*mainEnv)->ExceptionClear(mainEnv);
            }
        }];
    }
JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_awt_CDisplayRefreshSource
 * Method:    nativeOpen
 * Signature: (I)J
 */
JNIEXPORT jlong JNICALL
Java_sun_awt_CDisplayRefreshSource_nativeOpen(JNIEnv *env, jclass cls, jint displayID) {
    static dispatch_once_t timebaseOnce;
    dispatch_once(&timebaseOnce, ^{
        mach_timebase_info(&timebase);
    });

    DisplayRefreshLink *link = calloc(1, sizeof(DisplayRefreshLink));
    if (link == NULL) {
        return 0;
    }
    if (CVDisplayLinkCreateWithCGDisplay((CGDirectDisplayID) displayID, &link->displayLink) != kCVReturnSuccess) {
        free(link);
        return 0;
    }
    link->refreshes = dispatch_semaphore_create(0);
    if (link->refreshes == NULL
        || CVDisplayLinkSetOutputCallback(link->displayLink, &displayRefreshCallback, link) != kCVReturnSuccess
        || CVDisplayLinkStart(link->displayLink) != kCVReturnSuccess) {
        CVDisplayLinkRelease(link->displayLink);
        if (link->refreshes != NULL) {
            dispatch_release(link->refreshes);
        }
        free(link);
        return 0;
    }
    return ptr_to_jlong(link);
}

/*
 * Class:     sun_awt_CDisplayRefreshSource
 * Method:    nativeWaitForRefresh
 * Signature: (JJ)J
 */
JNIEXPORT jlong JNICALL
Java_sun_awt_CDisplayRefreshSource_nativeWaitForRefresh(JNIEnv *env, jclass cls, jlong ptr, jlong timeoutNanos) {
    DisplayRefreshLink *link = (DisplayRefreshLink *) jlong_to_ptr(ptr);
    if (dispatch_semaphore_wait(link->refreshes, dispatch_time(DISPATCH_TIME_NOW, timeoutNanos)) != 0) {
        return NO_REFRESH;
    }
    // the refreshes that came while the Java side was busy merge into the last one
    while (dispatch_semaphore_wait(link->refreshes, DISPATCH_TIME_NOW) == 0) {
    }
    // hostTime is in mach_absolute_time units; the Java side adds the delay to System.nanoTime()
    const int64_t hostDelta = (int64_t) atomic_load(&link->outputHostTime) - (int64_t) mach_absolute_time();
    return (jlong) hostTimeToNanos(hostDelta);
}

/*
 * Class:     sun_awt_CDisplayRefreshSource
 * Method:    nativeRefreshPeriod
 * Signature: (J)J
 */
JNIEXPORT jlong JNICALL
Java_sun_awt_CDisplayRefreshSource_nativeRefreshPeriod(JNIEnv *env, jclass cls, jlong ptr) {
    DisplayRefreshLink *link = (DisplayRefreshLink *) jlong_to_ptr(ptr);
    return (jlong) atomic_load(&link->refreshPeriodNanos);
}

/*
 * Class:     sun_awt_CDisplayRefreshSource
 * Method:    nativeClose
 * Signature: (J)V
 */
JNIEXPORT void JNICALL
Java_sun_awt_CDisplayRefreshSource_nativeClose(JNIEnv *env, jclass cls, jlong ptr) {
    DisplayRefreshLink *link = (DisplayRefreshLink *) jlong_to_ptr(ptr);
    // CVDisplayLinkStop returns after a running callback ends, and the callback never blocks
    CVDisplayLinkStop(link->displayLink);
    CVDisplayLinkRelease(link->displayLink);
    dispatch_release(link->refreshes);
    free(link);
}
