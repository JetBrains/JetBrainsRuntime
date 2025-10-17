/*
 * Copyright (c) 2011, 2023, Oracle and/or its affiliates. All rights reserved.
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

#import "NSApplicationAWT.h"

#import <objc/runtime.h>
#import <ExceptionHandling/NSExceptionHandler.h>
#import <JavaRuntimeSupport/JavaRuntimeSupport.h>

#import "PropertiesUtilities.h"
#import "ThreadUtilities.h"
#import "QueuingApplicationDelegate.h"
#import "AWTIconData.h"

/*
 * Declare library specific JNI_Onload entry if static build
 */
DEF_STATIC_JNI_OnLoad

/* may be worth to use system properties ? */
#define MAX_RETRY_WRITE_EXCEPTION   3
#define MAX_WRITE_EXCEPTION         50
#define MAX_LOGGED_EXCEPTION        1000

#define JBR_ERR_PID_FILE            @"%@/jbr_err_pid%d.log"
/** JBR file pattern with extra count parameter (see MAX_WRITE_EXCEPTION) */
#define JBR_ERR_PID_FILE_COUNT      @"%@/jbr_err_pid%d-%02d.log"

#define NO_FILE_LINE_FUNCTION_ARGS file:nil line:0 function:nil

static BOOL sUsingDefaultNIB = YES;
static NSString *SHARED_FRAMEWORK_BUNDLE = @"/System/Library/Frameworks/JavaVM.framework";
static id <NSApplicationDelegate> applicationDelegate = nil;
static QueuingApplicationDelegate * qad = nil;

// Flag used to indicate to the Plugin2 event synthesis code to do a postEvent instead of sendEvent
BOOL postEventDuringEventSynthesis = NO;

/**
 * Subtypes of NSApplicationDefined, which are used for custom events.
 */
enum {
    ExecuteBlockEvent = 777, NativeSyncQueueEvent
};

@implementation NSApplicationAWT

- (id) init
{
    // Headless: NO
    // Embedded: NO
    // Multiple Calls: NO
    //  Caller: +[NSApplication sharedApplication]

AWT_ASSERT_APPKIT_THREAD;
    fApplicationName = nil;
    dummyEventTimestamp = 0.0;
    seenDummyEventLock = nil;


    // NSApplication will call _RegisterApplication with the application's bundle, but there may not be one.
    // So, we need to call it ourselves to ensure the app is set up properly.
    [self registerWithProcessManager];

    return [super init];
}

- (void)dealloc
{
    [fApplicationName release];
    fApplicationName = nil;

    [super dealloc];
}

