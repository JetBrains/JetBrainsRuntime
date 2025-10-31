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

#import <AppKit/AppKit.h>
#import <objc/message.h>

#import "JNIUtilities.h"
#import "PropertiesUtilities.h"
#import "ThreadUtilities.h"
#import "NSApplicationAWT.h"


#define RUN_BLOCK_IF(COND, block)   \
    if ((COND)) {                   \
        block();                    \
        return;                     \
    }

/* See LWCToolkit.APPKIT_THREAD_NAME */
#define MAIN_THREAD_NAME    "AppKit Thread"

void CFRelease_even_NULL(CFTypeRef cf) {
    if (cf != NULL) {
        CFRelease(cf);
    }
}

// Global CrashOnException handling flags used by:
// - UncaughtExceptionHandler
// - NSApplicationAWT _crashOnException:(NSException *)exception
static BOOL isAWTCrashOnException() {
    static int awtCrashOnException = -1;
    if (awtCrashOnException == -1) {
        JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
        if (env == NULL) return NO;
        NSString* awtCrashOnExceptionProp = [PropertiesUtilities javaSystemPropertyForKey:@"apple.awt.crashOnException"
                                                                                     withEnv:env];
        awtCrashOnException = [@"true" isCaseInsensitiveLike:awtCrashOnExceptionProp] ? YES : NO;
    }
    return (BOOL)awtCrashOnException;
}

BOOL shouldCrashOnException() {
    static int crashOnException = -1;
    if (crashOnException == -1) {
        BOOL shouldCrashOnException = NO;
#if defined(DEBUG)
        shouldCrashOnException = YES;
#endif
        if (!shouldCrashOnException) {
            shouldCrashOnException = isAWTCrashOnException();
        }
        crashOnException = shouldCrashOnException;
        if (crashOnException) {
            NSLog(@"WARNING: shouldCrashOnException ENABLED");
        }
    }
    return (BOOL)crashOnException;
}

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
    return (obj == nil) ? "nil" : [NSString stringWithFormat:@"%@", obj].UTF8String;
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
static const BOOL forceTracing = NO;
static const BOOL enableTracing = NO || forceTracing;
static const BOOL enableTracingLog = NO;
static const BOOL enableTracingNSLog = YES && enableTracingLog;
static const BOOL enableCallStacks = YES;

/* Traceability data */
static const int TRACE_BLOCKING_FLAGS = 0;

/* RunLoop traceability identifier generators */
static atomic_long mainThreadActionId = 0L;

// --- AWT's NSUncaughtExceptionHandler ---
static void AWT_NSUncaughtExceptionHandler(NSException *exception) {
    // report exception to the NSApplicationAWT exception handler:
    NSAPP_AWT_REPORT_EXCEPTION(exception, YES);
}

/* Get AWT's NSUncaughtExceptionHandler */
NSUncaughtExceptionHandler* GetAWTUncaughtExceptionHandler(void) {
    static NSUncaughtExceptionHandler* _awtUncaughtExceptionHandler;
    static dispatch_once_t oncePredicate;

    dispatch_once(&oncePredicate, ^{
        _awtUncaughtExceptionHandler = AWT_NSUncaughtExceptionHandler;
        NSSetUncaughtExceptionHandler(_awtUncaughtExceptionHandler);
    });
    return _awtUncaughtExceptionHandler;
}

static inline void doLog(JNIEnv* env, const char *formatMsg, ...) {
    if (enableTracingNSLog) {
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
        /* use NSLog to get timestamp + outputs in console and stderr */
        NSLog(@"%s\n", buf);
    } else {
        va_list args;
        va_start(args, formatMsg);
        lwc_plog(env, formatMsg, args);
        va_end(args);
    }
}


static inline void attachCurrentThread(void** env) {
    if ([NSThread isMainThread]) {
        JavaVMAttachArgs args;
        args.version = JNI_VERSION_1_4;
        args.name = MAIN_THREAD_NAME;
        args.group = appkitThreadGroup;
        (*jvm)->AttachCurrentThreadAsDaemon(jvm, env, &args);
    } else {
        (*jvm)->AttachCurrentThreadAsDaemon(jvm, env, NULL);
    }
}

@implementation ThreadUtilities

