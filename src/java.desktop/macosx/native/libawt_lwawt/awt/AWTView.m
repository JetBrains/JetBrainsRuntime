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

#import "jni_util.h"
#import "CGLGraphicsConfig.h"
#import "AWTView.h"
#import "AWTEvent.h"
#import "AWTWindow.h"
#import "a11y/CommonComponentAccessibility.h"
#import "JavaAccessibilityUtilities.h"
#import "GeomUtilities.h"
#import "ThreadUtilities.h"
#import "JNIUtilities.h"
#import "jni_util.h"
#import "PropertiesUtilities.h"
#import "sun_lwawt_macosx_CPlatformWindow.h"

#import <Carbon/Carbon.h>

#define DEFAULT_FRAME_WIDTH 1504
#define DEFAULT_FRAME_HEIGHT 846

// keyboard layout
static NSString *kbdLayout;

// Constant for keyman layouts
#define KEYMAN_LAYOUT "keyman"

// workaround for JBR-6704
static unichar lastCtrlCombo;

@interface AWTView()
@property (retain) CDropTarget *_dropTarget;
@property (retain) CDragSource *_dragSource;

-(void) deliverResize: (NSRect) rect;
-(void) resetTrackingArea;
-(void) deliverJavaKeyEventHelper: (NSEvent*) event;
-(NSMutableString *) parseString : (id) complexString;
@end

// Uncomment this line to see fprintfs of each InputMethod API being called on this View
//#define IM_DEBUG TRUE
//#define LOG_KEY_EVENTS

static BOOL shouldUsePressAndHold() {
    return YES;
}

extern bool isSystemShortcut_NextWindowInApplication(NSUInteger modifiersMask, int keyCode, NSString * chars);

@implementation AWTView

@synthesize _dropTarget;
@synthesize _dragSource;
@synthesize cglLayer;
@synthesize mouseIsOver;

// Note: Must be called on main (AppKit) thread only
- (id) initWithRect: (NSRect) rect
       platformView: (jobject) cPlatformView
        windowLayer: (CALayer*) windowLayer
{
    AWT_ASSERT_APPKIT_THREAD;
    // Initialize ourselves
    self = [super initWithFrame: rect];
    if (self == nil) return self;

    m_cPlatformView = cPlatformView;
    fInputMethodLOCKABLE = NULL;
    fInputMethodInteractionEnabled = YES;
    fKeyEventsNeeded = NO;
    fProcessingKeystroke = NO;
    lastCtrlCombo = 0;

    fEnablePressAndHold = shouldUsePressAndHold();

    mouseIsOver = NO;
    [self resetTrackingArea];
    [self setAutoresizesSubviews:NO];

    if (windowLayer != nil) {
        self.cglLayer = windowLayer;
        //Layer hosting view
        [self setLayer: cglLayer];
        [self setWantsLayer: YES];
        //Layer backed view
        //[self.layer addSublayer: (CALayer *)cglLayer];
        //[self setLayerContentsRedrawPolicy: NSViewLayerContentsRedrawDuringViewResize];
        //[self setLayerContentsPlacement: NSViewLayerContentsPlacementTopLeft];
        //[self setAutoresizingMask: NSViewHeightSizable | NSViewWidthSizable];
    }

    return self;
}

- (void) dealloc {
    AWT_ASSERT_APPKIT_THREAD;

    self.cglLayer = nil;

    JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
    (*env)->DeleteWeakGlobalRef(env, m_cPlatformView);
    m_cPlatformView = NULL;

    if (fInputMethodLOCKABLE != NULL)
    {
        JNIEnv *env = [ThreadUtilities getJNIEnvUncached];

        (*env)->DeleteGlobalRef(env, fInputMethodLOCKABLE);
        fInputMethodLOCKABLE = NULL;
    }

    if (rolloverTrackingArea != nil) {
        [self removeTrackingArea:rolloverTrackingArea];
        [rolloverTrackingArea release];
        rolloverTrackingArea = nil;
    }

    [super dealloc];
}

- (void) viewDidMoveToWindow {
    AWT_ASSERT_APPKIT_THREAD;

    [AWTToolkit eventCountPlusPlus];

    [ThreadUtilities performOnMainThreadWaiting:NO block:^() {
        [[self window] makeFirstResponder: self];
    }];
    if ([self window] != NULL) {
        [self resetTrackingArea];
    }
}

- (void)setFrame:(NSRect)newFrame {
    if (isnan(newFrame.origin.x)) {
        newFrame.origin.x = 0;
    }

    if (isnan(newFrame.origin.y)) {
        newFrame.origin.y = 0;
    }

    if (isnan(newFrame.size.width)) {
        newFrame.size.width = DEFAULT_FRAME_WIDTH;
    }

    if (isnan(newFrame.size.height)) {
        newFrame.size.height = DEFAULT_FRAME_HEIGHT;
    }

    [super setFrame:newFrame];
}

- (BOOL) acceptsFirstMouse: (NSEvent *)event {
    return YES;
}

- (BOOL) acceptsFirstResponder {
    return YES;
}

- (BOOL) becomeFirstResponder {
    return YES;
}

- (BOOL) preservesContentDuringLiveResize {
    return YES;
}

/*
 * Automatically triggered functions.
 */

- (void)resizeWithOldSuperviewSize:(NSSize)oldBoundsSize {
    [super resizeWithOldSuperviewSize: oldBoundsSize];
    [self deliverResize: [self frame]];
}

/*
 * MouseEvents support
 */

- (BOOL)shouldDelayWindowOrderingForEvent:(NSEvent *)event {
    return [self isWindowDisabled];
}

- (void) mouseDown: (NSEvent *)event {
    if ([self isWindowDisabled]) {
        [NSApp preventWindowOrdering];
        [NSApp activateIgnoringOtherApps:YES];
    }

    JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
    NSString *checkForInputManagerInMouseDown =
            [PropertiesUtilities
             javaSystemPropertyForKey:@"apple.awt.checkForInputManagerInMouseDown"
                              withEnv:env];
    if ([@"true" isCaseInsensitiveLike:checkForInputManagerInMouseDown]) {
        NSInputManager *inputManager = [NSInputManager currentInputManager];
        if ([inputManager wantsToHandleMouseEvents]) {
#if IM_DEBUG
            NSLog(@"-> IM wants to handle event");
#endif
            if ([inputManager handleMouseEvent:event]) {
#if IM_DEBUG
                NSLog(@"-> Event was handled.");
                return;
#endif
            }
        } else {
#if IM_DEBUG
            NSLog(@"-> IM does not want to handle event");
#endif
        }
    }

    [self deliverJavaMouseEvent:event];
}

- (void) mouseUp: (NSEvent *)event {
    [self deliverJavaMouseEvent: event];
}

- (void) rightMouseDown: (NSEvent *)event {
    [self deliverJavaMouseEvent: event];
}

- (void) rightMouseUp: (NSEvent *)event {
    [self deliverJavaMouseEvent: event];
}

- (void) otherMouseDown: (NSEvent *)event {
    [self deliverJavaMouseEvent: event];
}

- (void) otherMouseUp: (NSEvent *)event {
    [self deliverJavaMouseEvent: event];
}

- (void) mouseMoved: (NSEvent *)event {
    // TODO: better way to redirect move events to the "under" view

    NSPoint eventLocation = [event locationInWindow];
    NSPoint localPoint = [self convertPoint: eventLocation fromView: nil];

    if  ([self mouse: localPoint inRect: [self bounds]]) {
        NSWindow* eventWindow = [event window];
        NSPoint screenPoint = (eventWindow == nil)
            ? eventLocation
            // SDK we build against is too old - it doesn't have convertPointToScreen
            : [eventWindow convertRectToScreen:NSMakeRect(eventLocation.x, eventLocation.y, 0, 0)].origin;
        // macOS can report mouseMoved events to a window even if it's not showing currently (see JBR-2702)
        // so we're performing an additional check here
        if (self.window.windowNumber == [NSWindow windowNumberAtPoint:screenPoint belowWindowWithWindowNumber:0]) {
            [self deliverJavaMouseEvent: event];
            return;
        }
    }
    [[self nextResponder] mouseMoved:event];
}

- (void) mouseDragged: (NSEvent *)event {
    [self deliverJavaMouseEvent: event];
}

- (void) rightMouseDragged: (NSEvent *)event {
    [self deliverJavaMouseEvent: event];
}

- (void) otherMouseDragged: (NSEvent *)event {
    [self deliverJavaMouseEvent: event];
}