- (void)finishLaunching
{
AWT_ASSERT_APPKIT_THREAD;

    JNIEnv *env = [ThreadUtilities getJNIEnv];

    SEL appearanceSel = @selector(setAppearance:); // macOS 10.14+
    if ([self respondsToSelector:appearanceSel]) {
        NSString *appearanceProp = [PropertiesUtilities
                javaSystemPropertyForKey:@"apple.awt.application.appearance"
                                 withEnv:env];
        if (![@"system" isEqual:appearanceProp]) {
            // by default use light mode, because dark mode is not supported yet
            NSAppearance *appearance = [NSAppearance appearanceNamed:NSAppearanceNameAqua];
            if (appearanceProp != nil) {
                NSAppearance *requested = [NSAppearance appearanceNamed:appearanceProp];
                if (requested != nil) {
                    appearance = requested;
                }
            }
            // [self setAppearance:appearance];
            [self performSelector:appearanceSel withObject:appearance];
        }
    }

    // Get default nib file location
    // NOTE: This should learn about the current java.version. Probably best thru
    //  the Makefile system's -DFRAMEWORK_VERSION define. Need to be able to pass this
    //  thru to PB from the Makefile system and for local builds.
    NSString *defaultNibFile = [PropertiesUtilities javaSystemPropertyForKey:@"apple.awt.application.nib" withEnv:env];
    if (!defaultNibFile) {
        NSBundle *javaBundle = [NSBundle bundleWithPath:SHARED_FRAMEWORK_BUNDLE];
        defaultNibFile = [javaBundle pathForResource:@"DefaultApp" ofType:@"nib"];
    } else {
        sUsingDefaultNIB = NO;
    }

    [NSBundle loadNibFile:defaultNibFile externalNameTable: [NSDictionary dictionaryWithObject:self forKey:@"NSOwner"] withZone:nil];

    // Set user defaults to not try to parse application arguments.
    NSDictionary *defaults = [NSDictionary dictionaryWithObjectsAndKeys:
                                 (shouldCrashOnException() ? @"YES" : @"NO"), @"NSApplicationCrashOnExceptions",
                                 @"NO", @"NSTreatUnknownArgumentsAsOpen",
                                 /* fix for JBR-3127 Modal dialogs invoked from modal or floating dialogs are opened in full screen */
                                 @"NO", @"NSWindowAllowsImplicitFullScreen",
                                 nil];

    [[NSUserDefaults standardUserDefaults] registerDefaults:defaults];

    // Fix up the dock icon now that we are registered with CAS and the Dock.
    [self setDockIconWithEnv:env];

    // If we are using our nib (the default application NIB) we need to put the app name into
    // the application menu, which has placeholders for the name.
    if (sUsingDefaultNIB) {
        NSUInteger i, itemCount;
        NSMenu *theMainMenu = [NSApp mainMenu];

        // First submenu off the main menu is the application menu.
        NSMenuItem *appMenuItem = [theMainMenu itemAtIndex:0];
        NSMenu *appMenu = [appMenuItem submenu];
        itemCount = [appMenu numberOfItems];

        for (i = 0; i < itemCount; i++) {
            NSMenuItem *anItem = [appMenu itemAtIndex:i];
            NSString *oldTitle = [anItem title];
            [anItem setTitle:[NSString stringWithFormat:oldTitle, fApplicationName]];
        }
    }

    if (applicationDelegate) {
        [self setDelegate:applicationDelegate];
    } else {
        qad = [QueuingApplicationDelegate sharedDelegate];
        [self setDelegate:qad];
    }

    [super finishLaunching];

    // temporary possibility to load deprecated NSJavaVirtualMachine (just for testing)
    // todo: remove when completely tested on BigSur
    // see https://youtrack.jetbrains.com/issue/JBR-3127#focus=Comments-27-4684465.0-0
    NSString * loadNSJVMProp = [PropertiesUtilities
            javaSystemPropertyForKey:@"apple.awt.application.instantiate.NSJavaVirtualMachine"
                             withEnv:env];
    if ([@"true" isCaseInsensitiveLike:loadNSJVMProp]) {
        if (objc_lookUpClass("NSJavaVirtualMachine") != nil) {
            NSLog(@"objc class NSJavaVirtualMachine is already registered");
        } else {
            Class nsjvm =  objc_allocateClassPair([NSObject class], "NSJavaVirtualMachine", 0);
            objc_registerClassPair(nsjvm);
            NSLog(@"registered class NSJavaVirtualMachine: %@", nsjvm);

            id nsjvmInst = [[nsjvm alloc] init];
            NSLog(@"instantiated dummy NSJavaVirtualMachine: %@", nsjvmInst);
        }
    }
}