static long eventDispatchThreadPtr = (long)nil;
static BOOL _blockingEventDispatchThread = NO;
static BOOL _blockingMainThread = NO;

// Empty block optimization using 1 single instance and avoid Block_copy macro

+ (void (^)()) GetEmptyBlock {
    static id _emptyBlock = nil;
    if (_emptyBlock == nil) {
        _emptyBlock = ^(){};
    }
    return _emptyBlock;
}

+ (BOOL) IsEmptyBlock:(void (^)())block {
    return (block == [ThreadUtilities GetEmptyBlock]);
}

#define Custom_Block_copy(block) ( ([ThreadUtilities IsEmptyBlock:block]) ? block : Block_copy(block) )

/*
 * When running a block where either we don't wait, or it needs to run on another thread
 * we need to copy it from stack to heap, use the copy in the call and release after use.
 * Do this only when we must because it could be expensive.
 * Note : if waiting cross-thread, possibly the stack allocated copy is accessible ?
 */
+ (void)invokeBlockCopy:(void (^)(void))blockCopy {
    if (![ThreadUtilities IsEmptyBlock:blockCopy]) {
        @try {
            blockCopy();
        } @finally {
            Block_release(blockCopy);
#if (CHECK_PENDING_EXCEPTION == 1)
            __JNI_CHECK_PENDING_EXCEPTION([ThreadUtilities getJNIEnvUncached]);
#endif
        }
    }
}

// Exception handling bridge
+ (void) reportException:(NSException *)exception {
    [[NSApplicationAWT sharedApplicationAWT] reportException:exception];
}

+ (void) reportException:(NSException *)exception uncaught:(BOOL)uncaught
                    file:(const char*)file line:(int)line function:(const char*)function
{
    [[NSApplicationAWT sharedApplicationAWT] reportException:exception uncaught:uncaught file:file line:line function:function];
}

+ (void) logException:(NSException *)exception {
    [NSApplicationAWT logException:exception];
}

+ (void) logException:(NSException *)exception
                 file:(const char*)file line:(int)line function:(const char*)function
{
    [NSApplicationAWT logException:exception file:file line:line function:function];
}

+ (void) logException:(NSException *)exception prefix:(NSString *)prefix
                 file:(const char*)file line:(int)line function:(const char*)function
{
    [NSApplicationAWT logException:exception prefix:prefix file:file line:line function:function];
}

+ (void) logMessage:(NSString *)message {
    [NSApplicationAWT logMessage:message];
}

+ (void) logMessage:(NSString *)message
               file:(const char*)file line:(int)line function:(const char*)function
{
    [NSApplicationAWT logMessage:message file:file line:line function:function];
}

static BOOL isEventDispatchThread() {
    return (long)[NSThread currentThread] == eventDispatchThreadPtr;
}

// The [blockingEventDispatchThread] property is readonly, so we implement a private setter
static void setBlockingEventDispatchThread(BOOL value) {
    assert([NSThread isMainThread]);
    if ((TRACE_BLOCKING_FLAGS & 1) != 0) {
        NSLog(@"setBlockingEventDispatchThread(%s)", value ? "YES" : "NO");
    }
    _blockingEventDispatchThread = value;
}

+ (BOOL) blockingEventDispatchThread {
    assert([NSThread isMainThread]);
    return _blockingEventDispatchThread;
}

+ (void)setBlockingMainThread:(BOOL)value {
    assert([NSThread isMainThread]);
    if ((TRACE_BLOCKING_FLAGS & 2) != 0) {
        NSLog(@"setBlockingMainThread(%s)", value ? "YES" : "NO");
    }
    _blockingMainThread = value;
}

+ (BOOL)blockingMainThread {
    return _blockingMainThread;
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
    if ([NSThread isMainThread] && (appKitEnv != NULL)) {
        return appKitEnv;
    } else {
        JNIEnv *env = NULL;
        attachCurrentThread((void **)&env);
        return env;
    }
}

+ (void)detachCurrentThread {
    (*jvm)->DetachCurrentThread(jvm);
}