- (void) mouseEntered: (NSEvent *)event {
    [[self window] setAcceptsMouseMovedEvents:YES];
    //[[self window] makeFirstResponder:self];
    [self deliverJavaMouseEvent: event];
}

- (void) mouseExited: (NSEvent *)event {
    [[self window] setAcceptsMouseMovedEvents:NO];
    [self deliverJavaMouseEvent: event];
    //Restore the cursor back.
    //[CCursorManager _setCursor: [NSCursor arrowCursor]];
}

- (void) scrollWheel: (NSEvent*) event {
    [self deliverJavaMouseEvent: event];
}

/*
 * KeyEvents support
 */

#ifdef LOG_KEY_EVENTS
static void debugPrintNSString(const char* name, NSString* s) {
    if (s == nil) {
        fprintf(stderr, "\t%s: nil\n", name);
        return;
    }
    const char* utf8 = [s UTF8String];
    int codeUnits = [s length];
    int bytes = strlen(utf8);
    fprintf(stderr, "\t%s: [utf8 = \"", name);
    for (const unsigned char* c = (const unsigned char*)utf8; *c; ++c) {
        if (*c >= 0x20 && *c <= 0x7e) {
            fputc(*c, stderr);
        } else {
            fprintf(stderr, "\\x%02x", *c);
        }
    }
    fprintf(stderr, "\", utf16 = \"");
    for (int i = 0; i < codeUnits; ++i) {
        int c = (int)[s characterAtIndex:i];
        if (c >= 0x20 && c <= 0x7e) {
            fputc(c, stderr);
        } else {
            fprintf(stderr, "\\u%04x", c);
        }
    }
    fprintf(stderr, "\", bytes = %d, codeUnits = %d]\n", bytes, codeUnits);
}

static void debugPrintNSEvent(NSEvent* event, const char* comment) {
    NSEventType type = [event type];
    if (type == NSEventTypeKeyDown) {
        fprintf(stderr, "[AWTView.m] keyDown in %s\n", comment);
    } else if (type == NSEventTypeKeyUp) {
        fprintf(stderr, "[AWTView.m] keyUp in %s\n", comment);
    } else if (type == NSEventTypeFlagsChanged) {
        fprintf(stderr, "[AWTView.m] flagsChanged in %s\n", comment);
    } else {
        fprintf(stderr, "[AWTView.m] unknown event %d in %s\n", (int)type, comment);
        return;
    }
    if (type == NSEventTypeKeyDown || type == NSEventTypeKeyUp) {
        debugPrintNSString("characters", [event characters]);
        debugPrintNSString("charactersIgnoringModifiers", [event charactersIgnoringModifiers]);
        fprintf(stderr, "\tkeyCode: %d (0x%02x)\n", [event keyCode], [event keyCode]);
    }
    fprintf(stderr, "\tmodifierFlags: 0x%08x\n", (unsigned)[event modifierFlags]);
    TISInputSourceRef is = TISCopyCurrentKeyboardLayoutInputSource();
    fprintf(stderr, "\tTISCopyCurrentKeyboardLayoutInputSource: %s\n", is == nil ? "(nil)" : [(NSString*) TISGetInputSourceProperty(is, kTISPropertyInputSourceID) UTF8String]);
    fprintf(stderr, "\twillBeHandledByComplexInputMethod: %s\n", [event willBeHandledByComplexInputMethod] ? "true" : "false");
    CFRelease(is);
}
#endif

- (void) keyDown: (NSEvent *)event {
#ifdef LOG_KEY_EVENTS
    debugPrintNSEvent(event, "keyDown");
#endif
    // Check for willBeHandledByComplexInputMethod here, because interpretKeyEvents might invalidate that field
    fIsPressAndHold = fEnablePressAndHold && [event willBeHandledByComplexInputMethod] && fInputMethodLOCKABLE;

    fProcessingKeystroke = YES;
    fKeyEventsNeeded = ![(NSString *)kbdLayout containsString:@KEYMAN_LAYOUT] && !fIsPressAndHold;

    NSString *eventCharacters = [event characters];
    unsigned mods = [event modifierFlags] & NSEventModifierFlagDeviceIndependentFlagsMask;

    if (([event modifierFlags] & NSControlKeyMask) && [eventCharacters length] == 1) {
        lastCtrlCombo = [eventCharacters characterAtIndex:0];
    } else {
        lastCtrlCombo = 0;
    }

    // Allow TSM to look at the event and potentially send back NSTextInputClient messages.
    if (fInputMethodInteractionEnabled == YES) {
        [self interpretKeyEvents:[NSArray arrayWithObject:event]];
    }

    if (fIsPressAndHold)
    {
        fIsPressAndHold = NO;
        BOOL skipProcessingCancelKeys = NO;
        fProcessingKeystroke = NO;
        // Abandon input to reset IM and unblock input after canceling
        // input accented symbols

        switch([event keyCode]) {
            case kVK_Return:
            case kVK_Escape:
            case kVK_PageUp:
            case kVK_PageDown:
            case kVK_DownArrow:
            case kVK_UpArrow:
            case kVK_Home:
            case kVK_End:
                skipProcessingCancelKeys = YES;

            case kVK_ForwardDelete:
            case kVK_Delete:
                // Abandon input to reset IM and unblock input after
                // canceling input accented symbols
#ifdef LOG_KEY_EVENTS
                fprintf(stderr, "[AWTView.m] Abandoning input in the keyDown event\n");
#endif
                [self abandonInput:nil];
                break;
        }
        if (skipProcessingCancelKeys) {
#ifdef LOG_KEY_EVENTS
            fprintf(stderr, "[AWTView.m] Skipping the keyDown event: isPressAndHold && skipProcessingCancelKeys\n");
#endif
            return;
        }
    }

    BOOL isDeadKey = (eventCharacters != nil && [eventCharacters length] == 0);

    if ((![self hasMarkedText] && fKeyEventsNeeded) || isDeadKey) {
        [self deliverJavaKeyEventHelper: event];
    }
#ifdef LOG_KEY_EVENTS
    else {
        fprintf(stderr, "[AWTView.m] Skipping the keyDown event: ([self hasMarkedText] || !fKeyEventsNeeded) && !isDeadKey\n");
        fprintf(stderr, "[AWTView.m] hasMarkedText: %s\n", [self hasMarkedText] ? "YES" : "NO");
        fprintf(stderr, "[AWTView.m] fKeyEventsNeeded: %s\n", fKeyEventsNeeded ? "YES" : "NO");
        fprintf(stderr, "[AWTView.m] isDeadKey: %s\n", isDeadKey ? "YES" : "NO");
    }
#endif

    if (actualCharacters != nil) {
        [actualCharacters release];
        actualCharacters = nil;
    }

    fProcessingKeystroke = NO;
}

- (void) keyUp: (NSEvent *)event {
#ifdef LOG_KEY_EVENTS
    debugPrintNSEvent(event, "keyUp");
#endif
    [self deliverJavaKeyEventHelper: event];
}

- (void) flagsChanged: (NSEvent *)event {
#ifdef LOG_KEY_EVENTS
    debugPrintNSEvent(event, "flagsChanged");
#endif
    [self deliverJavaKeyEventHelper: event];
}

