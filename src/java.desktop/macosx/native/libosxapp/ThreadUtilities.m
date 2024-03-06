/*
 * Copyright (c) 2011, 2024, Oracle and/or its affiliates. All rights reserved.
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

#import <AppKit/AppKit.h>
#import <objc/message.h>
#include <mach/mach_time.h>

#import "ThreadUtilities.h"


/*
 * Enable the MainThread task monitor to detect slow tasks that may cause high latencies or delays
 */
#define CHECK_MAIN_THREAD 0
#define TRACE_MAIN_THREAD 0

// ~ 2ms is already high for main thread:
#define LATENCY_HIGH_THRESHOLD 2.0

#define DECORATE_MAIN_THREAD (TRACE_MAIN_THREAD || CHECK_MAIN_THREAD)


// The following must be named "jvm", as there are extern references to it in AWT
JavaVM *jvm = NULL;
static JNIEnv *appKitEnv = NULL;
static jobject appkitThreadGroup = NULL;
static NSString* JavaRunLoopMode = @"AWTRunLoopMode";
static NSArray<NSString*> *javaModes = nil;

static inline void attachCurrentThread(void** env) {
    if ([NSThread isMainThread]) {
        JavaVMAttachArgs args;
        args.version = JNI_VERSION_1_4;
        args.name = "AppKit Thread";
        args.group = appkitThreadGroup;
        (*jvm)->AttachCurrentThreadAsDaemon(jvm, env, &args);
    } else {
        (*jvm)->AttachCurrentThreadAsDaemon(jvm, env, NULL);
    }
}

@implementation ThreadUtilities

static BOOL _blockingEventDispatchThread = NO;
static long eventDispatchThreadPtr = (long)nil;

static BOOL isEventDispatchThread() {
    return (long)[NSThread currentThread] == eventDispatchThreadPtr;
}

// The [blockingEventDispatchThread] property is readonly, so we implement a private setter
static void setBlockingEventDispatchThread(BOOL value) {
    assert([NSThread isMainThread]);
    _blockingEventDispatchThread = value;
}

+ (BOOL) blockingEventDispatchThread {
    assert([NSThread isMainThread]);
    return _blockingEventDispatchThread;
}

+ (void)initialize {
    /* All the standard modes plus ours */
    javaModes = [[NSArray alloc] initWithObjects:NSDefaultRunLoopMode,
                                           NSModalPanelRunLoopMode,
                                           NSEventTrackingRunLoopMode,
                                           JavaRunLoopMode,
                                           nil];
}

+ (JNIEnv*)getJNIEnv {
AWT_ASSERT_APPKIT_THREAD;
    if (appKitEnv == NULL) {
        attachCurrentThread((void **)&appKitEnv);
    }
    return appKitEnv;
}

+ (JNIEnv*)getJNIEnvUncached {
    JNIEnv *env = NULL;
    attachCurrentThread((void **)&env);
    return env;
}

+ (void)detachCurrentThread {
    (*jvm)->DetachCurrentThread(jvm);
}

+ (void)setAppkitThreadGroup:(jobject)group {
    appkitThreadGroup = group;
}

/*
 * When running a block where either we don't wait, or it needs to run on another thread
 * we need to copy it from stack to heap, use the copy in the call and release after use.
 * Do this only when we must because it could be expensive.
 * Note : if waiting cross-thread, possibly the stack allocated copy is accessible ?
 */
+ (void)invokeBlockCopy:(void (^)(void))blockCopy {
  blockCopy();
  Block_release(blockCopy);
}

+ (void)performOnMainThreadNowOrLater:(void (^)())block {
    if ([NSThread isMainThread]) {
        block();
    } else {
        [self performOnMainThread:@selector(invokeBlockCopy:) on:self withObject:Block_copy(block) waitUntilDone:NO];
    }
}

+ (void)performOnMainThreadWaiting:(BOOL)wait block:(void (^)())block {
    if ([NSThread isMainThread] && wait) {
        block();
    } else {
        [self performOnMainThread:@selector(invokeBlockCopy:) on:self withObject:Block_copy(block) waitUntilDone:wait];
    }
}

