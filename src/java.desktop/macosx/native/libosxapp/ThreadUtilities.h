/*
 * Copyright (c) 2011, 2025, Oracle and/or its affiliates. All rights reserved.
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

#ifndef __THREADUTILITIES_H
#define __THREADUTILITIES_H

#import <Foundation/Foundation.h>
#import <stdatomic.h>

#include "jni.h"

#import <pthread.h>

#import "AWT_debug.h"


// --------------------------------------------------------------------------
#ifndef PRODUCT_BUILD

// Turn on the AWT thread assert mechanism. See below for different variants.
// TODO: don't enable this for production builds...
#define AWT_THREAD_ASSERTS

#endif /* PRODUCT_BUILD */
// --------------------------------------------------------------------------

// --------------------------------------------------------------------------
#ifdef AWT_THREAD_ASSERTS

// Turn on to have awt thread asserts display a message on the console.
#define AWT_THREAD_ASSERTS_MESSAGES

// Turn on to have awt thread asserts use an environment variable switch to
// determine if assert should really be called.
//#define AWT_THREAD_ASSERTS_ENV_ASSERT

// Define AWT_THREAD_ASSERTS_WAIT to make asserts halt the asserting thread
// for debugging purposes.
//#define AWT_THREAD_ASSERTS_WAIT

#ifdef AWT_THREAD_ASSERTS_MESSAGES

#define AWT_THREAD_ASSERTS_NOT_APPKIT_MESSAGE \
    AWT_DEBUG_LOG(@"Not running on AppKit thread 0 when expected.")

#define AWT_THREAD_ASSERTS_ON_APPKIT_MESSAGE \
    AWT_DEBUG_LOG(@"Running on AppKit thread 0 when not expected.")

#ifdef AWT_THREAD_ASSERTS_ENV_ASSERT

extern int sAWTThreadAsserts;
#define AWT_THREAD_ASSERTS_ENV_ASSERT_CHECK    \
do {                                           \
    if (sAWTThreadAsserts) {                   \
        NSLog(@"\tPlease run this java program again with setenv COCOA_AWT_DISABLE_THREAD_ASSERTS to proceed with a warning."); \
        assert(NO);                            \
    }                                          \
} while (0)

#else

#define AWT_THREAD_ASSERTS_ENV_ASSERT_CHECK do {} while (0)

#endif /* AWT_THREAD_ASSERTS_ENV_ASSERT */

#define AWT_ASSERT_APPKIT_THREAD               \
do {                                           \
    if (pthread_main_np() == 0) {              \
        AWT_THREAD_ASSERTS_NOT_APPKIT_MESSAGE; \
        AWT_DEBUG_BUG_REPORT_MESSAGE;          \
        AWT_THREAD_ASSERTS_ENV_ASSERT_CHECK;   \
    }                                          \
} while (0)

#define AWT_ASSERT_NOT_APPKIT_THREAD           \
do {                                           \
    if (pthread_main_np() != 0) {              \
        AWT_THREAD_ASSERTS_ON_APPKIT_MESSAGE;  \
        AWT_DEBUG_BUG_REPORT_MESSAGE;          \
        AWT_THREAD_ASSERTS_ENV_ASSERT_CHECK;   \
    }                                          \
} while (0)

#endif /* AWT_THREAD_ASSERTS_MESSAGES */

#ifdef AWT_THREAD_ASSERTS_WAIT

#define AWT_ASSERT_APPKIT_THREAD      \
do {                                  \
    while (pthread_main_np() == 0) {} \
} while (0)

#define AWT_ASSERT_NOT_APPKIT_THREAD  \
do {                                  \
    while (pthread_main_np() != 0) {} \
} while (0)

#endif /* AWT_THREAD_ASSERTS_WAIT */

#else /* AWT_THREAD_ASSERTS */

#define AWT_ASSERT_APPKIT_THREAD     do {} while (0)
#define AWT_ASSERT_NOT_APPKIT_THREAD do {} while (0)

#endif /* AWT_THREAD_ASSERTS */
// --------------------------------------------------------------------------

/* bit flag to coalesce CGDisplayReconfigureCallbacks */
#define MAIN_CALLBACK_CGDISPLAY_RECONFIGURE  1

/* log given message (with thread call stack) */
#define NSAPP_AWT_LOG_MESSAGE(message) \
    [ThreadUtilities logMessage:message file:__FILE__ line:__LINE__ function:__FUNCTION__]

/* log given exception (ignored or explicitely muted) */
#define NSAPP_AWT_LOG_EXCEPTION(exception) \
    [ThreadUtilities logException:exception file:__FILE__ line:__LINE__ function:__FUNCTION__]

#define NSAPP_AWT_LOG_EXCEPTION_PREFIX(exception, prefixValue) \
    [ThreadUtilities logException:exception prefix:prefixValue file:__FILE__ line:__LINE__ function:__FUNCTION__]

/* report the given exception (ignored or explicitely muted), may crash the JVM (JNU_Fatal) */
#define NSAPP_AWT_REPORT_EXCEPTION(exception, uncaughtFlag) \
    [ThreadUtilities reportException:exception uncaught:uncaughtFlag file:__FILE__ line:__LINE__ function:__FUNCTION__]

