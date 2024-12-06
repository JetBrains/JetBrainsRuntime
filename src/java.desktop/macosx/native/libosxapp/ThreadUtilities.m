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
#import <stdatomic.h>

#import "JNIUtilities.h"
#import "PropertiesUtilities.h"
#import "ThreadUtilities.h"


/* Returns the MainThread latency threshold in milliseconds
 * used to detect slow operations that may cause high latencies or delays.
 * If negative, the MainThread monitor is disabled */
int getMainThreadLatencyThreshold() {
    static int latency = INT_MIN; // undefined
    if (latency == INT_MIN) {
        JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
        if (env == NULL) return NO;
        NSString *MTLatencyProp = [PropertiesUtilities javaSystemPropertyForKey:@"sun.awt.mac.mainThreadLatency"
                                                                        withEnv:env];
        if (MTLatencyProp != nil) {
            latency = [MTLatencyProp intValue];
        }
        // 0 means no threshold:
        if (latency < 0) {
            latency = -1; // disabled
        }
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
static NSString* CriticalRunLoopMode = @"AWTCriticalRunLoopMode";
static NSString* JavaRunLoopMode = @"AWTRunLoopMode";
static NSArray<NSString*> *javaModes = nil;
static NSArray<NSString*> *allModesExceptJava = nil;

/* Traceability data */
static const BOOL enableTracing = NO;
static const BOOL enableCallStacks = NO;
static const BOOL enableRunLoopObserver = NO;

/* RunLoop traceability identifier generators */
static atomic_long runLoopId = 0L;
static atomic_long mainThreadActionId = 0L;

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
    /* All the standard modes plus the Critical mode */
    allModesExceptJava = [[NSArray alloc] initWithObjects:NSRunLoopCommonModes,
                                                          NSModalPanelRunLoopMode,
                                                          NSEventTrackingRunLoopMode,
                                                          CriticalRunLoopMode,
                                                          nil];

    /* All the standard modes plus Critical and Java modes */
    javaModes = [[NSArray alloc] initWithObjects:NSRunLoopCommonModes,
                                           NSModalPanelRunLoopMode,
                                           NSEventTrackingRunLoopMode,
                                           CriticalRunLoopMode,
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

    if (enableTracing) {
        // Record thread stack now and return another copy (auto-released):
        [ThreadUtilities recordTraceContext];

        if (enableRunLoopObserver) {
            CFRunLoopObserverRef logObserver = CFRunLoopObserverCreateWithHandler(
                NULL,                        // CFAllocator
                kCFRunLoopAllActivities,     // CFOptionFlags
                true,                        // repeats
                NSIntegerMin,                // order = max priority
                ^(CFRunLoopObserverRef observer, CFRunLoopActivity activity) {
                    if ([[NSThread currentThread] isMainThread]) {
                        char* activityName = NULL;
                        switch (activity) {
                            default:
                                break;
                            case kCFRunLoopEntry:
                                activityName = "RunLoopEntry";
                                /* Increment global main RunLoop id */
                                runLoopId++;
                                break;
                            case kCFRunLoopBeforeTimers:
                                activityName = "RunLoopBeforeTimers";
                                break;
                            case kCFRunLoopBeforeSources :
                                activityName = "RunLoopBeforeSources";
                                break;
                            case kCFRunLoopBeforeWaiting:
                                activityName = "RunLoopBeforeWaiting";
                                break;
                            case kCFRunLoopAfterWaiting:
                                activityName = "RunLoopAfterWaiting";
                                break;
                            case kCFRunLoopExit:
                                activityName = "RunLoopExit";
                                break;
                            case kCFRunLoopAllActivities:
                                activityName = "RunLoopAllActivities";
                                break;
                        }
                        if (activityName != NULL) {
                            NSLog(@"RunLoop[on %s][%lu]: processing %s on mode = '%@'",
                                  NSThread.currentThread.name.UTF8String, runLoopId, activityName,
                                  NSRunLoop.currentRunLoop.currentMode);
                        }
                    }
                }
            );

            CFRunLoopRef runLoop = [[NSRunLoop mainRunLoop] getCFRunLoop];
            CFRunLoopAddObserver(runLoop, logObserver, kCFRunLoopDefaultMode);

            CFStringRef criticalModeRef = (__bridge CFStringRef)CriticalRunLoopMode;
            CFRunLoopAddObserver(runLoop, logObserver, criticalModeRef);

            CFStringRef javaModeRef = (__bridge CFStringRef)JavaRunLoopMode;
            CFRunLoopAddObserver(runLoop, logObserver, javaModeRef);

            CFRelease(javaModeRef);
            CFRelease(criticalModeRef);
            CFRelease(logObserver);
        }
    }
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

+ (NSString*)getCaller:(NSString*)prefixSymbol {
    const NSArray<NSString*> *symbols = NSThread.callStackSymbols;

    for (NSUInteger i = 2, len = symbols.count; i < len; i++) {
         NSString* symbol = symbols[i];
         if (![symbol containsString: @"performOnMainThread"]
             && ((prefixSymbol == nil) || ![symbol containsString: prefixSymbol])) {
             return [symbol retain];
         }
    }
    return nil;
}

+ (NSString*)getCallerStack:(NSString*)prefixSymbol {
    const NSArray<NSString*> *symbols = NSThread.callStackSymbols;

    int pos = -1;
    for (NSUInteger i = 2, len = symbols.count; i < len; i++) {
         NSString* symbol = symbols[i];
         if (![symbol containsString: @"performOnMainThread"]
             && ((prefixSymbol == nil) || ![symbol containsString: prefixSymbol])) {
             pos = i;
             break;
         }
    }
    if (pos != -1) {
        const NSRange theRange = NSMakeRange(pos, symbols.count - pos);
        const NSArray<NSString*> *filteredSymbols = [symbols subarrayWithRange:theRange];
        return [[filteredSymbols componentsJoinedByString:@"\n"] retain];
    }
    return nil;
}

/*
 * macOS internal use case to perform appkit actions from native code (ASYNC)
 */
+ (void)performOnMainThreadNowOrLater:(BOOL)useJavaModes
                                block:(void (^)())block
{
    if ([NSThread isMainThread]) {
        block();
    } else {
        [ThreadUtilities performOnMainThread:@selector(invokeBlockCopy:) on:self withObject:Block_copy(block)
                               waitUntilDone:NO useJavaModes:useJavaModes];
    }
}

/*
 * Main AWT performOnMainThread(block) use case to perform appkit actions from EDT
 */
+ (void)performOnMainThreadWaiting:(BOOL)wait
                             block:(void (^)())block
{
    [ThreadUtilities performOnMainThreadWaiting:wait useJavaModes:YES block:block];
}

/*
 * macOS internal performOnMainThread(block) use case to perform appkit actions from native code (ASYNC or SYNC if wait=YES)
 */
+ (void)performOnMainThreadWaiting:(BOOL)wait
                      useJavaModes:(BOOL)useJavaModes
                             block:(void (^)())block
{
    if ([NSThread isMainThread] && wait) {
        block();
    } else {
        [ThreadUtilities performOnMainThread:@selector(invokeBlockCopy:) on:self withObject:Block_copy(block)
                               waitUntilDone:wait useJavaModes:useJavaModes];
    }
}

/*
 * Main AWT performOnMainThread(selector) use case to perform appkit actions from EDT
 */
+ (void)performOnMainThread:(SEL)aSelector
                         on:(id)target
                 withObject:(id)arg
              waitUntilDone:(BOOL)wait
{
    [ThreadUtilities performOnMainThread:aSelector on:target withObject:arg
                           waitUntilDone:wait useJavaModes:YES];
}

/*
 * Internal implementation that always perform given selector
 * on appkit/main thread to ensure sequential ordering execution.
 *
 * This implementation uses fast-path if MainThread latency threshold < 0;
 * slow-path with tracing and monitoring otherwise.
 *
 * useJavaModes=NO will use the CriticalRunLoopMode
 * to ensure the execution of the given selector AS SOON AS POSSIBLE
 * i.e. during the next RunLoop run() with the lowest possible latency.
 */
+ (void)performOnMainThread:(SEL)aSelector
                         on:(id)target
                 withObject:(id)arg
              waitUntilDone:(BOOL)wait
               useJavaModes:(BOOL)useJavaModes
{
    const int mtThreshold = getMainThreadLatencyThreshold();

    if (mtThreshold < 0) {
        const BOOL invokeDirect = ([NSThread isMainThread] && wait);

        // Fast Path:
        if (invokeDirect) {
            [target performSelector:aSelector withObject:arg];
        } else {
            NSArray<NSString*> *runLoopModes = (useJavaModes) ? javaModes : allModesExceptJava;
            if (wait && isEventDispatchThread()) {
                void (^blockCopy)(void) = Block_copy(^() {
                    setBlockingEventDispatchThread(YES);
                    @try {
                        [target performSelector:aSelector withObject:arg];
                    } @finally {
                        setBlockingEventDispatchThread(NO);
                    }
                });
                [ThreadUtilities performSelectorOnMainThread:@selector(invokeBlockCopy:) withObject:blockCopy waitUntilDone:wait modes:runLoopModes];
            } else {
                [target performSelectorOnMainThread:aSelector withObject:arg waitUntilDone:wait modes:runLoopModes];
            }
        }
    } else {
        // Slow path:
        [ThreadUtilities performOnMainThreadWithTracing:aSelector on:target withObject:arg waitUntilDone:wait useJavaModes:useJavaModes];
    }
}

/*
 * Internal implementation that always perform given selector
 * on appkit/main thread to ensure sequential ordering execution.
 *
 * This implementation monitors the selector execution (tracing).
 *
 * useJavaModes=NO will use the CriticalRunLoopMode
 * to ensure the execution of the given selector AS SOON AS POSSIBLE
 * i.e. during the next RunLoop run() with the lowest possible latency.
 */
+ (void)performOnMainThreadWithTracing:(SEL)aSelector
                                    on:(id)target
                            withObject:(id)arg
                         waitUntilDone:(BOOL)wait
                          useJavaModes:(BOOL)useJavaModes
{
    // Slow path:
    const int mtThreshold = getMainThreadLatencyThreshold();

    NSArray<NSString*> *runLoopModes = (useJavaModes) ? javaModes : allModesExceptJava;
    const BOOL invokeDirect = ([NSThread isMainThread] && wait);

    // Perform instrumentation on selector:
    BOOL blockingEDT = NO;
    if (!invokeDirect && (wait && isEventDispatchThread())) {
        blockingEDT = YES;
    }
    /* Increment global main action id */
    long actionId = ++mainThreadActionId;

    /* tracing info */
    JNIEnv* env = NULL;
    ThreadTraceContext* callerCtx = nil;

    if (enableTracing) {
        // Get current thread env:
        env = [ThreadUtilities getJNIEnvUncached];
        char* operation = (invokeDirect ? "now  " : (blockingEDT ? "blocking" : "later"));

        // Record thread stack now and return another copy (auto-released):
        callerCtx = [ThreadUtilities recordTraceContext:nil actionId:actionId useJavaModes:useJavaModes operation:operation];
        [callerCtx retain];

        lwc_plog(env, "%s performOnMainThread[caller]", toCString([callerCtx description]));

        if ([callerCtx callStack] != nil) {
            lwc_plog(env, "%s performOnMainThread[caller]: call stack:\n%s",
                     [callerCtx identifier], toCString([callerCtx callStack]));
        }
    }

    // will be released in blockCopy() later:
    [callerCtx retain];

    void (^blockCopy)(void) = Block_copy(^(){
        AWT_ASSERT_APPKIT_THREAD;

        JNIEnv* runEnv = NULL;
        ThreadTraceContext* runCtx = nil;

        if (enableTracing) {
            // Get current thread env:
            runEnv = [ThreadUtilities getJNIEnv];

            // Record thread stack now and return another copy (auto-released):
            runCtx = [ThreadUtilities recordTraceContext];

            const double latencyMs = ([runCtx timestamp] - [callerCtx timestamp]) * 1000.0;

            lwc_plog(runEnv, "%s performOnMainThread[blockCopy]: latency = %.3lf ms. Calling: [%s]",
                     [callerCtx identifier], latencyMs, aSelector);

            if ([runCtx callStack] != nil) {
                lwc_plog(runEnv, "%s performOnMainThread[blockCopy]: run stack:\n%s",
                         [callerCtx identifier], toCString([runCtx callStack]));
            }
        }
        const CFTimeInterval start = (enableTracing) ? CACurrentMediaTime() : 0.0;
        @try {
            if (blockingEDT) {
                setBlockingEventDispatchThread(YES);
            }
            [target performSelector:aSelector withObject:arg];
        } @finally {
            if (blockingEDT) {
                setBlockingEventDispatchThread(NO);
            }
            if (enableTracing) {
                const double elapsedMs = (CACurrentMediaTime() - start) * 1000.0;
                if (elapsedMs > mtThreshold) {
                    lwc_plog(runEnv, "%s performOnMainThread[blockCopy]: time = %.3lf ms. Caller=[%s]",
                             [callerCtx identifier], elapsedMs, toCString([callerCtx caller]));
                }
                [callerCtx release];

                // Finally reset Main thread context in context store:
                [ThreadUtilities resetTraceContext];
            }
        }
    }); // End of blockCopy

    if (invokeDirect) {
        [ThreadUtilities invokeBlockCopy:blockCopy];
    } else {
        if (enableTracing) {
            lwc_plog(env, "%s performOnMainThread[caller]: waiting on MainThread(%s). Caller=[%s] [%s]",
                     [callerCtx identifier], aSelector, toCString([callerCtx caller]),
                     wait ? "WAIT" : "ASYNC");
        }

        [ThreadUtilities performSelectorOnMainThread:@selector(invokeBlockCopy:) withObject:blockCopy waitUntilDone:wait modes:runLoopModes];

        if (enableTracing) {
            lwc_plog(env, "%s performOnMainThread[caller]: finished on MainThread(%s). Caller=[%s] [DONE]",
                     [callerCtx identifier], aSelector, toCString([callerCtx caller]));
        }

        [callerCtx retain];
    }
}

+ (NSString*)criticalRunLoopMode {
    return CriticalRunLoopMode;
}

+ (NSString*)javaRunLoopMode {
    return JavaRunLoopMode;
}

+ (NSMutableDictionary*) threadContextStore {
    static NSMutableDictionary<NSString*, ThreadTraceContext*>* _threadTraceContextPerName;
    static dispatch_once_t oncePredicate;

    dispatch_once(&oncePredicate, ^{
        _threadTraceContextPerName = [[NSMutableDictionary alloc] init];
    });

    return _threadTraceContextPerName;
}

+ (ThreadTraceContext*) getTraceContext {
    const NSString* thName = [[NSThread currentThread] name];

    NSMutableDictionary* allContexts = [ThreadUtilities threadContextStore];
    ThreadTraceContext* thCtx = allContexts[thName];

    if (thCtx == nil) {
        // Create the empty thread context (auto-released):
        thCtx = [[[ThreadTraceContext alloc] init] autorelease];
        allContexts[thName] = thCtx;
    }
    return thCtx;
}

/*
 * TODO: call when Threads are destroyed.
 */
+ (void)removeTraceContext {
    const NSString* thName = [[NSThread currentThread] name];
    [[ThreadUtilities threadContextStore] removeObjectForKey:thName];
}

+ (void) resetTraceContext {
    [[ThreadUtilities getTraceContext] reset];
}

+ (ThreadTraceContext*) recordTraceContext {
    return [ThreadUtilities recordTraceContext:@"recordTraceContext"];
}

+ (ThreadTraceContext*) recordTraceContext:(NSString*) prefix {
    return [ThreadUtilities recordTraceContext:prefix actionId:-1 useJavaModes:NO operation:""];
}

+ (ThreadTraceContext*) recordTraceContext:(NSString*) prefix
                                  actionId:(long) actionId
                              useJavaModes:(BOOL) useJavaModes
                                 operation:(char*) operation
{
    ThreadTraceContext *thCtx = [ThreadUtilities getTraceContext];

    // Record stack trace:
    NSString *caller = [ThreadUtilities getCaller:prefix];
    NSString *callStack = (enableCallStacks) ? [ThreadUtilities getCallerStack:prefix] : nil;

    // Record thread stack now and return another copy (auto-released):
    return [thCtx set:actionId operation:operation useJavaModes:useJavaModes caller:caller callstack:callStack];
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

/* LWCToolkit's PlatformLogger wrapper */
JNIEXPORT void lwc_plog(JNIEnv* env, const char *formatMsg, ...) {
    if ((env == NULL) || (formatMsg == NULL)) {
        return;
    }
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
    if (midWarn != NULL) {
        va_list args;
        va_start(args, formatMsg);
        /* formatted message can be large (stack trace ?) => 16 kb */
        const int bufSize = 16 * 1024;
        char buf[bufSize];
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wformat-nonliteral"
        vsnprintf(buf, bufSize, formatMsg, args);
#pragma clang diagnostic pop
        va_end(args);

        const jstring javaString = (*env)->NewStringUTF(env, buf);
        if ((*env)->ExceptionCheck(env)) {
            // fallback:
            NSLog(@"%s\n", buf); \
        } else {
            JNU_CHECK_EXCEPTION(env);
            (*env)->CallVoidMethod(env, loggerObject, midWarn, javaString);
            CHECK_EXCEPTION();
            return;
        }
        (*env)->DeleteLocalRef(env, javaString);
    }
}

/* Traceability data */
@implementation ThreadTraceContext {
}

@synthesize sleep, useJavaModes, actionId, operation, timestamp, caller, callStack;

- (id _Nonnull)init
{
    self = [super init];
    if (self) {
        [self reset];
    }
    return self;
}

- (id)copyWithZone:(NSZone *)zone
{
    ThreadTraceContext *newCtx = [[ThreadTraceContext alloc] init];
    if (newCtx) {
        [newCtx setSleep:[self sleep]];
        [newCtx setUseJavaModes:[self useJavaModes]];
        [newCtx setActionId:[self actionId]];
        [newCtx setOperation:[self operation]];
        [newCtx setTimestamp:[self timestamp]];

        // shallow copies:
        [newCtx setCaller:[self caller]];
        [[newCtx caller] retain];
        [newCtx setCallStack:[self callStack]];
        [[newCtx callStack] retain];
    }
    return [newCtx autorelease];
}

- (void)reset
{
    self.sleep = NO;
    self.useJavaModes = NO;
    self.actionId = -1;
    self.operation = nil;
    self.timestamp = 0.0;
    [[self caller] release];
    self.caller = nil;
    [[self callStack] release];
    self.callStack = nil;
}

- (void)dealloc {
    [[self caller] release];
    [[self callStack] release];
    [super dealloc];
}

- (void)updateThreadState:(BOOL)sleepValue {
    self.timestamp = CACurrentMediaTime();
    self.sleep = sleepValue;
}

- (ThreadTraceContext*)set:(long)  pActionId
                 operation:(char*) pOperation
              useJavaModes:(BOOL)  pUseJavaModes
                    caller:(NSString*) pCaller
                 callstack:(NSString*) pCallStack
{
    [self updateThreadState:NO];
    self.useJavaModes = pUseJavaModes;
    self.actionId = pActionId;
    self.operation = pOperation;

    [[self caller] release];
    self.caller = pCaller;
    [[self caller] retain];

    [[self callStack] release];
    self.callStack = pCallStack;
    [[self callStack] retain];

    return [self copy];
}
- (const char*)identifier {
    return toCString([NSString stringWithFormat:@"[%.6lf] ThreadTrace[actionId = %ld](%s)",
            timestamp, actionId, operation]);
}

- (NSString *)getDescription {
    return [NSString stringWithFormat:@"%s useJavaModes=%d sleep=%d caller=[%@]",
            [self identifier], useJavaModes, sleep, caller];
}

@end