+ (NSString*)getCaller {
#if DECORATE_MAIN_THREAD > 0
    const NSArray<NSString*> *symbols = NSThread.callStackSymbols;

    for (NSUInteger i = 2, len = [symbols count]; i < len; i++) {
         NSString* symbol = [symbols objectAtIndex:i];
         if (![symbol hasPrefix: @"performOnMainThread"]) {
             return symbol;
         }
    }
#endif
    return nil;
}

+ (void)performOnMainThread:(SEL)aSelector on:(id)target withObject:(id)arg waitUntilDone:(BOOL)wait {
#if DECORATE_MAIN_THREAD == 0
    if ([NSThread isMainThread] && wait) {
        [target performSelector:aSelector withObject:arg];
    } else if (wait && isEventDispatchThread()) {
        void (^blockCopy)(void) = Block_copy(^() {
            setBlockingEventDispatchThread(YES);
            @try {
                [target performSelector:aSelector withObject:arg];
            } @finally {
                setBlockingEventDispatchThread(NO);
            }
        });
        [self performSelectorOnMainThread:@selector(invokeBlockCopy:) withObject:blockCopy waitUntilDone:YES modes:javaModes];
    } else {
        [target performSelectorOnMainThread:aSelector withObject:arg waitUntilDone:wait modes:javaModes];
    }
#else
    // Perform instrumentation on selector:
    static mach_timebase_info_data_t* timebase = nil;
    if (timebase == nil) {
        timebase = malloc(sizeof (mach_timebase_info_data_t));
        mach_timebase_info(timebase);
    }

    const NSString* caller = [self getCaller];
    BOOL invokeDirect = NO;
    BOOL blockingEDT;

    if ([NSThread isMainThread] && wait) {
        invokeDirect = YES;
        blockingEDT = NO;
    } else if (wait && isEventDispatchThread()) {
        blockingEDT = YES;
    } else {
        blockingEDT = NO;
    }
    const char* operation = (invokeDirect ? "now  " : (blockingEDT ? "block" : "later"));

    void (^blockCopy)(void) = Block_copy(^(){
        const uint64_t start = mach_absolute_time();

        if (TRACE_MAIN_THREAD) {
            NSLog(@"performOnMainThread(%s)[before block]: [%@]", operation, caller);
        }

        if (blockingEDT) {
            setBlockingEventDispatchThread(YES);
        }
        @try {
            [target performSelector:aSelector withObject:arg];
        } @finally {
            if (blockingEDT) {
                setBlockingEventDispatchThread(NO);
            }

            const double elapsedMs = ((mach_absolute_time() - start) * timebase->numer) / (1000000.0 * timebase->denom);

            if (TRACE_MAIN_THREAD) {
                NSLog(@"performOnMainThread(%s)[after block - time: %.3lf ms]: [%@]", operation, elapsedMs, caller);
            }

            if (CHECK_MAIN_THREAD && (elapsedMs >= LATENCY_HIGH_THRESHOLD)) {
                NSLog(@"performOnMainThread(%s)[time: %.3lf ms]: [%@]", operation, elapsedMs, caller);
            }
        }
    });
    if (invokeDirect) {
        [self performSelector:@selector(invokeBlockCopy:) withObject:blockCopy];
    } else {
        [self performSelectorOnMainThread:@selector(invokeBlockCopy:) withObject:blockCopy waitUntilDone:wait modes:javaModes];
    }
#endif
}

+ (NSString*)javaRunLoopMode {
    return JavaRunLoopMode;
}

@end


void OSXAPP_SetJavaVM(JavaVM *vm)
{
    jvm = vm;
}

/*
 * Class:     sun_lwawt_macosx_CThreading
 * Method:    isMainThread
 * Signature: ()Z
 */
JNIEXPORT jboolean JNICALL Java_sun_lwawt_macosx_CThreading_isMainThread
  (JNIEnv *env, jclass c)
{
    return [NSThread isMainThread];
}

/*
 * Class:     sun_awt_AWTThreading
 * Method:    notifyEventDispatchThreadStartedNative
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_sun_awt_AWTThreading_notifyEventDispatchThreadStartedNative
  (JNIEnv *env, jclass c)
{
    @synchronized([ThreadUtilities class]) {
        eventDispatchThreadPtr = (long)[NSThread currentThread];
    }
}

