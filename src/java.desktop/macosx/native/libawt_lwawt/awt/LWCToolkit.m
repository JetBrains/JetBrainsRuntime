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

#import <dlfcn.h>
#import <pthread.h>
#import <objc/runtime.h>
#import <Cocoa/Cocoa.h>
#import <Security/AuthSession.h>

#import "JNIUtilities.h"
#import "LWCToolkit.h"
#import "ThreadUtilities.h"
#import "CSystemColors.h"
#import  "NSApplicationAWT.h"
#import "PropertiesUtilities.h"
#import "ApplicationDelegate.h"

#import "sun_lwawt_macosx_LWCToolkit.h"

#import "sizecalc.h"
#import "AWTWindow.h"

#import <JavaRuntimeSupport/JavaRuntimeSupport.h>
#import <Carbon/Carbon.h>

#include <IOKit/hidsystem/IOHIDShared.h>
#include <IOKit/hidsystem/IOHIDParameter.h>

// SCROLL PHASE STATE
#define SCROLL_PHASE_UNSUPPORTED 1
#define SCROLL_PHASE_BEGAN 2
#define SCROLL_PHASE_CONTINUED 3
#define SCROLL_PHASE_MOMENTUM_BEGAN 4
#define SCROLL_PHASE_ENDED 5

/* RunLoop run max duration = 4 millis */
#define RUN_LOOP_TICK           (0.004)
/* RunLoop critical run max duration = 1 millis */
#define RUN_LOOP_TICK_CRITICAL  (0.001)

/* max wait timeout for AWTRunLoop > 10s */
#define WAIT_TIMEOUT_LIMIT      (13.333)

/* power transition period = 10s */
#define PWM_TRANSITION_PERIOD   (10.000)

#define TRACE_RUN_LOOP  0

int gNumberOfButtons;
jint* gButtonDownMasks;
int lcdSubPixelPosSupported;
int useFontSmoothing;

// Indicates that the app has been started with -XstartOnFirstThread
// (directly or via WebStart settings), and AWT should not run its
// own event loop in this mode. Even if a loop isn't running yet,
// we expect an embedder (e.g. SWT) to start it some time later.
static BOOL forceEmbeddedMode = NO;

// Indicates if awt toolkit is embedded into another UI toolkit
static BOOL isEmbedded = NO;

// This is the data necessary to have JNI_OnLoad wait for AppKit to start.
static BOOL sAppKitStarted = NO;
static pthread_mutex_t sAppKitStarted_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t sAppKitStarted_cv = PTHREAD_COND_INITIALIZER;

@implementation AWTToolkit

static long eventCount;
static BOOL inDoDragDropLoop;

+ (BOOL) inDoDragDropLoop {
  @synchronized(self) {
    return inDoDragDropLoop;
  }
}

+ (void) setInDoDragDropLoop:(BOOL)val {
  @synchronized(self) {
    inDoDragDropLoop = val;
  }
}

+ (long) getEventCount{
  @synchronized(self) {
    return eventCount;
  }
}

+ (void) eventCountPlusPlus{
  @synchronized(self) {
    eventCount++;
  }
}

+ (jint) scrollStateWithEvent: (NSEvent*) event {

    if ([event type] != NSEventTypeScrollWheel) {
        return 0;
    }

    if ([event phase]) {
        // process a phase of manual scrolling
        switch ([event phase]) {
            case NSEventPhaseBegan: return SCROLL_PHASE_BEGAN;
            case NSEventPhaseCancelled: return SCROLL_PHASE_ENDED;
            case NSEventPhaseEnded: return SCROLL_PHASE_ENDED;
            default: return SCROLL_PHASE_CONTINUED;
        }
    }

    if ([event momentumPhase]) {
        // process a phase of automatic scrolling
        switch ([event momentumPhase]) {
            case NSEventPhaseBegan: return SCROLL_PHASE_MOMENTUM_BEGAN;
            case NSEventPhaseCancelled: return SCROLL_PHASE_ENDED;
            case NSEventPhaseEnded: return SCROLL_PHASE_ENDED;
            default: return SCROLL_PHASE_CONTINUED;
        }
    }
    // phase and momentum phase both are not set
    return SCROLL_PHASE_UNSUPPORTED;
}

+ (BOOL) hasPreciseScrollingDeltas: (NSEvent*) event {
    return [event type] == NSEventTypeScrollWheel
        && [event respondsToSelector:@selector(hasPreciseScrollingDeltas)]
        && [event hasPreciseScrollingDeltas];
}
@end


@interface AWTRunLoopObject : NSObject {
    BOOL _shouldEndRunLoop;
}
@end

@implementation AWTRunLoopObject

- (id) init {
    self = [super init];
    if (self != nil) {
        _shouldEndRunLoop = NO;
    }
    return self;
}

- (BOOL) shouldEndRunLoop {
    return _shouldEndRunLoop;
}

- (void) endRunLoop {
    _shouldEndRunLoop = YES;
}

@end

@interface JavaRunnable : NSObject { }
@property jobject runnable;
- (id)initWithRunnable:(jobject)gRunnable;
- (void)perform;
@end

@implementation JavaRunnable
@synthesize runnable = _runnable;

- (id)initWithRunnable:(jobject)gRunnable {
    if (self = [super init]) {
        self.runnable = gRunnable;
    }
    return self;
}

- (void)dealloc {
    JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
    if (self.runnable) {
        (*env)->DeleteGlobalRef(env, self.runnable);
    }
    [super dealloc];
}

