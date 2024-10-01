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

#include <objc/objc-runtime.h>
#import <Cocoa/Cocoa.h>

#include <java_awt_Window_CustomTitleBar.h>
#import "sun_lwawt_macosx_CPlatformWindow.h"
#import "com_apple_eawt_event_GestureHandler.h"
#import "com_apple_eawt_FullScreenHandler.h"
#import "ApplicationDelegate.h"

#import "AWTWindow.h"
#import "AWTView.h"
#import "GeomUtilities.h"
#import "ThreadUtilities.h"
#import "NSApplicationAWT.h"
#import "JNIUtilities.h"
#import "PropertiesUtilities.h"
#include "Trace.h"

#define MASK(KEY) \
    (sun_lwawt_macosx_CPlatformWindow_ ## KEY)

#define IS(BITS, KEY) \
    ((BITS & MASK(KEY)) != 0)

#define SET(BITS, KEY, VALUE) \
    BITS = VALUE ? BITS | MASK(KEY) : BITS & ~MASK(KEY)

static jclass jc_CPlatformWindow = NULL;
#define GET_CPLATFORM_WINDOW_CLASS() \
    GET_CLASS(jc_CPlatformWindow, "sun/lwawt/macosx/CPlatformWindow");

#define GET_CPLATFORM_WINDOW_CLASS_RETURN(ret) \
    GET_CLASS_RETURN(jc_CPlatformWindow, "sun/lwawt/macosx/CPlatformWindow", ret);

BOOL isColorMatchingEnabled() {
    static int colorMatchingEnabled = -1;
    if (colorMatchingEnabled == -1) {
        JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
        if (env == NULL) return NO;
        NSString *colorMatchingEnabledProp = [PropertiesUtilities javaSystemPropertyForKey:@"sun.java2d.osx.colorMatching"
                                                                                   withEnv:env];
        if (colorMatchingEnabledProp == nil) {
            // fallback on the former metal property:
            colorMatchingEnabledProp = [PropertiesUtilities javaSystemPropertyForKey:@"sun.java2d.metal.colorMatching"
                                                                                   withEnv:env];
        }
        colorMatchingEnabled = [@"false" isCaseInsensitiveLike:colorMatchingEnabledProp] ? NO : YES;
        J2dRlsTraceLn(J2D_TRACE_INFO, "AWTWindow_isColorMatchingEnabled: %d", colorMatchingEnabled);
    }
    return (BOOL)colorMatchingEnabled;
}

@interface NSTitlebarAccessoryViewController (Private)
- (void)_setHidden:(BOOL)h animated:(BOOL)a;
@end

@interface NSWindow (Private)
- (void)_setTabBarAccessoryViewController:(id)controller;
- (void)setIgnoreMove:(BOOL)value;
- (BOOL)isIgnoreMove;
- (void)_adjustWindowToScreen;
@end

// Cocoa windowDidBecomeKey/windowDidResignKey notifications
// doesn't provide information about "opposite" window, so we
// have to do a bit of tracking. This variable points to a window
// which had been the key window just before a new key window
// was set. It would be nil if the new key window isn't an AWT
// window or the app currently has no key window.
static AWTWindow* lastKeyWindow = nil;

// This variable contains coordinates of a window's top left
// which was positioned via java.awt.Window.setLocationByPlatform.
// It would be NSZeroPoint if 'Location by Platform' is not used.
static NSPoint lastTopLeftPoint;

static BOOL ignoreResizeWindowDuringAnotherWindowEnd = NO;

static BOOL fullScreenTransitionInProgress = NO;
static BOOL orderingScheduled = NO;

// --------------------------------------------------------------
// NSWindow/NSPanel descendants implementation
#define AWT_NS_WINDOW_IMPLEMENTATION                            \
- (id) initWithDelegate:(AWTWindow *)delegate                   \
              frameRect:(NSRect)contectRect                     \
              styleMask:(NSUInteger)styleMask                   \
            contentView:(NSView *)view                          \
{                                                               \
    self = [super initWithContentRect:contectRect               \
                            styleMask:styleMask                 \
                              backing:NSBackingStoreBuffered    \
                                defer:NO];                      \
                                                                \
    if (self == nil) return nil;                                \
                                                                \
    [self setDelegate:delegate];                                \
    [self setContentView:view];                                 \
    [self setInitialFirstResponder:view];                       \
    [self setReleasedWhenClosed:NO];                            \
    [self setPreservesContentDuringLiveResize:YES];             \
    [[NSNotificationCenter defaultCenter] addObserver:self      \
         selector:@selector(windowDidChangeScreen)              \
         name:NSWindowDidChangeScreenNotification object:self]; \
    [[NSNotificationCenter defaultCenter] addObserver:self      \
         selector:@selector(windowDidChangeProfile)             \
         name:NSWindowDidChangeScreenProfileNotification        \
         object:self];                                          \
    [self addObserver:self forKeyPath:@"visible"                \
        options:NSKeyValueObservingOptionNew context:nil];      \
    return self;                                                \
}                                                               \
                                                                \
/* NSWindow overrides */                                        \
- (BOOL) canBecomeKeyWindow {                                   \
    return [(AWTWindow*)[self delegate] canBecomeKeyWindow];    \
}                                                               \
                                                                \
- (BOOL) canBecomeMainWindow {                                  \
    return [(AWTWindow*)[self delegate] canBecomeMainWindow];   \
}                                                               \
                                                                \
- (BOOL) worksWhenModal {                                       \
    return [(AWTWindow*)[self delegate] worksWhenModal];        \
}                                                               \
                                                                \
- (void)cursorUpdate:(NSEvent *)event {                         \
    /* Prevent cursor updates from OS side */                   \
}                                                               \
                                                                \
- (void)sendEvent:(NSEvent *)event {                            \
    [(AWTWindow*)[self delegate] sendEvent:event];              \
    [super sendEvent:event];                                    \
}                                                               \
                                                                \
- (void)becomeKeyWindow {                                       \
    [super becomeKeyWindow];                                    \
    [(AWTWindow*)[self delegate] becomeKeyWindow];              \
}                                                               \
                                                                \
- (NSWindowTabbingMode)tabbingMode {                            \
    return ((AWTWindow*)[self delegate]).javaWindowTabbingMode; \
}                                                               \
                                                                \
- (void)observeValueForKeyPath:(NSString *)keyPath              \
    ofObject:(id)object                                         \
    change:(NSDictionary<NSKeyValueChangeKey,id> *)change       \
    context:(void *)context {                                   \
    if ([keyPath isEqualToString:@"visible"]) {                 \
        BOOL isVisible =                                        \
            [[change objectForKey:NSKeyValueChangeNewKey]       \
            boolValue];                                         \
        if (isVisible) {                                        \
            [(AWTWindow*)[self delegate]                        \
                _windowDidBecomeVisible];                       \
        }                                                       \
    }                                                           \
}                                                               \
                                                                \
- (void)windowDidChangeScreen {                                 \
   [(AWTWindow*)[self delegate] _displayChanged:NO];           \
}                                                               \
                                                                \
- (void)windowDidChangeProfile {                                \
   [(AWTWindow*)[self delegate] _displayChanged:YES];            \
}                                                               \
                                                                \
- (void)dealloc {                                               \
   [[NSNotificationCenter defaultCenter] removeObserver:self    \
       name:NSWindowDidChangeScreenNotification object:self];   \
   [[NSNotificationCenter defaultCenter] removeObserver:self    \
       name:NSWindowDidChangeScreenProfileNotification          \
       object:self];                                            \
   [self removeObserver:self forKeyPath:@"visible"];            \
   [super dealloc];                                             \
}                                                               \

@implementation AWTWindow_Normal
AWT_NS_WINDOW_IMPLEMENTATION

// suppress exception (actually assertion) from [NSWindow _changeJustMain]
// workaround for https://youtrack.jetbrains.com/issue/JBR-2562
- (void)_changeJustMain {
    @try {
        // NOTE: we can't use [super _changeJustMain] directly because of the warning ('may not perform to selector')
        // And [super performSelector:@selector(_changeJustMain)] will invoke this method (not a base method).
        // So do it with objc-runtime.h (see stackoverflow.com/questions/14635024/using-objc-msgsendsuper-to-invoke-a-class-method)
        Class superClass = [self superclass];
        struct objc_super mySuper = {
            self,
            class_isMetaClass(object_getClass(self))        //check if we are an instance or Class
                            ? object_getClass(superClass)   //if we are a Class, we need to send our metaclass (our Class's Class)
                            : superClass                    //if we are an instance, we need to send our Class (which we already have)
        };
        void (*_objc_msgSendSuper)(struct objc_super *, SEL) = (void *)&objc_msgSendSuper; //cast our pointer so the compiler can sort out the ABI
        (*_objc_msgSendSuper)(&mySuper, @selector(_changeJustMain));
    } @catch (NSException *ex) {
        NSLog(@"WARNING: suppressed exception from _changeJustMain (workaround for JBR-2562)");
        NSProcessInfo *processInfo = [NSProcessInfo processInfo];
        [NSApplicationAWT logException:ex forProcess:processInfo];
    }
}

// Gesture support
- (void)postGesture:(NSEvent *)event as:(jint)type a:(jdouble)a b:(jdouble)b {
    AWT_ASSERT_APPKIT_THREAD;

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject platformWindow = (*env)->NewLocalRef(env, ((AWTWindow *)self.delegate).javaPlatformWindow);
    if (platformWindow != NULL) {
        // extract the target AWT Window object out of the CPlatformWindow
        GET_CPLATFORM_WINDOW_CLASS();
        DECLARE_FIELD(jf_target, jc_CPlatformWindow, "target", "Ljava/awt/Window;");
        jobject awtWindow = (*env)->GetObjectField(env, platformWindow, jf_target);
        if (awtWindow != NULL) {
            // translate the point into Java coordinates
            NSPoint loc = [event locationInWindow];
            loc.y = [self frame].size.height - loc.y;

            // send up to the GestureHandler to recursively dispatch on the AWT event thread
            DECLARE_CLASS(jc_GestureHandler, "com/apple/eawt/event/GestureHandler");
            DECLARE_STATIC_METHOD(sjm_handleGestureFromNative, jc_GestureHandler,
                            "handleGestureFromNative", "(Ljava/awt/Window;IDDDD)V");
            (*env)->CallStaticVoidMethod(env, jc_GestureHandler, sjm_handleGestureFromNative,
                               awtWindow, type, (jdouble)loc.x, (jdouble)loc.y, (jdouble)a, (jdouble)b);
            CHECK_EXCEPTION();
            (*env)->DeleteLocalRef(env, awtWindow);
        }
        (*env)->DeleteLocalRef(env, platformWindow);
    }
}

- (BOOL)postPhaseEvent:(NSEvent *)event {
    // Consider changing API to reflect MacOS api
    // Gesture event should come with phase field
    // PhaseEvent should be removed
    const unsigned int NSEventPhaseBegan = 0x1 << 0;
    const unsigned int NSEventPhaseEnded = 0x1 << 3;
    const unsigned int NSEventPhaseCancelled = 0x1 << 4;

    if (event.phase == NSEventPhaseBegan) {
        [self postGesture:event
                       as:com_apple_eawt_event_GestureHandler_PHASE
                        a:-1.0
                        b:0.0];
        return true;
    } else if (event.phase == NSEventPhaseEnded ||
               event.phase == NSEventPhaseCancelled) {
        [self postGesture:event
                       as:com_apple_eawt_event_GestureHandler_PHASE
                        a:1.0
                        b:0.0];
        return true;
    }
    return false;
}

- (void)magnifyWithEvent:(NSEvent *)event {
    if ([self postPhaseEvent:event]) {
        return;
    }
    [self postGesture:event
                   as:com_apple_eawt_event_GestureHandler_MAGNIFY
                    a:[event magnification]
                    b:0.0];
}

- (void)rotateWithEvent:(NSEvent *)event {
    if ([self postPhaseEvent:event]) {
        return;
    }
    [self postGesture:event
                   as:com_apple_eawt_event_GestureHandler_ROTATE
                    a:[event rotation]
                    b:0.0];
}

- (void)swipeWithEvent:(NSEvent *)event {
    [self postGesture:event
                   as:com_apple_eawt_event_GestureHandler_SWIPE
                    a:[event deltaX]
                    b:[event deltaY]];
}

- (void)pressureChangeWithEvent:(NSEvent *)event {
    float pressure = event.pressure;
    [self postGesture:event
                       as:com_apple_eawt_event_GestureHandler_PRESSURE
                        a:pressure
                        b:(([event respondsToSelector:@selector(stage)]) ? ((NSInteger)[event stage]) : -1)
    ];
}

- (void)moveTabToNewWindow:(id)sender {
    AWT_ASSERT_APPKIT_THREAD;

    [super moveTabToNewWindow:sender];

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject platformWindow = (*env)->NewLocalRef(env, ((AWTWindow *)self.delegate).javaPlatformWindow);
    if (platformWindow != NULL) {
        // extract the target AWT Window object out of the CPlatformWindow
        GET_CPLATFORM_WINDOW_CLASS();
        DECLARE_FIELD(jf_target, jc_CPlatformWindow, "target", "Ljava/awt/Window;");
        jobject awtWindow = (*env)->GetObjectField(env, platformWindow, jf_target);
        if (awtWindow != NULL) {
            DECLARE_CLASS(jc_Window, "java/awt/Window");
            DECLARE_METHOD(jm_runMoveTabToNewWindowCallback, jc_Window, "runMoveTabToNewWindowCallback", "()V");
            (*env)->CallVoidMethod(env, awtWindow, jm_runMoveTabToNewWindowCallback);
            CHECK_EXCEPTION();
            (*env)->DeleteLocalRef(env, awtWindow);
        }
        (*env)->DeleteLocalRef(env, platformWindow);
    }

#ifdef DEBUG
    NSLog(@"=== Move Tab to new Window ===");
#endif
}

// Call over Foundation from Java
- (CGFloat) getTabBarVisibleAndHeight {
    if (@available(macOS 10.13, *)) {
        id tabGroup = [self tabGroup];
#ifdef DEBUG
        NSLog(@"=== Window tabBar: %@ ===", tabGroup);
#endif
        if ([tabGroup isTabBarVisible]) {
            if ([tabGroup respondsToSelector:@selector(_tabBar)]) { // private member
                CGFloat height = [[tabGroup _tabBar] frame].size.height;
#ifdef DEBUG
                NSLog(@"=== Window tabBar visible: %f ===", height);
#endif
                return height;
            }
#ifdef DEBUG
            NSLog(@"=== NsWindow.tabGroup._tabBar not found ===");
#endif
            return -1; // if we don't get height return -1 and use default value in java without change native code
        }
#ifdef DEBUG
        NSLog(@"=== Window tabBar not visible ===");
#endif
    } else {
#ifdef DEBUG
        NSLog(@"=== Window tabGroup not supported before macOS 10.13 ===");
#endif
    }
    return 0;
}

- (void)orderOut:(id)sender {
    ignoreResizeWindowDuringAnotherWindowEnd = YES;
    [super orderOut:sender];
}

- (void)_setTabBarAccessoryViewController:(id)_controller {
    if (((AWTWindow *)self.delegate).hideTabController) {
        NSTitlebarAccessoryViewController* controller = [[NSTitlebarAccessoryViewController alloc] init];
        controller.view = [[NSView alloc] init];
        [controller.view setFrame:NSMakeRect(0, 0, 0, 0)];
        [controller _setHidden:YES animated:NO];

        [super _setTabBarAccessoryViewController:controller];
    } else {
        [super _setTabBarAccessoryViewController:_controller];
    }
}

- (BOOL)isNativeSelected {
    if (@available(macOS 10.13, *)) {
        return [[self tabGroup] selectedWindow] == self;
    }
    return NO;
}

- (void)setIgnoreMove:(BOOL)value {
    _ignoreMove = value;
    self.movable = !value;
}

- (BOOL)isIgnoreMove {
    return _ignoreMove;
}

- (void)_adjustWindowToScreen {
    if (_ignoreMove) {
        self.movable = YES;
    }

    [super _adjustWindowToScreen];
    [(AWTWindow *)self.delegate updateFullScreenButtons];

    if (_ignoreMove) {
        self.movable = NO;
    }
}

@end
@implementation AWTWindow_Panel
AWT_NS_WINDOW_IMPLEMENTATION
@end
// END of NSWindow/NSPanel descendants implementation
// --------------------------------------------------------------


@implementation AWTWindow

@synthesize nsWindow;
@synthesize javaPlatformWindow;
@synthesize javaMenuBar;
@synthesize javaMinSize;
@synthesize javaMaxSize;
@synthesize styleBits;
@synthesize isEnabled;
@synthesize ownerWindow;
@synthesize preFullScreenLevel;
@synthesize standardFrame;
@synthesize isMinimizing;
@synthesize isJustCreated;
@synthesize javaWindowTabbingMode;
@synthesize isEnterFullScreen;
@synthesize hideTabController;

- (void) updateMinMaxSize:(BOOL)resizable {
    if (resizable) {
        [self.nsWindow setMinSize:self.javaMinSize];
        [self.nsWindow setMaxSize:self.javaMaxSize];
    } else {
        NSRect currentFrame = [self.nsWindow frame];
        [self.nsWindow setMinSize:currentFrame.size];
        [self.nsWindow setMaxSize:currentFrame.size];
    }
}

// creates a new NSWindow style mask based on the _STYLE_PROP_BITMASK bits
+ (NSUInteger) styleMaskForStyleBits:(jint)styleBits {
    NSUInteger type = 0;
    if (IS(styleBits, DECORATED)) {
        type |= NSTitledWindowMask;
        if (IS(styleBits, CLOSEABLE))            type |= NSWindowStyleMaskClosable;
        if (IS(styleBits, RESIZABLE))            type |= NSWindowStyleMaskResizable;
        if (IS(styleBits, FULL_WINDOW_CONTENT))  type |= NSWindowStyleMaskFullSizeContentView;
    } else {
        type |= NSWindowStyleMaskBorderless;
    }

    if (IS(styleBits, MINIMIZABLE))   type |= NSWindowStyleMaskMiniaturizable;
    if (IS(styleBits, TEXTURED))      type |= NSWindowStyleMaskTexturedBackground;
    if (IS(styleBits, UNIFIED))       type |= NSWindowStyleMaskUnifiedTitleAndToolbar;
    if (IS(styleBits, UTILITY))       type |= NSWindowStyleMaskUtilityWindow;
    if (IS(styleBits, HUD))           type |= NSWindowStyleMaskHUDWindow;
    if (IS(styleBits, SHEET))         type |= NSWindowStyleMaskDocModalWindow;

    return type;
}

+ (jint) affectedStyleMaskForCustomTitleBar {
    return MASK(FULL_WINDOW_CONTENT) | MASK(TRANSPARENT_TITLE_BAR) | MASK(TITLE_VISIBLE);
}

+ (jint) overrideStyleBits:(jint)styleBits customTitleBarEnabled:(BOOL)customTitleBarEnabled  fullscreen:(BOOL)fullscreen {
    if (customTitleBarEnabled) {
        styleBits |= MASK(FULL_WINDOW_CONTENT) | MASK(TRANSPARENT_TITLE_BAR);
        if (!fullscreen) styleBits &= ~MASK(TITLE_VISIBLE);
    }
    return styleBits;
}

// updates _METHOD_PROP_BITMASK based properties on the window
- (void) setPropertiesForStyleBits:(jint)bits mask:(jint)mask {
    if (IS(mask, RESIZABLE)) {
        BOOL resizable = IS(bits, RESIZABLE);
        [self updateMinMaxSize:resizable];
        [self.nsWindow setShowsResizeIndicator:resizable];
        // Zoom button should be disabled, if the window is not resizable,
        // otherwise button should be restored to initial state.
        BOOL zoom = resizable && IS(bits, ZOOMABLE);
        [[self.nsWindow standardWindowButton:NSWindowZoomButton] setEnabled:zoom];
    }

    if (IS(mask, HAS_SHADOW)) {
        [self.nsWindow setHasShadow:IS(bits, HAS_SHADOW)];
    }

    if (IS(mask, ZOOMABLE)) {
        [[self.nsWindow standardWindowButton:NSWindowZoomButton] setEnabled:IS(bits, ZOOMABLE)];
    }

    if (IS(mask, ALWAYS_ON_TOP)) {
        [self.nsWindow setLevel:IS(bits, ALWAYS_ON_TOP) ? NSFloatingWindowLevel : NSNormalWindowLevel];
    }

    if (IS(mask, HIDES_ON_DEACTIVATE)) {
        [self.nsWindow setHidesOnDeactivate:IS(bits, HIDES_ON_DEACTIVATE)];
    }

    if (IS(mask, DRAGGABLE_BACKGROUND)) {
        [self.nsWindow setMovableByWindowBackground:IS(bits, DRAGGABLE_BACKGROUND)];
    }

    if (IS(mask, DOCUMENT_MODIFIED)) {
        [self.nsWindow setDocumentEdited:IS(bits, DOCUMENT_MODIFIED)];
    }

    if (IS(mask, FULLSCREENABLE) && [self.nsWindow respondsToSelector:@selector(toggleFullScreen:)]) {
        if (IS(bits, FULLSCREENABLE)) {
            self.nsWindow.collectionBehavior = self.nsWindow.collectionBehavior |
                                               NSWindowCollectionBehaviorFullScreenPrimary;
        } else {
            self.nsWindow.collectionBehavior = self.nsWindow.collectionBehavior &
                                               ~NSWindowCollectionBehaviorFullScreenPrimary;
        }
    }

    if (IS(mask, TRANSPARENT_TITLE_BAR) && [self.nsWindow respondsToSelector:@selector(setTitlebarAppearsTransparent:)]) {
        [self.nsWindow setTitlebarAppearsTransparent:IS(bits, TRANSPARENT_TITLE_BAR)];
    }

    if (IS(mask, TITLE_VISIBLE) && [self.nsWindow respondsToSelector:@selector(setTitleVisibility:)]) {
        [self.nsWindow setTitleVisibility:(IS(bits, TITLE_VISIBLE) ? NSWindowTitleVisible : NSWindowTitleHidden)];
    }

}

- (id) initWithPlatformWindow:(jobject)platformWindow
                  ownerWindow:owner
                    styleBits:(jint)bits
                    frameRect:(NSRect)rect
                  contentView:(NSView *)view
{
AWT_ASSERT_APPKIT_THREAD;

    self = [super init];
    if (self == nil) return nil; // no hope
    self.javaPlatformWindow = platformWindow;

    NSUInteger newBits = bits;
    if (IS(bits, SHEET) && owner == nil) {
        newBits = bits & ~NSWindowStyleMaskDocModalWindow;
    }

    _customTitleBarHeight = -1.0f; // Negative means uninitialized
    self.customTitleBarControlsVisible = YES;
    self.customTitleBarConstraints = nil;
    self.customTitleBarHeightConstraint = nil;
    self.customTitleBarButtonCenterXConstraints = nil;
    // Force properties if custom title bar is enabled, but store original value in self.styleBits.
    newBits = [AWTWindow overrideStyleBits:newBits customTitleBarEnabled:self.isCustomTitleBarEnabled fullscreen:false];

    NSUInteger styleMask = [AWTWindow styleMaskForStyleBits:newBits];

    NSRect contentRect = rect; //[NSWindow contentRectForFrameRect:rect styleMask:styleMask];
    if (contentRect.size.width <= 0.0) {
        contentRect.size.width = 1.0;
    }
    if (contentRect.size.height <= 0.0) {
        contentRect.size.height = 1.0;
    }

    if (IS(bits, UTILITY) ||
        IS(bits, HUD) ||
        IS(bits, HIDES_ON_DEACTIVATE) ||
        IS(bits, SHEET))
    {
        self.nsWindow = [[AWTWindow_Panel alloc] initWithDelegate:self
                            frameRect:contentRect
                            styleMask:styleMask
                          contentView:view];
    }
    else
    {
        // These windows will appear in the window list in the dock icon menu
        self.nsWindow = [[AWTWindow_Normal alloc] initWithDelegate:self
                            frameRect:contentRect
                            styleMask:styleMask
                          contentView:view];
    }

    if (self.nsWindow == nil) return nil; // no hope either
    [self.nsWindow release]; // the property retains the object already

    if (isColorMatchingEnabled()) {
        // Supported by both OpenGL & Metal pipelines
        // Tell the system we have an sRGB backing store
        // to ensure colors are displayed correctly on screen with non-SRGB profile:
        [self.nsWindow setColorSpace: [NSColorSpace sRGBColorSpace]];
    }

    self.isEnabled = YES;
    self.isMinimizing = NO;
    self.styleBits = bits;
    self.ownerWindow = owner;
    [self setPropertiesForStyleBits:newBits mask:MASK(_METHOD_PROP_BITMASK)];

    if (IS(bits, SHEET) && owner != nil) {
        [self.nsWindow setStyleMask: NSWindowStyleMaskDocModalWindow];
    }

    self.isJustCreated = YES;

    self.javaWindowTabbingMode = [self getJavaWindowTabbingMode];
    self.nsWindow.collectionBehavior = NSWindowCollectionBehaviorManaged;
    self.isEnterFullScreen = NO;

    [self configureJavaWindowTabbingIdentifier];

    if (self.isCustomTitleBarEnabled && !self.isFullScreen) {
        [self setUpCustomTitleBar];
    }

    self.currentDisplayID = nil;
    return self;
}

+ (BOOL) isAWTWindow:(NSWindow *)window {
    return [window isKindOfClass: [AWTWindow_Panel class]] || [window isKindOfClass: [AWTWindow_Normal class]];
}

// returns id for the topmost window under mouse
+ (NSInteger) getTopmostWindowUnderMouseID {
    return [NSWindow windowNumberAtPoint:[NSEvent mouseLocation] belowWindowWithWindowNumber:kCGNullWindowID];
}

// checks that this window is under the mouse cursor and this point is not overlapped by others windows
- (BOOL) isTopmostWindowUnderMouse {
    return [self.nsWindow windowNumber] == [AWTWindow getTopmostWindowUnderMouseID];
}

- (void) configureJavaWindowTabbingIdentifier {
    AWT_ASSERT_APPKIT_THREAD;

    self.hideTabController = NO;

    if (self.javaWindowTabbingMode != NSWindowTabbingModeAutomatic) {
        return;
    }

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject platformWindow = (*env)->NewLocalRef(env, self.javaPlatformWindow);
    if (platformWindow == NULL) {
        return;
    }

    GET_CPLATFORM_WINDOW_CLASS();
    DECLARE_FIELD(jf_target, jc_CPlatformWindow, "target", "Ljava/awt/Window;");
    jobject awtWindow = (*env)->GetObjectField(env, platformWindow, jf_target);

    if (awtWindow != NULL) {
        DECLARE_CLASS(jc_RootPaneContainer, "javax/swing/RootPaneContainer");
        if ((*env)->IsInstanceOf(env, awtWindow, jc_RootPaneContainer)) {
            DECLARE_METHOD(jm_getRootPane, jc_RootPaneContainer, "getRootPane", "()Ljavax/swing/JRootPane;");
            jobject rootPane = (*env)->CallObjectMethod(env, awtWindow, jm_getRootPane);
            CHECK_EXCEPTION();

            if (rootPane != NULL) {
                DECLARE_CLASS(jc_JComponent, "javax/swing/JComponent");
                DECLARE_METHOD(jm_getClientProperty, jc_JComponent, "getClientProperty", "(Ljava/lang/Object;)Ljava/lang/Object;");
                jstring jKey = NSStringToJavaString(env, @"JavaWindowTabbingIdentifier");
                jobject jValue = (*env)->CallObjectMethod(env, rootPane, jm_getClientProperty, jKey);
                CHECK_EXCEPTION();

                if (jValue != NULL) {
                    DECLARE_CLASS(jc_String, "java/lang/String");
                    if ((*env)->IsInstanceOf(env, jValue, jc_String)) {
                        NSString *winId = JavaStringToNSString(env, (jstring)jValue);
                        [self.nsWindow setTabbingIdentifier:winId];
                        if ([winId characterAtIndex:0] == '+') {
                            self.hideTabController = YES;
                            [self.nsWindow _setTabBarAccessoryViewController:nil];
                        }
                    }

                    (*env)->DeleteLocalRef(env, jValue);
                }

                (*env)->DeleteLocalRef(env, jKey);
                (*env)->DeleteLocalRef(env, rootPane);
            }
        }

        (*env)->DeleteLocalRef(env, awtWindow);
    }

    (*env)->DeleteLocalRef(env, platformWindow);
}

- (NSWindowTabbingMode) getJavaWindowTabbingMode {
    AWT_ASSERT_APPKIT_THREAD;

    BOOL result = NO;

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject platformWindow = (*env)->NewLocalRef(env, self.javaPlatformWindow);
    if (platformWindow != NULL) {
        // extract the target AWT Window object out of the CPlatformWindow
        GET_CPLATFORM_WINDOW_CLASS_RETURN(NSWindowTabbingModeDisallowed);
        DECLARE_FIELD_RETURN(jf_target, jc_CPlatformWindow, "target", "Ljava/awt/Window;", NSWindowTabbingModeDisallowed);
        jobject awtWindow = (*env)->GetObjectField(env, platformWindow, jf_target);
        if (awtWindow != NULL) {
            DECLARE_CLASS_RETURN(jc_Window, "java/awt/Window", NSWindowTabbingModeDisallowed);
            DECLARE_METHOD_RETURN(jm_hasTabbingMode, jc_Window, "hasTabbingMode", "()Z", NSWindowTabbingModeDisallowed);
            result = (*env)->CallBooleanMethod(env, awtWindow, jm_hasTabbingMode) == JNI_TRUE ? YES : NO;
            CHECK_EXCEPTION();
            (*env)->DeleteLocalRef(env, awtWindow);
        }
        (*env)->DeleteLocalRef(env, platformWindow);
    }

#ifdef DEBUG
    NSLog(@"=== getJavaWindowTabbingMode: %d ===", result);
#endif

    return result ? NSWindowTabbingModeAutomatic : NSWindowTabbingModeDisallowed;
}

+ (AWTWindow *) getTopmostWindowUnderMouse {
    NSEnumerator *windowEnumerator = [[NSApp windows] objectEnumerator];
    NSWindow *window;

    NSInteger topmostWindowUnderMouseID = [AWTWindow getTopmostWindowUnderMouseID];

    while ((window = [windowEnumerator nextObject]) != nil) {
        if ([window windowNumber] == topmostWindowUnderMouseID) {
            BOOL isAWTWindow = [AWTWindow isAWTWindow: window];
            return isAWTWindow ? (AWTWindow *) [window delegate] : nil;
        }
    }
    return nil;
}

+ (void) synthesizeMouseEnteredExitedEvents:(NSWindow*)window withType:(NSEventType)eventType {

    NSPoint screenLocation = [NSEvent mouseLocation];
    NSPoint windowLocation = [window convertScreenToBase: screenLocation];
    int modifierFlags = (eventType == NSEventTypeMouseEntered) ? NSMouseEnteredMask : NSMouseExitedMask;

    NSEvent *mouseEvent = [NSEvent enterExitEventWithType: eventType
                                                 location: windowLocation
                                            modifierFlags: modifierFlags
                                                timestamp: 0
                                             windowNumber: [window windowNumber]
                                                  context: nil
                                              eventNumber: 0
                                           trackingNumber: 0
                                                 userData: nil
                           ];

    [[window contentView] deliverJavaMouseEvent: mouseEvent];
}

+ (void) synthesizeMouseEnteredExitedEventsForAllWindows {

    NSInteger topmostWindowUnderMouseID = [AWTWindow getTopmostWindowUnderMouseID];
    NSArray *windows = [NSApp windows];
    NSWindow *window;

    NSEnumerator *windowEnumerator = [windows objectEnumerator];
    while ((window = [windowEnumerator nextObject]) != nil) {
        if ([AWTWindow isAWTWindow: window]) {
            BOOL isUnderMouse = ([window windowNumber] == topmostWindowUnderMouseID);
            BOOL mouseIsOver = [[window contentView] mouseIsOver];
            if (isUnderMouse && !mouseIsOver) {
                [AWTWindow synthesizeMouseEnteredExitedEvents:window withType:NSEventTypeMouseEntered];
            } else if (!isUnderMouse && mouseIsOver) {
                [AWTWindow synthesizeMouseEnteredExitedEvents:window withType:NSEventTypeMouseExited];
            }
        }
    }
}

+ (NSNumber *) getNSWindowDisplayID_AppKitThread:(NSWindow *)window {
    AWT_ASSERT_APPKIT_THREAD;
    NSScreen *screen = [window screen];
    NSDictionary *deviceDescription = [screen deviceDescription];
    return [deviceDescription objectForKey:@"NSScreenNumber"];
}

- (void) dealloc {
AWT_ASSERT_APPKIT_THREAD;

    JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
    (*env)->DeleteWeakGlobalRef(env, self.javaPlatformWindow);
    self.javaPlatformWindow = nil;
    self.nsWindow = nil;
    self.ownerWindow = nil;
    self.currentDisplayID = nil;
    self.customTitleBarConstraints = nil;
    self.customTitleBarHeightConstraint = nil;
    self.customTitleBarButtonCenterXConstraints = nil;
    self.zoomButtonMouseResponder = nil;
    [super dealloc];
}

// Test whether window is simple window and owned by embedded frame
- (BOOL) isSimpleWindowOwnedByEmbeddedFrame {
    BOOL isSimpleWindowOwnedByEmbeddedFrame = NO;

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject platformWindow = (*env)->NewLocalRef(env, self.javaPlatformWindow);
    if (platformWindow != NULL) {
        GET_CPLATFORM_WINDOW_CLASS_RETURN(NO);
        DECLARE_METHOD_RETURN(jm_isBlocked, jc_CPlatformWindow, "isSimpleWindowOwnedByEmbeddedFrame", "()Z", NO);
        isSimpleWindowOwnedByEmbeddedFrame = (*env)->CallBooleanMethod(env, platformWindow, jm_isBlocked) == JNI_TRUE ? YES : NO;
        CHECK_EXCEPTION();
        (*env)->DeleteLocalRef(env, platformWindow);
    }

    return isSimpleWindowOwnedByEmbeddedFrame;
}

// Tests whether the corresponding Java platform window is visible or not
+ (BOOL) isJavaPlatformWindowVisible:(NSWindow *)window {
    BOOL isVisible = NO;

    if ([AWTWindow isAWTWindow:window] && [window delegate] != nil) {
        AWTWindow *awtWindow = (AWTWindow *)[window delegate];
        [AWTToolkit eventCountPlusPlus];

        JNIEnv *env = [ThreadUtilities getJNIEnv];
        jobject platformWindow = (*env)->NewLocalRef(env, awtWindow.javaPlatformWindow);
        if (platformWindow != NULL) {
            GET_CPLATFORM_WINDOW_CLASS_RETURN(isVisible);
            DECLARE_METHOD_RETURN(jm_isVisible, jc_CPlatformWindow, "isVisible", "()Z", isVisible)
            isVisible = (*env)->CallBooleanMethod(env, platformWindow, jm_isVisible) == JNI_TRUE ? YES : NO;
            CHECK_EXCEPTION();
            (*env)->DeleteLocalRef(env, platformWindow);

        }
    }
    return isVisible;
}

- (BOOL) delayShowing {
    AWT_ASSERT_APPKIT_THREAD;

    return ownerWindow != nil &&
           ([ownerWindow delayShowing] || !ownerWindow.nsWindow.onActiveSpace) &&
           !nsWindow.visible;
}

- (BOOL) checkBlockingAndOrder {
    AWT_ASSERT_APPKIT_THREAD;

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject platformWindow = (*env)->NewLocalRef(env, self.javaPlatformWindow);
    if (platformWindow != NULL) {
        GET_CPLATFORM_WINDOW_CLASS_RETURN(NO);
        DECLARE_METHOD_RETURN(jm_checkBlockingAndOrder, jc_CPlatformWindow, "checkBlockingAndOrder", "()V", NO);
        (*env)->CallVoidMethod(env, platformWindow, jm_checkBlockingAndOrder);
        CHECK_EXCEPTION();
        (*env)->DeleteLocalRef(env, platformWindow);
    }
    return YES;
}

+ (void)activeSpaceDidChange {
    AWT_ASSERT_APPKIT_THREAD;

    if (fullScreenTransitionInProgress) {
        orderingScheduled = YES;
        return;
    }

    // show delayed windows
    for (NSWindow *window in NSApp.windows) {
        if ([AWTWindow isJavaPlatformWindowVisible:window] && !window.visible) {
            AWTWindow *awtWindow = (AWTWindow *)[window delegate];
            while (awtWindow.ownerWindow != nil) {
                awtWindow = awtWindow.ownerWindow;
            }
            if (awtWindow.nsWindow.visible && awtWindow.nsWindow.onActiveSpace) {
                [awtWindow checkBlockingAndOrder];
            }
        }
    }
}

- (void) processVisibleChildren:(void(^)(AWTWindow*))action {
    NSEnumerator *windowEnumerator = [[NSApp windows]objectEnumerator];
    NSWindow *window;
    while ((window = [windowEnumerator nextObject]) != nil) {
        if ([AWTWindow isJavaPlatformWindowVisible:window]) {
            AWTWindow *awtWindow = (AWTWindow *)[window delegate];
            AWTWindow *parent = awtWindow.ownerWindow;
            while (parent != nil) {
                if (parent == self) {
                    action(awtWindow);
                    break;
                }
                parent = parent.ownerWindow;
            }
        }
    }
}

// Orders window children based on the current focus state
- (void) orderChildWindows:(BOOL)focus {
AWT_ASSERT_APPKIT_THREAD;

    if (self.isMinimizing) {
        // Do not perform any ordering, if iconify is in progress
        return;
    }

    [self processVisibleChildren:^void(AWTWindow* child){
        // Do not order 'always on top' windows
        if (!IS(child.styleBits, ALWAYS_ON_TOP)) {
            NSWindow *window = child.nsWindow;
            NSWindow *owner = child.ownerWindow.nsWindow;
            if (focus) {
                // Move the childWindow to floating level
                // so it will appear in front of its
                // parent which owns the focus
                [window setLevel:NSFloatingWindowLevel];
            } else {
                // Focus owner has changed, move the childWindow
                // back to normal window level
                [window setLevel:NSNormalWindowLevel];
            }
        }
    }];
}

// NSWindow overrides
- (BOOL) canBecomeKeyWindow {
AWT_ASSERT_APPKIT_THREAD;
    return self.isEnabled && (IS(self.styleBits, SHOULD_BECOME_KEY) || [self isSimpleWindowOwnedByEmbeddedFrame]);
}

- (void) becomeKeyWindow {
    AWT_ASSERT_APPKIT_THREAD;

    // Reset current cursor in CCursorManager such that any following mouse update event
    // restores the correct cursor to the frame context specific one.
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    DECLARE_CLASS(jc_CCursorManager, "sun/lwawt/macosx/CCursorManager");
    DECLARE_STATIC_METHOD(sjm_resetCurrentCursor, jc_CCursorManager, "resetCurrentCursor", "()V");
    (*env)->CallStaticVoidMethod(env, jc_CCursorManager, sjm_resetCurrentCursor);
    CHECK_EXCEPTION();
}

- (BOOL) canBecomeMainWindow {
AWT_ASSERT_APPKIT_THREAD;
    if (!self.isEnabled) {
        // Native system can bring up the NSWindow to
        // the top even if the window is not main.
        // We should bring up the modal dialog manually
        [AWTToolkit eventCountPlusPlus];

        if (![self checkBlockingAndOrder]) return NO;
    }

    return self.isEnabled && IS(self.styleBits, SHOULD_BECOME_MAIN);
}

- (BOOL) worksWhenModal {
AWT_ASSERT_APPKIT_THREAD;
    return IS(self.styleBits, MODAL_EXCLUDED);
}


// NSWindowDelegate methods

- (void)_windowDidBecomeVisible {
    self.currentDisplayID = [AWTWindow getNSWindowDisplayID_AppKitThread:nsWindow];
}

- (void)_displayChanged:(BOOL)profileOnly {
    AWT_ASSERT_APPKIT_THREAD;
    if (!profileOnly) {
        NSNumber* newDisplayID = [AWTWindow getNSWindowDisplayID_AppKitThread:nsWindow];
        if (newDisplayID == nil) {
            // Do not proceed with unset window display id
            // to avoid receiving wrong boundary values
            return;
        }

        if (self.currentDisplayID == nil) {
            // Do not trigger notification at first initialization
            self.currentDisplayID = newDisplayID;
            return;
        }

        if ([self.currentDisplayID isEqualToNumber: newDisplayID]) {
            return;
        }
        self.currentDisplayID = newDisplayID;
    }

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject platformWindow = (*env)->NewLocalRef(env, self.javaPlatformWindow);
    if (platformWindow == NULL) {
        NSLog(@"[AWTWindow _displayChanged]: platformWindow == NULL");
        return;
    }
    GET_CPLATFORM_WINDOW_CLASS();
    DECLARE_METHOD(jm_displayChanged, jc_CPlatformWindow, "displayChanged", "(Z)V");
    (*env)->CallVoidMethod(env, platformWindow, jm_displayChanged, profileOnly);
    CHECK_EXCEPTION();
    (*env)->DeleteLocalRef(env, platformWindow);
}

- (void) _deliverMoveResizeEvent {
    AWT_ASSERT_APPKIT_THREAD;

    // deliver the event if this is a user-initiated live resize or as a side-effect
    // of a Java initiated resize, because AppKit can override the bounds and force
    // the bounds of the window to avoid the Dock or remain on screen.
    [AWTToolkit eventCountPlusPlus];
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject platformWindow = (*env)->NewLocalRef(env, self.javaPlatformWindow);
    if (platformWindow == NULL) {
        NSLog(@"[AWTWindow _deliverMoveResizeEvent]: platformWindow == NULL");
        return;
    }
    NSRect frame;
    @try {
        frame = ConvertNSScreenRect(env, [self.nsWindow frame]);
    } @catch (NSException *e) {
        NSLog(@"WARNING: suppressed exception from ConvertNSScreenRect() in [AWTWindow _deliverMoveResizeEvent]");
        NSProcessInfo *processInfo = [NSProcessInfo processInfo];
        [NSApplicationAWT logException:e forProcess:processInfo];
        return;
    }

    GET_CPLATFORM_WINDOW_CLASS();
    DECLARE_METHOD(jm_deliverMoveResizeEvent, jc_CPlatformWindow, "deliverMoveResizeEvent", "(IIIIZ)V");
    (*env)->CallVoidMethod(env, platformWindow, jm_deliverMoveResizeEvent,
                      (jint)frame.origin.x,
                      (jint)frame.origin.y,
                      (jint)frame.size.width,
                      (jint)frame.size.height,
                      (jboolean)[self.nsWindow inLiveResize]);
    CHECK_EXCEPTION();
    (*env)->DeleteLocalRef(env, platformWindow);

    [AWTWindow synthesizeMouseEnteredExitedEventsForAllWindows];
}

- (void)windowDidMove:(NSNotification *)notification {
AWT_ASSERT_APPKIT_THREAD;

    [self _deliverMoveResizeEvent];
}

- (void)windowDidResize:(NSNotification *)notification {
AWT_ASSERT_APPKIT_THREAD;
    if (self.isEnterFullScreen && ignoreResizeWindowDuringAnotherWindowEnd) {
#ifdef DEBUG
        NSLog(@"=== Native.windowDidResize: %@ | ignored in transition to fullscreen ===", self.nsWindow.title);
#endif
        return;
    }

    [self _deliverMoveResizeEvent];
}

- (void)windowDidExpose:(NSNotification *)notification {
AWT_ASSERT_APPKIT_THREAD;

    [AWTToolkit eventCountPlusPlus];
    // TODO: don't see this callback invoked anytime so we track
    // window exposing in _setVisible:(BOOL)
}

- (NSRect)windowWillUseStandardFrame:(NSWindow *)window
                        defaultFrame:(NSRect)newFrame {

    return NSEqualSizes(NSZeroSize, [self standardFrame].size)
                ? newFrame
                : [self standardFrame];
}

// Hides/shows window children during iconify/de-iconify operation
- (void) iconifyChildWindows:(BOOL)iconify {
AWT_ASSERT_APPKIT_THREAD;

    [self processVisibleChildren:^void(AWTWindow* child){
        NSWindow *window = child.nsWindow;
        if (iconify) {
            [window orderOut:window];
        } else {
            [window orderFront:window];
        }
    }];
}

- (void) _deliverIconify:(BOOL)iconify {
AWT_ASSERT_APPKIT_THREAD;

    [AWTToolkit eventCountPlusPlus];
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject platformWindow = (*env)->NewLocalRef(env, self.javaPlatformWindow);
    if (platformWindow != NULL) {
        GET_CPLATFORM_WINDOW_CLASS();
        DECLARE_METHOD(jm_deliverIconify, jc_CPlatformWindow, "deliverIconify", "(Z)V");
        (*env)->CallVoidMethod(env, platformWindow, jm_deliverIconify, iconify);
        CHECK_EXCEPTION();
        (*env)->DeleteLocalRef(env, platformWindow);
    }
}

- (void)windowWillMiniaturize:(NSNotification *)notification {
AWT_ASSERT_APPKIT_THREAD;

    self.isMinimizing = YES;

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject platformWindow = (*env)->NewLocalRef(env, self.javaPlatformWindow);
    if (platformWindow != NULL) {
        GET_CPLATFORM_WINDOW_CLASS();
        DECLARE_METHOD(jm_windowWillMiniaturize, jc_CPlatformWindow, "windowWillMiniaturize", "()V");
        (*env)->CallVoidMethod(env, platformWindow, jm_windowWillMiniaturize);
        CHECK_EXCEPTION();
        (*env)->DeleteLocalRef(env, platformWindow);
    }
    // Explicitly make myself a key window to avoid possible
    // negative visual effects during iconify operation
    [self.nsWindow makeKeyAndOrderFront:self.nsWindow];
    [self iconifyChildWindows:YES];
}

- (void)windowDidMiniaturize:(NSNotification *)notification {
AWT_ASSERT_APPKIT_THREAD;

    [self _deliverIconify:JNI_TRUE];
    self.isMinimizing = NO;
}

- (void)windowDidDeminiaturize:(NSNotification *)notification {
AWT_ASSERT_APPKIT_THREAD;

    [self _deliverIconify:JNI_FALSE];
    [self iconifyChildWindows:NO];
}

- (void) _deliverWindowFocusEvent:(BOOL)focused oppositeWindow:(AWTWindow *)opposite {
//AWT_ASSERT_APPKIT_THREAD;
    JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
    jobject platformWindow = (*env)->NewLocalRef(env, self.javaPlatformWindow);
    if (platformWindow != NULL) {
        jobject oppositeWindow = (*env)->NewLocalRef(env, opposite.javaPlatformWindow);
        GET_CPLATFORM_WINDOW_CLASS();
        DECLARE_METHOD(jm_deliverWindowFocusEvent, jc_CPlatformWindow, "deliverWindowFocusEvent", "(ZLsun/lwawt/macosx/CPlatformWindow;)V");
        (*env)->CallVoidMethod(env, platformWindow, jm_deliverWindowFocusEvent, (jboolean)focused, oppositeWindow);
        CHECK_EXCEPTION();
        (*env)->DeleteLocalRef(env, platformWindow);
        (*env)->DeleteLocalRef(env, oppositeWindow);
    }
}

- (void) windowDidBecomeMain: (NSNotification *) notification {
AWT_ASSERT_APPKIT_THREAD;
    [AWTToolkit eventCountPlusPlus];
#ifdef DEBUG
    NSLog(@"became main: %d %@ %@", [self.nsWindow isKeyWindow], [self.nsWindow title], [self menuBarForWindow]);
#endif

    [self activateWindowMenuBar];

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject platformWindow = (*env)->NewLocalRef(env, self.javaPlatformWindow);
    if (platformWindow != NULL) {
        GET_CPLATFORM_WINDOW_CLASS();
        DECLARE_METHOD(jm_windowDidBecomeMain, jc_CPlatformWindow, "windowDidBecomeMain", "()V");
        (*env)->CallVoidMethod(env, platformWindow, jm_windowDidBecomeMain);
        CHECK_EXCEPTION();
        (*env)->DeleteLocalRef(env, platformWindow);
    }

    [self orderChildWindows:YES];
}

- (void) windowDidBecomeKey: (NSNotification *) notification {
AWT_ASSERT_APPKIT_THREAD;
    [AWTToolkit eventCountPlusPlus];
#ifdef DEBUG
    NSLog(@"became key: %d %@ %@", [self.nsWindow isMainWindow], [self.nsWindow title], [self menuBarForWindow]);
#endif
    AWTWindow *opposite = [AWTWindow lastKeyWindow];

    if (![self.nsWindow isMainWindow]) {
        [self makeRelevantAncestorMain];
    }

    [AWTWindow setLastKeyWindow:nil];

    [self _deliverWindowFocusEvent:YES oppositeWindow: opposite];
}

- (void) makeRelevantAncestorMain {
    NSWindow *nativeWindow;
    AWTWindow *awtWindow = self;

    do {
        nativeWindow = awtWindow.nsWindow;
        if ([nativeWindow canBecomeMainWindow]) {
            [nativeWindow makeMainWindow];
            break;
        }
        awtWindow = awtWindow.ownerWindow;
    } while (awtWindow);
}

- (void) activateWindowMenuBar {
AWT_ASSERT_APPKIT_THREAD;
    // Finds appropriate menubar in our hierarchy
    AWTWindow *awtWindow = self;
    while (awtWindow.ownerWindow != nil) {
        awtWindow = awtWindow.ownerWindow;
    }

    CMenuBar *menuBar = nil;
    BOOL isDisabled = NO;
    if ([awtWindow.nsWindow isVisible]){
        menuBar = awtWindow.javaMenuBar;
        isDisabled = !awtWindow.isEnabled;
    }

    if (menuBar == nil && [ApplicationDelegate sharedDelegate] != nil) {
        menuBar = [[ApplicationDelegate sharedDelegate] defaultMenuBar];
        isDisabled = NO;
    }

    [CMenuBar activate:menuBar modallyDisabled:isDisabled];
}

#ifdef DEBUG
- (CMenuBar *) menuBarForWindow {
AWT_ASSERT_APPKIT_THREAD;
    AWTWindow *awtWindow = self;
    while (awtWindow.ownerWindow != nil) {
        awtWindow = awtWindow.ownerWindow;
    }
    return awtWindow.javaMenuBar;
}
#endif

- (void) windowDidResignKey: (NSNotification *) notification {
    // TODO: check why sometimes at start is invoked *not* on AppKit main thread.
AWT_ASSERT_APPKIT_THREAD;
    [AWTToolkit eventCountPlusPlus];
#ifdef DEBUG
    NSLog(@"resigned key: %d %@ %@", [self.nsWindow isMainWindow], [self.nsWindow title], [self menuBarForWindow]);
#endif
    if (![self.nsWindow isMainWindow] || [NSApp keyWindow] == self.nsWindow) {
        [self deactivateWindow];
    }
}

- (void) windowDidResignMain: (NSNotification *) notification {
AWT_ASSERT_APPKIT_THREAD;
    [AWTToolkit eventCountPlusPlus];
#ifdef DEBUG
    NSLog(@"resigned main: %d %@ %@", [self.nsWindow isKeyWindow], [self.nsWindow title], [self menuBarForWindow]);
#endif
    if (![self.nsWindow isKeyWindow]) {
        [self deactivateWindow];
    }

    [self.javaMenuBar deactivate];
    [self orderChildWindows:NO];
}

- (void) deactivateWindow {
AWT_ASSERT_APPKIT_THREAD;
#ifdef DEBUG
    NSLog(@"deactivating window: %@", [self.nsWindow title]);
#endif

    // the new key window
    NSWindow *keyWindow = [NSApp keyWindow];
    AWTWindow *opposite = nil;
    if ([AWTWindow isAWTWindow: keyWindow]) {
        if (keyWindow != self.nsWindow) {
            opposite = (AWTWindow *)[keyWindow delegate];
        }
        [AWTWindow setLastKeyWindow: self];
    } else {
        [AWTWindow setLastKeyWindow: nil];
    }

    [self _deliverWindowFocusEvent:NO oppositeWindow: opposite];
}

- (BOOL)windowShouldClose:(id)sender {
AWT_ASSERT_APPKIT_THREAD;
    [AWTToolkit eventCountPlusPlus];
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject platformWindow = (*env)->NewLocalRef(env, self.javaPlatformWindow);
    if (platformWindow != NULL) {
        GET_CPLATFORM_WINDOW_CLASS_RETURN(NO);
        DECLARE_METHOD_RETURN(jm_deliverWindowClosingEvent, jc_CPlatformWindow, "deliverWindowClosingEvent", "()V", NO);
        (*env)->CallVoidMethod(env, platformWindow, jm_deliverWindowClosingEvent);
        CHECK_EXCEPTION();
        (*env)->DeleteLocalRef(env, platformWindow);
    }
    // The window will be closed (if allowed) as result of sending Java event
    return NO;
}

- (void)_notifyFullScreenOp:(jint)op withEnv:(JNIEnv *)env {
    DECLARE_CLASS(jc_FullScreenHandler, "com/apple/eawt/FullScreenHandler");
    DECLARE_STATIC_METHOD(jm_notifyFullScreenOperation, jc_FullScreenHandler,
                           "handleFullScreenEventFromNative", "(Ljava/awt/Window;I)V");
    GET_CPLATFORM_WINDOW_CLASS();
    DECLARE_FIELD(jf_target, jc_CPlatformWindow, "target", "Ljava/awt/Window;");
    jobject platformWindow = (*env)->NewLocalRef(env, self.javaPlatformWindow);
    if (platformWindow != NULL) {
        jobject awtWindow = (*env)->GetObjectField(env, platformWindow, jf_target);
        if (awtWindow != NULL) {
            (*env)->CallStaticVoidMethod(env, jc_FullScreenHandler, jm_notifyFullScreenOperation, awtWindow, op);
            CHECK_EXCEPTION();
            (*env)->DeleteLocalRef(env, awtWindow);
        }
        (*env)->DeleteLocalRef(env, platformWindow);
    }
}

// this is required to move owned windows to the full-screen space when owner goes to full-screen mode
- (void)allowMovingChildrenBetweenSpaces:(BOOL)allow {
    [self processVisibleChildren:^void(AWTWindow* child){
        NSWindow *window = child.nsWindow;
        NSWindowCollectionBehavior behavior = window.collectionBehavior;
        behavior &= ~(NSWindowCollectionBehaviorManaged | NSWindowCollectionBehaviorTransient);
        behavior |= allow ? NSWindowCollectionBehaviorTransient : NSWindowCollectionBehaviorManaged;
        window.collectionBehavior = behavior;
    }];
}

- (void) fullScreenTransitionStarted {
    fullScreenTransitionInProgress = YES;
}

- (void) fullScreenTransitionFinished {
    fullScreenTransitionInProgress = NO;
    if (orderingScheduled) {
        orderingScheduled = NO;
        [self checkBlockingAndOrder];
    }
}

- (CGFloat) customTitleBarHeight {
    CGFloat h = _customTitleBarHeight;
    if (h < 0.0f) {
        JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
        GET_CPLATFORM_WINDOW_CLASS_RETURN(YES);
        DECLARE_FIELD_RETURN(jf_target, jc_CPlatformWindow, "target", "Ljava/awt/Window;", 0.0f);
        DECLARE_CLASS_RETURN(jc_Window, "java/awt/Window", 0.0f);
        DECLARE_METHOD_RETURN(jm_internalCustomTitleBarHeight, jc_Window, "internalCustomTitleBarHeight", "()F", 0.0f);
        DECLARE_METHOD_RETURN(jm_internalCustomTitleBarControlsVisible, jc_Window, "internalCustomTitleBarControlsVisible", "()Z", 0.0f);

        jobject platformWindow = (*env)->NewLocalRef(env, self.javaPlatformWindow);
        if (!platformWindow) return 0.0f;
        jobject target = (*env)->GetObjectField(env, platformWindow, jf_target);
        if (target) {
            h = (CGFloat) (*env)->CallFloatMethod(env, target, jm_internalCustomTitleBarHeight);
            self.customTitleBarControlsVisible = (BOOL) (*env)->CallBooleanMethod(env, target, jm_internalCustomTitleBarControlsVisible);
            (*env)->DeleteLocalRef(env, target);
        }
        CHECK_EXCEPTION();
        (*env)->DeleteLocalRef(env, platformWindow);
        if (h < 0.0f) h = 0.0f;
        _customTitleBarHeight = h;
    }
    return h;
}

- (BOOL) isCustomTitleBarEnabled {
    CGFloat h = _customTitleBarHeight;
    if (h < 0.0f) h = self.customTitleBarHeight;
    return h > 0.0f;
}

- (void) updateCustomTitleBarInsets:(BOOL)hasControls {
    CGFloat leftInset;
    if (hasControls) {
        CGFloat shrinkingFactor = self.customTitleBarButtonShrinkingFactor;
        CGFloat horizontalButtonOffset = shrinkingFactor * DefaultHorizontalTitleBarButtonOffset;
        leftInset = self.customTitleBarHeight + 2.0f * horizontalButtonOffset;
    } else leftInset = 0.0f;

    JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
    GET_CPLATFORM_WINDOW_CLASS();
    DECLARE_FIELD(jf_target, jc_CPlatformWindow, "target", "Ljava/awt/Window;");
    DECLARE_CLASS(jc_Window, "java/awt/Window");
    DECLARE_METHOD(jm_internalCustomTitleBarUpdateInsets, jc_Window, "internalCustomTitleBarUpdateInsets", "(FF)V");

    jobject platformWindow = (*env)->NewLocalRef(env, self.javaPlatformWindow);
    if (!platformWindow) return;
    jobject target = (*env)->GetObjectField(env, platformWindow, jf_target);
    if (target) {
        (*env)->CallVoidMethod(env, target, jm_internalCustomTitleBarUpdateInsets, (jfloat) leftInset, (jfloat) 0.0f);
        (*env)->DeleteLocalRef(env, target);
    }
    CHECK_EXCEPTION();
    (*env)->DeleteLocalRef(env, platformWindow);
}

- (void)windowWillEnterFullScreen:(NSNotification *)notification {
    [self fullScreenTransitionStarted];
    [self allowMovingChildrenBetweenSpaces:YES];

    self.isEnterFullScreen = YES;

    if (self.isCustomTitleBarEnabled) {
        [self resetCustomTitleBar];
    }

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    GET_CPLATFORM_WINDOW_CLASS();
    DECLARE_METHOD(jm_windowWillEnterFullScreen, jc_CPlatformWindow, "windowWillEnterFullScreen", "()V");
    jobject platformWindow = (*env)->NewLocalRef(env, self.javaPlatformWindow);
    if (platformWindow != NULL) {
        (*env)->CallVoidMethod(env, platformWindow, jm_windowWillEnterFullScreen);
        CHECK_EXCEPTION();
        [self _notifyFullScreenOp:com_apple_eawt_FullScreenHandler_FULLSCREEN_WILL_ENTER withEnv:env];
        (*env)->DeleteLocalRef(env, platformWindow);
    }
}

- (void)windowDidEnterFullScreen:(NSNotification *)notification {
    self.isEnterFullScreen = YES;

    if (self.isCustomTitleBarEnabled) {
        [self forceHideCustomTitleBarTitle:NO];
        [self updateCustomTitleBarInsets:NO];

        JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
        NSString *newFullScreenControls = [PropertiesUtilities
            javaSystemPropertyForKey:@"apple.awt.newFullScreenControls" withEnv:env];
        if ([@"true" isCaseInsensitiveLike:newFullScreenControls]) {
            [self setWindowFullScreenControls];
        }
    }
    [self allowMovingChildrenBetweenSpaces:NO];
    [self fullScreenTransitionFinished];

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    GET_CPLATFORM_WINDOW_CLASS();
    DECLARE_METHOD(jm_windowDidEnterFullScreen, jc_CPlatformWindow, "windowDidEnterFullScreen", "()V");
    jobject platformWindow = (*env)->NewLocalRef(env, self.javaPlatformWindow);
    if (platformWindow != NULL) {
        (*env)->CallVoidMethod(env, platformWindow, jm_windowDidEnterFullScreen);
        CHECK_EXCEPTION();
        [self _notifyFullScreenOp:com_apple_eawt_FullScreenHandler_FULLSCREEN_DID_ENTER withEnv:env];
        (*env)->DeleteLocalRef(env, platformWindow);
    }
    [AWTWindow synthesizeMouseEnteredExitedEventsForAllWindows];
}

- (void)windowWillExitFullScreen:(NSNotification *)notification {
    self.isEnterFullScreen = NO;

    [self fullScreenTransitionStarted];

    if (self.isCustomTitleBarEnabled) {
        [self setWindowControlsHidden:YES];
        [self updateCustomTitleBarInsets:self.customTitleBarControlsVisible];
        [self forceHideCustomTitleBarTitle:YES];
    }

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    GET_CPLATFORM_WINDOW_CLASS();
    DECLARE_METHOD(jm_windowWillExitFullScreen, jc_CPlatformWindow, "windowWillExitFullScreen", "()V");
    if (jm_windowWillExitFullScreen == NULL) {
        GET_CPLATFORM_WINDOW_CLASS();
        jm_windowWillExitFullScreen = (*env)->GetMethodID(env, jc_CPlatformWindow, "windowWillExitFullScreen", "()V");
    }
    CHECK_NULL(jm_windowWillExitFullScreen);
    jobject platformWindow = (*env)->NewLocalRef(env, self.javaPlatformWindow);
    if (platformWindow != NULL) {
        (*env)->CallVoidMethod(env, platformWindow, jm_windowWillExitFullScreen);
        CHECK_EXCEPTION();
        [self _notifyFullScreenOp:com_apple_eawt_FullScreenHandler_FULLSCREEN_WILL_EXIT withEnv:env];
        (*env)->DeleteLocalRef(env, platformWindow);
    }
}

- (void)windowDidExitFullScreen:(NSNotification *)notification {
    [self resetWindowFullScreenControls];

    self.isEnterFullScreen = NO;

    [self fullScreenTransitionFinished];

    if (self.isCustomTitleBarEnabled) {
        [self setUpCustomTitleBar];
    }

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject platformWindow = (*env)->NewLocalRef(env, self.javaPlatformWindow);
    if (platformWindow != NULL) {
        GET_CPLATFORM_WINDOW_CLASS();
        DECLARE_METHOD(jm_windowDidExitFullScreen, jc_CPlatformWindow, "windowDidExitFullScreen", "()V");
        (*env)->CallVoidMethod(env, platformWindow, jm_windowDidExitFullScreen);
        CHECK_EXCEPTION();
        [self _notifyFullScreenOp:com_apple_eawt_FullScreenHandler_FULLSCREEN_DID_EXIT withEnv:env];
        (*env)->DeleteLocalRef(env, platformWindow);
    }
    [AWTWindow synthesizeMouseEnteredExitedEventsForAllWindows];
}

- (void)sendEvent:(NSEvent *)event {
        if ([event type] == NSLeftMouseDown ||
            [event type] == NSRightMouseDown ||
            [event type] == NSOtherMouseDown) {
            NSPoint p = [NSEvent mouseLocation];
            NSRect frame = [self.nsWindow frame];
            NSRect contentRect = [self.nsWindow contentRectForFrameRect:frame];

            // Check if the click happened in the non-client area (title bar)
            // Also, non-client area includes the edges at left, right and botton of frame
            if ((p.y >= (frame.origin.y + contentRect.size.height)) ||
                (p.x >= (frame.origin.x + contentRect.size.width - 3)) ||
                (fabs(frame.origin.x - p.x) < 3) ||
                (fabs(frame.origin.y - p.y) < 3)) {
                JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
                jobject platformWindow = (*env)->NewLocalRef(env, self.javaPlatformWindow);
                if (platformWindow != NULL) {
                    // Currently, no need to deliver the whole NSEvent.
                    GET_CPLATFORM_WINDOW_CLASS();
                    DECLARE_METHOD(jm_deliverNCMouseDown, jc_CPlatformWindow, "deliverNCMouseDown", "()V");
                    (*env)->CallVoidMethod(env, platformWindow, jm_deliverNCMouseDown);
                    CHECK_EXCEPTION();
                    (*env)->DeleteLocalRef(env, platformWindow);
                }
            }
        }
}

- (void)constrainSize:(NSSize*)size {
    float minWidth = 0.f, minHeight = 0.f;

    if (IS(self.styleBits, DECORATED)) {
        NSRect frame = [self.nsWindow frame];
        NSRect contentRect = [NSWindow contentRectForFrameRect:frame styleMask:[self.nsWindow styleMask]];

        float top = frame.size.height - contentRect.size.height;
        float left = contentRect.origin.x - frame.origin.x;
        float bottom = contentRect.origin.y - frame.origin.y;
        float right = frame.size.width - (contentRect.size.width + left);

        // Speculative estimation: 80 - enough for window decorations controls
        minWidth += left + right + 80;
        minHeight += top + bottom;
    }

    minWidth = MAX(1.f, minWidth);
    minHeight = MAX(1.f, minHeight);

    size->width = MAX(size->width, minWidth);
    size->height = MAX(size->height, minHeight);
}

- (void) setEnabled: (BOOL)flag {
    self.isEnabled = flag;

    if (IS(self.styleBits, CLOSEABLE)) {
        [[self.nsWindow standardWindowButton:NSWindowCloseButton] setEnabled: flag];
    }

    if (IS(self.styleBits, MINIMIZABLE)) {
        [[self.nsWindow standardWindowButton:NSWindowMiniaturizeButton] setEnabled: flag];
    }

    if (IS(self.styleBits, ZOOMABLE)) {
        [[self.nsWindow standardWindowButton:NSWindowZoomButton] setEnabled: flag];
    }

    if (IS(self.styleBits, RESIZABLE)) {
        [self updateMinMaxSize:flag];
        [self.nsWindow setShowsResizeIndicator:flag];
    }
}

+ (void) setLastKeyWindow:(AWTWindow *)window {
    [window retain];
    [lastKeyWindow release];
    lastKeyWindow = window;
}

+ (AWTWindow *) lastKeyWindow {
    return lastKeyWindow;
}

static const CGFloat DefaultHorizontalTitleBarButtonOffset = 20.0;

- (CGFloat) customTitleBarButtonShrinkingFactor {
    CGFloat minimumHeightWithoutShrinking = 28.0; // This is the smallest macOS title bar availabe with public APIs as of Monterey
    CGFloat shrinkingFactor = fmin(self.customTitleBarHeight / minimumHeightWithoutShrinking, 1.0);
    return shrinkingFactor;
}

- (void) setUpCustomTitleBar {
    if (self.customTitleBarConstraints != nil) {
        [self resetCustomTitleBar];
    }
    /**
     * The view hierarchy normally looks as follows:
     * NSThemeFrame
     * ├─NSView (content view)
     * └─NSTitlebarContainerView
     *   ├─_NSTitlebarDecorationView (only on Mojave 10.14 and newer)
     *   └─NSTitlebarView
     *     ├─NSVisualEffectView (only on Big Sur 11 and newer)
     *     ├─NSView (only on Big Sur and newer)
     *     ├─_NSThemeCloseWidget - Close
     *     ├─_NSThemeZoomWidget - Full Screen
     *     ├─_NSThemeWidget - Minimize (note the different order compared to their layout)
     *     └─AWTWindowDragView (we will create this)
     *
     * But the order and presence of decorations and effects has been unstable across different macOS versions,
     * even patch upgrades, which is why the code below uses scans instead of indexed access
     */
    NSView* closeButtonView = [self.nsWindow standardWindowButton:NSWindowCloseButton];
    NSView* zoomButtonView = [self.nsWindow standardWindowButton:NSWindowZoomButton];
    NSView* miniaturizeButtonView = [self.nsWindow standardWindowButton:NSWindowMiniaturizeButton];
    if (!closeButtonView || !zoomButtonView || !miniaturizeButtonView) {
        NSLog(@"WARNING: setUpCustomTitleBar closeButtonView=%@, zoomButtonView=%@, miniaturizeButtonView=%@",
              closeButtonView, zoomButtonView, miniaturizeButtonView);
        return;
    }
    NSView* titlebar = closeButtonView.superview;
    NSView* titlebarContainer = titlebar.superview;
    NSView* themeFrame = titlebarContainer.superview;
    if (!themeFrame) {
        NSLog(@"WARNING: setUpCustomTitleBar titlebar=%@, titlebarContainer=%@, themeFrame=%@",
              titlebar, titlebarContainer, themeFrame);
        return;
    }

    self.customTitleBarConstraints = [[NSMutableArray alloc] init];
    titlebarContainer.translatesAutoresizingMaskIntoConstraints = NO;
    self.customTitleBarHeightConstraint = [titlebarContainer.heightAnchor constraintEqualToConstant:self.customTitleBarHeight];
    [self.customTitleBarConstraints addObjectsFromArray:@[
        [titlebarContainer.leftAnchor constraintEqualToAnchor:themeFrame.leftAnchor],
        [titlebarContainer.widthAnchor constraintEqualToAnchor:themeFrame.widthAnchor],
        [titlebarContainer.topAnchor constraintEqualToAnchor:themeFrame.topAnchor],
        self.customTitleBarHeightConstraint,
    ]];

    [self.nsWindow setIgnoreMove:YES];

    self.zoomButtonMouseResponder = [[AWTWindowZoomButtonMouseResponder alloc] initWithWindow:self.nsWindow];
    [self.zoomButtonMouseResponder release]; // property retains the object
    
    AWTWindowDragView* windowDragView = [[AWTWindowDragView alloc] initWithPlatformWindow:self.javaPlatformWindow];
    [titlebar addSubview:windowDragView positioned:NSWindowBelow relativeTo:closeButtonView];

    [@[titlebar, windowDragView] enumerateObjectsUsingBlock:^(NSView* view, NSUInteger index, BOOL* stop)
    {
        view.translatesAutoresizingMaskIntoConstraints = NO;
        [self.customTitleBarConstraints addObjectsFromArray:@[
                [view.leftAnchor constraintEqualToAnchor:titlebarContainer.leftAnchor],
                [view.rightAnchor constraintEqualToAnchor:titlebarContainer.rightAnchor],
                [view.topAnchor constraintEqualToAnchor:titlebarContainer.topAnchor],
                [view.bottomAnchor constraintEqualToAnchor:titlebarContainer.bottomAnchor],
        ]];
    }];

    CGFloat shrinkingFactor = self.customTitleBarButtonShrinkingFactor;
    CGFloat horizontalButtonOffset = shrinkingFactor * DefaultHorizontalTitleBarButtonOffset;
    self.customTitleBarButtonCenterXConstraints = [[NSMutableArray alloc] initWithCapacity:3];
    [@[closeButtonView, miniaturizeButtonView, zoomButtonView] enumerateObjectsUsingBlock:^(NSView* button, NSUInteger index, BOOL* stop)
    {
        button.translatesAutoresizingMaskIntoConstraints = NO;
        NSLayoutConstraint* buttonCenterXConstraint = [button.centerXAnchor constraintEqualToAnchor:titlebarContainer.leftAnchor
                                                       constant:(self.customTitleBarHeight / 2.0 + (index * horizontalButtonOffset))];
        [self.customTitleBarButtonCenterXConstraints addObject:buttonCenterXConstraint];
        [self.customTitleBarConstraints addObjectsFromArray:@[
            [button.widthAnchor constraintLessThanOrEqualToAnchor:titlebarContainer.heightAnchor multiplier:0.5],
            // Those corrections are required to keep the icons perfectly round because macOS adds a constant 2 px in resulting height to their frame
            [button.heightAnchor constraintEqualToAnchor: button.widthAnchor multiplier:14.0/12.0 constant:-2.0],
            [button.centerYAnchor constraintEqualToAnchor:titlebarContainer.centerYAnchor],
            buttonCenterXConstraint,
        ]];
    }];

    [NSLayoutConstraint activateConstraints:self.customTitleBarConstraints];
    // These properties are already retained, release them so that retainCount = 1
    [self.customTitleBarConstraints release];
    [self.customTitleBarButtonCenterXConstraints release];

    [self setWindowControlsHidden:!self.customTitleBarControlsVisible];
    [self updateCustomTitleBarInsets:self.customTitleBarControlsVisible];
}

- (void) updateCustomTitleBarConstraints {
    self.customTitleBarHeightConstraint.constant = self.customTitleBarHeight;
    CGFloat shrinkingFactor = self.customTitleBarButtonShrinkingFactor;
    CGFloat horizontalButtonOffset = shrinkingFactor * DefaultHorizontalTitleBarButtonOffset;
    [self.customTitleBarButtonCenterXConstraints enumerateObjectsUsingBlock:^(NSLayoutConstraint* buttonConstraint, NSUInteger index, BOOL *stop)
    {
        buttonConstraint.constant = (self.customTitleBarHeight / 2.0 + (index * horizontalButtonOffset));
    }];
    [self setWindowControlsHidden:!self.customTitleBarControlsVisible];
    [self updateCustomTitleBarInsets:self.customTitleBarControlsVisible];

    [self updateFullScreenButtons];
}

- (void) resetCustomTitleBar {
    // See [setUpCustomTitleBar] for the view hierarchy we're working with
    NSView* closeButtonView = [self.nsWindow standardWindowButton:NSWindowCloseButton];
    NSView* titlebar = closeButtonView.superview;
    NSView* titlebarContainer = titlebar.superview;
    if (!titlebarContainer) {
        NSLog(@"WARNING: resetCustomTitleBar closeButtonView=%@, titlebar=%@, titlebarContainer=%@",
              closeButtonView, titlebar, titlebarContainer);
        return;
    }

    [NSLayoutConstraint deactivateConstraints:self.customTitleBarConstraints];

    AWTWindowDragView* windowDragView = nil;
    for (NSView* view in titlebar.subviews) {
        if ([view isMemberOfClass:[AWTWindowDragView class]]) {
            windowDragView = view;
        }
    }

    if (windowDragView != nil) {
        [windowDragView removeFromSuperview];
    }

    titlebarContainer.translatesAutoresizingMaskIntoConstraints = YES;
    titlebar.translatesAutoresizingMaskIntoConstraints = YES;

    self.customTitleBarConstraints = nil;
    self.customTitleBarHeightConstraint = nil;
    self.customTitleBarButtonCenterXConstraints = nil;

    [self setWindowControlsHidden:NO];
    [self updateCustomTitleBarInsets:NO];
    
    [self.nsWindow setIgnoreMove:NO];
}

- (void) setWindowControlsHidden: (BOOL) hidden {
    if (_fullScreenOriginalButtons != nil) {
        [_fullScreenOriginalButtons.window.contentView setHidden:NO];
        _fullScreenButtons.hidden = YES;
    }

    [self.nsWindow standardWindowButton:NSWindowCloseButton].hidden = hidden;
    [self.nsWindow standardWindowButton:NSWindowZoomButton].hidden = hidden;
    [self.nsWindow standardWindowButton:NSWindowMiniaturizeButton].hidden = hidden;
}

- (void) setWindowFullScreenControls {
    JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
    NSString *dfmMode = [PropertiesUtilities javaSystemPropertyForKey:@"apple.awt.distraction.free.mode" withEnv:env];
    if ([@"true" isCaseInsensitiveLike:dfmMode]) {
        return;
    }

    NSView* oldCloseButton = [self.nsWindow standardWindowButton:NSWindowCloseButton];
    _fullScreenOriginalButtons = oldCloseButton.superview;

    NSRect closeButtonRect = [oldCloseButton frame];
    NSRect miniaturizeButtonRect = [[self.nsWindow standardWindowButton:NSWindowMiniaturizeButton] frame];
    NSRect zoomButtonRect = [[self.nsWindow standardWindowButton:NSWindowZoomButton] frame];

    for (NSWindow* window in [[NSApplication sharedApplication] windows]) {
          if ([window isKindOfClass:NSClassFromString(@"NSToolbarFullScreenWindow")]) {
            [window.contentView setHidden:YES];
          }
    }

    _fullScreenButtons = [[AWTButtonsView alloc] init];
    [self updateFullScreenButtons];
    [_fullScreenButtons addTrackingArea:[[NSTrackingArea alloc] initWithRect:[_fullScreenButtons visibleRect]
                                                      options:(NSTrackingActiveAlways | NSTrackingMouseEnteredAndExited | NSTrackingMouseMoved)
                                                      owner:_fullScreenButtons userInfo:nil]];

    NSUInteger masks = [self.nsWindow styleMask];

    NSButton *closeButton = [NSWindow standardWindowButton:NSWindowCloseButton forStyleMask:masks];
    [closeButton setFrame:closeButtonRect];
    [_fullScreenButtons addSubview:closeButton];

    NSButton *miniaturizeButton = [NSWindow standardWindowButton:NSWindowMiniaturizeButton forStyleMask:masks];
    [miniaturizeButton setFrame:miniaturizeButtonRect];
    [_fullScreenButtons addSubview:miniaturizeButton];

    NSButton *zoomButton = [NSWindow standardWindowButton:NSWindowZoomButton forStyleMask:masks];
    [zoomButton setFrame:zoomButtonRect];
    [_fullScreenButtons addSubview:zoomButton];

    [self.nsWindow.contentView addSubview:_fullScreenButtons];

    [self updateColors];
}

- (void)updateColors {
    if (_fullScreenButtons != nil) {
        [(AWTButtonsView *)_fullScreenButtons configureColors];
    }
}

- (void)updateFullScreenButtons {
    if (_fullScreenButtons == nil || _fullScreenOriginalButtons == nil) {
        return;
    }

    NSView *parent = self.nsWindow.contentView;
    CGFloat w = 80;
    CGFloat h = _fullScreenOriginalButtons.frame.size.height;
    CGFloat x = 6;
    CGFloat y = parent.frame.size.height - h - (self.customTitleBarHeight - h) / 2.0;

    [_fullScreenButtons setFrame:NSMakeRect(x, y, w - x, h)];
}

- (void)updateFullScreenButtons: (BOOL) dfm {
    if (dfm) {
        if (_fullScreenButtons == nil || _fullScreenOriginalButtons == nil) {
            return;
        }
        [_fullScreenOriginalButtons.window.contentView setHidden:NO];
        [self resetWindowFullScreenControls];
    } else {
        if (!self.isCustomTitleBarEnabled || _fullScreenButtons != nil) {
            return;
        }
        [self setWindowFullScreenControls];
    }
}

- (void) resetWindowFullScreenControls {
    if (_fullScreenButtons != nil) {
        [_fullScreenButtons removeFromSuperview];
        _fullScreenButtons = nil;
        _fullScreenOriginalButtons = nil;
    }
}

- (BOOL) isFullScreen {
    NSUInteger masks = [self.nsWindow styleMask];
    return (masks & NSWindowStyleMaskFullScreen) != 0;
}

- (void) forceHideCustomTitleBarTitle: (BOOL) hide {
    jint bits = self.styleBits;
    if (hide) bits &= ~MASK(TITLE_VISIBLE);
    [self setPropertiesForStyleBits:bits mask:MASK(TITLE_VISIBLE)];
}

- (void) updateCustomTitleBar {
    _customTitleBarHeight = -1.0f; // Reset for lazy init
    BOOL enabled = self.isCustomTitleBarEnabled;
    BOOL fullscreen = self.isFullScreen;

    jint mask = [AWTWindow affectedStyleMaskForCustomTitleBar];
    jint newBits = [AWTWindow overrideStyleBits:self.styleBits customTitleBarEnabled:enabled fullscreen:fullscreen];
    // Copied from nativeSetNSWindowStyleBits:
    // The content view must be resized first, otherwise the window will be resized to fit the existing
    // content view.
    NSUInteger styleMask = [AWTWindow styleMaskForStyleBits:newBits];
    if (!fullscreen) {
        NSRect frame = [nsWindow frame];
        NSRect screenContentRect = [NSWindow contentRectForFrameRect:frame styleMask:styleMask];
        NSRect contentFrame = NSMakeRect(screenContentRect.origin.x - frame.origin.x,
                                         screenContentRect.origin.y - frame.origin.y,
                                         screenContentRect.size.width,
                                         screenContentRect.size.height);
        nsWindow.contentView.frame = contentFrame;
    }
    // NSWindowStyleMaskFullScreen bit shouldn't be updated directly
    [nsWindow setStyleMask:(((NSWindowStyleMask) styleMask) & ~NSWindowStyleMaskFullScreen |
                            nsWindow.styleMask & NSWindowStyleMaskFullScreen)];
    // calls methods on NSWindow to change other properties, based on the mask
    [self setPropertiesForStyleBits:newBits mask:mask];
    if (!fullscreen && !self.nsWindow.miniaturized) [self _deliverMoveResizeEvent];

    if (enabled != (self.customTitleBarConstraints != nil)) {
        if (!fullscreen) {
            if (self.isCustomTitleBarEnabled) {
                [self setUpCustomTitleBar];
            } else {
                [self resetCustomTitleBar];
            }
        } else {
            [self updateFullScreenButtons];
        }
    } else if (enabled) {
        [self updateCustomTitleBarConstraints];
    }
}

@end // AWTWindow

@implementation AWTWindowZoomButtonMouseResponder {
    NSWindow* _window;
    NSTrackingArea* _trackingArea;
}

- (id) initWithWindow:(NSWindow*)window {
    self = [super init];
    if (self == nil) {
        return nil;
    }

    if (![window isKindOfClass: [AWTWindow_Normal class]]) {
        [self release];
        return nil;
    }

    NSView* zoomButtonView = [window standardWindowButton:NSWindowZoomButton];
    if (zoomButtonView == nil) {
        [self release];
        return nil;
    }

    _window = [window retain];
    _trackingArea = [[NSTrackingArea alloc]
                    initWithRect:zoomButtonView.bounds
                         options:NSTrackingMouseEnteredAndExited | NSTrackingActiveInKeyWindow
                           owner:self
                        userInfo:nil
                    ];
    [zoomButtonView addTrackingArea:_trackingArea];

    return self;
}

- (void)mouseEntered:(NSEvent*)event {
    if ([_window isIgnoreMove]) {
        // Enable moving the window while we're mousing over the "maximize" button so that
        // macOS 15 window tiling actions can properly appear
        _window.movable = YES;
    }
}

- (void)mouseExited:(NSEvent*)event {
    if ([_window isIgnoreMove]) {
        _window.movable = NO;
    }
}

- (void)dealloc {
    [_window release];
    [_trackingArea release];
    [super dealloc];
}

@end

@implementation AWTWindowDragView {
    BOOL _dragging;
}

- (id) initWithPlatformWindow:(jobject)javaPlatformWindow {
    self = [super init];
    if (self == nil) return nil; // no hope

    self.javaPlatformWindow = javaPlatformWindow;
    return self;
}

- (BOOL) areCustomTitleBarNativeActionsAllowed {
    JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
    GET_CPLATFORM_WINDOW_CLASS_RETURN(YES);
    DECLARE_FIELD_RETURN(jf_target, jc_CPlatformWindow, "target", "Ljava/awt/Window;", YES);
    DECLARE_CLASS_RETURN(jc_Window, "java/awt/Window", YES);
    DECLARE_FIELD_RETURN(jf_customTitleBarHitTest, jc_Window, "customTitleBarHitTest", "I", YES);

    jobject platformWindow = (*env)->NewLocalRef(env, self.javaPlatformWindow);
    if (!platformWindow) return YES;
    jint hitTest = java_awt_Window_CustomTitleBar_HIT_UNDEFINED;
    jobject target = (*env)->GetObjectField(env, platformWindow, jf_target);
    if (target) {
        hitTest = (jint) (*env)->GetIntField(env, target, jf_customTitleBarHitTest);
        (*env)->DeleteLocalRef(env, target);
    }
    CHECK_EXCEPTION();
    (*env)->DeleteLocalRef(env, platformWindow);
    return hitTest <= java_awt_Window_CustomTitleBar_HIT_TITLEBAR;
}

- (BOOL) acceptsFirstMouse:(NSEvent *)event {
    return YES;
}

- (BOOL) shouldDelayWindowOrderingForEvent:(NSEvent *)event {
    return [[self.window contentView] shouldDelayWindowOrderingForEvent:event];
}
- (void) mouseDown: (NSEvent *)event {
    _dragging = NO;
    [[self.window contentView] mouseDown:event];
}
- (void) mouseUp: (NSEvent *)event {
    [[self.window contentView] mouseUp:event];
    if (event.clickCount == 2 && [self areCustomTitleBarNativeActionsAllowed]) {
        NSString *action = [[NSUserDefaults standardUserDefaults] stringForKey:@"AppleActionOnDoubleClick"];
        if (action != nil && [action caseInsensitiveCompare:@"Minimize"] == NSOrderedSame) {
            [self.window performMiniaturize:nil];
        } else if (action == nil || [action caseInsensitiveCompare:@"None"] != NSOrderedSame) { // action == "Maximize" (default)
            [self.window performZoom:nil];
        }
    }
}
- (void) rightMouseDown: (NSEvent *)event {
    [[self.window contentView] rightMouseDown:event];
}
- (void) rightMouseUp: (NSEvent *)event {
    [[self.window contentView] rightMouseUp:event];
}
- (void) otherMouseDown: (NSEvent *)event {
    [[self.window contentView] otherMouseDown:event];
}
- (void) otherMouseUp: (NSEvent *)event {
    [[self.window contentView] otherMouseUp:event];
}
- (void) mouseMoved: (NSEvent *)event {
    [[self.window contentView] mouseMoved:event];
}
- (void) mouseDragged: (NSEvent *)event {
    if (!_dragging) {
        _dragging = YES;
        if ([self areCustomTitleBarNativeActionsAllowed]) {
            [self.window performWindowDragWithEvent:event];
            return;
        }
    }
    [[self.window contentView] mouseDragged:event];
}
- (void) rightMouseDragged: (NSEvent *)event {
    [[self.window contentView] rightMouseDragged:event];
}
- (void) otherMouseDragged: (NSEvent *)event {
    [[self.window contentView] otherMouseDragged:event];
}
- (void) mouseEntered: (NSEvent *)event {
    [[self.window contentView] mouseEntered:event];
}
- (void) mouseExited: (NSEvent *)event {
    [[self.window contentView] mouseExited:event];
}
- (void) scrollWheel: (NSEvent*) event {
    [[self.window contentView] scrollWheel:event];
}
- (void) keyDown: (NSEvent *)event {
    [[self.window contentView] keyDown:event];
}
- (void) keyUp: (NSEvent *)event {
    [[self.window contentView] keyUp:event];
}
- (void) flagsChanged: (NSEvent *)event {
    [[self.window contentView] flagsChanged:event];
}

@end

@implementation AWTButtonsView

- (void)dealloc {
    [_color release];
    [super dealloc];
}

- (void)mouseEntered:(NSEvent *)theEvent {
    [self updateButtons:YES];
}

- (void)mouseExited:(NSEvent *)theEvent {
    [self updateButtons:NO];
}

- (void)configureColors {
    JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
    NSString *javaColor = [PropertiesUtilities javaSystemPropertyForKey:@"apple.awt.newFullScreenControls.background" withEnv:env];

    [_color release];

    if (javaColor == nil) {
        _color = nil;
    } else {
        int rgb = [javaColor intValue];

        CGFloat alpha = (((rgb >> 24) & 0xff) / 255.0);
        CGFloat red   = (((rgb >> 16) & 0xff) / 255.0);
        CGFloat green = (((rgb >>  8) & 0xff) / 255.0);
        CGFloat blue  = (((rgb >>  0) & 0xff) / 255.0);

        _color = [NSColor colorWithDeviceRed:red green:green blue:blue alpha:alpha];
        [_color retain];
    }

    [self updateButtons:NO];
}

- (void)updateButtons:(BOOL) flag {
    _showButtons = flag;

    if (self.subviews.count == 3) {
        [self updateButton:0 flag:flag]; // close
        [self updateButton:1 flag:NO];   // miniaturize
        [self updateButton:2 flag:flag]; // zoom
    }

    [self setNeedsDisplay:YES];
}

- (void)updateButton: (int)index flag:(BOOL) flag {
    NSButton *button = (NSButton*)self.subviews[index];
    [button setHidden:!flag];
    [button setHighlighted:flag];
}

- (void)drawRect: (NSRect)dirtyRect {
    if (self.subviews.count != 3) {
        return;
    }

    if (_color == nil) {
        [[NSColor whiteColor] setFill];
    } else {
        [_color setFill];
    }

    if (_showButtons) {
        [self drawButton:1]; // miniaturize
    } else {
        for (int i = 0; i < 3; i++) {
            [self drawButton:i];
        }
    }
}

- (void)drawButton: (int)index {
    NSButton *button = (NSButton*)self.subviews[index];
    NSRect rect = button.frame;
    CGFloat r = 12;
    CGFloat x = rect.origin.x + (rect.size.width - r) / 2;
    CGFloat y = rect.origin.y + (rect.size.height - r) / 2;

    NSBezierPath* circlePath = [NSBezierPath bezierPath];
    [circlePath appendBezierPathWithOvalInRect:CGRectMake(x, y, r, r)];
    [circlePath fill];
}

@end

/*
 * Class:     sun_lwawt_macosx_CPlatformWindow
 * Method:    nativeSetAllAllowAutomaticTabbingProperty
 * Signature: (Z)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CPlatformWindow_nativeSetAllowAutomaticTabbingProperty
        (JNIEnv *env, jclass clazz, jboolean allowAutomaticTabbing)
{
    JNI_COCOA_ENTER(env);
    [ThreadUtilities performOnMainThreadWaiting:NO block:^(){
        if (allowAutomaticTabbing) {
            [NSWindow setAllowsAutomaticWindowTabbing:YES];
        } else {
            [NSWindow setAllowsAutomaticWindowTabbing:NO];
        }
    }];
    JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CPlatformWindow
 * Method:    nativeCreateNSWindow
 * Signature: (JJIDDDD)J
 */
JNIEXPORT jlong JNICALL Java_sun_lwawt_macosx_CPlatformWindow_nativeCreateNSWindow
(JNIEnv *env, jobject obj, jlong contentViewPtr, jlong ownerPtr, jlong styleBits, jdouble x, jdouble y, jdouble w, jdouble h)
{
    __block AWTWindow *window = nil;

JNI_COCOA_ENTER(env);

    jobject platformWindow = (*env)->NewWeakGlobalRef(env, obj);
    NSView *contentView = OBJC(contentViewPtr);
    NSRect frameRect = NSMakeRect(x, y, w, h);
    AWTWindow *owner = [OBJC(ownerPtr) delegate];

    BOOL isIgnoreMouseEvents = NO;
    GET_CPLATFORM_WINDOW_CLASS_RETURN(0);
    DECLARE_FIELD_RETURN(jf_target, jc_CPlatformWindow, "target", "Ljava/awt/Window;", 0);
    jobject awtWindow = (*env)->GetObjectField(env, obj, jf_target);
    if (awtWindow != NULL) {
        DECLARE_CLASS_RETURN(jc_Window, "java/awt/Window", 0);
        DECLARE_METHOD_RETURN(jm_isIgnoreMouseEvents, jc_Window, "isIgnoreMouseEvents", "()Z", 0);
        isIgnoreMouseEvents = (*env)->CallBooleanMethod(env, awtWindow, jm_isIgnoreMouseEvents) == JNI_TRUE ? YES : NO;
        (*env)->DeleteLocalRef(env, awtWindow);
    }
    [ThreadUtilities performOnMainThreadWaiting:YES block:^(){

        window = [[AWTWindow alloc] initWithPlatformWindow:platformWindow
                                               ownerWindow:owner
                                                 styleBits:styleBits
                                                 frameRect:frameRect
                                               contentView:contentView];
        // the window is released is CPlatformWindow.nativeDispose()

        if (window) {
            [window.nsWindow retain];
            if (isIgnoreMouseEvents) {
                [window.nsWindow setIgnoresMouseEvents:YES];
            }
        }
    }];

JNI_COCOA_EXIT(env);

    return ptr_to_jlong(window ? window.nsWindow : nil);
}

/*
 * Class:     sun_lwawt_macosx_CPlatformWindow
 * Method:    nativeSetNSWindowStyleBits
 * Signature: (JII)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CPlatformWindow_nativeSetNSWindowStyleBits
(JNIEnv *env, jclass clazz, jlong windowPtr, jint mask, jint bits)
{
JNI_COCOA_ENTER(env);

    NSWindow *nsWindow = OBJC(windowPtr);

    [ThreadUtilities performOnMainThreadWaiting:NO block:^(){

        AWTWindow *window = (AWTWindow*)[nsWindow delegate];

        BOOL customTitleBarEnabled = window.isCustomTitleBarEnabled;
        BOOL fullscreen = window.isFullScreen;
        // scans the bit field, and only updates the values requested by the mask
        // (this implicitly handles the _CALLBACK_PROP_BITMASK case, since those are passive reads)
        jint actualBits = window.styleBits & ~mask | bits & mask;
        // Force properties if custom title bar is enabled, but store original value in self.styleBits.
        jint newBits = [AWTWindow overrideStyleBits:actualBits customTitleBarEnabled:customTitleBarEnabled fullscreen:fullscreen];

        BOOL resized = NO;

        // Check for a change to the full window content view option.
        // The content view must be resized first, otherwise the window will be resized to fit the existing
        // content view.
        if (IS(mask, FULL_WINDOW_CONTENT)) {
            if ((IS(newBits, FULL_WINDOW_CONTENT) != IS(window.styleBits, FULL_WINDOW_CONTENT) ||
                 customTitleBarEnabled) && !fullscreen) {
                NSRect frame = [nsWindow frame];
                NSUInteger styleMask = [AWTWindow styleMaskForStyleBits:newBits];
                NSRect screenContentRect = [NSWindow contentRectForFrameRect:frame styleMask:styleMask];
                NSRect contentFrame = NSMakeRect(screenContentRect.origin.x - frame.origin.x,
                    screenContentRect.origin.y - frame.origin.y,
                    screenContentRect.size.width,
                    screenContentRect.size.height);
                nsWindow.contentView.frame = contentFrame;
                resized = YES;
            }
            if (window.isJustCreated) {
                // Perform Move/Resize event for just created windows
                resized = YES;
                window.isJustCreated = NO;
            }
        }

        // resets the NSWindow's style mask if the mask intersects any of those bits
        if (mask & MASK(_STYLE_PROP_BITMASK)) {
            NSWindowStyleMask styleMask = [AWTWindow styleMaskForStyleBits:newBits];
            NSWindowStyleMask curMask = nsWindow.styleMask;
            // NSWindowStyleMaskFullScreen bit shouldn't be updated directly
            [nsWindow setStyleMask:(styleMask & ~NSWindowStyleMaskFullScreen | curMask & NSWindowStyleMaskFullScreen)];
        }

        // calls methods on NSWindow to change other properties, based on the mask
        if (mask & MASK(_METHOD_PROP_BITMASK)) {
            [window setPropertiesForStyleBits:newBits mask:mask];
        }

        window.styleBits = actualBits;

        if (resized) {
            [window _deliverMoveResizeEvent];
        }
    }];

JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CPlatformWindow
 * Method:    nativeSetNSWindowAppearance
 * Signature: (JLjava/lang/String;)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CPlatformWindow_nativeSetNSWindowAppearance
        (JNIEnv *env, jclass clazz, jlong windowPtr,  jstring appearanceName)
{
    JNI_COCOA_ENTER(env);

        NSWindow *nsWindow = OBJC(windowPtr);
        // create a global-ref around the appearanceName, so it can be safely passed to Main thread
        jobject appearanceNameRef= (*env)->NewGlobalRef(env, appearanceName);

        [ThreadUtilities performOnMainThreadWaiting:NO block:^(){
           // attach the dispatch thread to the JVM if necessary, and get an env
            JNIEnv*      blockEnv = [ThreadUtilities getJNIEnvUncached];
            NSAppearance* appearance = [NSAppearance appearanceNamed:
                                        JavaStringToNSString(blockEnv, appearanceNameRef)];
            if (appearance != NULL) {
                [nsWindow setAppearance:appearance];
            }
            (*blockEnv)->DeleteGlobalRef(blockEnv, appearanceNameRef);
        }];

    JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CPlatformWindow
 * Method:    nativeSetNSWindowMenuBar
 * Signature: (JJ)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CPlatformWindow_nativeSetNSWindowMenuBar
(JNIEnv *env, jclass clazz, jlong windowPtr, jlong menuBarPtr)
{
JNI_COCOA_ENTER(env);

    NSWindow *nsWindow = OBJC(windowPtr);
    CMenuBar *menuBar = OBJC(menuBarPtr);
    [ThreadUtilities performOnMainThreadWaiting:NO block:^(){

        AWTWindow *window = (AWTWindow*)[nsWindow delegate];

        if ([nsWindow isMainWindow]) {
            [window.javaMenuBar deactivate];
        }

        window.javaMenuBar = menuBar;

        CMenuBar* actualMenuBar = menuBar;
        if (actualMenuBar == nil && [ApplicationDelegate sharedDelegate] != nil) {
            actualMenuBar = [[ApplicationDelegate sharedDelegate] defaultMenuBar];
        }

        if ([nsWindow isMainWindow]) {
            [CMenuBar activate:actualMenuBar modallyDisabled:NO];
        }
    }];

JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CPlatformWindow
 * Method:    nativeGetNSWindowInsets
 * Signature: (J)Ljava/awt/Insets;
 */
JNIEXPORT jobject JNICALL Java_sun_lwawt_macosx_CPlatformWindow_nativeGetNSWindowInsets
(JNIEnv *env, jclass clazz, jlong windowPtr)
{
    jobject ret = NULL;

JNI_COCOA_ENTER(env);

    NSWindow *nsWindow = OBJC(windowPtr);
    __block NSRect contentRect = NSZeroRect;
    __block NSRect frame = NSZeroRect;

    [ThreadUtilities performOnMainThreadWaiting:YES block:^(){

        frame = [nsWindow frame];
        contentRect = [NSWindow contentRectForFrameRect:frame styleMask:[nsWindow styleMask]];
    }];

    jint top = (jint)(frame.size.height - contentRect.size.height);
    jint left = (jint)(contentRect.origin.x - frame.origin.x);
    jint bottom = (jint)(contentRect.origin.y - frame.origin.y);
    jint right = (jint)(frame.size.width - (contentRect.size.width + left));

    DECLARE_CLASS_RETURN(jc_Insets, "java/awt/Insets", NULL);
    DECLARE_METHOD_RETURN(jc_Insets_ctor, jc_Insets, "<init>", "(IIII)V", NULL);
    ret = (*env)->NewObject(env, jc_Insets, jc_Insets_ctor, top, left, bottom, right);

JNI_COCOA_EXIT(env);
    return ret;
}

/*
 * Class:     sun_lwawt_macosx_CPlatformWindow
 * Method:    nativeSetNSWindowBounds
 * Signature: (JDDDD)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CPlatformWindow_nativeSetNSWindowBounds
(JNIEnv *env, jclass clazz, jlong windowPtr, jdouble originX, jdouble originY, jdouble width, jdouble height)
{
JNI_COCOA_ENTER(env);

    NSRect jrect = NSMakeRect(originX, originY, width, height);

    // TODO: not sure we need displayIfNeeded message in our view
    NSWindow *nsWindow = OBJC(windowPtr);
    [ThreadUtilities performOnMainThreadWaiting:NO block:^(){

        AWTWindow *window = (AWTWindow*)[nsWindow delegate];

        NSRect rect = ConvertNSScreenRect(NULL, jrect);
        [window constrainSize:&rect.size];

        [nsWindow setFrame:rect display:YES];

        // only start tracking events if pointer is above the toplevel
        // TODO: should post an Entered event if YES.
        NSPoint mLocation = [NSEvent mouseLocation];
        [nsWindow setAcceptsMouseMovedEvents:NSPointInRect(mLocation, rect)];

        // ensure we repaint the whole window after the resize operation
        // (this will also re-enable screen updates, which were disabled above)
        // TODO: send PaintEvent

        // the macOS may ignore our "setFrame" request, in this, case the
        // windowDidMove() will not come and we need to manually resync the
        // "java.awt.Window" and NSWindow locations, because "java.awt.Window"
        // already uses location ignored by the macOS.
        // see sun.lwawt.LWWindowPeer#notifyReshape()
        if (!NSEqualRects(rect, [nsWindow frame])) {
            [window _deliverMoveResizeEvent];
        }
    }];

JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CPlatformWindow
 * Method:    nativeSetNSWindowStandardFrame
 * Signature: (JDDDD)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CPlatformWindow_nativeSetNSWindowStandardFrame
(JNIEnv *env, jclass clazz, jlong windowPtr, jdouble originX, jdouble originY,
     jdouble width, jdouble height)
{
    JNI_COCOA_ENTER(env);

    NSRect jrect = NSMakeRect(originX, originY, width, height);

    NSWindow *nsWindow = OBJC(windowPtr);
    [ThreadUtilities performOnMainThreadWaiting:NO block:^(){

        NSRect rect = ConvertNSScreenRect(NULL, jrect);
        AWTWindow *window = (AWTWindow*)[nsWindow delegate];
        window.standardFrame = rect;
    }];

    JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CPlatformWindow
 * Method:    nativeSetNSWindowLocationByPlatform
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CPlatformWindow_nativeSetNSWindowLocationByPlatform
(JNIEnv *env, jclass clazz, jlong windowPtr)
{
    JNI_COCOA_ENTER(env);

    NSWindow *nsWindow = OBJC(windowPtr);
    [ThreadUtilities performOnMainThreadWaiting:NO block:^(){

        if (NSEqualPoints(lastTopLeftPoint, NSZeroPoint)) {
            // This is the first usage of lastTopLeftPoint. So invoke cascadeTopLeftFromPoint
            // twice to avoid positioning the window's top left to zero-point, since it may
            // cause negative user experience.
            lastTopLeftPoint = [nsWindow cascadeTopLeftFromPoint:lastTopLeftPoint];
        }
        lastTopLeftPoint = [nsWindow cascadeTopLeftFromPoint:lastTopLeftPoint];
    }];

    JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CPlatformWindow
 * Method:    nativeSetNSWindowMinMax
 * Signature: (JDDDD)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CPlatformWindow_nativeSetNSWindowMinMax
(JNIEnv *env, jclass clazz, jlong windowPtr, jdouble minW, jdouble minH, jdouble maxW, jdouble maxH)
{
JNI_COCOA_ENTER(env);

    if (minW < 1) minW = 1;
    if (minH < 1) minH = 1;
    if (maxW < 1) maxW = 1;
    if (maxH < 1) maxH = 1;

    NSWindow *nsWindow = OBJC(windowPtr);
    [ThreadUtilities performOnMainThreadWaiting:NO block:^(){

        AWTWindow *window = (AWTWindow*)[nsWindow delegate];

        NSSize min = { minW, minH };
        NSSize max = { maxW, maxH };

        [window constrainSize:&min];
        [window constrainSize:&max];

        window.javaMinSize = min;
        window.javaMaxSize = max;
        [window updateMinMaxSize:IS(window.styleBits, RESIZABLE)];
    }];

JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CPlatformWindow
 * Method:    nativePushNSWindowToBack
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CPlatformWindow_nativePushNSWindowToBack
(JNIEnv *env, jclass clazz, jlong windowPtr)
{
JNI_COCOA_ENTER(env);

    NSWindow *nsWindow = OBJC(windowPtr);
    [ThreadUtilities performOnMainThreadWaiting:NO block:^(){
        [nsWindow orderBack:nil];
        // Order parent windows
        AWTWindow *awtWindow = (AWTWindow*)[nsWindow delegate];
        while (awtWindow.ownerWindow != nil) {
            awtWindow = awtWindow.ownerWindow;
            if ([AWTWindow isJavaPlatformWindowVisible:awtWindow.nsWindow]) {
                [awtWindow.nsWindow orderBack:nil];
            }
        }
        // Order child windows
        [(AWTWindow*)[nsWindow delegate] orderChildWindows:NO];
    }];

JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CPlatformWindow
 * Method:    nativePushNSWindowToFront
 * Signature: (JZ)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CPlatformWindow_nativePushNSWindowToFront
(JNIEnv *env, jclass clazz, jlong windowPtr, jboolean wait)
{
JNI_COCOA_ENTER(env);

    NSWindow *nsWindow = OBJC(windowPtr);
    [ThreadUtilities performOnMainThreadWaiting:(BOOL)wait block:^(){

        if (![nsWindow isKeyWindow]) {
            [nsWindow makeKeyAndOrderFront:nsWindow];
        } else {
            [nsWindow orderFront:nsWindow];
        }
    }];

JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CPlatformWindow
 * Method:    nativeHideWindow
 * Signature: (JZ)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CPlatformWindow_nativeHideWindow
(JNIEnv *env, jclass clazz, jlong windowPtr, jboolean wait)
{
JNI_COCOA_ENTER(env);

    NSWindow *nsWindow = OBJC(windowPtr);
    [ThreadUtilities performOnMainThreadWaiting:(BOOL)wait block:^(){
        if (nsWindow.keyWindow) {
            // When 'windowDidResignKey' is called during 'orderOut', current key window
            // is reported as 'nil', so it's impossible to create WINDOW_FOCUS_LOST event
            // with correct 'opposite' window.
            // So, as a workaround, we perform focus transfer to a parent window explicitly here.
            NSWindow *parentWindow = nsWindow;
            while ((parentWindow = ((AWTWindow*)parentWindow.delegate).ownerWindow.nsWindow) != nil) {
                if (parentWindow.canBecomeKeyWindow) {
                    [parentWindow makeKeyWindow];
                    break;
                }
            }
        }
        [nsWindow orderOut:nsWindow];
        [nsWindow close];
    }];

JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CPlatformWindow
 * Method:    nativeSetNSWindowTitle
 * Signature: (JLjava/lang/String;)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CPlatformWindow_nativeSetNSWindowTitle
(JNIEnv *env, jclass clazz, jlong windowPtr, jstring jtitle)
{
JNI_COCOA_ENTER(env);

    NSWindow *nsWindow = OBJC(windowPtr);
    [ThreadUtilities performOnMainThread:@selector(setTitle:) on:nsWindow
                             withObject:JavaStringToNSString(env, jtitle)
                           waitUntilDone:NO];

JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CPlatformWindow
 * Method:    nativeRevalidateNSWindowShadow
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CPlatformWindow_nativeRevalidateNSWindowShadow
(JNIEnv *env, jclass clazz, jlong windowPtr)
{
JNI_COCOA_ENTER(env);

    NSWindow *nsWindow = OBJC(windowPtr);
    [ThreadUtilities performOnMainThreadWaiting:NO block:^(){
        [nsWindow invalidateShadow];
    }];

JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CPlatformWindow
 * Method:    nativeScreenOn_AppKitThread
 * Signature: (J)I
 */
JNIEXPORT jint JNICALL Java_sun_lwawt_macosx_CPlatformWindow_nativeScreenOn_1AppKitThread
(JNIEnv *env, jclass clazz, jlong windowPtr)
{
    jint ret = 0;

JNI_COCOA_ENTER(env);
AWT_ASSERT_APPKIT_THREAD;

    NSWindow *nsWindow = OBJC(windowPtr);
    NSDictionary *props = [[nsWindow screen] deviceDescription];
    ret = [[props objectForKey:@"NSScreenNumber"] intValue];

JNI_COCOA_EXIT(env);

    return ret;
}

/*
 * Class:     sun_lwawt_macosx_CPlatformWindow
 * Method:    nativeSetNSWindowMinimizedIcon
 * Signature: (JJ)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CPlatformWindow_nativeSetNSWindowMinimizedIcon
(JNIEnv *env, jclass clazz, jlong windowPtr, jlong nsImagePtr)
{
JNI_COCOA_ENTER(env);

    NSWindow *nsWindow = OBJC(windowPtr);
    NSImage *image = OBJC(nsImagePtr);
    [ThreadUtilities performOnMainThreadWaiting:NO block:^(){
        [nsWindow setMiniwindowImage:image];
    }];

JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CPlatformWindow
 * Method:    nativeSetNSWindowRepresentedFilename
 * Signature: (JLjava/lang/String;)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CPlatformWindow_nativeSetNSWindowRepresentedFilename
(JNIEnv *env, jclass clazz, jlong windowPtr, jstring filename)
{
JNI_COCOA_ENTER(env);

    NSWindow *nsWindow = OBJC(windowPtr);
    NSURL *url = (filename == NULL) ? nil : [NSURL fileURLWithPath:NormalizedPathNSStringFromJavaString(env, filename)];
    [ThreadUtilities performOnMainThreadWaiting:NO block:^(){
        [nsWindow setRepresentedURL:url];
    }];

JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CPlatformWindow
 * Method:    nativeGetTopmostPlatformWindowUnderMouse
 * Signature: (J)V
 */
JNIEXPORT jobject
JNICALL Java_sun_lwawt_macosx_CPlatformWindow_nativeGetTopmostPlatformWindowUnderMouse
(JNIEnv *env, jclass clazz)
{
    __block jobject topmostWindowUnderMouse = nil;

    JNI_COCOA_ENTER(env);

    [ThreadUtilities performOnMainThreadWaiting:YES block:^{
        AWTWindow *awtWindow = [AWTWindow getTopmostWindowUnderMouse];
        if (awtWindow != nil) {
            topmostWindowUnderMouse = awtWindow.javaPlatformWindow;
        }
    }];

    JNI_COCOA_EXIT(env);

    return topmostWindowUnderMouse;
}

/*
 * Class:     sun_lwawt_macosx_CPlatformWindow
 * Method:    nativeSynthesizeMouseEnteredExitedEvents
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CPlatformWindow_nativeSynthesizeMouseEnteredExitedEvents__
(JNIEnv *env, jclass clazz)
{
    JNI_COCOA_ENTER(env);

    [ThreadUtilities performOnMainThreadWaiting:NO block:^(){
        [AWTWindow synthesizeMouseEnteredExitedEventsForAllWindows];
    }];

    JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CPlatformWindow
 * Method:    nativeSynthesizeMouseEnteredExitedEvents
 * Signature: (JI)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CPlatformWindow_nativeSynthesizeMouseEnteredExitedEvents__JI
(JNIEnv *env, jclass clazz, jlong windowPtr, jint eventType)
{
JNI_COCOA_ENTER(env);

    if (eventType == NSEventTypeMouseEntered || eventType == NSEventTypeMouseExited) {
        NSWindow *nsWindow = OBJC(windowPtr);

        [ThreadUtilities performOnMainThreadWaiting:NO block:^(){
            [AWTWindow synthesizeMouseEnteredExitedEvents:nsWindow withType:eventType];
        }];
    } else {
        JNU_ThrowIllegalArgumentException(env, "unknown event type");
    }

JNI_COCOA_EXIT(env);
}

// undocumented approach which avoids focus stealing
// and can be used full screen switch is in progress for another window
void enableFullScreenSpecial(NSWindow *nsWindow) {
    NSKeyedArchiver *coder = [[NSKeyedArchiver alloc] init];
    [nsWindow encodeRestorableStateWithCoder:coder];
    [coder encodeBool:YES forKey:@"NSIsFullScreen"];
    NSKeyedUnarchiver *decoder = [[NSKeyedUnarchiver alloc] initForReadingWithData:coder.encodedData];
    decoder.requiresSecureCoding = YES;
    decoder.decodingFailurePolicy = NSDecodingFailurePolicySetErrorAndReturn;
    [nsWindow restoreStateWithCoder:decoder];
    [decoder finishDecoding];
    [decoder release];
    [coder release];
}

/*
 * Class:     sun_lwawt_macosx_CPlatformWindow
 * Method:    _toggleFullScreenMode
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CPlatformWindow__1toggleFullScreenMode
(JNIEnv *env, jobject peer, jlong windowPtr)
{
JNI_COCOA_ENTER(env);

    NSWindow *nsWindow = OBJC(windowPtr);
    SEL toggleFullScreenSelector = @selector(toggleFullScreen:);
    if (![nsWindow respondsToSelector:toggleFullScreenSelector]) return;

    [ThreadUtilities performOnMainThreadWaiting:NO block:^(){
        static BOOL inProgress = NO;
        if ((nsWindow.styleMask & NSWindowStyleMaskFullScreen) != NSWindowStyleMaskFullScreen &&
            (inProgress || !NSApp.active)) {
            enableFullScreenSpecial(nsWindow);
            if ((nsWindow.styleMask & NSWindowStyleMaskFullScreen) == NSWindowStyleMaskFullScreen) return; // success
            // otherwise fall back to standard approach
        }
        BOOL savedValue = inProgress;
        inProgress = YES;
        [nsWindow performSelector:toggleFullScreenSelector withObject:nil];
        inProgress = savedValue;
    }];

JNI_COCOA_EXIT(env);
}

JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CPlatformWindow_nativeSetEnabled
(JNIEnv *env, jclass clazz, jlong windowPtr, jboolean isEnabled)
{
JNI_COCOA_ENTER(env);

    NSWindow *nsWindow = OBJC(windowPtr);
    [ThreadUtilities performOnMainThreadWaiting:NO block:^(){
        AWTWindow *window = (AWTWindow*)[nsWindow delegate];

        [window setEnabled: isEnabled];
    }];

JNI_COCOA_EXIT(env);
}

JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CPlatformWindow_nativeDispose
(JNIEnv *env, jclass clazz, jlong windowPtr)
{
JNI_COCOA_ENTER(env);

    NSWindow *nsWindow = OBJC(windowPtr);
    [ThreadUtilities performOnMainThreadWaiting:NO block:^(){
        AWTWindow *window = (AWTWindow*)[nsWindow delegate];

        if ([AWTWindow lastKeyWindow] == window) {
            [AWTWindow setLastKeyWindow: nil];
        }

        // AWTWindow holds a reference to the NSWindow in its nsWindow
        // property. Unsetting the delegate allows it to be deallocated
        // which releases the reference. This, in turn, allows the window
        // itself be deallocated.
        [nsWindow setDelegate: nil];

        [window release];

        ignoreResizeWindowDuringAnotherWindowEnd = NO;
    }];

JNI_COCOA_EXIT(env);
}

JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CPlatformWindow_nativeEnterFullScreenMode
(JNIEnv *env, jclass clazz, jlong windowPtr)
{
JNI_COCOA_ENTER(env);

    NSWindow *nsWindow = OBJC(windowPtr);
    [ThreadUtilities performOnMainThreadWaiting:NO block:^(){
        AWTWindow *window = (AWTWindow*)[nsWindow delegate];
        NSNumber* screenID = [AWTWindow getNSWindowDisplayID_AppKitThread: nsWindow];
        CGDirectDisplayID aID = [screenID intValue];

        if (CGDisplayCapture(aID) == kCGErrorSuccess) {
            // remove window decoration
            NSUInteger styleMask = [AWTWindow styleMaskForStyleBits:window.styleBits];
            [nsWindow setStyleMask:(styleMask & ~NSTitledWindowMask) | NSWindowStyleMaskBorderless];

            int shieldLevel = CGShieldingWindowLevel();
            window.preFullScreenLevel = [nsWindow level];
            [nsWindow setLevel: shieldLevel];
            [nsWindow makeKeyAndOrderFront: nil];

            NSRect screenRect = [[nsWindow screen] frame];
            [nsWindow setFrame:screenRect display:YES];
        } else {
            [NSException raise:@"Java Exception" reason:@"Failed to enter full screen." userInfo:nil];
        }
    }];

JNI_COCOA_EXIT(env);
}

JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CPlatformWindow_nativeExitFullScreenMode
(JNIEnv *env, jclass clazz, jlong windowPtr)
{
JNI_COCOA_ENTER(env);

    NSWindow *nsWindow = OBJC(windowPtr);
    [ThreadUtilities performOnMainThreadWaiting:NO block:^(){
        AWTWindow *window = (AWTWindow*)[nsWindow delegate];
        NSNumber* screenID = [AWTWindow getNSWindowDisplayID_AppKitThread: nsWindow];
        CGDirectDisplayID aID = [screenID intValue];

        if (CGDisplayRelease(aID) == kCGErrorSuccess) {
            NSUInteger styleMask = [AWTWindow styleMaskForStyleBits:window.styleBits];
            [nsWindow setStyleMask:styleMask];
            [nsWindow setLevel: window.preFullScreenLevel];

            // GraphicsDevice takes care of restoring pre full screen bounds
        } else {
            [NSException raise:@"Java Exception" reason:@"Failed to exit full screen." userInfo:nil];
        }
    }];

JNI_COCOA_EXIT(env);
}

JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CPlatformWindow_nativeRaiseLevel
(JNIEnv *env, jclass clazz, jlong windowPtr, jboolean popup, jboolean onlyIfParentIsActive)
{
JNI_COCOA_ENTER(env);

    NSWindow *nsWindow = OBJC(windowPtr);
    [ThreadUtilities performOnMainThreadWaiting:NO block:^(){
        AWTWindow *window = (AWTWindow*)[nsWindow delegate];
        if (onlyIfParentIsActive) {
            AWTWindow *parent = window;
            do {
                parent = parent.ownerWindow;
            } while (parent != nil && !parent.nsWindow.isMainWindow);
            if (parent == nil) {
                return;
            }
        }
        [nsWindow setLevel: popup ? NSPopUpMenuWindowLevel : NSFloatingWindowLevel];
    }];

JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CPlatformWindow
 * Method:    nativeDelayShowing
 * Signature: (J)Z
 */
JNIEXPORT jboolean JNICALL Java_sun_lwawt_macosx_CPlatformWindow_nativeDelayShowing
(JNIEnv *env, jclass clazz, jlong windowPtr)
{
    __block jboolean result = JNI_FALSE;

    JNI_COCOA_ENTER(env);

    NSWindow *nsWindow = (NSWindow *)jlong_to_ptr(windowPtr);
    [ThreadUtilities performOnMainThreadWaiting:YES block:^(){
        AWTWindow *window = (AWTWindow*)[nsWindow delegate];
        result = [window delayShowing];
    }];

    JNI_COCOA_EXIT(env);

    return result;
}


JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CPlatformWindow_nativeUpdateCustomTitleBar
(JNIEnv *env, jclass clazz, jlong windowPtr)
{
    JNI_COCOA_ENTER(env);

    NSWindow *nsWindow = (NSWindow *)jlong_to_ptr(windowPtr);
    [ThreadUtilities performOnMainThreadWaiting:YES block:^(){
        AWTWindow *window = (AWTWindow*)[nsWindow delegate];
        [window updateCustomTitleBar];
    }];

    JNI_COCOA_EXIT(env);
}

JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CPlatformWindow_nativeCallDeliverMoveResizeEvent
(JNIEnv *env, jclass clazz, jlong windowPtr)
{
    JNI_COCOA_ENTER(env);

    NSWindow *nsWindow = (NSWindow *)jlong_to_ptr(windowPtr);
    [ThreadUtilities performOnMainThreadWaiting:NO block:^(){
        AWTWindow *window = (AWTWindow*)[nsWindow delegate];
        [window _deliverMoveResizeEvent];
    }];

    JNI_COCOA_EXIT(env);
}

JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CPlatformWindow_nativeSetRoundedCorners
(JNIEnv *env, jclass clazz, jlong windowPtr, jfloat radius, jint borderWidth, jint borderRgb)
{
    JNI_COCOA_ENTER(env);

    NSWindow *w = (NSWindow *)jlong_to_ptr(windowPtr);
    [ThreadUtilities performOnMainThreadWaiting:NO block:^(){
        w.hasShadow = YES;
        w.contentView.wantsLayer = YES;
        w.contentView.layer.cornerRadius = radius;
        w.contentView.layer.masksToBounds = YES;
        w.contentView.layer.opaque = NO;

        if (borderWidth > 0) {
            CGFloat alpha = (((borderRgb >> 24) & 0xff) / 255.0);
            CGFloat red   = (((borderRgb >> 16) & 0xff) / 255.0);
            CGFloat green = (((borderRgb >>  8) & 0xff) / 255.0);
            CGFloat blue  = (((borderRgb >>  0) & 0xff) / 255.0);
            NSColor *color = [NSColor colorWithDeviceRed:red green:green blue:blue alpha:alpha];

            w.contentView.layer.borderWidth = borderWidth;
            w.contentView.layer.borderColor = color.CGColor;
        }

        w.backgroundColor = NSColor.clearColor;
        w.opaque = NO;
        // remove corner radius animation
        [w.contentView.layer removeAllAnimations];
        [w invalidateShadow];
    }];

    JNI_COCOA_EXIT(env);
}