- (BOOL) performKeyEquivalent: (NSEvent *) event {
#ifdef LOG_KEY_EVENTS
    debugPrintNSEvent(event, "performKeyEquivalent");
#endif
    // if IM is active key events should be ignored
    if (![self hasMarkedText] && !fIsPressAndHold && ![event willBeHandledByComplexInputMethod]) {
        [self deliverJavaKeyEventHelper: event];
    }

    const NSUInteger modFlags =
        [event modifierFlags] & (NSCommandKeyMask | NSAlternateKeyMask | NSShiftKeyMask | NSControlKeyMask);

    // Workaround for JBR-3544
    // When tabbing mode is on (jdk.allowMacOSTabbedWindows=true) and "Ctrl Opt N" / "Cmd Opt N" is pressed,
    //   macOS first sends it, and immediately then sends "Ctrl N" / "Cmd N".
    // The workaround is to "eat" (by returning TRUE) the "Ctrl Opt N" / "Cmd Opt N",
    //   so macOS won't send its "fallback" version ("Ctrl N" / "Cmd N").
    if ([event keyCode] == kVK_ANSI_N) {
        const NSUInteger ctrlOpt = (NSControlKeyMask | NSAlternateKeyMask);
        const NSUInteger cmdOpt = (NSCommandKeyMask | NSAlternateKeyMask);

        if ((modFlags == ctrlOpt) || (modFlags == cmdOpt)) {
            [[NSApp mainMenu] performKeyEquivalent: event]; // just in case (as in the workaround for 8020209 below)
            return YES;
        }
    }

    // Workaround for 8020209: special case for "Cmd =" and "Cmd ."
    // because Cocoa calls performKeyEquivalent twice for these keystrokes
    if (modFlags == NSCommandKeyMask) {
        NSString *eventChars = [event charactersIgnoringModifiers];
        if ([eventChars length] == 1) {
            unichar ch = [eventChars characterAtIndex:0];
            if (ch == '=' || ch == '.') {
                [[NSApp mainMenu] performKeyEquivalent: event];
                return YES;
            }
        }

    }

    JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
    NSString *captureNextAppWinKey = [PropertiesUtilities javaSystemPropertyForKey:@"apple.awt.captureNextAppWinKey"
                                                                           withEnv:env];
    if ([@"true" isCaseInsensitiveLike:captureNextAppWinKey]) {
        NSUInteger deviceIndependentModifierFlagsMask =
            [event modifierFlags] & NSDeviceIndependentModifierFlagsMask;
        // Why translate the key code here and not just use event.characters?
        // The default macOS shortcut for NextWindowInApplication is Cmd+Backtick. Pressing Cmd+Dead Grave also works
        // for layouts that have the backtick as a dead key. Unfortunately, some (but notably not all) of these layouts
        // consider Cmd+Dead Grave to also be a dead key, which means that event.characters will be an empty string.
        // Explicitly translating the key code with a proper underlying key layout fixes this.
        struct KeyCodeTranslationResult translationResult = TranslateKeyCodeUsingLayout(GetCurrentUnderlyingLayout(YES), [event keyCode], 0);
        if (translationResult.isSuccess && translationResult.character) {
            return isSystemShortcut_NextWindowInApplication(deviceIndependentModifierFlagsMask, [event keyCode], [NSString stringWithCharacters:&translationResult.character length:1]) ? YES : NO;
        }
    }

    return NO;
}

/**
 * Utility methods and accessors
 */

-(BOOL) isWindowDisabled {
    NSWindow* window = [self window];
    if ([window isKindOfClass: [AWTWindow_Panel class]] || [window isKindOfClass: [AWTWindow_Normal class]]) {
        return ![(AWTWindow*)[window delegate] isEnabled];
    } else {
        return NO;
    }
}

-(void) deliverJavaMouseEvent: (NSEvent *) event {
    if ([self isWindowDisabled]) {
        return;
    }

    NSEventType type = [event type];

    // check synthesized mouse entered/exited events
    if ((type == NSEventTypeMouseEntered && mouseIsOver) || (type == NSEventTypeMouseExited && !mouseIsOver)) {
        return;
    }else if ((type == NSEventTypeMouseEntered && !mouseIsOver) || (type == NSEventTypeMouseExited && mouseIsOver)) {
        mouseIsOver = !mouseIsOver;
    }

    [AWTToolkit eventCountPlusPlus];

    JNIEnv *env = [ThreadUtilities getJNIEnv];

    NSPoint eventLocation = [event locationInWindow];
    NSPoint localPoint = [self convertPoint: eventLocation fromView: nil];
    NSPoint absP = [NSEvent mouseLocation];

    // Convert global numbers between Cocoa's coordinate system and Java.
    // TODO: need consistent way for doing that both with global as well as with local coordinates.
    // The reason to do it here is one more native method for getting screen dimension otherwise.

    NSRect screenRect = [[[NSScreen screens] objectAtIndex:0] frame];
    absP.y = screenRect.size.height - absP.y;
    jint clickCount;

    if (type == NSEventTypeMouseEntered ||
        type == NSEventTypeMouseExited  ||
        type == NSEventTypeScrollWheel  ||
        type == NSEventTypeMouseMoved)  {
        clickCount = 0;
    } else {
        clickCount = [event clickCount];
    }

    jdouble deltaX = [event deltaX];
    jdouble deltaY = [event deltaY];
    if ([AWTToolkit hasPreciseScrollingDeltas: event]) {
        deltaX = [event scrollingDeltaX] * 0.1;
        deltaY = [event scrollingDeltaY] * 0.1;
    }

    DECLARE_CLASS(jc_NSEvent, "sun/lwawt/macosx/NSEvent");
    DECLARE_METHOD(jctor_NSEvent, jc_NSEvent, "<init>", "(IIIIIIIIDDI)V");
    jobject jEvent = (*env)->NewObject(env, jc_NSEvent, jctor_NSEvent,
                                  [event type],
                                  [event modifierFlags],
                                  clickCount,
                                  [event buttonNumber],
                                  (jint)localPoint.x, (jint)localPoint.y,
                                  (jint)absP.x, (jint)absP.y,
                                  deltaY,
                                  deltaX,
                                  [AWTToolkit scrollStateWithEvent: event]);
    CHECK_NULL(jEvent);

    DECLARE_CLASS(jc_PlatformView, "sun/lwawt/macosx/CPlatformView");
    DECLARE_METHOD(jm_deliverMouseEvent, jc_PlatformView, "deliverMouseEvent", "(Lsun/lwawt/macosx/NSEvent;)V");
    jobject jlocal = (*env)->NewLocalRef(env, m_cPlatformView);
    if (!(*env)->IsSameObject(env, jlocal, NULL)) {
        (*env)->CallVoidMethod(env, jlocal, jm_deliverMouseEvent, jEvent);
        CHECK_EXCEPTION();
        (*env)->DeleteLocalRef(env, jlocal);
    }
    (*env)->DeleteLocalRef(env, jEvent);
}

- (void) resetTrackingArea {
    if (rolloverTrackingArea != nil) {
        [self removeTrackingArea:rolloverTrackingArea];
        [rolloverTrackingArea release];
    }

    int options = (NSTrackingActiveAlways | NSTrackingMouseEnteredAndExited |
                   NSTrackingMouseMoved | NSTrackingEnabledDuringMouseDrag);

    rolloverTrackingArea = [[NSTrackingArea alloc] initWithRect:[self visibleRect]
                                                        options: options
                                                          owner:self
                                                       userInfo:nil
                            ];
    [self addTrackingArea:rolloverTrackingArea];
}

- (void)updateTrackingAreas {
    [super updateTrackingAreas];
    [self resetTrackingArea];
}

- (void) resetCursorRects {
    [super resetCursorRects];
    [self resetTrackingArea];
}

-(void) deliverJavaKeyEventHelper: (NSEvent *) event {
    static NSEvent* sLastKeyEvent = nil;
    if (event == sLastKeyEvent) {
        // The event is repeatedly delivered by keyDown: after performKeyEquivalent:
#ifdef LOG_KEY_EVENTS
        fprintf(stderr, "[AWTView.m] deliverJavaKeyEventHelper: ignoring duplicate events\n");
#endif
        return;
    }
    [sLastKeyEvent release];
    sLastKeyEvent = [event retain];

    [AWTToolkit eventCountPlusPlus];
    JNIEnv *env = [ThreadUtilities getJNIEnv];

    jstring characters = NULL;
    if ([event type] != NSEventTypeFlagsChanged) {
        characters = NSStringToJavaString(env, [event characters]);
    }
    jstring jActualCharacters = NULL;
    if (actualCharacters != nil) {
        jActualCharacters = NSStringToJavaString(env, actualCharacters);
    }

    DECLARE_CLASS(jc_NSEvent, "sun/lwawt/macosx/NSEvent");
    DECLARE_METHOD(jctor_NSEvent, jc_NSEvent, "<init>", "(IISLjava/lang/String;Ljava/lang/String;)V");
    jobject jEvent = (*env)->NewObject(env, jc_NSEvent, jctor_NSEvent,
                                       [event type],
                                       [event modifierFlags],
                                       [event keyCode],
                                       characters,
                                       jActualCharacters);
    CHECK_NULL(jEvent);

    DECLARE_CLASS(jc_PlatformView, "sun/lwawt/macosx/CPlatformView");
    DECLARE_METHOD(jm_deliverKeyEvent, jc_PlatformView,
                            "deliverKeyEvent", "(Lsun/lwawt/macosx/NSEvent;)V");
    jobject jlocal = (*env)->NewLocalRef(env, m_cPlatformView);
    if (!(*env)->IsSameObject(env, jlocal, NULL)) {
        (*env)->CallVoidMethod(env, jlocal, jm_deliverKeyEvent, jEvent);
        CHECK_EXCEPTION();
        (*env)->DeleteLocalRef(env, jlocal);
    }
    if (characters != NULL) {
        (*env)->DeleteLocalRef(env, characters);
    }
    if (jActualCharacters != NULL) {
        (*env)->DeleteLocalRef(env, jActualCharacters);
    }
    (*env)->DeleteLocalRef(env, jEvent);
}

