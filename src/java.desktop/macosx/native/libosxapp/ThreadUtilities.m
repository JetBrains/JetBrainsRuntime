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

#import "JNIUtilities.h"
#import "PropertiesUtilities.h"
#import "ThreadUtilities.h"


#define USE_LWC_LOG 1

#if USE_LWC_LOG == 1
    void lwc_plog(JNIEnv* env, const char *formatMsg, ...);
#endif

/* Returns the MainThread latency threshold in milliseconds
 * used to detect slow operations that may cause high latencies or delays.
 * If <=0, the MainThread monitor is disabled */
int getMainThreadLatencyThreshold() {
    static int latency = -1; // undefined
    if (latency == -1) {
        JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
        if (env == NULL) return NO;
        NSString *MTLatencyProp = [PropertiesUtilities javaSystemPropertyForKey:@"sun.awt.mac.mainThreadLatency"
                                                                          withEnv:env];
        // 0 means disabled:
        latency = (MTLatencyProp != nil) ? [MTLatencyProp intValue] : 0; // ms
    }
    return latency;
}

static const char* toCString(id obj) {
    return obj == nil ? "nil" : [NSString stringWithFormat:@"%@", obj].UTF8String;
}

// The following must be named "jvm", as there are extern references to it in AWT
JavaVM *jvm = NULL;
static BOOL isNSApplicationOwner = NO;
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

+ (void)setApplicationOwner:(BOOL)owner {
    isNSApplicationOwner = owner;
}

+ (JNIEnv*)getJNIEnv {
AWT_ASSERT_APPKIT_THREAD;
    if (isNSApplicationOwner) {
        if (appKitEnv == NULL) {
            attachCurrentThread((void **)&appKitEnv);
        }
        return appKitEnv;
    } else {
        return [ThreadUtilities getJNIEnvUncached];
    }
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
    const NSArray<NSString*> *symbols = NSThread.callStackSymbols;

    for (NSUInteger i = 2, len = symbols.count; i < len; i++) {
         NSString* symbol = symbols[i];
         if (![symbol hasPrefix: @"performOnMainThread"]) {
             return symbol;
         }
    }
    return nil;
}

+ (void)performOnMainThread:(SEL)aSelector on:(id)target withObject:(id)arg waitUntilDone:(BOOL)wait {
    const int mtThreshold = getMainThreadLatencyThreshold();
    if (mtThreshold <= 0.0) {
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
    } else {
        // Perform instrumentation on selector:
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
            const CFTimeInterval start = CACurrentMediaTime();
            if (blockingEDT) {
                setBlockingEventDispatchThread(YES);
            }
            @try {
                [target performSelector:aSelector withObject:arg];
            } @finally {
                if (blockingEDT) {
                    setBlockingEventDispatchThread(NO);
                }
                const double elapsedMs = (CACurrentMediaTime() - start) * 1000.0;
                if (elapsedMs > mtThreshold) {
#if USE_LWC_LOG == 1
                    lwc_plog([self getJNIEnv], "performOnMainThread(%s)[time: %.3lf ms]: [%s]", operation, elapsedMs, toCString(caller));
#else
                    NSLog(@"performOnMainThread(%s)[time: %.3lf ms]: [%@]", operation, elapsedMs, caller);
#endif
                }
            }
        });
        if (invokeDirect) {
            [self performSelector:@selector(invokeBlockCopy:) withObject:blockCopy];
        } else {
            [self performSelectorOnMainThread:@selector(invokeBlockCopy:) withObject:blockCopy waitUntilDone:wait modes:javaModes];
        }
    }
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

#if USE_LWC_LOG == 1
void lwc_plog(JNIEnv* env, const char *formatMsg, ...) {
    if (formatMsg == NULL)
        return;

    static jobject loggerObject = NULL;
    static jmethodID midWarn = NULL;

    if (loggerObject == NULL) {
        DECLARE_CLASS(lwctClass, "sun/lwawt/macosx/LWCToolkit");
        jfieldID fieldId = (*env)->GetStaticFieldID(env, lwctClass, "log", "Lsun/util/logging/PlatformLogger;");
        CHECK_NULL(fieldId);
        loggerObject = (*env)->GetStaticObjectField(env, lwctClass, fieldId);
        CHECK_NULL(loggerObject);
        loggerObject = (*env)->NewGlobalRef(env, loggerObject);
        jclass clazz = (*env)->GetObjectClass(env, loggerObject);
        if (clazz == NULL) {
            NSLog(@"lwc_plog: can't find PlatformLogger class");
            return;
        }
        GET_METHOD(midWarn, clazz, "warning", "(Ljava/lang/String;)V");
    }

    va_list args;
    va_start(args, formatMsg);
    const int bufSize = 512;
    char buf[bufSize];
    #pragma clang diagnostic push
    #pragma clang diagnostic ignored "-Wformat-nonliteral"
    vsnprintf(buf, bufSize, formatMsg, args);
    #pragma clang diagnostic pop
    va_end(args);

    jstring jstr = (*env)->NewStringUTF(env, buf);

    (*env)->CallVoidMethod(env, loggerObject, midWarn, jstr);
    (*env)->DeleteLocalRef(env, jstr);
}
#endif