- (void) registerWithProcessManager
{
    // Headless: NO
    // Embedded: NO
    // Multiple Calls: NO
    //  Caller: -[NSApplicationAWT init]

AWT_ASSERT_APPKIT_THREAD;
    JNIEnv *env = [ThreadUtilities getJNIEnv];

    char envVar[80];

    // The following environment variable is set from the -Xdock:name param. It should be UTF8.
    snprintf(envVar, sizeof(envVar), "APP_NAME_%d", getpid());
    char *appName = getenv(envVar);
    if (appName != NULL) {
        fApplicationName = [NSString stringWithUTF8String:appName];
        unsetenv(envVar);
    }

    // If it wasn't specified as an argument, see if it was specified as a system property.
    // The launcher code sets this if it is not already set on the command line.
    if (fApplicationName == nil) {
        fApplicationName = [PropertiesUtilities javaSystemPropertyForKey:@"apple.awt.application.name" withEnv:env];
    }

    // The dock name is nil for double-clickable Java apps (bundled and Web Start apps)
    // When that happens get the display name, and if that's not available fall back to
    // CFBundleName.
    NSBundle *mainBundle = [NSBundle mainBundle];
    if (fApplicationName == nil) {
        fApplicationName = (NSString *)[mainBundle objectForInfoDictionaryKey:@"CFBundleDisplayName"];

        if (fApplicationName == nil) {
            fApplicationName = (NSString *)[mainBundle objectForInfoDictionaryKey:(NSString *)kCFBundleNameKey];

            if (fApplicationName == nil) {
                fApplicationName = (NSString *)[mainBundle objectForInfoDictionaryKey: (NSString *)kCFBundleExecutableKey];

                if (fApplicationName == nil) {
                    // Name of last resort is the last part of the applicatoin name without the .app (consistent with CopyProcessName)
                    fApplicationName = [[mainBundle bundlePath] lastPathComponent];

                    if ([fApplicationName hasSuffix:@".app"]) {
                        fApplicationName = [fApplicationName stringByDeletingPathExtension];
                    }
                }
            }
        }
    }

    // We're all done trying to determine the app name.  Hold on to it.
    [fApplicationName retain];

    NSDictionary *registrationOptions = [NSMutableDictionary dictionaryWithObject:fApplicationName forKey:@"JRSAppNameKey"];

    NSString *launcherType = [PropertiesUtilities javaSystemPropertyForKey:@"sun.java.launcher" withEnv:env];
    if ([@"SUN_STANDARD" isEqualToString:launcherType]) {
        [registrationOptions setValue:[NSNumber numberWithBool:YES] forKey:@"JRSAppIsCommandLineKey"];
    }

    NSString *uiElementProp = [PropertiesUtilities javaSystemPropertyForKey:@"apple.awt.UIElement" withEnv:env];
    if ([@"true" isCaseInsensitiveLike:uiElementProp]) {
        [registrationOptions setValue:[NSNumber numberWithBool:YES] forKey:@"JRSAppIsUIElementKey"];
    }

    NSString *backgroundOnlyProp = [PropertiesUtilities javaSystemPropertyForKey:@"apple.awt.BackgroundOnly" withEnv:env];
    if ([@"true" isCaseInsensitiveLike:backgroundOnlyProp]) {
        [registrationOptions setValue:[NSNumber numberWithBool:YES] forKey:@"JRSAppIsBackgroundOnlyKey"];
    }

    // TODO replace with direct call
    // [JRSAppKitAWT registerAWTAppWithOptions:registrationOptions];
    // and remove below transform/activate/run hack

    id jrsAppKitAWTClass = objc_getClass("JRSAppKitAWT");
    SEL registerSel = @selector(registerAWTAppWithOptions:);
    if ([jrsAppKitAWTClass respondsToSelector:registerSel]) {
        [jrsAppKitAWTClass performSelector:registerSel withObject:registrationOptions];
        return;
    }

// HACK BEGIN
    // The following is necessary to make the java process behave like a
    // proper foreground application...
    [ThreadUtilities performOnMainThreadWaiting:NO block:^(){
        ProcessSerialNumber psn;
        GetCurrentProcess(&psn);
        TransformProcessType(&psn, kProcessTransformToForegroundApplication);

        [NSApp activateIgnoringOtherApps:YES];
        [NSApp run];
    }];
// HACK END
}

- (void) setDockIconWithEnv:(JNIEnv *)env {
    NSString *theIconPath = nil;

    // The following environment variable is set in java.c. It is probably UTF8.
    char envVar[80];
    snprintf(envVar, sizeof(envVar), "APP_ICON_%d", getpid());
    char *appIcon = getenv(envVar);
    if (appIcon != NULL) {
        theIconPath = [NSString stringWithUTF8String:appIcon];
        unsetenv(envVar);
    }

    if (theIconPath == nil) {
        theIconPath = [PropertiesUtilities javaSystemPropertyForKey:@"apple.awt.application.icon" withEnv:env];
    }

    // Use the path specified to get the icon image
    NSImage* iconImage = nil;
    if (theIconPath != nil) {
        iconImage = [[NSImage alloc] initWithContentsOfFile:theIconPath];
    }

    // If no icon file was specified or we failed to get the icon image
    // and there is no bundle's icon, then use the default icon
    if (iconImage == nil) {
        NSString* bundleIcon = [[NSBundle mainBundle] objectForInfoDictionaryKey:@"CFBundleIconFile"];
        if (bundleIcon == nil) {
            NSData* iconData;
            iconData = [[NSData alloc] initWithBytesNoCopy: sAWTIconData length: sizeof(sAWTIconData) freeWhenDone: NO];
            iconImage = [[NSImage alloc] initWithData: iconData];
            [iconData release];
        }
    }

    // Set up the dock icon if we have an icon image.
    if (iconImage != nil) {
        [NSApp setApplicationIconImage:iconImage];
        [iconImage release];
    }
}