-(void) deliverResize: (NSRect) rect {
    jint x = (jint) rect.origin.x;
    jint y = (jint) rect.origin.y;
    jint w = (jint) rect.size.width;
    jint h = (jint) rect.size.height;
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    DECLARE_CLASS(jc_PlatformView, "sun/lwawt/macosx/CPlatformView");
    DECLARE_METHOD(jm_deliverResize, jc_PlatformView, "deliverResize", "(IIII)V");

    jobject jlocal = (*env)->NewLocalRef(env, m_cPlatformView);
    if (!(*env)->IsSameObject(env, jlocal, NULL)) {
        (*env)->CallVoidMethod(env, jlocal, jm_deliverResize, x,y,w,h);
        CHECK_EXCEPTION();
        (*env)->DeleteLocalRef(env, jlocal);
    }
}


- (void) drawRect:(NSRect)dirtyRect {
    AWT_ASSERT_APPKIT_THREAD;

    [super drawRect:dirtyRect];
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    if (env != NULL) {
        DECLARE_CLASS(jc_CPlatformView, "sun/lwawt/macosx/CPlatformView");
        DECLARE_METHOD(jm_deliverWindowDidExposeEvent, jc_CPlatformView, "deliverWindowDidExposeEvent", "()V");
        jobject jlocal = (*env)->NewLocalRef(env, m_cPlatformView);
        if (!(*env)->IsSameObject(env, jlocal, NULL)) {
            (*env)->CallVoidMethod(env, jlocal, jm_deliverWindowDidExposeEvent);
            CHECK_EXCEPTION();
            (*env)->DeleteLocalRef(env, jlocal);
        }
    }
}

-(NSMutableString *) parseString : (id) complexString {
    if ([complexString isKindOfClass:[NSString class]]) {
        return [complexString mutableCopy];
    }
    else {
        return [complexString mutableString];
    }
}

// NSAccessibility support
- (jobject)awtComponent:(JNIEnv*)env
{
    DECLARE_CLASS_RETURN(jc_CPlatformView, "sun/lwawt/macosx/CPlatformView", NULL);
    DECLARE_FIELD_RETURN(jf_Peer, jc_CPlatformView, "peer", "Lsun/lwawt/LWWindowPeer;", NULL);
    if ((env == NULL) || (m_cPlatformView == NULL)) {
        NSLog(@"Apple AWT : Error AWTView:awtComponent given bad parameters.");
        NSLog(@"%@",[NSThread callStackSymbols]);
        return NULL;
    }

    jobject peer = NULL;
    jobject jlocal = (*env)->NewLocalRef(env, m_cPlatformView);
    if (!(*env)->IsSameObject(env, jlocal, NULL)) {
        peer = (*env)->GetObjectField(env, jlocal, jf_Peer);
        (*env)->DeleteLocalRef(env, jlocal);
    }
    DECLARE_CLASS_RETURN(jc_LWWindowPeer, "sun/lwawt/LWWindowPeer", NULL);
    DECLARE_FIELD_RETURN(jf_Target, jc_LWWindowPeer, "target", "Ljava/awt/Component;", NULL);
    if (peer == NULL) {
        NSLog(@"Apple AWT : Error AWTView:awtComponent got null peer from CPlatformView");
        NSLog(@"%@",[NSThread callStackSymbols]);
        return NULL;
    }
    jobject comp = (*env)->GetObjectField(env, peer, jf_Target);
    (*env)->DeleteLocalRef(env, peer);
    return comp;
}

+ (AWTView *) awtView:(JNIEnv*)env ofAccessible:(jobject)jaccessible
{
    DECLARE_CLASS_RETURN(sjc_CAccessibility, "sun/lwawt/macosx/CAccessibility", NULL);
    DECLARE_STATIC_METHOD_RETURN(jm_getAWTView, sjc_CAccessibility, "getAWTView", "(Ljavax/accessibility/Accessible;)J", NULL);

    jlong jptr = (*env)->CallStaticLongMethod(env, sjc_CAccessibility, jm_getAWTView, jaccessible);
    CHECK_EXCEPTION();
    if (jptr == 0) return nil;

    return (AWTView *)jlong_to_ptr(jptr);
}

- (id)getAxData:(JNIEnv*)env
{
    NSString *a11yEnabledProp = [PropertiesUtilities javaSystemPropertyForKey:@"sun.awt.mac.a11y.enabled" withEnv:env];
    if ([@"false" isCaseInsensitiveLike:a11yEnabledProp]) {
        return nil;
    }
    jobject jcomponent = [self awtComponent:env];
    id ax = [[[CommonComponentAccessibility alloc] initWithParent:self withEnv:env withAccessible:jcomponent withIndex:-1 withView:self withJavaRole:nil] autorelease];
    (*env)->DeleteLocalRef(env, jcomponent);
    return ax;
}

// NSAccessibility messages
- (id)accessibilityChildren
{
    AWT_ASSERT_APPKIT_THREAD;
    JNIEnv *env = [ThreadUtilities getJNIEnv];

    (*env)->PushLocalFrame(env, 4);

    id result = NSAccessibilityUnignoredChildrenForOnlyChild([self getAxData:env]);

    (*env)->PopLocalFrame(env, NULL);

    return result;
}

- (BOOL)isAccessibilityElement
{
    return NO;
}

- (id)accessibilityHitTest:(NSPoint)point
{
    AWT_ASSERT_APPKIT_THREAD;
    JNIEnv *env = [ThreadUtilities getJNIEnv];

    (*env)->PushLocalFrame(env, 4);

    id result = [[self getAxData:env] accessibilityHitTest:point];

    (*env)->PopLocalFrame(env, NULL);

    return result;
}

- (id)accessibilityFocusedUIElement
{
    AWT_ASSERT_APPKIT_THREAD;

    JNIEnv *env = [ThreadUtilities getJNIEnv];

    (*env)->PushLocalFrame(env, 4);

    id result = [[self getAxData:env] accessibilityFocusedUIElement];

    (*env)->PopLocalFrame(env, NULL);

    return result;
}

// --- Services menu support for lightweights ---

// finds the focused accessible element, and if it is a text element, obtains the text from it
- (NSString *)accessibilitySelectedText
{
    id focused = [self accessibilityFocusedUIElement];
    if (![focused respondsToSelector:@selector(accessibilitySelectedText)]) return nil;
    return [focused accessibilitySelectedText];
}

- (void)setAccessibilitySelectedText:(NSString *)accessibilitySelectedText {
    id focused = [self accessibilityFocusedUIElement];
    if ([focused respondsToSelector:@selector(setAccessibilitySelectedText:)]) {
    [focused setAccessibilitySelectedText:accessibilitySelectedText];
}
}

// same as above, but converts to RTFD
- (NSData *)accessibleSelectedTextAsRTFD
{
    NSString *selectedText = [self accessibilitySelectedText];
    NSAttributedString *styledText = [[NSAttributedString alloc] initWithString:selectedText];
    NSData *rtfdData = [styledText RTFDFromRange:NSMakeRange(0, [styledText length])
                              documentAttributes:
                                @{NSDocumentTypeDocumentAttribute: NSRTFTextDocumentType}];
    [styledText release];
    return rtfdData;
}

// finds the focused accessible element, and if it is a text element, sets the text in it
- (BOOL)replaceAccessibleTextSelection:(NSString *)text
{
    id focused = [self accessibilityFocusedUIElement];
    if (![focused respondsToSelector:@selector(setAccessibilitySelectedText:)]) return NO;
    [focused setAccessibilitySelectedText:text];
    return YES;
}

// called for each service in the Services menu - only handle text for now
- (id)validRequestorForSendType:(NSString *)sendType returnType:(NSString *)returnType
{
    if ([[self window] firstResponder] != self) return nil; // let AWT components handle themselves

    if ([sendType isEqual:NSStringPboardType] || [returnType isEqual:NSStringPboardType]) {
        NSString *selectedText = [self accessibilitySelectedText];
        if (selectedText) return self;
    }

    return nil;
}