- (void)perform {
    JNIEnv* env = [ThreadUtilities getJNIEnvUncached];
    DECLARE_CLASS(sjc_Runnable, "java/lang/Runnable");
    DECLARE_METHOD(jm_Runnable_run, sjc_Runnable, "run", "()V");
    (*env)->CallVoidMethod(env, self.runnable, jm_Runnable_run);
    CHECK_EXCEPTION();
    [self release];
}
@end

void setBusy(BOOL busy) {
    AWT_ASSERT_APPKIT_THREAD;

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    DECLARE_CLASS(jc_AWTAutoShutdown, "sun/awt/AWTAutoShutdown");

    if (busy) {
        DECLARE_STATIC_METHOD(jm_notifyBusyMethod, jc_AWTAutoShutdown, "notifyToolkitThreadBusy", "()V");
        (*env)->CallStaticVoidMethod(env, jc_AWTAutoShutdown, jm_notifyBusyMethod);
    } else {
        DECLARE_STATIC_METHOD(jm_notifyFreeMethod, jc_AWTAutoShutdown, "notifyToolkitThreadFree", "()V");
        (*env)->CallStaticVoidMethod(env, jc_AWTAutoShutdown, jm_notifyFreeMethod);
    }
     CHECK_EXCEPTION();
}

static void setUpAWTAppKit(BOOL installObservers)
{
    if (installObservers) {
        AWT_STARTUP_LOG(@"Setting up busy observers");

        // Add CFRunLoopObservers to call into AWT so that AWT knows that the
        //  AWT thread (which is the AppKit main thread) is alive. This way AWT
        //  will not automatically shutdown.
        CFRunLoopObserverRef busyObserver = CFRunLoopObserverCreateWithHandler(
                                               NULL,                        // CFAllocator
                                               kCFRunLoopAfterWaiting,      // CFOptionFlags
                                               true,                        // repeats
                                               NSIntegerMax,                // order
                                               ^(CFRunLoopObserverRef observer, CFRunLoopActivity activity) {
                                                   setBusy(YES);
                                               });

        CFRunLoopObserverRef notBusyObserver = CFRunLoopObserverCreateWithHandler(
                                                NULL,                        // CFAllocator
                                                kCFRunLoopBeforeWaiting,     // CFOptionFlags
                                                true,                        // repeats
                                                NSIntegerMin,                // order
                                                ^(CFRunLoopObserverRef observer, CFRunLoopActivity activity) {
                                                    setBusy(NO);
                                                });

        CFRunLoopRef runLoop = [[NSRunLoop currentRunLoop] getCFRunLoop];
        CFRunLoopAddObserver(runLoop, busyObserver, kCFRunLoopDefaultMode);
        CFRunLoopAddObserver(runLoop, notBusyObserver, kCFRunLoopDefaultMode);

        CFRelease(busyObserver);
        CFRelease(notBusyObserver);

        setBusy(YES);
    }

    JNIEnv* env = [ThreadUtilities getJNIEnv];
    DECLARE_CLASS(jc_LWCToolkit, "sun/lwawt/macosx/LWCToolkit");
    DECLARE_STATIC_METHOD(jsm_installToolkitThreadInJava, jc_LWCToolkit, "installToolkitThreadInJava", "()V");
    (*env)->CallStaticVoidMethod(env, jc_LWCToolkit, jsm_installToolkitThreadInJava);
    CHECK_EXCEPTION();

}

BOOL isSWTInWebStart(JNIEnv* env) {
    NSString *swtWebStart = [PropertiesUtilities javaSystemPropertyForKey:@"com.apple.javaws.usingSWT" withEnv:env];
    return [@"true" isCaseInsensitiveLike:swtWebStart];
}

static void AWT_NSUncaughtExceptionHandler(NSException *exception) {
    NSLog(@"Apple AWT Internal Exception: %@", [exception description]);
    NSLog(@"trace: %@", [exception callStackSymbols]);
}

@interface AWTStarter : NSObject
+ (void)start:(BOOL)headless;
+ (void)starter:(BOOL)onMainThread headless:(BOOL)headless;
+ (void)appKitIsRunning:(id)arg;
@end

@implementation AWTStarter

+ (BOOL) isConnectedToWindowServer {
    SecuritySessionId session_id;
    SessionAttributeBits session_info;
    OSStatus status = SessionGetInfo(callerSecuritySession, &session_id, &session_info);
    if (status != noErr) return NO;
    if (!(session_info & sessionHasGraphicAccess)) return NO;
    return YES;
}

+ (BOOL) markAppAsDaemon {
    id jrsAppKitAWTClass = objc_getClass("JRSAppKitAWT");
    SEL markAppSel = @selector(markAppIsDaemon);
    if (![jrsAppKitAWTClass respondsToSelector:markAppSel]) return NO;
    return [jrsAppKitAWTClass performSelector:markAppSel] ? YES : NO;
}

+ (void)appKitIsRunning:(id)arg {
    AWT_ASSERT_APPKIT_THREAD;
    AWT_STARTUP_LOG(@"About to message AppKit started");

    // Signal that AppKit has started (or is already running).
    pthread_mutex_lock(&sAppKitStarted_mutex);
    sAppKitStarted = YES;
    pthread_cond_signal(&sAppKitStarted_cv);
    pthread_mutex_unlock(&sAppKitStarted_mutex);

    AWT_STARTUP_LOG(@"Finished messaging AppKit started");
}