+ (void) runAWTLoopWithApp:(NSApplication*)app {
    // Define the special Critical RunLoop mode to ensure action executed ASAP:
    [[NSRunLoop currentRunLoop] addPort:[NSPort port] forMode:[ThreadUtilities criticalRunLoopMode]];

    // Make sure that when we run in javaRunLoopMode we don't exit randomly
    [[NSRunLoop currentRunLoop] addPort:[NSPort port] forMode:[ThreadUtilities javaRunLoopMode]];

    do {
        NSAutoreleasePool *pool = [NSAutoreleasePool new];
        @try {
            [app run];
        } @catch (NSException *exception) {
            NSLog(@"Apple AWT runAWTLoop Exception: %@\nCallstack: %@",
                [exception description], [exception callStackSymbols]);

            // interrupt the run loop now:
            [NSApplicationAWT interruptAWTLoop];
        } @ finally {
            [pool drain];
        }
    } while (YES);
}

/*
 * Thread-safe:
 * stop the shared application (run loop [NSApplication run])
 * and restart it
 */
+ (void) interruptAWTLoop {
    NSLog(@"Apple AWT Restarting Native Event Thread...");
    NSApplication *app = [NSApplicationAWT sharedApplication];
    // mark the current run loop [NSApplication run] to be stopped:
    [app stop:app];
    // send event to interrupt the run loop now (after the current event is processed):
    [app abortModal];
}


- (BOOL)usingDefaultNib {
    return sUsingDefaultNIB;
}

- (void)orderFrontStandardAboutPanelWithOptions:(NSDictionary *)optionsDictionary {
    if (!optionsDictionary) {
        optionsDictionary = [NSMutableDictionary dictionaryWithCapacity:2];
        [optionsDictionary setValue:[[[[[NSApp mainMenu] itemAtIndex:0] submenu] itemAtIndex:0] title] forKey:@"ApplicationName"];
        if (![NSImage imageNamed:@"NSApplicationIcon"]) {
            [optionsDictionary setValue:[NSApp applicationIconImage] forKey:@"ApplicationIcon"];
        }
    }

    [super orderFrontStandardAboutPanelWithOptions:optionsDictionary];
}

#define DRAGMASK (NSMouseMovedMask | NSLeftMouseDraggedMask | NSRightMouseDownMask | NSRightMouseDraggedMask | NSLeftMouseUpMask | NSRightMouseUpMask | NSFlagsChangedMask | NSKeyDownMask)

#if defined(MAC_OS_X_VERSION_10_12) && __LP64__
   // 10.12 changed `mask` to NSEventMask (unsigned long long) for x86_64 builds.
- (NSEvent *)nextEventMatchingMask:(NSEventMask)mask
#else
- (NSEvent *)nextEventMatchingMask:(NSUInteger)mask
#endif
untilDate:(NSDate *)expiration inMode:(NSString *)mode dequeue:(BOOL)deqFlag {
    if (mask == DRAGMASK && [((NSString *)kCFRunLoopDefaultMode) isEqual:mode]) {
        postEventDuringEventSynthesis = YES;
    }

    NSEvent *event = [super nextEventMatchingMask:mask untilDate:expiration inMode:mode dequeue: deqFlag];
    postEventDuringEventSynthesis = NO;

    return event;
}

// NSTimeInterval has microseconds precision
#define TS_EQUAL(ts1, ts2) (fabs((ts1) - (ts2)) < 1e-6)