// fetch text from Java and hand off to the service
- (BOOL)writeSelectionToPasteboard:(NSPasteboard *)pboard types:(NSArray *)types
{
    if ([types containsObject:NSStringPboardType])
    {
        [pboard declareTypes:[NSArray arrayWithObject:NSStringPboardType] owner:nil];
        return [pboard setString:[self accessibilitySelectedText] forType:NSStringPboardType];
    }

    if ([types containsObject:NSRTFDPboardType])
    {
        [pboard declareTypes:[NSArray arrayWithObject:NSRTFDPboardType] owner:nil];
        return [pboard setData:[self accessibleSelectedTextAsRTFD] forType:NSRTFDPboardType];
    }

    return NO;
}

// write text back to Java from the service
- (BOOL)readSelectionFromPasteboard:(NSPasteboard *)pboard
{
    if ([[pboard types] containsObject:NSStringPboardType])
    {
        NSString *text = [pboard stringForType:NSStringPboardType];
        return [self replaceAccessibleTextSelection:text];
    }

    if ([[pboard types] containsObject:NSRTFDPboardType])
    {
        NSData *rtfdData = [pboard dataForType:NSRTFDPboardType];
        NSAttributedString *styledText = [[NSAttributedString alloc] initWithRTFD:rtfdData documentAttributes:NULL];
        NSString *text = [styledText string];
        [styledText release];

        return [self replaceAccessibleTextSelection:text];
    }

    return NO;
}


-(void) setDragSource:(CDragSource *)source {
    self._dragSource = source;
}


- (void) setDropTarget:(CDropTarget *)target {
    self._dropTarget = target;
    [ThreadUtilities performOnMainThread:@selector(controlModelControlValid) on:self._dropTarget withObject:nil waitUntilDone:YES];
}

/********************************  BEGIN NSDraggingSource Interface  ********************************/

- (NSDragOperation)draggingSourceOperationMaskForLocal:(BOOL)flag
{
    // If draggingSource is nil route the message to the superclass (if responding to the selector):
    CDragSource *dragSource = self._dragSource;
    NSDragOperation dragOp = NSDragOperationNone;

    if (dragSource != nil)
        dragOp = [dragSource draggingSourceOperationMaskForLocal:flag];
    else if ([super respondsToSelector:@selector(draggingSourceOperationMaskForLocal:)])
        dragOp = [super draggingSourceOperationMaskForLocal:flag];

    return dragOp;
}

- (NSArray *)namesOfPromisedFilesDroppedAtDestination:(NSURL *)dropDestination
{
    // If draggingSource is nil route the message to the superclass (if responding to the selector):
    CDragSource *dragSource = self._dragSource;
    NSArray* array = nil;

    if (dragSource != nil)
        array = [dragSource namesOfPromisedFilesDroppedAtDestination:dropDestination];
    else if ([super respondsToSelector:@selector(namesOfPromisedFilesDroppedAtDestination:)])
        array = [super namesOfPromisedFilesDroppedAtDestination:dropDestination];

    return array;
}

- (void)draggedImage:(NSImage *)image beganAt:(NSPoint)screenPoint
{
    // If draggingSource is nil route the message to the superclass (if responding to the selector):
    CDragSource *dragSource = self._dragSource;

    if (dragSource != nil) {
        [dragSource draggedImage:image beganAt:screenPoint];
    }
}

- (void)draggedImage:(NSImage *)image endedAt:(NSPoint)screenPoint operation:(NSDragOperation)operation
{
    // If draggingSource is nil route the message to the superclass (if responding to the selector):
    CDragSource *dragSource = self._dragSource;

    if (dragSource != nil) {
        [dragSource draggedImage:image endedAt:screenPoint operation:operation];
    }
}

- (void)draggedImage:(NSImage *)image movedTo:(NSPoint)screenPoint
{
    // If draggingSource is nil route the message to the superclass (if responding to the selector):
    CDragSource *dragSource = self._dragSource;

    if (dragSource != nil) {
        [dragSource draggedImage:image movedTo:screenPoint];
    }
}

- (BOOL)ignoreModifierKeysWhileDragging
{
    // If draggingSource is nil route the message to the superclass (if responding to the selector):
    CDragSource *dragSource = self._dragSource;
    BOOL result = FALSE;

    if (dragSource != nil)
        result = [dragSource ignoreModifierKeysWhileDragging];
    else if ([super respondsToSelector:@selector(ignoreModifierKeysWhileDragging)])
        result = [super ignoreModifierKeysWhileDragging];

    return result;
}

/********************************  END NSDraggingSource Interface  ********************************/

/********************************  BEGIN NSDraggingDestination Interface  ********************************/

- (NSDragOperation)draggingEntered:(id <NSDraggingInfo>)sender
{
    // If draggingDestination is nil route the message to the superclass:
    CDropTarget *dropTarget = self._dropTarget;
    NSDragOperation dragOp = NSDragOperationNone;

    if (dropTarget != nil)
        dragOp = [dropTarget draggingEntered:sender];
    else if ([super respondsToSelector:@selector(draggingEntered:)])
        dragOp = [super draggingEntered:sender];

    return dragOp;
}

- (NSDragOperation)draggingUpdated:(id <NSDraggingInfo>)sender
{
    // If draggingDestination is nil route the message to the superclass:
    CDropTarget *dropTarget = self._dropTarget;
    NSDragOperation dragOp = NSDragOperationNone;

    if (dropTarget != nil)
        dragOp = [dropTarget draggingUpdated:sender];
    else if ([super respondsToSelector:@selector(draggingUpdated:)])
        dragOp = [super draggingUpdated:sender];

    return dragOp;
}

- (void)draggingExited:(id <NSDraggingInfo>)sender
{
    // If draggingDestination is nil route the message to the superclass:
    CDropTarget *dropTarget = self._dropTarget;

    if (dropTarget != nil) {
        [dropTarget draggingExited:sender];
    }
}

- (BOOL)prepareForDragOperation:(id <NSDraggingInfo>)sender
{
    // If draggingDestination is nil route the message to the superclass:
    CDropTarget *dropTarget = self._dropTarget;
    BOOL result = FALSE;

    if (dropTarget != nil)
        result = [dropTarget prepareForDragOperation:sender];
    else if ([super respondsToSelector:@selector(prepareForDragOperation:)])
        result = [super prepareForDragOperation:sender];

    return result;
}

- (BOOL)performDragOperation:(id <NSDraggingInfo>)sender
{
    // If draggingDestination is nil route the message to the superclass:
    CDropTarget *dropTarget = self._dropTarget;
    BOOL result = FALSE;

    if (dropTarget != nil)
        result = [dropTarget performDragOperation:sender];
    else if ([super respondsToSelector:@selector(performDragOperation:)])
        result = [super performDragOperation:sender];

    return result;
}

- (void)concludeDragOperation:(id <NSDraggingInfo>)sender
{
    // If draggingDestination is nil route the message to the superclass:
    CDropTarget *dropTarget = self._dropTarget;

    if (dropTarget != nil) {
        [dropTarget concludeDragOperation:sender];
    }
}

- (void)draggingEnded:(id <NSDraggingInfo>)sender
{
    // If draggingDestination is nil route the message to the superclass:
    CDropTarget *dropTarget = self._dropTarget;

    if (dropTarget != nil) {
        [dropTarget draggingEnded:sender];
    }
}

/********************************  END NSDraggingDestination Interface  ********************************/

/********************************  BEGIN NSTextInputClient Protocol  ********************************/


static jclass jc_CInputMethod = NULL;

#define GET_CIM_CLASS() \
    GET_CLASS(jc_CInputMethod, "sun/lwawt/macosx/CInputMethod");

#define GET_CIM_CLASS_RETURN(ret) \
    GET_CLASS_RETURN(jc_CInputMethod, "sun/lwawt/macosx/CInputMethod", ret);

- (NSInteger) windowLevel
{
#ifdef IM_DEBUG
    fprintf(stderr, "AWTView InputMethod Selector Called : [windowLevel]\n");
#endif // IM_DEBUG

    NSWindow* const ownerWindow = [self window];
    if (ownerWindow == nil) {
        return NSNormalWindowLevel;
    }

    const NSWindowLevel ownerWindowLevel = [ownerWindow level];
    if ( (ownerWindowLevel != NSNormalWindowLevel) && (ownerWindowLevel != NSFloatingWindowLevel) ) {
        // the window level has been overridden, let's believe it
        return ownerWindowLevel;
    }

    AWTWindow* const delegate = (AWTWindow*)[ownerWindow delegate];
    if (delegate == nil) {
        return ownerWindowLevel;
    }

    const jint styleBits = [delegate styleBits];

    const BOOL isPopup = ( (styleBits & sun_lwawt_macosx_CPlatformWindow_IS_POPUP) != 0 );
    if (isPopup) {
        return NSPopUpMenuWindowLevel;
    }

    const BOOL isModal = ( (styleBits & sun_lwawt_macosx_CPlatformWindow_IS_MODAL) != 0 );
    if (isModal) {
        return NSFloatingWindowLevel;
    }

    return ownerWindowLevel;
}