+ (void)setAppkitThreadGroup:(jobject)group {
    appkitThreadGroup = group;

    if (enableTracing) {
        // Record thread stack now and return another copy (auto-released):
        [ThreadUtilities recordTraceContext];
    }
    @try {
        // Start callback queue:
        [RunLoopCallbackQueue shared];

        CFRunLoopObserverRef observer = CFRunLoopObserverCreateWithHandler(
                NULL,                        // CFAllocator
                kCFRunLoopAllActivities,     // CFOptionFlags
                true,                        // repeats
                NSIntegerMin,                // order (Highest priority = earliest)
                ^(CFRunLoopObserverRef observerRef, CFRunLoopActivity activity)
                {
                    // Run any registered callback:
                    [[RunLoopCallbackQueue shared] processQueuedCallbacks];
                }
        );
        // Register observer on the Main RunLoop for all modes (common, critical & java):
        CFRunLoopRef mainRunLoop = [[NSRunLoop mainRunLoop] getCFRunLoop];
        CFRunLoopAddObserver(mainRunLoop, observer, kCFRunLoopCommonModes);

        CFStringRef criticalModeRef = (__bridge CFStringRef) CriticalRunLoopMode;
        CFRunLoopAddObserver(mainRunLoop, observer, criticalModeRef);
        CFRelease(criticalModeRef);

        CFStringRef javaModeRef = (__bridge CFStringRef) JavaRunLoopMode;
        CFRunLoopAddObserver(mainRunLoop, observer, javaModeRef);
        CFRelease(javaModeRef);
        CFRelease(observer);

    } @finally {
        if (enableTracing) {
            // Finally reset Main thread context in context store:
            [ThreadUtilities resetTraceContext];
        }
    }
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
    RUN_BLOCK_IF([NSThread isMainThread], block);

    [ThreadUtilities performOnMainThread:@selector(invokeBlockCopy:) on:self withObject:Custom_Block_copy(block)
                           waitUntilDone:NO useJavaModes:useJavaModes];
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
    RUN_BLOCK_IF([NSThread isMainThread] && wait, block);

    [ThreadUtilities performOnMainThread:@selector(invokeBlockCopy:) on:self withObject:Custom_Block_copy(block)
                           waitUntilDone:wait useJavaModes:useJavaModes];
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

    if (!forceTracing && (!enableTracing || (mtThreshold < 0))) {
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
    const BOOL invokeDirect = [NSThread isMainThread] && wait;
    const BOOL doWait = !invokeDirect && wait;
    const BOOL blockingEDT = doWait && isEventDispatchThread();

    // Slow path:
    const int mtThreshold = getMainThreadLatencyThreshold();
    const bool doTrace = (enableTracing && doWait);

    NSArray<NSString*> *runLoopModes = (useJavaModes) ? javaModes : allModesExceptJava;

    // Perform instrumentation on selector:
    /* Increment global main action id */
    long actionId = ++mainThreadActionId;

    /* tracing info */
    JNIEnv* cenv = NULL;
    ThreadTraceContext* callerCtx = nil;

    if (doTrace) {
        // Get current thread env:
        cenv = [ThreadUtilities getJNIEnvUncached];
        char* operation = (invokeDirect ? "now  " : (blockingEDT ? "blocking" : "later"));

        // Record thread stack now and return another copy (auto-released):
        callerCtx = [ThreadUtilities recordTraceContext:nil actionId:actionId useJavaModes:useJavaModes operation:operation];

        if (enableTracingLog) {
            doLog(cenv, "%s performOnMainThread[caller]: %s",
                  [callerCtx identifier], toCString([callerCtx description]));
        }

        // will be released in blockCopy() later:
        [callerCtx retain];
    }

    void (^blockCopy)(void) = Block_copy(^(){
        AWT_ASSERT_APPKIT_THREAD;

        JNIEnv* renv = NULL;
        ThreadTraceContext* runCtx = nil;

        if (doTrace) {
            // Get current thread env:
            renv = [ThreadUtilities getJNIEnv];

            // Record thread stack now and return another copy (auto-released):
            runCtx = [ThreadUtilities recordTraceContext];

            if (enableTracingLog) {
                const double latencyMs = ([runCtx timestamp] - [callerCtx timestamp]) * 1000.0;
                doLog(renv, "%s performOnMainThread[blockCopy:before]: latency = %.5lf ms. Calling: [%s]",
                         [callerCtx identifier], latencyMs, aSelector);
                doLog(renv, "%s performOnMainThread[blockCopy:before]: caller = %s",
                      [callerCtx identifier], toCString([callerCtx description]));

                if (false && [runCtx callStack] != nil) {
                    doLog(renv, "%s performOnMainThread[blockCopy:before]: run stack:\n%s",
                             [callerCtx identifier], toCString([runCtx callStack]));
                }
            }
        }
        const CFTimeInterval start = (doTrace) ? CACurrentMediaTime() : 0.0;
        @try {
            if (blockingEDT) {
                setBlockingEventDispatchThread(YES);
            }
            [target performSelector:aSelector withObject:arg];
        } @finally {
            if (blockingEDT) {
                setBlockingEventDispatchThread(NO);
            }
            if (doTrace) {
                if (enableTracingLog) {
                    const double elapsedMs = (CACurrentMediaTime() - start) * 1000.0;
                    if (doTrace || (elapsedMs > mtThreshold)) {
                        doLog(renv, "%s performOnMainThread[blockCopy:after]: time = %.5lf ms. Called: [%s]",
                              [callerCtx identifier], elapsedMs, aSelector);
                        doLog(renv, "%s performOnMainThread[blockCopy:after]: caller = %s",
                              [callerCtx identifier], toCString([callerCtx description]));
                    }
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
        if (doTrace && enableTracingLog) {
            doLog(cenv, "%s performOnMainThread[caller]: waiting on MainThread(%s). Caller=[%s] [%s]",
                     [callerCtx identifier], aSelector, toCString([callerCtx caller]),
                     wait ? "WAIT" : "ASYNC");
        }

        [ThreadUtilities performSelectorOnMainThread:@selector(invokeBlockCopy:) withObject:blockCopy waitUntilDone:wait modes:runLoopModes];

        if (doTrace && enableTracingLog) {
            doLog(cenv, "%s performOnMainThread[caller]: finished on MainThread(%s). Caller=[%s] [DONE]",
                     [callerCtx identifier], aSelector, toCString([callerCtx caller]));
        }
        // Finally reset thread context in context store:
        [ThreadUtilities resetTraceContext];
    }
}

+ (NSString*)criticalRunLoopMode {
    return CriticalRunLoopMode;
}

+ (NSString*)javaRunLoopMode {
    return JavaRunLoopMode;
}

/* internal special RunLoop callbacks */

+ (BOOL)hasMainThreadRunLoopCallback:(u_long)coalesingBit {
    return [[RunLoopCallbackQueue shared] hasCallback:coalesingBit];
}

+ (void)registerMainThreadRunLoopCallback:(u_long)coalesingBit block:(void (^)())block {
    [[RunLoopCallbackQueue shared] addCallback:coalesingBit block:block];
}

/* native thread tracing */

+ (NSString*) getCurrentThreadName {
    return ([NSThread isMainThread]) ? @MAIN_THREAD_NAME : [[NSThread currentThread] name];
}

+ (NSMutableDictionary*)threadContextStore {
    static NSMutableDictionary<NSString*, ThreadTraceContext*>* _threadTraceContextPerName;
    static dispatch_once_t oncePredicate;

    dispatch_once(&oncePredicate, ^{
        _threadTraceContextPerName = [[NSMutableDictionary alloc] init];
    });
    return _threadTraceContextPerName;
}

+ (ThreadTraceContext*)getTraceContext {
    NSString* thName = [ThreadUtilities getCurrentThreadName];

    NSMutableDictionary* allContexts = [ThreadUtilities threadContextStore];
    ThreadTraceContext* thCtx = allContexts[thName];

    if (thCtx == nil) {
        // Create the empty thread context (auto-released):
        thCtx = [[[ThreadTraceContext alloc] init:thName] autorelease];
        allContexts[thName] = thCtx;
    }
    return thCtx;
}

/*
 * TODO: call when Threads are destroyed.
 */
+ (void)removeTraceContext {
    const NSString* thName = [ThreadUtilities getCurrentThreadName];
    [[ThreadUtilities threadContextStore] removeObjectForKey:thName];
}

+ (void)resetTraceContext {
    [[ThreadUtilities getTraceContext] reset];
}

+ (ThreadTraceContext*)recordTraceContext {
    return [ThreadUtilities recordTraceContext:@"recordTraceContext"];
}

+ (ThreadTraceContext*)recordTraceContext:(NSString*) prefix {
    return [ThreadUtilities recordTraceContext:prefix actionId:-1 useJavaModes:NO operation:""];
}

+ (ThreadTraceContext*)recordTraceContext:(NSString*) prefix operation:(const char*)pOperation {
    return [ThreadUtilities recordTraceContext:prefix actionId:-1 useJavaModes:NO operation:pOperation];
}

+ (ThreadTraceContext*)recordTraceContext:(NSString*) prefix
                                  actionId:(long) actionId
                              useJavaModes:(BOOL) useJavaModes
                                 operation:(const char*)pOperation
{
    ThreadTraceContext *thCtx = [ThreadUtilities getTraceContext];

    // Record stack trace:
    NSString *caller = [ThreadUtilities getCaller:prefix];
    NSString *callStack = (enableCallStacks) ? [ThreadUtilities getCallerStack:prefix] : nil;
    // update recorded thread state:
    [thCtx set:actionId operation:pOperation useJavaModes:useJavaModes caller:caller callstack:callStack];

    // Record thread stack now and return another copy (auto-released):
    return [[thCtx copy] autorelease];
}

+ (void)dumpThreadTraceContext:(const char*)pOperation {
    if (enableTracingLog) {
        JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
        // Record thread stack now and return another copy (auto-released):
        ThreadTraceContext* thCtx = [ThreadUtilities recordTraceContext:@"dumpThreadTraceContext" operation:pOperation];
        doLog(env, "dumpThreadTraceContext: {\n%s\n}", toCString([thCtx description]));
    }
}

+ (NSString*)getThreadTraceContexts
{
    NSMutableDictionary* allContexts = [ThreadUtilities threadContextStore];

    // CHECK LEAK ?
    NSMutableString* dump = [[[NSMutableString alloc] initWithCapacity:4096] autorelease];
    [dump appendString:@"[\n"];

    for (NSString* thName in allContexts) {
        ThreadTraceContext *thCtx = allContexts[thName];

        [dump appendString:@"\n["];
        [dump appendFormat:@"\n  thread:'%@'", thName];
        [dump appendString:@"\n  traceContext: "];

        if (thCtx == nil) {
            [dump appendString:@"null"];
        } else {
            [dump appendString:@"["];
            [dump appendFormat:@"\n    %@", thCtx.description];
            [dump appendString:@"\n  ]"];
        }
        [dump appendString:@"\n] \n"];
    }
    [dump appendString:@"]"];
    [dump retain];
    return dump;
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
        /* formatted message can be large (stack trace) => 16 kb buffer size */
        const int bufSize = 16 * 1024;
        char buf[bufSize];
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wformat-nonliteral"
        vsnprintf(buf, bufSize, formatMsg, args);
#pragma clang diagnostic pop
        va_end(args);

        BOOL logged = NO;
        const jstring javaString = (*env)->NewStringUTF(env, buf);

        if ((javaString != NULL) && (*env)->ExceptionCheck(env) == JNI_FALSE) {
            // call PlatformLogger.warning(javaString):
            (*env)->CallVoidMethod(env, loggerObject, midWarn, javaString);

            // derived from CHECK_EXCEPTION but NO NSException raised:
            if ((*env)->ExceptionCheck(env)) {
                // fallback:
                (*(env))->ExceptionDescribe(env);
                // note: [NSApplicationAWT logMessage:message] is not used
                // to avoid reentrancy issues.
                 NSLog(@"%@", [NSThread callStackSymbols]);
            } else {
                logged = YES;
            }
        }
        (*env)->DeleteLocalRef(env, javaString);
        if (!logged) {
            // fallback:
            NSLog(@"%s\n", buf);
        }
    }
}

/* RunLoop Callback Queue */

@implementation RunLoopCallbackQueue

+ (RunLoopCallbackQueue*) shared {
    static RunLoopCallbackQueue* _runLoopCallbackQueue = nil;
    static dispatch_once_t oncePredicate;

    dispatch_once(&oncePredicate, ^{
        _runLoopCallbackQueue = [RunLoopCallbackQueue new];
    });
    return _runLoopCallbackQueue;
}

- (id) init {
    self = [super init];
    if (self) {
        self.queue = [NSMutableArray arrayWithCapacity: 8];
        [self reset];
    }
    return self;
}

- (void)dealloc {
    [self reset];
    self.queue = nil;
    [super dealloc];
}

- (void)reset {
    [self.queue removeAllObjects];
    self.coalesingflags = 0;
}

- (BOOL)coalesingFlag:(u_long)bit {
    return (self.coalesingflags & (1L << bit)) != 0;
}
- (void)setCoalesingFlag:(u_long)bit {
    self.coalesingflags |= (1L << bit);
}

- (BOOL)hasCallback:(u_long)bit {
    return (bit != 0) && [self coalesingFlag:bit];
}

- (BOOL)addCallback:(u_long)bit block:(void (^)())block {
    if ([NSThread isMainThread] == NO) {
        NSLog(@"addCallback should be called on main thread");
        return NO;
    }
    // check coalesing flag:
    if (bit != 0) {
        if ([self coalesingFlag:bit]) {
            // skip coalesing callback:
            return NO;
        }
        [self setCoalesingFlag:bit];
    }
    [self.queue addObject:Block_copy(block)];
    return YES;
}

- (void)processQueuedCallbacks {
    const NSUInteger count = [self.queue count];
    if (count != 0) {
#if (CHECK_PENDING_EXCEPTION == 1)
        JNIEnv *env = [ThreadUtilities getJNIEnv];
#endif
        for (NSUInteger i = 0; i < count; i++) {
            NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
            @try {
                void (^blockCopy)(void) = (void (^)()) [self.queue objectAtIndex:i];
                // invoke callback:
                [ThreadUtilities invokeBlockCopy:blockCopy];
            } @catch (NSException *exception) {
                // report exception to the NSApplicationAWT exception handler:
                NSAPP_AWT_REPORT_EXCEPTION(exception, NO);
            } @finally {
                __JNI_CHECK_PENDING_EXCEPTION(env);
                [pool drain];
            }
        }
        // reset queue anyway:
        [self reset];
    }
}
@end

/* Traceability data */
@implementation ThreadTraceContext

@synthesize sleep, useJavaModes, actionId, operation, timestamp, caller, callStack;

- (id)init:(NSString*)threadName {
    self = [super init];
    if (self) {
        self.threadName = threadName;
        [self reset];
    }
    return self;
}

- (id)copyWithZone:(NSZone *)zone {
    ThreadTraceContext *newCtx = [[ThreadTraceContext alloc] init];
    if (newCtx) {
        [newCtx setSleep:[self sleep]];
        [newCtx setUseJavaModes:[self useJavaModes]];
        [newCtx setActionId:[self actionId]];
        [newCtx setOperation:[self operation]];
        [newCtx setTimestamp:[self timestamp]];

        // shallow copies:
        [newCtx setThreadName:[self threadName]];
        [newCtx setCaller:[self caller]];
        [newCtx setCallStack:[self callStack]];
        return newCtx;
    }
    return nil;
}

- (void)reset {
    self.sleep = NO;
    self.useJavaModes = NO;
    self.actionId = -1;
    self.operation = nil;
    self.timestamp = 0.0;
    self.caller = nil;
    self.callStack = nil;
}

- (void)dealloc {
    [super dealloc];
}

- (void)updateThreadState:(BOOL)sleepValue {
    self.timestamp = CACurrentMediaTime();
    self.sleep = sleepValue;
}

- (void) set:(long) pActionId
   operation:(const char*) pOperation
useJavaModes:(BOOL) pUseJavaModes
      caller:(NSString*) pCaller
   callstack:(NSString*) pCallStack
{
    [self updateThreadState:NO];
    self.useJavaModes = pUseJavaModes;
    self.actionId = pActionId;
    self.operation = pOperation;

    self.caller = pCaller;
    [pCaller release];
    self.callStack = pCallStack;
    [pCallStack release];
}

- (const char*)identifier {
    return toCString([NSString stringWithFormat:@"[%.6lf '%@' Trace[actionId = %ld](%s)",
            timestamp, [self threadName], actionId, operation]);
}

- (NSString *)description {
    // creates autorelease string:
    return [NSString stringWithFormat:@"%s useJavaModes=%d sleep=%d caller=[%@] callStack={\n%@}",
            [self identifier], useJavaModes, sleep, caller,
            ([self callStack] == nil) ? @"-" : [self callStack]];
}
@end