+ (void)start:(BOOL)headless
{
    // onMainThread is NOT the same at SWT mode!
    // If the JVM was started on the first thread for SWT, but the SWT loads the AWT on a secondary thread,
    // onMainThread here will be false but SWT mode will be true.  If we are currently on the main thread, we don't
    // need to throw AWT startup over to another thread.
    BOOL onMainThread = [NSThread isMainThread];

    NSString* msg = [NSString stringWithFormat:@"+[AWTStarter start headless:%d] { onMainThread:%d }", headless, onMainThread];
    AWT_STARTUP_LOG(msg);

    if (!headless)
    {
        // Listen for the NSApp to start. This indicates that JNI_OnLoad can proceed.
        //  It must wait because there is a chance that another java thread will grab
        //  the AppKit lock before the +[NSApplication sharedApplication] returns.
        //  See <rdar://problem/3492666> for an example.
        [[NSNotificationCenter defaultCenter] addObserver:[AWTStarter class]
                                                 selector:@selector(appKitIsRunning:)
                                                     name:NSApplicationDidFinishLaunchingNotification
                                                   object:nil];

        AWT_STARTUP_LOG(@"+[AWTStarter start:::]: registered NSApplicationDidFinishLaunchingNotification");
    }

    [ThreadUtilities performOnMainThreadWaiting:NO block:^() {
        [AWTStarter starter:onMainThread headless:headless];
    }];


    if (!headless && !onMainThread) {

        AWT_STARTUP_LOG(@"about to wait on AppKit startup mutex");

        // Wait here for AppKit to have started (or for AWT to have been loaded into
        //  an already running NSApplication).
        pthread_mutex_lock(&sAppKitStarted_mutex);
        while (sAppKitStarted == NO) {
            pthread_cond_wait(&sAppKitStarted_cv, &sAppKitStarted_mutex);
        }
        pthread_mutex_unlock(&sAppKitStarted_mutex);

        // AWT gets here AFTER +[AWTStarter appKitIsRunning:] is called.
        AWT_STARTUP_LOG(@"got out of the AppKit startup mutex");
    }

    if (!headless) {
        // Don't set the delegate until the NSApplication has been created and
        // its finishLaunching has initialized it.
        //  ApplicationDelegate is the support code for com.apple.eawt.
        [ThreadUtilities performOnMainThreadWaiting:YES block:^(){
            id<NSApplicationDelegate> delegate = [ApplicationDelegate sharedDelegate];
            if (delegate != nil) {
                OSXAPP_SetApplicationDelegate(delegate);
            }
        }];
    }
}

+ (void)starter:(BOOL)wasOnMainThread headless:(BOOL)headless {
    NSAutoreleasePool *pool = [NSAutoreleasePool new];
    // Add the exception handler of last resort
    NSSetUncaughtExceptionHandler(AWT_NSUncaughtExceptionHandler);

    // Headless mode trumps either ordinary AWT or SWT-in-AWT mode.  Declare us a daemon and return.
    if (headless) {
        // Note that we don't install run loop observers in headless mode
        // because we don't need them (see 7174704)
        if (!forceEmbeddedMode) {
            setUpAWTAppKit(false);
        }
        [AWTStarter markAppAsDaemon];
        return;
    }

    if (forceEmbeddedMode) {
        AWT_STARTUP_LOG(@"in SWT or SWT/WebStart mode");

        // Init a default NSApplication instance instead of the NSApplicationAWT.
        // Note that [NSApp isRunning] will return YES after that, though
        // this behavior isn't specified anywhere. We rely on that.
        NSApplicationLoad();
    }

    // This will create a NSApplicationAWT for standalone AWT programs, unless there is
    //  already a NSApplication instance. If there is already a NSApplication instance,
    //  and -[NSApplication isRunning] returns YES, AWT is embedded inside another
    //  AppKit Application.
    NSApplication *app = [NSApplicationAWT sharedApplication];
    isEmbedded = ![NSApp isKindOfClass:[NSApplicationAWT class]];

    if (!isEmbedded) {
        // Install run loop observers and set the AppKit Java thread name
        setUpAWTAppKit(true);
    }

    // AWT gets to this point BEFORE NSApplicationDidFinishLaunchingNotification is sent.
    if (![app isRunning]) {
        AWT_STARTUP_LOG(@"+[AWTStarter startAWT]: ![app isRunning]");
        // This is where the AWT AppKit thread parks itself to process events.
        [NSApplicationAWT runAWTLoopWithApp: app];
    } else {
        // We're either embedded, or showing a splash screen
        if (isEmbedded) {
            AWT_STARTUP_LOG(@"running embedded");

            // We don't track if the runloop is busy, so set it free to let AWT finish when it needs
            setBusy(NO);
        } else {
            AWT_STARTUP_LOG(@"running after showing a splash screen");
        }

        // Signal so that JNI_OnLoad can proceed.
        if (!wasOnMainThread) [AWTStarter appKitIsRunning:nil];

        // Proceed to exit this call as there is no reason to run the NSApplication event loop.
    }

    [pool drain];
}

@end

/*
 * Class:     sun_lwawt_macosx_LWCToolkit
 * Method:    nativeSyncQueue
 * Signature: (J)Z
 */