- (void) insertText:(id)aString replacementRange:(NSRange)replacementRange
{
#ifdef IM_DEBUG
    fprintf(stderr,
            "AWTView InputMethod Selector Called : [insertText]: %s, replacementRange: location=%lu, length=%lu\n",
            [aString UTF8String], replacementRange.location, replacementRange.length);
#endif // IM_DEBUG

    NSMutableString * useString = [self parseString:aString];

    // See JBR-6704
    if (lastCtrlCombo && !fProcessingKeystroke && [useString length] == 1 && [useString characterAtIndex:0] == lastCtrlCombo) {
        lastCtrlCombo = 0;
        return;
    }

    if (fInputMethodLOCKABLE == NULL) {
        return;
    }

    // insertText gets called when the user commits text generated from an input method.  It also gets
    // called during ordinary input as well.  We only need to send an input method event when we have marked
    // text, or 'text in progress'.  We also need to send the event if we get an insert text out of the blue!
    // (i.e., when the user uses the Character palette or Inkwell), or when the string to insert is a complex
    // Unicode value.
    BOOL usingComplexIM = [self hasMarkedText] || !fProcessingKeystroke;

#ifdef IM_DEBUG
    NSUInteger utf16Length = [useString lengthOfBytesUsingEncoding:NSUTF16StringEncoding];
    NSUInteger utf8Length = [useString lengthOfBytesUsingEncoding:NSUTF8StringEncoding];

    NSLog(@"insertText kbdlayout %@ ",(NSString *)kbdLayout);

    NSLog(@"utf8Length %lu utf16Length %lu", (unsigned long)utf8Length, (unsigned long)utf16Length);
    NSLog(@"hasMarkedText: %s, fProcessingKeyStroke: %s", [self hasMarkedText] ? "YES" : "NO", fProcessingKeystroke ? "YES" : "NO");
#endif // IM_DEBUG

    JNIEnv *env = [ThreadUtilities getJNIEnv];

    GET_CIM_CLASS();

    if (replacementRange.length > 0) {
        DECLARE_METHOD(jm_selectRange, jc_CInputMethod, "selectRange", "(II)V");
        (*env)->CallVoidMethod(env, fInputMethodLOCKABLE, jm_selectRange, replacementRange.location,
                               replacementRange.length);
        CHECK_EXCEPTION();
    }

    if (usingComplexIM) {
        DECLARE_METHOD(jm_insertText, jc_CInputMethod, "insertText", "(Ljava/lang/String;)V");
        jstring insertedText = NSStringToJavaString(env, useString);
        (*env)->CallVoidMethod(env, fInputMethodLOCKABLE, jm_insertText, insertedText);
        CHECK_EXCEPTION();
        (*env)->DeleteLocalRef(env, insertedText);
        fKeyEventsNeeded = NO;
    } else {
        if (actualCharacters != nil) {
            [actualCharacters release];
        }
        actualCharacters = [useString copy];
        fKeyEventsNeeded = YES;
    }

    // Abandon input to reset IM and unblock input after entering accented
    // symbols

    [self abandonInput:nil];
}

+ (void)keyboardInputSourceChanged:(NSNotification *)notification
{
#ifdef IM_DEBUG
    NSLog(@"keyboardInputSourceChangeNotification received");
#endif
    NSTextInputContext *curContxt = [NSTextInputContext currentInputContext];
    kbdLayout = curContxt.selectedKeyboardInputSource;
}

- (void) doCommandBySelector:(SEL)aSelector
{
#ifdef IM_DEBUG
    fprintf(stderr, "AWTView InputMethod Selector Called : [doCommandBySelector]\n");
    NSLog(@"%@", NSStringFromSelector(aSelector));
#endif // IM_DEBUG
    if (@selector(insertNewline:) == aSelector || @selector(insertTab:) == aSelector || @selector(deleteBackward:) == aSelector)
    {
        fKeyEventsNeeded = YES;
    }
}

// setMarkedText: cannot take a nil first argument. aString can be NSString or NSAttributedString
- (void) setMarkedText:(id)aString selectedRange:(NSRange)selectionRange replacementRange:(NSRange)replacementRange
{
    if (!fInputMethodLOCKABLE)
        return;

    BOOL isAttributedString = [aString isKindOfClass:[NSAttributedString class]];
    NSAttributedString *attrString = (isAttributedString ? (NSAttributedString *)aString : nil);
    NSString *incomingString = (isAttributedString ? [aString string] : aString);
#ifdef IM_DEBUG
    fprintf(stderr, "AWTView InputMethod Selector Called :[setMarkedText] \"%s\","
                    "selectionRange(%lu, %lu), replacementRange(%lu, %lu)\n", [incomingString UTF8String],
            selectionRange.location, selectionRange.length, replacementRange.location, replacementRange.length);
#endif // IM_DEBUG
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    GET_CIM_CLASS();
    DECLARE_METHOD(jm_startIMUpdate, jc_CInputMethod, "startIMUpdate", "(Ljava/lang/String;)V");
    DECLARE_METHOD(jm_addAttribute, jc_CInputMethod, "addAttribute", "(ZZII)V");
    DECLARE_METHOD(jm_dispatchText, jc_CInputMethod, "dispatchText", "(IIZ)V");

    if (replacementRange.length > 0) {
        DECLARE_METHOD(jm_selectRange, jc_CInputMethod, "selectRange", "(II)V");
        (*env)->CallVoidMethod(env, fInputMethodLOCKABLE, jm_selectRange, replacementRange.location,
                               replacementRange.length);
        CHECK_EXCEPTION();
    }


    // NSInputContext already did the analysis of the TSM event and created attributes indicating
    // the underlining and color that should be done to the string.  We need to look at the underline
    // style and color to determine what kind of Java highlighting needs to be done.
    jstring inProcessText = NSStringToJavaString(env, incomingString);
    (*env)->CallVoidMethod(env, fInputMethodLOCKABLE, jm_startIMUpdate, inProcessText);
    CHECK_EXCEPTION();
    (*env)->DeleteLocalRef(env, inProcessText);

    if (isAttributedString) {
        NSUInteger length;
        NSRange effectiveRange;
        NSDictionary *attributes;
        length = [attrString length];
        effectiveRange = NSMakeRange(0, 0);
        while (NSMaxRange(effectiveRange) < length) {
            attributes = [attrString attributesAtIndex:NSMaxRange(effectiveRange)
                                        effectiveRange:&effectiveRange];
            if (attributes) {
                BOOL isThickUnderline, isGray;
                NSNumber *underlineSizeObj =
                (NSNumber *)[attributes objectForKey:NSUnderlineStyleAttributeName];
                NSInteger underlineSize = [underlineSizeObj integerValue];
                isThickUnderline = (underlineSize > 1);

                NSColor *underlineColorObj =
                (NSColor *)[attributes objectForKey:NSUnderlineColorAttributeName];
                isGray = !([underlineColorObj isEqual:[NSColor blackColor]]);

                (*env)->CallVoidMethod(env, fInputMethodLOCKABLE, jm_addAttribute, isThickUnderline,
                       isGray, effectiveRange.location, effectiveRange.length);
                CHECK_EXCEPTION();
            }
        }
    }

    (*env)->CallVoidMethod(env, fInputMethodLOCKABLE, jm_dispatchText,
            selectionRange.location, selectionRange.length, JNI_FALSE);
         CHECK_EXCEPTION();
    // If the marked text is being cleared (zero-length string) don't handle the key event.
    if ([incomingString length] == 0) {
        fKeyEventsNeeded = NO;
    }
}

- (void) unmarkText {
    [self unmarkText:nil];
}