- (void)sendEvent:(NSEvent *)event {
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    @try {
        if ([event type] == NSApplicationDefined
                && TS_EQUAL([event timestamp], dummyEventTimestamp)
                && (short)[event subtype] == NativeSyncQueueEvent
                && [event data1] == NativeSyncQueueEvent
                && [event data2] == NativeSyncQueueEvent) {
            [seenDummyEventLock lockWhenCondition:NO];
            [seenDummyEventLock unlockWithCondition:YES];
        } else if ([event type] == NSApplicationDefined
                   && (short)[event subtype] == ExecuteBlockEvent
                   && [event data1] != 0 && [event data2] == ExecuteBlockEvent) {
            void (^block)() = (void (^)()) [event data1];
            block();
            [block release];
        } else if ([event type] == NSEventTypeKeyUp && ([event modifierFlags] & NSCommandKeyMask)) {
            // Cocoa won't send us key up event when releasing a key while Cmd is down,
            // so we have to do it ourselves.
            [[self keyWindow] sendEvent:event];
        } else {
            [super sendEvent:event];
        }
    } @catch (NSException *exception) {
        // report exception to the NSApplicationAWT exception handler:
        NSAPP_AWT_REPORT_EXCEPTION(exception, NO);
    } @finally {
        [pool drain];
    }
}

/*
 * Posts the block to the AppKit event queue which will be executed
 * on the main AppKit loop.
 * While running nested loops this event will be ignored.
 */
- (void)postRunnableEvent:(void (^)())block
{
    void (^copy)() = [block copy];
    NSInteger encode = (NSInteger) copy;

    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    @try {
        NSEvent* event = [NSEvent otherEventWithType: NSApplicationDefined
                                            location: NSMakePoint(0,0)
                                       modifierFlags: 0
                                           timestamp: 0
                                        windowNumber: 0
                                             context: nil
                                             subtype: ExecuteBlockEvent
                                               data1: encode
                                               data2: ExecuteBlockEvent];

        [NSApp postEvent: event atStart: NO];
    } @finally {
        [pool drain];
    }
}

- (void)postDummyEvent:(bool)useCocoa {
    seenDummyEventLock = [[NSConditionLock alloc] initWithCondition:NO];
    dummyEventTimestamp = [NSProcessInfo processInfo].systemUptime;

    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    @try {
        NSEvent* event = [NSEvent otherEventWithType: NSApplicationDefined
                                            location: NSMakePoint(0,0)
                                       modifierFlags: 0
                                           timestamp: dummyEventTimestamp
                                        windowNumber: 0
                                             context: nil
                                             subtype: NativeSyncQueueEvent
                                               data1: NativeSyncQueueEvent
                                               data2: NativeSyncQueueEvent];
        if (useCocoa) {
            [NSApp postEvent:event atStart:NO];
        } else {
            ProcessSerialNumber psn;
            GetCurrentProcess(&psn);
            CGEventPostToPSN(&psn, [event CGEvent]);
        }
    } @finally {
        [pool drain];
    }
}

- (void)waitForDummyEvent:(double)timeout {
    bool unlock = true;
    @try {
        if (timeout >= 0) {
            double sec = timeout / 1000;
            unlock = [seenDummyEventLock lockWhenCondition:YES
                                   beforeDate:[NSDate dateWithTimeIntervalSinceNow:sec]];
        } else {
            [seenDummyEventLock lockWhenCondition:YES];
        }
        if (unlock) {
            [seenDummyEventLock unlock];
        }
    } @finally {
        [seenDummyEventLock release];
        seenDummyEventLock = nil;
    }
}

// Provide helper methods to get the shared NSApplicationAWT instance
+ (BOOL) isNSApplicationAWT {
    return [NSApp isKindOfClass:[NSApplicationAWT class]];
}

+ (NSApplicationAWT*) sharedApplicationAWT {
    return [NSApplicationAWT isNSApplicationAWT] ? (NSApplicationAWT*)[NSApplication sharedApplication] : nil;
}

// Provide info from unhandled ObjectiveC exceptions

/*
 * Handle all exceptions handled by NSApplication.
 *
 * Note: depending on the userDefaults.NSApplicationCrashOnExceptions (YES/ NO) flag,
 *       (set in finishLaunching), calling with crash or not the JVM.
 */
- (void) reportException:(NSException *)exception {
    [self reportException:exception uncaught:NO NO_FILE_LINE_FUNCTION_ARGS];
}

- (void) _crashOnException:(NSException *)exception {
    [self crashOnException:exception uncaught:NO withEnv:[ThreadUtilities getJNIEnvUncached] NO_FILE_LINE_FUNCTION_ARGS];
}