JNIEXPORT jboolean JNICALL Java_sun_lwawt_macosx_LWCToolkit_nativeSyncQueue
(JNIEnv *env, jobject self, jlong timeout)
{
    long currentEventNum = [AWTToolkit getEventCount];

    NSApplication* sharedApp = [NSApplication sharedApplication];
    if ([sharedApp isKindOfClass:[NSApplicationAWT class]]) {
        NSApplicationAWT* theApp = (NSApplicationAWT*)sharedApp;
        // We use two different API to post events to the application,
        //  - [NSApplication postEvent]
        //  - CGEventPost(), see CRobot.m
        // It was found that if we post an event via CGEventPost in robot and
        // immediately after this we will post the second event via
        // [NSApp postEvent] then sometimes the second event will be handled
        // first. The opposite isn't proved, but we use both here to be safer.

        // If the native drag is in progress, skip native sync.
        if (!AWTToolkit.inDoDragDropLoop) {
            [theApp postDummyEvent:false];
            [theApp waitForDummyEvent:timeout / 2.0];
        }
        if (!AWTToolkit.inDoDragDropLoop) {
            [theApp postDummyEvent:true];
            [theApp waitForDummyEvent:timeout / 2.0];
        }

    } else {
        // could happen if we are embedded inside SWT application,
        // in this case just spin a single empty block through
        // the event loop to give it a chance to process pending events
        [ThreadUtilities performOnMainThreadWaiting:YES block:^(){}];
    }

    if (([AWTToolkit getEventCount] - currentEventNum) != 0) {
        return JNI_TRUE;
    }

    return JNI_FALSE;
}

/*
 * Class:     sun_lwawt_macosx_LWCToolkit
 * Method:    flushNativeSelectors
 * Signature: ()J
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_LWCToolkit_flushNativeSelectors
(JNIEnv *env, jclass clz)
{
JNI_COCOA_ENTER(env);
        [ThreadUtilities performOnMainThreadWaiting:YES block:^(){}];
JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_LWCToolkit
 * Method:    beep
 * Signature: ()V
 */
JNIEXPORT void JNICALL
Java_sun_lwawt_macosx_LWCToolkit_beep
(JNIEnv *env, jobject self)
{
    NSBeep(); // produces both sound and visual flash, if configured in System Preferences
}

static UInt32 RGB(NSColor *c) {
    c = [c colorUsingColorSpaceName:NSCalibratedRGBColorSpace];
    if (c == nil)
    {
        return -1; // opaque white
    }

    CGFloat r, g, b, a;
    [c getRed:&r green:&g blue:&b alpha:&a];

    UInt32 ir = (UInt32) (r*255+0.5),
    ig = (UInt32) (g*255+0.5),
    ib = (UInt32) (b*255+0.5),
    ia = (UInt32) (a*255+0.5);

    //    NSLog(@"%@ %d, %d, %d", c, ir, ig, ib);

    return ((ia & 0xFF) << 24) | ((ir & 0xFF) << 16) | ((ig & 0xFF) << 8) | ((ib & 0xFF) << 0);
}

BOOL doLoadNativeColors(JNIEnv *env, jintArray jColors, BOOL useAppleColors) {
    jint len = (*env)->GetArrayLength(env, jColors);

    UInt32 colorsArray[len];
    UInt32 *colors = colorsArray;

    [ThreadUtilities performOnMainThreadWaiting:YES block:^(){
        NSUInteger i;
        for (i = 0; i < len; i++) {
            colors[i] = RGB([CSystemColors getColor:i useAppleColor:useAppleColors]);
        }
    }];

    jint *_colors = (*env)->GetPrimitiveArrayCritical(env, jColors, 0);
    if (_colors == NULL) {
        return NO;
    }
    memcpy(_colors, colors, len * sizeof(UInt32));
    (*env)->ReleasePrimitiveArrayCritical(env, jColors, _colors, 0);
    return YES;
}

/**
 * Class:     sun_lwawt_macosx_LWCToolkit
 * Method:    loadNativeColors
 * Signature: ([I[I)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_LWCToolkit_loadNativeColors
(JNIEnv *env, jobject peer, jintArray jSystemColors, jintArray jAppleColors)
{
JNI_COCOA_ENTER(env);
    if (doLoadNativeColors(env, jSystemColors, NO)) {
        doLoadNativeColors(env, jAppleColors, YES);
    }
JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_LWCToolkit
 * Method:    createAWTRunLoopMediator
 * Signature: ()J
 */
JNIEXPORT jlong JNICALL Java_sun_lwawt_macosx_LWCToolkit_createAWTRunLoopMediator
(JNIEnv *env, jclass clz)
{
AWT_ASSERT_APPKIT_THREAD;

    jlong result;

JNI_COCOA_ENTER(env);
    // We double retain because this object is owned by both main thread and "other" thread
    // We release in both doAWTRunLoop and stopAWTRunLoop
    result = ptr_to_jlong([[[AWTRunLoopObject alloc] init] retain]);
JNI_COCOA_EXIT(env);

    return result;
}

/*
 * Class:     sun_lwawt_macosx_LWCToolkit
 * Method:    doAWTRunLoopImpl
 * Signature: (JZZI)Z
 */