/* CFRelease wrapper that ignores NULL argument */
JNIEXPORT void CFRelease_even_NULL(CFTypeRef cf);

/* Return true if uncaught exceptions should crash JVM (JNU_Fatal) */
BOOL shouldCrashOnException();

/* Get AWT's NSUncaughtExceptionHandler */
JNIEXPORT NSUncaughtExceptionHandler* GetAWTUncaughtExceptionHandler(void);

@interface RunLoopCallbackQueue : NSObject

@property(readwrite, atomic) u_long coalesingflags;
@property(retain) NSMutableArray* queue;

+ (RunLoopCallbackQueue*) shared;

- (id) init;
- (void) dealloc;

- (BOOL)hasCallback:(u_long)bit;

- (BOOL)addCallback:(u_long)bit block:(void (^)())block;
- (void)processQueuedCallbacks;
@end


@interface ThreadTraceContext : NSObject <NSCopying>

@property (readwrite, atomic) BOOL sleep;
@property (readwrite, atomic) BOOL useJavaModes;
@property (readwrite, atomic) long actionId;
@property (readwrite, atomic) const char* operation;
@property (readwrite, atomic) CFTimeInterval timestamp;
@property (readwrite, atomic, retain) NSString* threadName;
@property (readwrite, atomic, retain) NSString* caller;
@property (readwrite, atomic, retain) NSString* callStack;

/* autorelease in init and copy */
- (id)init:(NSString*)threadName;
- (void)reset;
- (void)updateThreadState:(BOOL)sleepValue;

- (void)set:(long)pActionId operation:(const char*)pOperation useJavaModes:(BOOL)pUseJavaModes
            caller:(NSString *)pCaller callstack:(NSString *)pCallStack;

- (const char*)identifier;
@end


__attribute__((visibility("default")))
@interface ThreadUtilities : NSObject { } /* Extend NSObject so can call performSelectorOnMainThread */

/*
 * When a blocking performSelectorOnMainThread is executed from the EventDispatch thread,
 * and the executed code triggers an opposite blocking a11y call (via LWCToolkit.invokeAndWait)
 * this is a deadlock case, and then this property is used to discard LWCToolkit.invokeAndWait.
 */
@property (class, nonatomic, readonly) BOOL blockingEventDispatchThread;

+ (void (^)()) GetEmptyBlock;

+ (void) reportException:(NSException *)exception;
+ (void) reportException:(NSException *)exception uncaught:(BOOL)uncaught
                    file:(const char*)file line:(int)line function:(const char*)function;

+ (void) logException:(NSException *)exception;
+ (void) logException:(NSException *)exception
                 file:(const char*)file line:(int)line function:(const char*)function;
+ (void) logException:(NSException *)exception prefix:(NSString *)prefix
                 file:(const char*)file line:(int)line function:(const char*)function;

+ (void) logMessage:(NSString *)message;
+ (void) logMessage:(NSString *)message
               file:(const char*)file line:(int)line function:(const char*)function;

+ (JNIEnv*)getJNIEnv;
+ (JNIEnv*)getJNIEnvUncached;

+ (void)detachCurrentThread;
+ (void)setAppkitThreadGroup:(jobject)group;
+ (void)setApplicationOwner:(BOOL)owner;

+ (void)performOnMainThreadWaiting:(BOOL)wait block:(void (^)())block;
+ (void)performOnMainThread:(SEL)aSelector on:(id)target withObject:(id)arg waitUntilDone:(BOOL)wait;

/* internal use with care to specify which RunLoopMode to use high priority queue (default run mode) or java queue */
+ (void)performOnMainThreadNowOrLater:(BOOL)useJavaModes block:(void (^)())block;
+ (void)performOnMainThreadWaiting:(BOOL)wait useJavaModes:(BOOL)useJavaModes block:(void (^)())block;
+ (void)performOnMainThread:(SEL)aSelector on:(id)target withObject:(id)arg waitUntilDone:(BOOL)wait useJavaModes:(BOOL)useJavaModes;

+ (NSString*)criticalRunLoopMode;
+ (NSString*)javaRunLoopMode;

+ (void)setBlockingMainThread:(BOOL)value;
+ (BOOL)blockingMainThread;

/* internal special RunLoop callbacks */
+ (void)registerMainThreadRunLoopCallback:(u_long)coalesingBit block:(void (^)())block;

/* native thread tracing */
+ (ThreadTraceContext*)getTraceContext;
+ (void)removeTraceContext;
+ (void)resetTraceContext;

+ (ThreadTraceContext*)recordTraceContext;
+ (ThreadTraceContext*)recordTraceContext:(NSString*)prefix;
+ (ThreadTraceContext*)recordTraceContext:(NSString*)prefix actionId:(long)actionId useJavaModes:(BOOL)useJavaModes operation:(const char*)pOperation;

+ (void)dumpThreadTraceContext:(const char*)pOperation;

+ (NSString*)getThreadTraceContexts;
@end

JNIEXPORT void OSXAPP_SetJavaVM(JavaVM *vm);

/* LWCToolkit's PlatformLogger wrapper */
JNIEXPORT void lwc_plog(JNIEnv* env, const char *formatMsg, ...);

#endif /* __THREADUTILITIES_H */