// implementation (internals)
- (void) reportException:(NSException *)exception uncaught:(BOOL)uncaught
                    file:(const char*)file line:(int)line function:(const char*)function
{
    @autoreleasepool {
        JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
        if (shouldCrashOnException()) {
            // calling [super reportException:exception] will cause a crash
            // so _crashOnException:exception will be then called but losing details.
            // Use the direct approach (full details):
            [self crashOnException:exception uncaught:uncaught withEnv:env
                              file:file line:line function:function];
        } else {
            NSMutableString *info = [[[NSMutableString alloc] init] autorelease];
            [NSApplicationAWT logException:exception uncaught:uncaught forProcess:[NSProcessInfo processInfo]
                                   withEnv:env prefix:@"Reported " to:info file:file line:line function:function];

            // interrupt the run loop now:
            [NSApplicationAWT interruptAWTLoop];
        }
    }
}

- (void) crashOnException:(NSException *)exception uncaught:(BOOL)uncaught withEnv:(JNIEnv *)env
                     file:(const char*)file line:(int)line function:(const char*)function
{
    @autoreleasepool {
        NSMutableString *info = [[[NSMutableString alloc] init] autorelease];
        [NSApplicationAWT logException:exception uncaught:uncaught forProcess:[NSProcessInfo processInfo]
                               withEnv:env prefix:@"Crash: " to:info file:file line:line function:function];

        if (shouldCrashOnException()) {
            // Use JNU_Fatal macro to trigger JVM fatal error with the esception information and generate hs_err_ file
            JNU_Fatal(env, __FILE__, __LINE__, [info UTF8String]); \
        }
    }
}

// log exception methods
+ (void) logException:(NSException *)exception {
    [NSApplicationAWT logException:exception NO_FILE_LINE_FUNCTION_ARGS];
}

+ (void) logException:(NSException *)exception
                 file:(const char*)file line:(int)line function:(const char*)function
{
    [NSApplicationAWT logException:exception prefix:@"Log: "
                              file:file line:line function:function];
}

+ (void) logException:(NSException *)exception prefix:(NSString *)prefix
                 file:(const char*)file line:(int)line function:(const char*)function
{
    @autoreleasepool {
        JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
        NSMutableString *info = [[[NSMutableString alloc] init] autorelease];
        [NSApplicationAWT logException:exception uncaught:NO forProcess:[NSProcessInfo processInfo]
                               withEnv:env prefix:prefix to:info
                                  file:file line:line function:function];
    }
}

+ (void) logException:(NSException *)exception uncaught:(BOOL)uncaught forProcess:(NSProcessInfo *)processInfo
              withEnv:(JNIEnv *)env prefix:(NSString *)prefix to:(NSMutableString *)info
                 file:(const char*)file line:(int)line function:(const char*)function
{
    static int loggedException  = 0;
    static int writtenException = 0;

    if (loggedException < MAX_LOGGED_EXCEPTION) {
        [NSApplicationAWT toString:exception uncaught:uncaught prefix:prefix to:info
                              file:file line:line function:function];

        [NSApplicationAWT logMessage:info withEnv:env];

        if (++loggedException == MAX_LOGGED_EXCEPTION) {
            NSLog(@"Stop logging follow-up exceptions (max %d logged exceptions reached)",
                  MAX_LOGGED_EXCEPTION);
        }

        if (writtenException < MAX_WRITE_EXCEPTION) {
            const NSString *homePath = [processInfo environment][@"HOME"];
            if (homePath != nil) {
                const int processID = [processInfo processIdentifier];
                const NSString *fileName = [NSString stringWithFormat:JBR_ERR_PID_FILE, homePath, processID];

                BOOL available = NO;
                int retry = 0;
                NSString *realFileName = nil;
                int count = 0;
                do {
                    realFileName = (count == 0) ? fileName :
                            [NSString stringWithFormat:JBR_ERR_PID_FILE_COUNT, homePath, processID, count];

                    available = ![[NSFileManager defaultManager] fileExistsAtPath:realFileName];

                    if (available) {
                        NSLog(@"Writing exception to '%@'...", realFileName);
                        // write the exception atomatically to the file:
                        if ([info writeToFile:realFileName
                                    atomically:YES
                                      encoding:NSUTF8StringEncoding
                                         error:NULL])
                        {
                            if (++writtenException == MAX_WRITE_EXCEPTION) {
                                NSLog(@"Stop writing follow-up exceptions (max %d written exceptions reached)",
                                      MAX_WRITE_EXCEPTION);
                            }
                        } else {
                            if (++retry >= MAX_RETRY_WRITE_EXCEPTION) {
                                NSLog(@"Failed to write exception to '%@' after %d retries", realFileName,
                                      MAX_RETRY_WRITE_EXCEPTION);
                                break;
                            }
                            // retry few times:
                            available = NO;
                        }
                    }
                    count++;
                } while (!available);
            }
        }
    }
}