JNIEXPORT jboolean JNICALL Java_sun_lwawt_macosx_LWCToolkit_doAWTRunLoopImpl
(JNIEnv *env, jclass clz, jlong mediator, jboolean processEvents, jboolean inAWT, jdouble timeoutSeconds/*(<=0.0>) for infinite*/)
{
    AWT_ASSERT_APPKIT_THREAD;
    jboolean result = JNI_TRUE;
JNI_COCOA_ENTER(env);

    /* defensive programming (should not happen) */
    if ([ThreadUtilities blockingMainThread]) {
        NSLog(@"LWCToolkit_doAWTRunLoopImpl: invalid state: blockingMainThread = YES !");
        return JNI_FALSE;
    }

    AWTRunLoopObject* mediatorObject = (AWTRunLoopObject*)jlong_to_ptr(mediator);

    if (mediatorObject == nil) {
        return JNI_TRUE;
    }

    /*
     * 2025.02: infinite timeout means possible deadlocks or freezes may happen.
     * To ensure responsiveness, infinite is limited to a huge delay (~10s)
     */
    if ((timeoutSeconds <= 0.0) || (timeoutSeconds > WAIT_TIMEOUT_LIMIT)) {
        timeoutSeconds = WAIT_TIMEOUT_LIMIT;
    }

    NSDate *timeoutDate = [NSDate dateWithTimeIntervalSinceNow:timeoutSeconds];
    if (TRACE_RUN_LOOP) {
        NSLog(@"LWCToolkit_doAWTRunLoopImpl: timeoutDate = %s", [[timeoutDate description] UTF8String]);
        NSLog(@"LWCToolkit_doAWTRunLoopImpl: processEvents = %d", processEvents);
    }
    NSRunLoopMode criticalRunMode = [ThreadUtilities criticalRunLoopMode];
    NSRunLoopMode runMode = inAWT ? [ThreadUtilities javaRunLoopMode] : NSDefaultRunLoopMode;
    if (TRACE_RUN_LOOP) NSLog(@"LWCToolkit_doAWTRunLoopImpl: runMode = %@", runMode);

    // Don't use acceptInputForMode because that doesn't setup autorelease pools properly
    BOOL isRunning = true;
    NSDate *deadlineDate = nil;

    while (![mediatorObject shouldEndRunLoop] && isRunning) {
        // always process critical events:
        // Check every few ms at least:
        deadlineDate = [NSDate dateWithTimeIntervalSinceNow:RUN_LOOP_TICK_CRITICAL];

        // Runs the loop once, blocking for input in the specified mode until the deadline date:
        BOOL hasRunCritical = [[NSRunLoop currentRunLoop] runMode:criticalRunMode beforeDate:deadlineDate];
        [deadlineDate release];
        if (TRACE_RUN_LOOP) NSLog(@"LWCToolkit_doAWTRunLoopImpl: hasRunCritical = %d", hasRunCritical);

        // Check every few ms at least:
        deadlineDate = [NSDate dateWithTimeIntervalSinceNow:RUN_LOOP_TICK];

        // Runs the loop once, blocking for input in the specified mode until the deadline date:
        isRunning = [[NSRunLoop currentRunLoop] runMode:runMode beforeDate:deadlineDate];
        [deadlineDate release];
        if (TRACE_RUN_LOOP) NSLog(@"LWCToolkit_doAWTRunLoopImpl: isRunning = %d", isRunning);

        NSDate *now = [[NSDate alloc] init];
        if ([timeoutDate compare:(now)] == NSOrderedAscending) {
            [now release];
            result = JNI_FALSE;
            break;
        }
        [now release];

        if (processEvents) {
            // We do not spin a runloop here as date is nil, so does not matter which mode to use
            // Processing all events excluding NSApplicationDefined which need to be processed
            // on the main loop only (those events are intended for disposing resources)
            NSEvent *event = [NSApp nextEventMatchingMask:(NSAnyEventMask & ~NSApplicationDefinedMask)
                                                untilDate:nil
                                                   inMode:NSDefaultRunLoopMode
                                                  dequeue:YES];
            if (event != nil) {
                if ([event.window isKindOfClass:[AWTWindow_Normal class]]) {
                    // Filter only events from AWTWindow (to skip events from ScreenMenu)
                    // See https://youtrack.jetbrains.com/issue/IDEA-305287/Implement-non-blocking-ScreenMenu.invokeOpenLater#focus=Comments-27-6614719.0-0
                    if (TRACE_RUN_LOOP) NSLog(@"LWCToolkit_doAWTRunLoopImpl: send event = %@", event);
                    [NSApp sendEvent:event];
                } else {
                    if (TRACE_RUN_LOOP) NSLog(@"LWCToolkit_doAWTRunLoopImpl: discarded event = %@", event);
                }
            }
        }
    } // while loop

    [timeoutDate release];
    [mediatorObject release];

    if (TRACE_RUN_LOOP) NSLog(@"LWCToolkit_doAWTRunLoopImpl: exit result = %d", result);
JNI_COCOA_EXIT(env);

    return result;
}

/*
 * Class:     sun_lwawt_macosx_LWCToolkit
 * Method:    isBlockingEventDispatchThread
 * Signature: ()Z
 */
JNIEXPORT jboolean JNICALL Java_sun_lwawt_macosx_LWCToolkit_isBlockingEventDispatchThread
        (JNIEnv *env, jclass clz)
{
    return ThreadUtilities.blockingEventDispatchThread;
}

/*
 * Class:     sun_lwawt_macosx_LWCToolkit
 * Method:    isBlockingMainThread
 * Signature: ()Z
 */
JNIEXPORT jboolean JNICALL Java_sun_lwawt_macosx_LWCToolkit_isBlockingMainThread
        (JNIEnv *env, jclass clz)
{
    return [ThreadUtilities blockingMainThread];
}

/*
 * Class:     sun_lwawt_macosx_LWCToolkit
 * Method:    getThreadTraceContexts
 * Signature: (Ljava/lang/String)
 */