- (void) unmarkText:(jobject) component
{
#ifdef IM_DEBUG
    fprintf(stderr, "AWTView InputMethod Selector Called : [unmarkText]\n");
#endif // IM_DEBUG

    if (!fInputMethodLOCKABLE) {
        return;
    }

    // unmarkText cancels any input in progress and commits it to the text field.
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    GET_CIM_CLASS();
    DECLARE_METHOD(jm_unmarkText, jc_CInputMethod, "unmarkText", "(Ljava/awt/Component;)V");
    (*env)->CallVoidMethod(env, fInputMethodLOCKABLE, jm_unmarkText, component);
    CHECK_EXCEPTION();
}

- (BOOL) hasMarkedText
{
#ifdef IM_DEBUG
    fprintf(stderr, "AWTView InputMethod Selector Called : [hasMarkedText]\n");
#endif // IM_DEBUG

    if (!fInputMethodLOCKABLE) {
        return NO;
    }

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    GET_CIM_CLASS_RETURN(NO);
    DECLARE_FIELD_RETURN(jf_fCurrentText, jc_CInputMethod, "fCurrentText", "Ljava/text/AttributedString;", NO);
    DECLARE_FIELD_RETURN(jf_fCurrentTextLength, jc_CInputMethod, "fCurrentTextLength", "I", NO);
    jobject currentText = (*env)->GetObjectField(env, fInputMethodLOCKABLE, jf_fCurrentText);
    CHECK_EXCEPTION();

    jint currentTextLength = (*env)->GetIntField(env, fInputMethodLOCKABLE, jf_fCurrentTextLength);
    CHECK_EXCEPTION();

    BOOL hasMarkedText = (currentText != NULL && currentTextLength > 0);

    if (currentText != NULL) {
        (*env)->DeleteLocalRef(env, currentText);
    }

    return hasMarkedText;
}

- (NSInteger) conversationIdentifier
{
#ifdef IM_DEBUG
    fprintf(stderr, "AWTView InputMethod Selector Called : [conversationIdentifier]\n");
#endif // IM_DEBUG

    return (NSInteger) self;
}

/* Returns attributed string at the range.  This allows input mangers to
 query any range in backing-store (Andy's request)
 */
- (NSAttributedString *) attributedSubstringForProposedRange:(NSRange)theRange actualRange:(NSRangePointer)actualRange
{
#ifdef IM_DEBUG
    fprintf(stderr, "AWTView InputMethod Selector Called : [attributedSubstringFromRange] location=%lu, length=%lu\n", (unsigned long)theRange.location, (unsigned long)theRange.length);
#endif // IM_DEBUG
    if (!fInputMethodLOCKABLE) {
        return nil;
    }

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    GET_CIM_CLASS_RETURN(nil);
    DECLARE_METHOD_RETURN(jm_substringFromRange, jc_CInputMethod, "attributedSubstringFromRange", "(II)Ljava/lang/String;", nil);
    jobject theString = (*env)->CallObjectMethod(env, fInputMethodLOCKABLE, jm_substringFromRange, theRange.location, theRange.length);
    CHECK_EXCEPTION_NULL_RETURN(theString, nil);

    if (!theString) {
        return NULL;
    }
    id result = [[[NSAttributedString alloc] initWithString:JavaStringToNSString(env, theString)] autorelease];
#ifdef IM_DEBUG
    NSLog(@"attributedSubstringFromRange returning \"%@\"", result);
#endif // IM_DEBUG

    (*env)->DeleteLocalRef(env, theString);
    return result;
}

/* This method returns the range for marked region.  If hasMarkedText == false,
 it'll return NSNotFound location & 0 length range.
 */
- (NSRange) markedRange
{

#ifdef IM_DEBUG
    fprintf(stderr, "AWTView InputMethod Selector Called : [markedRange]\n");
#endif // IM_DEBUG

    if (!fInputMethodLOCKABLE) {
        return NSMakeRange(NSNotFound, 0);
    }

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jarray array;
    jboolean isCopy;
    jint *_array;
    NSRange range = NSMakeRange(NSNotFound, 0);
    GET_CIM_CLASS_RETURN(range);
    DECLARE_METHOD_RETURN(jm_markedRange, jc_CInputMethod, "markedRange", "()[I", range);

    array = (*env)->CallObjectMethod(env, fInputMethodLOCKABLE, jm_markedRange);
    CHECK_EXCEPTION();

    if (array) {
        _array = (*env)->GetIntArrayElements(env, array, &isCopy);
        if (_array != NULL) {
            range.location = _array[0];
            range.length = _array[1];
#ifdef IM_DEBUG
            fprintf(stderr, "markedRange returning (%lu, %lu)\n",
                    (unsigned long)range.location, (unsigned long)range.length);
#endif // IM_DEBUG
            (*env)->ReleaseIntArrayElements(env, array, _array, 0);
        }
        (*env)->DeleteLocalRef(env, array);
    }

    return range;
}

/* This method returns the range for selected region.  Just like markedRange method,
 its location field contains char index from the text beginning.
 */
- (NSRange) selectedRange
{
    if (!fInputMethodLOCKABLE) {
        return NSMakeRange(NSNotFound, 0);
    }

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jarray array;
    jboolean isCopy;
    jint *_array;
    NSRange range = NSMakeRange(NSNotFound, 0);
    GET_CIM_CLASS_RETURN(range);
    DECLARE_METHOD_RETURN(jm_selectedRange, jc_CInputMethod, "selectedRange", "()[I", range);

#ifdef IM_DEBUG
    fprintf(stderr, "AWTView InputMethod Selector Called : [selectedRange]\n");
#endif // IM_DEBUG

    array = (*env)->CallObjectMethod(env, fInputMethodLOCKABLE, jm_selectedRange);
    CHECK_EXCEPTION();
    if (array) {
        _array = (*env)->GetIntArrayElements(env, array, &isCopy);
        if (_array != NULL) {
            range.location = _array[0];
            range.length = _array[1];
            (*env)->ReleaseIntArrayElements(env, array, _array, 0);
        }
        (*env)->DeleteLocalRef(env, array);
    }

    return range;
}

/* This method returns the first frame of rects for theRange in screen coordinate system.
 */
- (NSRect) firstRectForCharacterRange:(NSRange)theRange actualRange:(NSRangePointer)actualRange
{
    if (!fInputMethodLOCKABLE) {
        return NSZeroRect;
    }

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    GET_CIM_CLASS_RETURN(NSZeroRect);
    DECLARE_METHOD_RETURN(jm_firstRectForCharacterRange, jc_CInputMethod,
                            "firstRectForCharacterRange", "(I)[I", NSZeroRect);
    jarray array;
    jboolean isCopy;
    jint *_array;
    NSRect rect;

#ifdef IM_DEBUG
    fprintf(stderr,
            "AWTView InputMethod Selector Called : [firstRectForCharacterRange:] location=%lu, length=%lu\n",
            (unsigned long)theRange.location, (unsigned long)theRange.length);
#endif // IM_DEBUG

    array = (*env)->CallObjectMethod(env, fInputMethodLOCKABLE, jm_firstRectForCharacterRange,
                                theRange.location);
    CHECK_EXCEPTION();

    _array = (*env)->GetIntArrayElements(env, array, &isCopy);
    if (_array) {
        rect = ConvertNSScreenRect(env, NSMakeRect(_array[0], _array[1], _array[2], _array[3]));
        (*env)->ReleaseIntArrayElements(env, array, _array, 0);
    } else {
        rect = NSZeroRect;
    }
    (*env)->DeleteLocalRef(env, array);

#ifdef IM_DEBUG
    fprintf(stderr,
            "firstRectForCharacterRange returning x=%f, y=%f, width=%f, height=%f\n",
            rect.origin.x, rect.origin.y, rect.size.width, rect.size.height);
#endif // IM_DEBUG
    return rect;
}

/* This method returns the index for character that is nearest to thePoint.  thPoint is in
 screen coordinate system.
 */
- (NSUInteger)characterIndexForPoint:(NSPoint)thePoint
{
    if (!fInputMethodLOCKABLE) {
        return NSNotFound;
    }

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    GET_CIM_CLASS_RETURN(NSNotFound);
    DECLARE_METHOD_RETURN(jm_characterIndexForPoint, jc_CInputMethod,
                            "characterIndexForPoint", "(II)I", NSNotFound);

    NSPoint flippedLocation = ConvertNSScreenPoint(env, thePoint);

#ifdef IM_DEBUG
    fprintf(stderr, "AWTView InputMethod Selector Called : [characterIndexForPoint:(NSPoint)thePoint] x=%f, y=%f\n", flippedLocation.x, flippedLocation.y);
#endif // IM_DEBUG

    jint index = (*env)->CallIntMethod(env, fInputMethodLOCKABLE, jm_characterIndexForPoint,
                      (jint)flippedLocation.x, (jint)flippedLocation.y);
    CHECK_EXCEPTION();

#ifdef IM_DEBUG
    fprintf(stderr, "characterIndexForPoint returning %d\n", index);
#endif // IM_DEBUG

    if (index == -1) {
        return NSNotFound;
    } else {
        return (NSUInteger)index;
    }
}