+ (void) logMessage:(NSString *)message {
    [NSApplicationAWT logMessage:message NO_FILE_LINE_FUNCTION_ARGS];
}

+ (void) logMessage:(NSString *)message
               file:(const char*)file line:(int)line function:(const char*)function
{
    @autoreleasepool {
        [NSApplicationAWT logMessage:message
                    callStackSymbols:[NSThread callStackSymbols]
                             withEnv:[ThreadUtilities getJNIEnvUncached]
                            file:file line:line function:function];
    }
}

+ (void) logMessage:(NSString *)message callStackSymbols:(NSArray<NSString *> *)callStackSymbols withEnv:(JNIEnv *)env
               file:(const char*)file line:(int)line function:(const char*)function
{
    NSMutableString *info = [[[NSMutableString alloc] init] autorelease];
    [NSApplicationAWT toString:message callStackSymbols:callStackSymbols to:info
                          file:file line:line function:function];

    [NSApplicationAWT logMessage:info withEnv:env];
}

+ (void) logMessage:(NSString *)info withEnv:(JNIEnv *)env {
    // Always log to the console first:
    NSLog(@"%@", info);
    // Send to PlatformLogger too:
    lwc_plog(env, "%s", info.UTF8String);
}

+ (void) toString:(NSException *)exception uncaught:(BOOL)uncaught prefix:(NSString *)prefix to:(NSMutableString *)info
             file:(const char*)file line:(int)line function:(const char*)function
{
    const char* uncaughtMark = (uncaught) ? "Uncaught " : "";

    NSString *header = (file != nil) ?
            [NSString stringWithFormat: @"%@%sException (%s:%d %s):\n%@\n", prefix, uncaughtMark, file, line, function, exception]
          : [NSString stringWithFormat: @"%@%sException (unknown):\n%@\n", prefix, uncaughtMark, exception];
    [info appendString:header];

    [NSApplicationAWT toString:[exception callStackSymbols] to:info];
}

+ (void) toString:(NSString *)message callStackSymbols:(NSArray<NSString *> *)callStackSymbols to:(NSMutableString *)info
             file:(const char*)file line:(int)line function:(const char*)function
{
    NSString *header = (file != nil) ?
            [NSString stringWithFormat: @"%@ (%s:%d %s):\n", message, file, line, function]
          : [NSString stringWithFormat: @"%@ (unknown):\n", message];
    [info appendString:header];

    [NSApplicationAWT toString:callStackSymbols to:info];
}

+ (void) toString:(NSArray<NSString *> *)callStackSymbols to:(NSMutableString *)info
{
    for (NSUInteger i = 0; i < callStackSymbols.count; i++) {
        [info appendFormat:@"  %@\n", callStackSymbols[i]];
    }
}

// --- NSExceptionHandlerDelegate category to handle all exceptions ---
- (BOOL) exceptionHandler:(NSExceptionHandler *)sender
    shouldHandleException:(NSException *)exception mask:(NSUInteger)aMask {
    /* handle all exception types to invoke reportException:exception */
    return YES;
}

- (BOOL) exceptionHandler:(NSExceptionHandler *)sender
       shouldLogException:(NSException *)exception mask:(NSUInteger)aMask {
    /* disable default logging (stderr) */
    return NO;
}
@end


void OSXAPP_SetApplicationDelegate(id <NSApplicationDelegate> newdelegate)
{
AWT_ASSERT_APPKIT_THREAD;
    applicationDelegate = newdelegate;

    if (NSApp != nil) {
        [NSApp setDelegate: applicationDelegate];

        if (applicationDelegate && qad) {
            [qad processQueuedEventsWithTargetDelegate: applicationDelegate];
            qad = nil;
        }
    }
}