JNIEXPORT jstring JNICALL Java_sun_lwawt_macosx_LWCToolkit_getThreadTraceContexts
        (JNIEnv *env, jclass clz)
{
JNI_COCOA_ENTER(env);

    // Convert NSString* to JavaString
    NSString* result = [ThreadUtilities getThreadTraceContexts];

    jstring javaString = (*env)->NewStringUTF(env, result.UTF8String);
    [result release];
    return javaString;

JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_LWCToolkit
 * Method:    stopAWTRunLoop
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_LWCToolkit_stopAWTRunLoop
(JNIEnv *env, jclass clz, jlong mediator)
{
JNI_COCOA_ENTER(env);

    AWTRunLoopObject* mediatorObject = (AWTRunLoopObject*)jlong_to_ptr(mediator);

    if (mediatorObject == nil) return;

    [ThreadUtilities performOnMainThread:@selector(endRunLoop) on:mediatorObject withObject:nil waitUntilDone:NO];

    [mediatorObject release];

JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_LWCToolkit
 * Method:    performOnMainThreadAfterDelay
 * Signature: (Ljava/lang/Runnable;J)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_LWCToolkit_performOnMainThreadAfterDelay
(JNIEnv *env, jclass clz, jobject runnable, jlong delay)
{
JNI_COCOA_ENTER(env);
    jobject gRunnable = (*env)->NewGlobalRef(env, runnable);
    CHECK_NULL(gRunnable);
    [ThreadUtilities performOnMainThreadWaiting:NO block:^() {
        JavaRunnable* performer = [[JavaRunnable alloc] initWithRunnable:gRunnable];
        [performer performSelector:@selector(perform) withObject:nil afterDelay:(delay/1000.0)];
    }];
JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_LWCToolkit
 * Method:    performOnMainThreadAndWait
 * Signature: (Ljava/lang/Runnable)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_LWCToolkit_performOnMainThreadAndWait
(JNIEnv *env, jclass clz, jobject runnable)
{
JNI_COCOA_ENTER(env);
    jobject gRunnable = (*env)->NewGlobalRef(env, runnable);
    CHECK_NULL(gRunnable);
    [ThreadUtilities performOnMainThreadWaiting:YES block:^() {
        JavaRunnable* performer = [[JavaRunnable alloc] initWithRunnable:gRunnable];
        [performer perform];
    }];
JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_LWCToolkit
 * Method:    isCapsLockOn
 * Signature: ()Z
 */
JNIEXPORT jboolean JNICALL Java_sun_lwawt_macosx_LWCToolkit_isCapsLockOn
(JNIEnv *env, jobject self)
{
    __block jboolean isOn = JNI_FALSE;
    [ThreadUtilities performOnMainThreadWaiting:YES block:^(){
        NSUInteger modifiers = [NSEvent modifierFlags];
        isOn = (modifiers & NSAlphaShiftKeyMask) != 0;
    }];

    return isOn;
}

/*
 * Class:     sun_lwawt_macosx_LWCToolkit
 * Method:    isApplicationActive
 * Signature: ()Z
 */
JNIEXPORT jboolean JNICALL Java_sun_lwawt_macosx_LWCToolkit_isApplicationActive
(JNIEnv *env, jclass clazz)
{
    __block jboolean active = JNI_FALSE;

JNI_COCOA_ENTER(env);

    [ThreadUtilities performOnMainThreadWaiting:YES block:^() {
        active = (jboolean)[NSRunningApplication currentApplication].active;
    }];

JNI_COCOA_EXIT(env);

    return active;
}

/*
 * Class:     sun_lwawt_macosx_LWCToolkit
 * Method:    activateApplicationIgnoringOtherApps
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_LWCToolkit_activateApplicationIgnoringOtherApps
(JNIEnv *env, jclass clazz)
{
    JNI_COCOA_ENTER(env);
    [ThreadUtilities performOnMainThreadWaiting:NO block:^(){
        if(![NSApp isActive]){
            [NSApp activateIgnoringOtherApps:YES];
        }
    }];
    JNI_COCOA_EXIT(env);
}


/*
 * Class:     sun_awt_SunToolkit
 * Method:    closeSplashScreen
 * Signature: ()V
 */
JNIEXPORT void JNICALL
Java_sun_awt_SunToolkit_closeSplashScreen(JNIEnv *env, jclass cls)
{
    void *hSplashLib = dlopen(0, RTLD_LAZY);
    if (!hSplashLib) return;

    void (*splashClose)() = dlsym(hSplashLib, "SplashClose");
    if (splashClose) {
        splashClose();
    }
    dlclose(hSplashLib);
}


// TODO: definitely doesn't belong here (copied from fontpath.c in the
// solaris tree)...

JNIEXPORT jstring JNICALL
Java_sun_font_FontManager_getFontPath
(JNIEnv *env, jclass obj, jboolean noType1)
{
    return NSStringToJavaString(env, @"/Library/Fonts");
}

// This isn't yet used on unix, the implementation is added since shared
// code calls this method in preparation for future use.
JNIEXPORT void JNICALL
Java_sun_font_FontManager_populateFontFileNameMap
(JNIEnv *env, jclass obj, jobject fontToFileMap, jobject fontToFamilyMap, jobject familyToFontListMap, jobject locale)
{

}

/*
 * Class:     sun_lwawt_macosx_LWCToolkit
 * Method:    initIDs
 * Signature: ()V
 */
JNIEXPORT void JNICALL
Java_sun_lwawt_macosx_LWCToolkit_initIDs
(JNIEnv *env, jclass klass) {

    JNI_COCOA_ENTER(env);

    gNumberOfButtons = sun_lwawt_macosx_LWCToolkit_BUTTONS;

    jclass inputEventClazz = (*env)->FindClass(env, "java/awt/event/InputEvent");
    CHECK_NULL(inputEventClazz);
    jmethodID getButtonDownMasksID = (*env)->GetStaticMethodID(env, inputEventClazz, "getButtonDownMasks", "()[I");
    CHECK_NULL(getButtonDownMasksID);
    jintArray obj = (jintArray)(*env)->CallStaticObjectMethod(env, inputEventClazz, getButtonDownMasksID);
    CHECK_EXCEPTION();
    jint * tmp = (*env)->GetIntArrayElements(env, obj, NULL);
    CHECK_NULL(tmp);

    gButtonDownMasks = (jint*)SAFE_SIZE_ARRAY_ALLOC(malloc, sizeof(jint), gNumberOfButtons);
    if (gButtonDownMasks == NULL) {
        gNumberOfButtons = 0;
        (*env)->ReleaseIntArrayElements(env, obj, tmp, JNI_ABORT);
        JNU_ThrowOutOfMemoryError(env, NULL);
        return;
    }

    int i;
    for (i = 0; i < gNumberOfButtons; i++) {
        gButtonDownMasks[i] = tmp[i];
    }

    (*env)->ReleaseIntArrayElements(env, obj, tmp, 0);
    (*env)->DeleteLocalRef(env, obj);

    JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_LWCToolkit
 * Method:    initAppkit
 * Signature: (Ljava/lang/ThreadGroup;)V
 */
JNIEXPORT void JNICALL
Java_sun_lwawt_macosx_LWCToolkit_initAppkit
(JNIEnv *env, jclass klass, jobject appkitThreadGroup, jboolean headless) {
    JNI_COCOA_ENTER(env);

    [ThreadUtilities setAppkitThreadGroup:(*env)->NewGlobalRef(env, appkitThreadGroup)];

    // Launcher sets this env variable if -XstartOnFirstThread is specified
    char envVar[80];
    snprintf(envVar, sizeof(envVar), "JAVA_STARTED_ON_FIRST_THREAD_%d", getpid());
    if (getenv(envVar) != NULL) {
        forceEmbeddedMode = YES;
        unsetenv(envVar);
    }

    if (isSWTInWebStart(env)) {
        forceEmbeddedMode = YES;
    }

    [AWTStarter start:headless ? YES : NO];

    JNI_COCOA_EXIT(env);
}

JNIEXPORT jint JNICALL DEF_JNI_OnLoad(JavaVM *vm, void *reserved) {
    OSXAPP_SetJavaVM(vm);

    // We need to let Foundation know that this is a multithreaded application,
    // if it isn't already.
    if (![NSThread isMultiThreaded]) {
        [[[[NSThread alloc] init] autorelease] start];
    }

    return JNI_VERSION_1_4;
}

/*
 * Class:     sun_lwawt_macosx_LWCToolkit
 * Method:    isEmbedded
 * Signature: ()Z
 */
JNIEXPORT jboolean JNICALL
Java_sun_lwawt_macosx_LWCToolkit_isEmbedded
(JNIEnv *env, jclass klass) {
    return isEmbedded ? JNI_TRUE : JNI_FALSE;
}

/*
 * Class:     sun_awt_PlatformGraphicsInfo
 * Method:    isInAquaSession
 * Signature: ()Z
 */
JNIEXPORT jboolean JNICALL
Java_sun_awt_PlatformGraphicsInfo_isInAquaSession
(JNIEnv *env, jclass klass) {
    // originally from java.base/macosx/native/libjava/java_props_macosx.c
    // environment variable to bypass the aqua session check
    char *ev = getenv("AWT_FORCE_HEADFUL");
    if (ev && (strncasecmp(ev, "true", 4) == 0)) {
        // if "true" then tell the caller we're in an Aqua session without
        // actually checking
        return JNI_TRUE;
    }
    // Is the WindowServer available?
    SecuritySessionId session_id;
    SessionAttributeBits session_info;
    OSStatus status = SessionGetInfo(callerSecuritySession, &session_id, &session_info);
    if (status == noErr) {
        if (session_info & sessionHasGraphicAccess) {
            return JNI_TRUE;
        }
    }
    return JNI_FALSE;
}

/*
 * Class:     sun_lwawt_macosx_LWCToolkit
 * Method:    getMultiClickTime
 * Signature: ()I
 */
JNIEXPORT jint JNICALL
Java_sun_lwawt_macosx_LWCToolkit_getMultiClickTime(JNIEnv *env, jclass klass) {
    __block jint multiClickTime = 0;
    JNI_COCOA_ENTER(env);
    [ThreadUtilities performOnMainThreadWaiting:YES block:^(){
        multiClickTime = (jint)([NSEvent doubleClickInterval] * 1000);
    }];
    JNI_COCOA_EXIT(env);
    return multiClickTime;
}

/*
 * Class:     sun_lwawt_macosx_LWCToolkit
 * Method:    getKeyboardLayoutNativeId
 * Signature: ()Ljava/lang/String;
 */
JNIEXPORT jstring JNICALL
JNICALL Java_sun_lwawt_macosx_LWCToolkit_getKeyboardLayoutNativeId(JNIEnv *env, jclass cls) {
    jstring jLayoutId = NULL;
    JNI_COCOA_ENTER(env);
        __block CFStringRef layoutId = nil;
        [ThreadUtilities performOnMainThreadWaiting:YES block:^(){
            TISInputSourceRef source = TISCopyCurrentKeyboardInputSource();
            layoutId = (CFStringRef)CFRetain(TISGetInputSourceProperty(source, kTISPropertyInputSourceID));
            CFRelease(source);
        }];
        jLayoutId = NSStringToJavaString(env, (NSString*)layoutId);
        CFRelease(layoutId);
    JNI_COCOA_EXIT(env);
    return jLayoutId;
}

/*
 * Class:     sun_lwawt_macosx_LWCToolkit
 * Method:    switchKeyboardLayoutNative
 * Signature: (Ljava/lang/String;)V
 */
JNIEXPORT jboolean JNICALL
JNICALL Java_sun_lwawt_macosx_LWCToolkit_switchKeyboardLayoutNative(JNIEnv *env, jclass cls, jstring jLayoutId) {
    __block bool success = false;
    JNI_COCOA_ENTER(env);
        __block NSString* layoutId = JavaStringToNSString(env, jLayoutId);
        [ThreadUtilities performOnMainThreadWaiting:YES block:^(){
            NSDictionary* searchParam = @{ (NSString*)kTISPropertyInputSourceID : layoutId };
            CFArrayRef sources = TISCreateInputSourceList((CFDictionaryRef)searchParam, NO);

            if (CFArrayGetCount(sources) == 0) {
                NSLog(@"failed to switch to keyboard layout %@: no such layout", layoutId);
                CFRelease(sources);
                return;
            }

            TISInputSourceRef source = (TISInputSourceRef)CFArrayGetValueAtIndex(sources, 0);
            OSStatus status = TISSelectInputSource(source);
            if (status == noErr) {
                success = true;
            } else {
                NSLog(@"failed to switch to keyboard layout %@, error code %d", layoutId, status);
            }

            CFRelease(sources);
        }];
    JNI_COCOA_EXIT(env);
    return success;
}

/*
 * Class:     sun_lwawt_macosx_LWCToolkit
 * Method:    getKeyboardLayoutListNative
 * Signature: (Z)[Ljava/lang/String;
 */
JNIEXPORT jarray JNICALL
JNICALL Java_sun_lwawt_macosx_LWCToolkit_getKeyboardLayoutListNative(JNIEnv *env, jclass cls, jboolean includeAll) {
    jclass stringClazz = (*env)->FindClass(env, "java/lang/String");
    jarray out = NULL;
    JNI_COCOA_ENTER(env);
        __block NSMutableArray* layoutIds = [[[NSMutableArray alloc] init] autorelease];
        [ThreadUtilities performOnMainThreadWaiting:YES block:^(){
            CFArrayRef sources = TISCreateInputSourceList(nil, includeAll);
            int numSources = CFArrayGetCount(sources);

            for (int i = 0; i < numSources; ++i) {
                TISInputSourceRef layout = CFArrayGetValueAtIndex(sources, i);
                NSString* sourceId = TISGetInputSourceProperty(layout, kTISPropertyInputSourceID);
                [layoutIds addObject:sourceId];
            }

            CFRelease(sources);
        }];

        int numLayoutIds = (int)[layoutIds count];
        out = (*env)->NewObjectArray(env, numLayoutIds, stringClazz, NULL);
        for (int i = 0; i < numLayoutIds; ++i) {
            NSString* layoutId = [layoutIds objectAtIndex:i];
            jstring jLayoutId = NSStringToJavaString(env, layoutId);
            (*env)->SetObjectArrayElement(env, out, i, jLayoutId);
        }
    JNI_COCOA_EXIT(env);
    return out;
}

/*
 * Class:     sun_lwawt_macosx_LWCToolkit
 * Method:    setKeyboardLayoutEnabledNative
 * Signature: (Ljava/lang/String;Z)V
 */
JNIEXPORT jboolean JNICALL
JNICALL Java_sun_lwawt_macosx_LWCToolkit_setKeyboardLayoutEnabledNative(JNIEnv *env, jclass cls, jstring jLayoutId, jboolean enabled) {
    __block bool success = false;
    JNI_COCOA_ENTER(env);
        __block NSString* layoutId = JavaStringToNSString(env, jLayoutId);
        [ThreadUtilities performOnMainThreadWaiting:YES block:^(){
            NSDictionary* searchParam = @{ (NSString*)kTISPropertyInputSourceID : layoutId };
            CFArrayRef sources = TISCreateInputSourceList((CFDictionaryRef)searchParam, YES);

            if (CFArrayGetCount(sources) == 0) {
                NSLog(@"failed to set keyboard layout %@ enabled state: no such layout", layoutId);
                CFRelease(sources);
                return;
            }

            TISInputSourceRef source = (TISInputSourceRef)CFArrayGetValueAtIndex(sources, 0);
            OSStatus status = enabled ? TISEnableInputSource(source) : TISDisableInputSource(source);
            if (status == noErr) {
                success = true;
            } else {
                NSLog(@"failed to set keyboard layout %@ enabled state, error code %d", layoutId, status);
            }

            CFRelease(sources);
        }];
    JNI_COCOA_EXIT(env);
    return success;
}

/*
 * Class:     sun_lwawt_macosx_LWCToolkit
 * Method:    setCapsLockState
 * Signature: (Z)Z
 */
JNIEXPORT jboolean JNICALL
JNICALL Java_sun_lwawt_macosx_LWCToolkit_setCapsLockState(JNIEnv *env, jclass cls, jboolean on) {
    __block kern_return_t err = KERN_SUCCESS;
    JNI_COCOA_ENTER(env);
        [ThreadUtilities performOnMainThreadWaiting:YES block:^() {
            io_service_t ioHIDService = IOServiceGetMatchingService(kIOMasterPortDefault,
                                                                    IOServiceMatching(kIOHIDSystemClass));
            if (!ioHIDService) {
                err = KERN_FAILURE;
                return;
            }

            io_connect_t ioHIDConnect;
            err = IOServiceOpen(ioHIDService, mach_task_self(), kIOHIDParamConnectType, &ioHIDConnect);
            if (err == KERN_SUCCESS) {
                err = IOHIDSetModifierLockState(ioHIDConnect, kIOHIDCapsLockState, on);
                IOServiceClose(ioHIDConnect);
            }

            IOObjectRelease(ioHIDService);
        }];
    JNI_COCOA_EXIT(env);
    return err == KERN_SUCCESS;
}