- (NSArray*) validAttributesForMarkedText
{
#ifdef IM_DEBUG
    fprintf(stderr, "AWTView InputMethod Selector Called : [validAttributesForMarkedText]\n");
#endif // IM_DEBUG

    return [NSArray array];
}

- (void)setInputMethod:(jobject)inputMethod
{
#ifdef IM_DEBUG
    fprintf(stderr, "AWTView InputMethod Selector Called : [setInputMethod]\n");
#endif // IM_DEBUG

    JNIEnv *env = [ThreadUtilities getJNIEnv];

    // Get rid of the old one
    if (fInputMethodLOCKABLE) {
        (*env)->DeleteGlobalRef(env, fInputMethodLOCKABLE);
    }

    fInputMethodLOCKABLE = inputMethod; // input method arg must be a GlobalRef

    NSTextInputContext *curContxt = [NSTextInputContext currentInputContext];
    kbdLayout = curContxt.selectedKeyboardInputSource;
    [[NSNotificationCenter defaultCenter] addObserver:[AWTView class]
                                           selector:@selector(keyboardInputSourceChanged:)
                                               name:NSTextInputContextKeyboardSelectionDidChangeNotification
                                             object:nil];
}

- (void)abandonInput:(jobject) component
{
#ifdef IM_DEBUG
    fprintf(stderr, "AWTView InputMethod Selector Called : [abandonInput]\n");
#endif // IM_DEBUG

    [ThreadUtilities performOnMainThread:@selector(markedTextAbandoned:) on:[NSInputManager currentInputManager] withObject:self waitUntilDone:YES];
    [self unmarkText:component];
}

-(void)enableImInteraction:(BOOL)enabled
{
#ifdef IM_DEBUG
    fprintf(stderr, "AWTView InputMethod Selector Called : [enableImInteraction:%d]\n", enabled);
#endif // IM_DEBUG

    AWT_ASSERT_APPKIT_THREAD;

    fInputMethodInteractionEnabled = (enabled == YES) ? YES : NO;
}

/********************************   END NSTextInputClient Protocol   ********************************/

- (void)viewDidChangeBackingProperties {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    if ((*env)->IsSameObject(env, m_cPlatformView, NULL)) {
        return;
    }
    static double debugScale = -2.0;
    if (debugScale == -2.0) { // default debugScale value in SGE is -1.0
        debugScale = JNU_CallStaticMethodByName(env, NULL, "sun/java2d/SunGraphicsEnvironment",
                                                "getDebugScale", "()D").d;
    }

    if (self.window.backingScaleFactor > 0 && debugScale < 0) {
        if (self.layer.contentsScale != self.window.backingScaleFactor) {
            self.layer.contentsScale = self.window.backingScaleFactor;
            DECLARE_CLASS(jc_CPlatformView, "sun/lwawt/macosx/CPlatformView");
            DECLARE_METHOD(deliverChangeBackingProperties, jc_CPlatformView, "deliverChangeBackingProperties", "(F)V");
            jobject jlocal = (*env)->NewLocalRef(env, m_cPlatformView);
            (*env)->CallVoidMethod(env, jlocal, deliverChangeBackingProperties, self.window.backingScaleFactor);
            CHECK_EXCEPTION();
            (*env)->DeleteLocalRef(env, jlocal);
        }
    }
}

@end // AWTView

/*
 * Class:     sun_lwawt_macosx_CPlatformView
 * Method:    nativeCreateView
 * Signature: (IIII)J
 */
JNIEXPORT jlong JNICALL
Java_sun_lwawt_macosx_CPlatformView_nativeCreateView
(JNIEnv *env, jobject obj, jint originX, jint originY, jint width, jint height, jlong windowLayerPtr)
{
    __block AWTView *newView = nil;

    JNI_COCOA_ENTER(env);

    NSRect rect = NSMakeRect(originX, originY, width, height);
    jobject cPlatformView = (*env)->NewWeakGlobalRef(env, obj);
    CHECK_EXCEPTION();

    [ThreadUtilities performOnMainThreadWaiting:YES block:^(){

        CALayer *windowLayer = jlong_to_ptr(windowLayerPtr);
        newView = [[AWTView alloc] initWithRect:rect
                                   platformView:cPlatformView
                                    windowLayer:windowLayer];
    }];

    JNI_COCOA_EXIT(env);

    return ptr_to_jlong(newView);
}

/*
 * Class:     sun_lwawt_macosx_CPlatformView
 * Method:    nativeSetAutoResizable
 * Signature: (JZ)V;
 */

JNIEXPORT void JNICALL
Java_sun_lwawt_macosx_CPlatformView_nativeSetAutoResizable
(JNIEnv *env, jclass cls, jlong viewPtr, jboolean toResize)
{
    JNI_COCOA_ENTER(env);

    NSView *view = (NSView *)jlong_to_ptr(viewPtr);

    [ThreadUtilities performOnMainThreadWaiting:NO block:^(){

        if (toResize) {
            [view setAutoresizingMask: NSViewHeightSizable | NSViewWidthSizable];
        } else {
            [view setAutoresizingMask: NSViewMinYMargin | NSViewMaxXMargin];
        }

        if ([view superview] != nil) {
            [[view superview] setAutoresizesSubviews:(BOOL)toResize];
        }

    }];
    JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CPlatformView
 * Method:    nativeGetNSViewDisplayID
 * Signature: (J)I;
 */

JNIEXPORT jint JNICALL
Java_sun_lwawt_macosx_CPlatformView_nativeGetNSViewDisplayID
(JNIEnv *env, jclass cls, jlong viewPtr)
{
    __block jint ret; //CGDirectDisplayID

    JNI_COCOA_ENTER(env);

    NSView *view = (NSView *)jlong_to_ptr(viewPtr);
    [ThreadUtilities performOnMainThreadWaiting:YES block:^(){
        NSWindow *window = [view window];
        ret = (jint)[[AWTWindow getNSWindowDisplayID_AppKitThread: window] intValue];
    }];

    JNI_COCOA_EXIT(env);

    return ret;
}

/*
 * Class:     sun_lwawt_macosx_CPlatformView
 * Method:    nativeGetLocationOnScreen
 * Signature: (J)Ljava/awt/Rectangle;
 */

JNIEXPORT jobject JNICALL
Java_sun_lwawt_macosx_CPlatformView_nativeGetLocationOnScreen
(JNIEnv *env, jclass cls, jlong viewPtr)
{
    jobject jRect = NULL;

    JNI_COCOA_ENTER(env);

    __block NSRect rect = NSZeroRect;

    NSView *view = (NSView *)jlong_to_ptr(viewPtr);
    [ThreadUtilities performOnMainThreadWaiting:YES block:^(){

        NSRect viewBounds = [view bounds];
        NSRect frameInWindow = [view convertRect:viewBounds toView:nil];
        rect = [[view window] convertRectToScreen:frameInWindow];
        //Convert coordinates to top-left corner origin
        rect = ConvertNSScreenRect(NULL, rect);

    }];
    jRect = NSToJavaRect(env, rect);

    JNI_COCOA_EXIT(env);

    return jRect;
}

/*
 * Class:     sun_lwawt_macosx_CPlatformView
 * Method:    nativeIsViewUnderMouse
 * Signature: (J)Z;
 */

JNIEXPORT jboolean JNICALL Java_sun_lwawt_macosx_CPlatformView_nativeIsViewUnderMouse
(JNIEnv *env, jclass clazz, jlong viewPtr)
{
    __block jboolean underMouse = JNI_FALSE;

    JNI_COCOA_ENTER(env);

    NSView *nsView = OBJC(viewPtr);
    [ThreadUtilities performOnMainThreadWaiting:YES block:^(){
        NSPoint ptWindowCoords = [[nsView window] mouseLocationOutsideOfEventStream];
        NSPoint ptViewCoords = [nsView convertPoint:ptWindowCoords fromView:nil];
        underMouse = [nsView hitTest:ptViewCoords] != nil;
    }];

    JNI_COCOA_EXIT(env);

    return underMouse;
}
