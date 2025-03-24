#include <pthread.h>

#import <Foundation/Foundation.h>
#import <Carbon/Carbon.h>
#import <Cocoa/Cocoa.h>

#import "CRobotKeyCode.h"
#import "java_awt_event_KeyEvent.h"

#include <jni.h>
#import <ThreadUtilities.h>
#import <JNIUtilities.h>
#include "jni_util.h"


extern JavaVM *jvm;

@interface SystemHotkey : NSObject
+ (void)subscribeToChanges;
@end

enum LOG_LEVEL {
    LL_TRACE,
    LL_DEBUG,
    LL_INFO,
    LL_WARNING,
    LL_ERROR
};

static void plog(int logLevel, const char *formatMsg, ...) {
    if (!jvm)
        return;
    if (logLevel < LL_TRACE || logLevel > LL_ERROR || formatMsg == NULL)
        return;

    // TODO: check whether current logLevel is enabled in PlatformLogger
    static jobject loggerObject = NULL;
    static jclass clazz = NULL;
    static jmethodID midTrace = NULL, midDebug = NULL, midInfo = NULL, midWarn = NULL, midError = NULL;

    if (loggerObject == NULL) {
        JNIEnv* env;
        (*jvm)->AttachCurrentThreadAsDaemon(jvm, (void**)&env, NULL);
        jclass shkClass = (*env)->FindClass(env, "java/awt/desktop/SystemHotkey");
        if (shkClass == NULL)
            return;
        jfieldID fieldId = (*env)->GetStaticFieldID(env, shkClass, "ourLog", "Lsun/util/logging/PlatformLogger;");
        if (fieldId == NULL)
            return;
        loggerObject = (*env)->GetStaticObjectField(env, shkClass, fieldId);
        loggerObject = (*env)->NewGlobalRef(env, loggerObject);

        clazz = (*env)->GetObjectClass(env, loggerObject);
        if (clazz == NULL) {
            NSLog(@"plogImpl: can't find PlatformLogger class");
            return;
        }

        midTrace = (*env)->GetMethodID(env, clazz, "finest", "(Ljava/lang/String;)V");
        midDebug = (*env)->GetMethodID(env, clazz, "fine", "(Ljava/lang/String;)V");
        midInfo = (*env)->GetMethodID(env, clazz, "info", "(Ljava/lang/String;)V");
        midWarn = (*env)->GetMethodID(env, clazz, "warning", "(Ljava/lang/String;)V");
        midError = (*env)->GetMethodID(env, clazz, "severe", "(Ljava/lang/String;)V");
    }

    jmethodID mid = midTrace;
    switch (logLevel) {
        case LL_DEBUG: mid = midDebug; break;
        case LL_INFO: mid = midInfo; break;
        case LL_WARNING: mid = midWarn; break;
        case LL_ERROR: mid = midError; break;
    }
    if (mid == NULL) {
        NSLog(@"plogImpl: undefined log-method for level %d", logLevel);
        return;
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

    JNIEnv* env;
    jstring jstr;
    (*jvm)->AttachCurrentThreadAsDaemon(jvm, (void**)&env, NULL);
    jstr = (*env)->NewStringUTF(env, buf);

    (*env)->CallVoidMethod(env, loggerObject, mid, jstr);
    (*env)->DeleteLocalRef(env, jstr);
    CHECK_EXCEPTION();
}

static const char * toCString(id obj) {
    return obj == nil ? "nil" : [NSString stringWithFormat:@"%@", obj].UTF8String;
}

// Copy of java.awt.event.KeyEvent.[MODIFIER]_DOWN_MASK
static const int AWT_SHIFT_DOWN_MASK = 1 << 6;
static const int AWT_CTRL_DOWN_MASK = 1 << 7;
static const int AWT_META_DOWN_MASK = 1 << 8;
static const int AWT_ALT_DOWN_MASK = 1 << 9;

static int symbolicHotKeysModifiers2java(int mask) {
    // NOTE: these masks doesn't coincide with macos events masks
    // and can be used only to parse data from settings domain "com.apple.symbolichotkeys"
    const int MACOS_SHIFT_MASK      = 0x020000;
    const int MACOS_CONTROL_MASK    = 0x040000;
    const int MACOS_OPTION_MASK     = 0x080000;
    const int MACOS_CMD_MASK        = 0x100000;

    int result = 0;
    if (mask & MACOS_SHIFT_MASK)
        result |= AWT_SHIFT_DOWN_MASK;
    if (mask & MACOS_CONTROL_MASK)
        result |= AWT_CTRL_DOWN_MASK;
    if (mask & MACOS_OPTION_MASK)
        result |= AWT_ALT_DOWN_MASK;
    if (mask & MACOS_CMD_MASK)
        result |= AWT_META_DOWN_MASK;
    return result;
}

enum ShortcutID {
    Shortcut_FocusMenuBar = 7,
    Shortcut_FocusDock = 8,
    Shortcut_FocusActiveWindow = 9,
    Shortcut_FocusToolbar = 10,
    Shortcut_FocusFloatingWindow = 11,
    Shortcut_ToggleKeyboardAccess = 12,
    Shortcut_ChangeTabMode = 13,
    Shortcut_ToggleZoom = 15,
    Shortcut_ZoomIn = 17,
    Shortcut_ZoomOut = 19,
    Shortcut_InvertColors = 21,
    Shortcut_ToggleZoomImageSmoothing = 23,
    Shortcut_IncreaseContrast = 25,
    Shortcut_DecreaseContrast = 26,
    Shortcut_FocusNextApplicationWindow = 27,
    Shortcut_ScreenshotToFile = 28,
    Shortcut_ScreenshotToClipboard = 29,
    Shortcut_ScreenshotAreaToFile = 30,
    Shortcut_ScreenshotAreaToClipboard = 31,
    Shortcut_ShowAllWindows = 32,
    Shortcut_ShowApplicationWindows = 33,
    Shortcut_ShowDesktop = 36,
    Shortcut_ToggleDockHiding = 52,
    Shortcut_DecreaseBrightness = 53,
    Shortcut_IncreaseBrightness = 54,
    Shortcut_FocusStatusMenu = 57,
    Shortcut_ToggleVoiceOver = 59,
    Shortcut_SelectPreviousInputSource = 60,
    Shortcut_SelectNextInputSource = 61,
    Shortcut_ShowSpotlight = 64,
    Shortcut_ShowFinderSearch = 65,
    Shortcut_SwitchToDesktopLeft = 79,
    Shortcut_SwitchToDesktopRight = 81,
    Shortcut_SwitchToDesktop1 = 118,
    Shortcut_SwitchToDesktop2 = 119,
    Shortcut_SwitchToDesktop3 = 120,
    Shortcut_SwitchToDesktop4 = 121,
    Shortcut_ShowContextualMenu = 159,
    Shortcut_ShowLaunchpad = 160,
    Shortcut_ShowAccessibilityControls = 162,
    Shortcut_ShowNotificationCenter = 163,
    Shortcut_ToggleDoNotDisturb = 175,
    Shortcut_ToggleZoomFocusFollowing = 179,
    Shortcut_ScreenshotOptions = 184,
    Shortcut_OpenQuickNote = 190,
    Shortcut_ToggleStageManager = 222,
    Shortcut_TogglePresenterOverlayLarge = 223,
    Shortcut_TogglePresenterOverlaySmall = 224,
    Shortcut_ToggleLiveSpeech = 225,
    Shortcut_ToggleLiveSpeechVisibility = 226,
    Shortcut_PauseOrResumeLiveSpeech = 227,
    Shortcut_CancelLiveSpeech = 228,
    Shortcut_ToggleLiveSpeechPhrases = 229,
    Shortcut_ToggleSpeakSelection = 230,
    Shortcut_ToggleSpeakItemUnderPointer = 231,
    Shortcut_ToggleTypingFeedback = 232,
};

struct SymbolicHotKey {
    // Unique human-readable identifier for the shortcut
    const char* id;

    // English-language description of the shortcut
    const char* description;

    // Whether this shortcut is enabled by default.
    // Note that a shortcut can be enabled but not have any keys assigned
    bool enabled;

    // Character for shortcuts that are triggered based on the character value of the key,
    // instead of its physical position on a keyboard. It's set to 65535 if it's unassigned.
    int character;

    // Virtual key code for the shortcut.
    // This key code identifies a key on a keyboard independent of the logical layout used.
    // It's set to 65535 if it's unassigned.
    int key;

    // Modifier mask using the NSEventModifierFlag* values for this shortcut
    int modifiers;

    // The first major version of macOS that has this shortcut or -1 if unknown.
    int macOSVersion;
};

static const struct SymbolicHotKey defaultSymbolicHotKeys[] = {
    [Shortcut_FocusMenuBar] = { "FocusMenuBar", "Move focus to the menu bar", YES, 65535, 120, 0x00840000, -1 },
    [Shortcut_FocusDock] = { "FocusDock", "Move focus to the Dock", YES, 65535, 99, 0x00840000, -1 },
    [Shortcut_FocusActiveWindow] = { "FocusActiveWindow", "Move focus to active or next window", YES, 65535, 118, 0x00840000, -1 },
    [Shortcut_FocusToolbar] = { "FocusToolbar", "Move focus to window toolbar", YES, 65535, 96, 0x00840000, -1 },
    [Shortcut_FocusFloatingWindow] = { "FocusFloatingWindow", "Move focus to floating window", YES, 65535, 97, 0x00840000, -1 },
    [Shortcut_ToggleKeyboardAccess] = { "ToggleKeyboardAccess", "Turn keyboard access on or off", YES, 65535, 122, 0x00840000, -1 },
    [Shortcut_ChangeTabMode] = { "ChangeTabMode", "Change the way Tab moves focus", YES, 65535, 98, 0x00840000, -1 },
    [Shortcut_ToggleZoom] = { "ToggleZoom", "Zoom: Turn zoom on or off", NO, 56, 28, 0x00180000, -1 },
    [Shortcut_ZoomIn] = { "ZoomIn", "Zoom: Zoom in", NO, 61, 24, 0x00180000, -1 },
    [Shortcut_ZoomOut] = { "ZoomOut", "Zoom: Zoom out", NO, 45, 27, 0x00180000, -1 },
    [Shortcut_InvertColors] = { "InvertColors", "Invert colors", YES, 56, 28, 0x001c0000, -1 },
    [Shortcut_ToggleZoomImageSmoothing] = { "ToggleZoomImageSmoothing", "Zoom: Turn image smoothing on or off", NO, 92, 42, 0x00180000, -1 },
    [Shortcut_IncreaseContrast] = { "IncreaseContrast", "Increase contrast", NO, 46, 47, 0x001c0000, -1 },
    [Shortcut_DecreaseContrast] = { "DecreaseContrast", "Decrease contrast", NO, 44, 43, 0x001c0000, -1 },
    [Shortcut_FocusNextApplicationWindow] = { "FocusNextApplicationWindow", "Move focus to the next window in application", YES, 96, 50, 0x00100000, -1 },
    [Shortcut_ScreenshotToFile] = { "ScreenshotToFile", "Save picture of screen as a file", YES, 51, 20, 0x00120000, -1 },
    [Shortcut_ScreenshotToClipboard] = { "ScreenshotToClipboard", "Copy picture of screen to the clipboard", YES, 51, 20, 0x00160000, -1 },
    [Shortcut_ScreenshotAreaToFile] = { "ScreenshotAreaToFile", "Save picture of selected area as a file", YES, 52, 21, 0x00120000, -1 },
    [Shortcut_ScreenshotAreaToClipboard] = { "ScreenshotAreaToClipboard", "Copy picture of selected area to the clipboard", YES, 52, 21, 0x00160000, -1 },
    [Shortcut_ShowAllWindows] = { "ShowAllWindows", "Mission Control", YES, 65535, 126, 0x00840000, -1 },
    [Shortcut_ShowApplicationWindows] = { "ShowApplicationWindows", "Application windows", YES, 65535, 125, 0x00840000, -1 },
    [Shortcut_ShowDesktop] = { "ShowDesktop", "Show desktop", YES, 65535, 103, 0x00800000, -1 },
    [Shortcut_ToggleDockHiding] = { "ToggleDockHiding", "Turn Dock hiding on/off", YES, 100, 2, 0x00180000, -1 },
    [Shortcut_DecreaseBrightness] = { "DecreaseBrightness", "Decrease display brightness", YES, 65535, 107, 0x00800000, -1 },
    [Shortcut_IncreaseBrightness] = { "IncreaseBrightness", "Increase display brightness", YES, 65535, 113, 0x00800000, -1 },
    [Shortcut_FocusStatusMenu] = { "FocusStatusMenu", "Move focus to the status menus", YES, 65535, 100, 0x00840000, -1 },
    [Shortcut_ToggleVoiceOver] = { "ToggleVoiceOver", "Turn VoiceOver on or off", YES, 65535, 96, 0x00900000, -1 },
    [Shortcut_SelectPreviousInputSource] = { "SelectPreviousInputSource", "Select the previous input source", YES, 32, 49, 0x00040000, -1 },
    [Shortcut_SelectNextInputSource] = { "SelectNextInputSource", "Select next source in Input menu", YES, 32, 49, 0x000c0000, -1 },
    [Shortcut_ShowSpotlight] = { "ShowSpotlight", "Show Spotlight Search", YES, 32, 49, 0x00100000, -1 },
    [Shortcut_ShowFinderSearch] = { "ShowFinderSearch", "Show Finder search window", YES, 32, 49, 0x00180000, -1 },
    [Shortcut_SwitchToDesktopLeft] = { "SwitchToDesktopLeft", "Move left a space", NO, 65535, 123, 0x00840000, -1 },
    [Shortcut_SwitchToDesktopRight] = { "SwitchToDesktopRight", "Move right a space", NO, 65535, 124, 0x00840000, -1 },
    [Shortcut_SwitchToDesktop1] = { "SwitchToDesktop1", "Switch to Desktop 1", NO, 65535, 18, 0x00040000, -1 },
    [Shortcut_SwitchToDesktop2] = { "SwitchToDesktop2", "Switch to Desktop 2", NO, 65535, 19, 0x00040000, -1 },
    [Shortcut_SwitchToDesktop3] = { "SwitchToDesktop3", "Switch to Desktop 3", NO, 65535, 20, 0x00040000, -1 },
    [Shortcut_SwitchToDesktop4] = { "SwitchToDesktop4", "Switch to Desktop 4", NO, 65535, 21, 0x00040000, -1 },
    [Shortcut_ShowContextualMenu] = { "ShowContextualMenu", "Show contextual menu", YES, 65535, 36, 0x00040000, 15 },
    [Shortcut_ShowLaunchpad] = { "ShowLaunchpad", "Show Launchpad", NO, 65535, 65535, 0, -1 },
    [Shortcut_ShowAccessibilityControls] = { "ShowAccessibilityControls", "Show Accessibility controls", YES, 65535, 96, 0x00980000, -1 },
    [Shortcut_ShowNotificationCenter] = { "ShowNotificationCenter", "Show Notification Center", NO, 65535, 65535, 0, -1 },
    [Shortcut_ToggleDoNotDisturb] = { "ToggleDoNotDisturb", "Turn Do Not Disturb on/off", YES, 65535, 65535, 0, -1 },
    [Shortcut_ToggleZoomFocusFollowing] = { "ToggleZoomFocusFollowing", "Zoom: Turn focus following on or off", NO, 65535, 65535, 0, -1 },
    [Shortcut_ScreenshotOptions] = { "ScreenshotOptions", "Screenshot and recording options", YES, 53, 23, 0x00120000, -1 },
    [Shortcut_OpenQuickNote] = { "OpenQuickNote", "Quick note", YES, 113, 12, 0x00800000, -1 },
    [Shortcut_ToggleStageManager] = { "ToggleStageManager", "Turn Stage Manager on/off", NO, 65535, 65535, 0, -1 },
    [Shortcut_TogglePresenterOverlayLarge] = { "TogglePresenterOverlayLarge", "Turn Presenter Overlay (large) on or off", YES, 65535, 65535, 0, -1 },
    [Shortcut_TogglePresenterOverlaySmall] = { "TogglePresenterOverlaySmall", "Turn Presenter Overlay (small) on or off", YES, 65535, 65535, 0, -1 },
    [Shortcut_ToggleLiveSpeech] = { "ToggleLiveSpeech", "LiveSpeech: Turn Live Speech on or off", YES, 65535, 65535, 0, 14 },
    [Shortcut_ToggleLiveSpeechVisibility] = { "ToggleLiveSpeechVisibility", "LiveSpeech: Toggle visibility", YES, 65535, 65535, 0, 14 },
    [Shortcut_PauseOrResumeLiveSpeech] = { "PauseOrResumeLiveSpeech", "LiveSpeech: Pause or resume speech", YES, 65535, 65535, 0, 14 },
    [Shortcut_CancelLiveSpeech] = { "CancelLiveSpeech", "LiveSpeech: Cancel speech", YES, 65535, 65535, 0, 14 },
    [Shortcut_ToggleLiveSpeechPhrases] = { "ToggleLiveSpeechPhrases", "LiveSpeech: Hide or show phrases", YES, 65535, 65535, 0, 14 },
    [Shortcut_ToggleSpeakSelection] = { "ToggleSpeakSelection", "Turn speak selection on or off", YES, 65535, 65535, 0, 14 },
    [Shortcut_ToggleSpeakItemUnderPointer] = { "ToggleSpeakItemUnderPointer", "Turn speak item under the pointer on or off", YES, 65535, 65535, 0, 14 },
    [Shortcut_ToggleTypingFeedback] = { "ToggleTypingFeedback", "Turn typing feedback on or off", YES, 65535, 65535, 0, 14 },
};

static const int numSymbolicHotkeys = sizeof(defaultSymbolicHotKeys) / sizeof(defaultSymbolicHotKeys[0]);

static struct SystemHotkeyState {
    bool symbolicHotkeysFilled;
    struct SymbolicHotKey currentSymbolicHotkeys[numSymbolicHotkeys];

    bool initialized;
    bool enabled;

    pthread_mutex_t mutex;
} state = {
        .symbolicHotkeysFilled = false,
        .initialized = false,
        .enabled = false,
        .mutex = PTHREAD_RECURSIVE_MUTEX_INITIALIZER,
};

@interface DefaultParams: NSObject
@property (assign) BOOL enabled;
@property (strong) NSString *key_equivalent;
+(DefaultParams *) create:(NSString *)key_equivalent enabled:(BOOL)enabled;
@end

@implementation DefaultParams
+(DefaultParams *) create:(NSString *)key_equivalent enabled:(BOOL)enabled {
    DefaultParams * result = [[[DefaultParams alloc] init] autorelease];
    result.enabled = enabled;
    result.key_equivalent = key_equivalent;
    return result;
}
@end

static NSMutableDictionary * createDefaultParams();

static int NSModifiers2java(int mask) {
    int result = 0;
    if (mask & shiftKey)
        result |= AWT_SHIFT_DOWN_MASK;
    if (mask & controlKey)
        result |= AWT_CTRL_DOWN_MASK;
    if (mask & optionKey)
        result |= AWT_ALT_DOWN_MASK;
    if (mask & cmdKey)
        result |= AWT_META_DOWN_MASK;
    return result;
}

static int javaModifiers2NS(int jmask) {
    int result = 0;
    if (jmask & AWT_SHIFT_DOWN_MASK)
        result |= NSShiftKeyMask;
    if (jmask & AWT_CTRL_DOWN_MASK)
        result |= NSControlKeyMask;
    if (jmask & AWT_ALT_DOWN_MASK)
        result |= NSAlternateKeyMask;
    if (jmask & AWT_META_DOWN_MASK)
        result |= NSCommandKeyMask;
    return result;
}

typedef void (^ Visitor)(int, const char *, int, const char *, int, const char*);

static void visitServicesShortcut(Visitor visitorBlock, NSString * key_equivalent, NSString * desc) {
    // @ - command
    // $ - shift
    // ^ - control
    // ~ - alt(option)
    const bool modIsSHIFT = [key_equivalent containsString:@"$"];
    const bool modIsCONTROL = [key_equivalent containsString:@"^"];
    const bool modIsOPTION = [key_equivalent containsString:@"~"];
    const bool modIsCMD = [key_equivalent containsString:@"@"];
    int modifiers = 0;
    if (modIsSHIFT)
        modifiers |= AWT_SHIFT_DOWN_MASK;
    if (modIsCONTROL)
        modifiers |= AWT_CTRL_DOWN_MASK;
    if (modIsOPTION)
        modifiers |= AWT_ALT_DOWN_MASK;
    if (modIsCMD)
        modifiers |= AWT_META_DOWN_MASK;

    NSCharacterSet * excludeSet = [NSCharacterSet characterSetWithCharactersInString:@"@$^~"];
    NSString * keyChar = [key_equivalent stringByTrimmingCharactersInSet:excludeSet];

    visitorBlock(-1, keyChar.UTF8String, modifiers, desc.UTF8String, -1, NULL);
}

static void readAppleSymbolicHotkeys(struct SymbolicHotKey hotkeys[numSymbolicHotkeys]) {
    // Called from the main thread

    @try {
        NSDictionary<NSString *, id> *shk = [[NSUserDefaults standardUserDefaults] persistentDomainForName:@"com.apple.symbolichotkeys"];

        //        AppleSymbolicHotKeys =     {
        //                10 =         {
        //                        enabled = 1;
        //                        value =             {
        //                            parameters =                 (
        //                                    65535,
        //                                            96,
        //                                            8650752
        //                            );
        //                            type = standard;
        //                        };
        //                };
        //          ......
        //         }
        id hkObj = shk ? [shk valueForKey:@"AppleSymbolicHotKeys"] : nil;
        if (hkObj && ![hkObj isKindOfClass:[NSDictionary class]]) {
            plog(LL_DEBUG, "object for key 'AppleSymbolicHotKeys' isn't NSDictionary (class=%s)",
                 [[hkObj className] UTF8String]);
            return;
        }

        memcpy(hotkeys, defaultSymbolicHotKeys, numSymbolicHotkeys * sizeof(struct SymbolicHotKey));

        for (id keyObj in hkObj) {
            if (![keyObj isKindOfClass:[NSString class]]) {
                plog(LL_DEBUG, "key '%s' isn't instance of NSString (class=%s)", toCString(keyObj),
                     [[keyObj className] UTF8String]);
                continue;
            }
            NSString *hkNumber = keyObj;

            int uid = [hkNumber intValue];

            id hkDesc = hkObj[hkNumber];
            if (![hkDesc isKindOfClass:[NSDictionary class]]) {
                plog(LL_DEBUG, "hotkey descriptor '%s' isn't instance of NSDictionary (class=%s)", toCString(hkDesc),
                     [[hkDesc className] UTF8String]);
                continue;
            }
            NSDictionary<id, id> *sdict = hkDesc;

            id objEnabled = sdict[@"enabled"];
            BOOL enabled = objEnabled != nil && [objEnabled boolValue] == YES;
            hotkeys[uid].enabled = enabled;

            if (!enabled)
                continue;

            id objValue = sdict[@"value"];
            if (objValue == nil)
                continue;

            if (![objValue isKindOfClass:[NSDictionary class]]) {
                plog(LL_DEBUG, "property 'value' %s isn't instance of NSDictionary (class=%s)", toCString(objValue),
                     [[objValue className] UTF8String]);
                continue;
            }

            NSDictionary *value = objValue;
            id objParams = value[@"parameters"];
            if (![objParams isKindOfClass:[NSArray class]]) {
                plog(LL_DEBUG, "property 'parameters' %s isn't instance of NSArray (class=%s)", toCString(objParams),
                     [[objParams className] UTF8String]);
                continue;
            }

            NSArray *parameters = objParams;
            if ([parameters count] < 3) {
                plog(LL_DEBUG, "too small length of parameters %d", [parameters count]);
                continue;
            }

            id p0 = parameters[0];
            id p1 = parameters[1];
            id p2 = parameters[2];

            if (![p0 isKindOfClass:[NSNumber class]] || ![p1 isKindOfClass:[NSNumber class]] ||
                ![p2 isKindOfClass:[NSNumber class]]) {
                plog(LL_DEBUG, "some of parameters isn't instance of NSNumber (%s, %s, %s)",
                     [[p0 className] UTF8String],
                     [[p1 className] UTF8String], [[p2 className] UTF8String]);
                continue;
            }

            //parameter 1: ASCII code of the character (or 65535 - hex 0xFFFF - for non-ASCII characters).
            //parameter 2: the keyboard key code for the character.
            //Parameter 3: the sum of the control, command, shift and option keys. these are bits 17-20 in binary: shift is bit 17, control is bit 18, option is bit 19, and command is bit 20.
            //      0x020000 => "Shift",
            //      0x040000 => "Control",
            //      0x080000 => "Option",
            //      0x100000 => "Command"

            hotkeys[uid].character = p0 == nil ? 0xFFFF : [p0 intValue];
            hotkeys[uid].key = p1 == nil ? 0xFFFF : [p1 intValue];
            hotkeys[uid].modifiers = p2 == nil ? 0 : [p2 intValue];
        }
    }
    @catch (NSException *exception) {
        NSLog(@"readCachedAppleSymbolicHotkeys: catched exception, reason '%@'", exception.reason);
    }
}

static void updateAppleSymbolicHotkeysCache() {
    struct SymbolicHotKey hotkeys[numSymbolicHotkeys];
    readAppleSymbolicHotkeys(hotkeys);

    pthread_mutex_lock(&state.mutex);
    memcpy(state.currentSymbolicHotkeys, hotkeys, sizeof(hotkeys));
    state.symbolicHotkeysFilled = true;
    pthread_mutex_unlock(&state.mutex);
}

static void iterateAppleSymbolicHotkeys(struct SymbolicHotKey hotkeys[numSymbolicHotkeys], Visitor visitorBlock) {
    const NSOperatingSystemVersion macOSVersion = [[NSProcessInfo processInfo] operatingSystemVersion];

    for (int uid = 0; uid < numSymbolicHotkeys; ++uid) {
        struct SymbolicHotKey* hotkey = &hotkeys[uid];
        if (!hotkey->enabled) continue;
        if (hotkey->macOSVersion > macOSVersion.majorVersion) continue;

        char keyCharBuf[64];
        const char *keyCharStr = keyCharBuf;
        if (hotkey->character >= 0 && hotkey->character <= 0xFF) {
            sprintf(keyCharBuf, "%c", hotkey->character);
        } else {
            keyCharStr = NULL;
        }
        int modifiers = symbolicHotKeysModifiers2java(hotkey->modifiers);
        visitorBlock(hotkey->key, keyCharStr, modifiers, hotkey->description, uid, hotkey->id);

        if (uid == Shortcut_FocusNextApplicationWindow) {
            // Derive the "Move focus to the previous window in application" shortcut
            if (!(modifiers & AWT_SHIFT_DOWN_MASK)) {
                visitorBlock(hotkey->key, keyCharStr, modifiers | AWT_SHIFT_DOWN_MASK,
                             "Move focus to the previous window in application", -1, "FocusPreviousApplicationWindow");
            }
        }
    }
}

static void readPbsHotkeys(Visitor visitorBlock) {
    @try {
        NSMutableDictionary *allDefParams = createDefaultParams();
        NSDictionary<NSString *, id> *pbs = [[NSUserDefaults standardUserDefaults] persistentDomainForName:@"pbs"];
        if (pbs) {
//        NSServicesStatus =     {
//                "com.apple.Terminal - Open man Page in Terminal - openManPage" =         {
//                        "key_equivalent" = "@$m";
//                };
//                "com.apple.Terminal - Search man Page Index in Terminal - searchManPages" =         {
//                    "enabled_context_menu" = 0;
//                    "enabled_services_menu" = 0;
//                    "key_equivalent" = "@$a";
//                    "presentation_modes" =             {
//                            ContextMenu = 0;
//                            ServicesMenu = 0;
//                    };
//                };
//        };
//    }

            NSDictionary<NSString *, id> *services = [pbs valueForKey:@"NSServicesStatus"];
            if (services) {
                for (NSString *key in services) {
                    id value = services[key];
                    if (![value isKindOfClass:[NSDictionary class]]) {
                        plog(LL_DEBUG, "'%s' isn't instance of NSDictionary (class=%s)", toCString(value),
                             [[value className] UTF8String]);
                        continue;
                    }
                    // NOTE: unchanged default params will not appear here, check allDefParams at the end of this loop
                    DefaultParams *defParams = [allDefParams objectForKey:key];
                    [allDefParams removeObjectForKey:key];

                    NSDictionary<NSString *, id> *sdict = value;
                    NSString *key_equivalent = sdict[@"key_equivalent"];
                    if (!key_equivalent && defParams != nil) {
                        key_equivalent = defParams.key_equivalent;
                    }
                    if (!key_equivalent)
                        continue;

                    NSString *enabled = sdict[@"enabled_services_menu"];
                    if (enabled != nil && [enabled boolValue] == NO) {
                        continue;
                    }
                    if (enabled == nil && defParams != nil && !defParams.enabled) {
                        continue;
                    }

                    visitServicesShortcut(visitorBlock, key_equivalent, key);
                }
            }
        }

        // Iterate through rest of allDefParams
        for (NSString *key in allDefParams) {
            DefaultParams *defParams = allDefParams[key];
            if (!defParams.enabled) {
                continue;
            }
            visitServicesShortcut(visitorBlock, defParams.key_equivalent, key);
        }
    }
    @catch (NSException *exception) {
        NSLog(@"readPbsHotkeys: catched exception, reason '%@'", exception.reason);
    }
}

static bool ensureInitializedAndEnabled() {
    pthread_mutex_lock(&state.mutex);
    if (state.initialized) {
        bool enabled = state.enabled;
        pthread_mutex_unlock(&state.mutex);
        return enabled;
    }

    // JBR-8422
    const NSOperatingSystemVersion macOSVersion = [[NSProcessInfo processInfo] operatingSystemVersion];
    if (macOSVersion.majorVersion > 15 || (macOSVersion.majorVersion == 15 && macOSVersion.minorVersion >= 4)) {
        state.initialized = true;
        state.enabled = false;
        pthread_mutex_unlock(&state.mutex);
        return false;
    }

    state.initialized = true;
    state.enabled = true;
    [SystemHotkey subscribeToChanges];
    pthread_mutex_unlock(&state.mutex);
    updateAppleSymbolicHotkeysCache();

    return true;
}

static void readAppleSymbolicHotkeysCached(struct SymbolicHotKey hotkeys[numSymbolicHotkeys]) {
    memset(hotkeys, 0, sizeof(struct SymbolicHotKey) * numSymbolicHotkeys);

    pthread_mutex_lock(&state.mutex);
    if (state.symbolicHotkeysFilled) {
        memcpy(hotkeys, state.currentSymbolicHotkeys, sizeof(struct SymbolicHotKey) * numSymbolicHotkeys);
    }
    pthread_mutex_unlock(&state.mutex);
}

static void readSystemHotkeysImpl(Visitor visitorBlock) {
    if (!ensureInitializedAndEnabled()) {
        return;
    }

    struct SymbolicHotKey hotkeys[numSymbolicHotkeys];
    readAppleSymbolicHotkeysCached(hotkeys);

    iterateAppleSymbolicHotkeys(hotkeys, visitorBlock);
    readPbsHotkeys(visitorBlock);
}

@implementation SystemHotkey
+ (void)subscribeToChanges {
    // Subscribe to changes
    NSUserDefaults *symbolicHotKeys = [[NSUserDefaults alloc] initWithSuiteName:@"com.apple.symbolichotkeys"];
    [symbolicHotKeys addObserver:self forKeyPath:@"AppleSymbolicHotKeys" options:NSKeyValueObservingOptionNew
                         context:nil];

    NSUserDefaults *pbsHotKeys = [[NSUserDefaults alloc] initWithSuiteName:@"pbs"];
    [pbsHotKeys addObserver:self forKeyPath:@"NSServicesStatus" options:NSKeyValueObservingOptionNew context:nil];
}

+ (void)observeValueForKeyPath:(NSString *)keyPath ofObject:(id)object change:(NSDictionary<NSKeyValueChangeKey, id> *)change context:(void *)context {
    // Called after AppleSymbolicHotKeys or pbs hotkeys change.

    if ([keyPath isEqualToString:@"AppleSymbolicHotKeys"]) {
        updateAppleSymbolicHotkeysCache();
    }

    // This method should only be called from the main thread, but let's check anyway
    if (pthread_main_np() == 0) {
        return;
    }

    // Since this notification is sent *after* the configuration was updated,
    // the user can safely re-read the hotkeys info after receiving this callback.
    // On the Java side, this simply enqueues the change handler to run on the EDT later.

    JNIEnv* env = [ThreadUtilities getJNIEnv];
    DECLARE_CLASS(jc_SystemHotkey, "java/awt/desktop/SystemHotkey");
    DECLARE_STATIC_METHOD(jsm_onChange, jc_SystemHotkey, "onChange", "()V");
    (*env)->CallStaticVoidMethod(env, jc_SystemHotkey, jsm_onChange);
    CHECK_EXCEPTION();
}
@end

bool isSystemShortcut_NextWindowInApplication(NSUInteger modifiersMask, int keyCode, NSString *chars) {
    struct SymbolicHotKey shortcut = defaultSymbolicHotKeys[Shortcut_FocusNextApplicationWindow];
    if (ensureInitializedAndEnabled()) {
        pthread_mutex_lock(&state.mutex);
        if (state.symbolicHotkeysFilled) {
            shortcut = state.currentSymbolicHotkeys[Shortcut_FocusNextApplicationWindow];
        }
        pthread_mutex_unlock(&state.mutex);
    }

    int ignoredModifiers = NSAlphaShiftKeyMask | NSFunctionKeyMask | NSNumericPadKeyMask | NSHelpKeyMask;
    // Ignore Shift because of JBR-4899.
    if (!(shortcut.modifiers & NSShiftKeyMask)) {
        ignoredModifiers |= NSShiftKeyMask;
    }

    if ((modifiersMask & ~ignoredModifiers) != shortcut.modifiers) {
        return false;
    }

    if (shortcut.key == keyCode) {
        return true;
    }

    if (shortcut.character > 0 && shortcut.character < 0xFFFF) {
        unichar ch = shortcut.character;
        return [chars isEqualToString:[NSString stringWithCharacters:&ch length:1]];
    }

    return false;
}

JNIEXPORT void JNICALL Java_java_awt_desktop_SystemHotkeyReader_readSystemHotkeys(JNIEnv* env, jobject reader) {
    jclass clsReader = (*env)->GetObjectClass(env, reader);
    jmethodID methodAdd = (*env)->GetMethodID(env, clsReader, "add", "(ILjava/lang/String;ILjava/lang/String;Ljava/lang/String;)V");

    readSystemHotkeysImpl(
        ^(int vkeyCode, const char * keyCharStr, int jmodifiers, const char * descriptionStr, int hotkeyUid, const char * idStr) {
            jstring jkeyChar = keyCharStr == NULL ? NULL : (*env)->NewStringUTF(env, keyCharStr);
            jstring jdesc = descriptionStr == NULL ? NULL : (*env)->NewStringUTF(env, descriptionStr);
            jstring jid = idStr == NULL ? NULL : (*env)->NewStringUTF(env, idStr);
            (*env)->CallVoidMethod(
                    env, reader, methodAdd, (jint)vkeyCode, jkeyChar, (jint)jmodifiers, jdesc, jid
            );
            CHECK_EXCEPTION();
        }
    );
}

JNIEXPORT jint JNICALL Java_java_awt_desktop_SystemHotkeyReader_osx2java(JNIEnv* env, jclass clazz, jint osxKeyCode) {
    static NSDictionary * osx2javaMap = nil;
    if (osx2javaMap == nil) {
        osx2javaMap = [NSDictionary dictionaryWithObjectsAndKeys:
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_BACK_SPACE], [NSNumber numberWithInt:OSX_Delete],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_TAB], [NSNumber numberWithInt:OSX_kVK_Tab],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_ENTER], [NSNumber numberWithInt:OSX_kVK_Return],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_CLEAR], [NSNumber numberWithInt:OSX_kVK_ANSI_KeypadClear],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_SHIFT], [NSNumber numberWithInt:OSX_Shift],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_CONTROL], [NSNumber numberWithInt:OSX_Control],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_ALT], [NSNumber numberWithInt:OSX_Option],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_ALT_GRAPH], [NSNumber numberWithInt:OSX_RightOption],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_CAPS_LOCK], [NSNumber numberWithInt:OSX_CapsLock],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_ESCAPE], [NSNumber numberWithInt:OSX_Escape],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_SPACE], [NSNumber numberWithInt:OSX_kVK_Space],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_PAGE_UP], [NSNumber numberWithInt:OSX_PageUp],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_PAGE_DOWN], [NSNumber numberWithInt:OSX_PageDown],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_END], [NSNumber numberWithInt:OSX_End],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_HOME], [NSNumber numberWithInt:OSX_Home],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_LEFT], [NSNumber numberWithInt:OSX_LeftArrow],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_UP], [NSNumber numberWithInt:OSX_UpArrow],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_RIGHT], [NSNumber numberWithInt:OSX_RightArrow],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_DOWN], [NSNumber numberWithInt:OSX_DownArrow],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_COMMA], [NSNumber numberWithInt:OSX_kVK_ANSI_Comma],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_MINUS], [NSNumber numberWithInt:OSX_kVK_ANSI_Minus],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_PERIOD], [NSNumber numberWithInt:OSX_kVK_ANSI_Period],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_SLASH], [NSNumber numberWithInt:OSX_kVK_ANSI_Slash],

                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_0], [NSNumber numberWithInt:OSX_kVK_ANSI_0],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_1], [NSNumber numberWithInt:OSX_kVK_ANSI_1],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_2], [NSNumber numberWithInt:OSX_kVK_ANSI_2],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_3], [NSNumber numberWithInt:OSX_kVK_ANSI_3],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_4], [NSNumber numberWithInt:OSX_kVK_ANSI_4],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_5], [NSNumber numberWithInt:OSX_kVK_ANSI_5],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_6], [NSNumber numberWithInt:OSX_kVK_ANSI_6],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_7], [NSNumber numberWithInt:OSX_kVK_ANSI_7],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_8], [NSNumber numberWithInt:OSX_kVK_ANSI_8],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_9], [NSNumber numberWithInt:OSX_kVK_ANSI_9],

                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_SEMICOLON], [NSNumber numberWithInt:OSX_kVK_ANSI_Semicolon],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_EQUALS], [NSNumber numberWithInt:OSX_kVK_ANSI_Equal],

                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_A], [NSNumber numberWithInt:OSX_kVK_ANSI_A],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_B], [NSNumber numberWithInt:OSX_kVK_ANSI_B],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_C], [NSNumber numberWithInt:OSX_kVK_ANSI_C],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_D], [NSNumber numberWithInt:OSX_kVK_ANSI_D],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_E], [NSNumber numberWithInt:OSX_kVK_ANSI_E],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_F], [NSNumber numberWithInt:OSX_kVK_ANSI_F],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_G], [NSNumber numberWithInt:OSX_kVK_ANSI_G],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_H], [NSNumber numberWithInt:OSX_kVK_ANSI_H],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_I], [NSNumber numberWithInt:OSX_kVK_ANSI_I],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_J], [NSNumber numberWithInt:OSX_kVK_ANSI_J],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_K], [NSNumber numberWithInt:OSX_kVK_ANSI_K],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_L], [NSNumber numberWithInt:OSX_kVK_ANSI_L],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_M], [NSNumber numberWithInt:OSX_kVK_ANSI_M],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_N], [NSNumber numberWithInt:OSX_kVK_ANSI_N],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_O], [NSNumber numberWithInt:OSX_kVK_ANSI_O],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_P], [NSNumber numberWithInt:OSX_kVK_ANSI_P],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_Q], [NSNumber numberWithInt:OSX_kVK_ANSI_Q],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_R], [NSNumber numberWithInt:OSX_kVK_ANSI_R],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_S], [NSNumber numberWithInt:OSX_kVK_ANSI_S],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_T], [NSNumber numberWithInt:OSX_kVK_ANSI_T],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_U], [NSNumber numberWithInt:OSX_kVK_ANSI_U],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_V], [NSNumber numberWithInt:OSX_kVK_ANSI_V],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_W], [NSNumber numberWithInt:OSX_kVK_ANSI_W],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_X], [NSNumber numberWithInt:OSX_kVK_ANSI_X],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_Y], [NSNumber numberWithInt:OSX_kVK_ANSI_Y],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_Z], [NSNumber numberWithInt:OSX_kVK_ANSI_Z],

                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_OPEN_BRACKET], [NSNumber numberWithInt:OSX_kVK_ANSI_LeftBracket],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_BACK_SLASH], [NSNumber numberWithInt:OSX_kVK_ANSI_Backslash],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_CLOSE_BRACKET], [NSNumber numberWithInt:OSX_kVK_ANSI_RightBracket],

                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_NUMPAD0], [NSNumber numberWithInt:OSX_kVK_ANSI_Keypad0],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_NUMPAD1], [NSNumber numberWithInt:OSX_kVK_ANSI_Keypad1],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_NUMPAD2], [NSNumber numberWithInt:OSX_kVK_ANSI_Keypad2],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_NUMPAD3], [NSNumber numberWithInt:OSX_kVK_ANSI_Keypad3],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_NUMPAD4], [NSNumber numberWithInt:OSX_kVK_ANSI_Keypad4],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_NUMPAD5], [NSNumber numberWithInt:OSX_kVK_ANSI_Keypad5],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_NUMPAD6], [NSNumber numberWithInt:OSX_kVK_ANSI_Keypad6],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_NUMPAD7], [NSNumber numberWithInt:OSX_kVK_ANSI_Keypad7],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_NUMPAD8], [NSNumber numberWithInt:OSX_kVK_ANSI_Keypad8],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_NUMPAD9], [NSNumber numberWithInt:OSX_kVK_ANSI_Keypad9],

                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_MULTIPLY], [NSNumber numberWithInt:OSX_kVK_ANSI_KeypadMultiply],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_ADD], [NSNumber numberWithInt:OSX_kVK_ANSI_KeypadPlus],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_SUBTRACT], [NSNumber numberWithInt:OSX_kVK_ANSI_KeypadMinus],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_DECIMAL], [NSNumber numberWithInt:OSX_kVK_ANSI_KeypadDecimal],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_DIVIDE], [NSNumber numberWithInt:OSX_kVK_ANSI_KeypadDivide],

                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_F1], [NSNumber numberWithInt:OSX_F1],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_F2], [NSNumber numberWithInt:OSX_F2],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_F3], [NSNumber numberWithInt:OSX_F3],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_F4], [NSNumber numberWithInt:OSX_F4],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_F5], [NSNumber numberWithInt:OSX_F5],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_F6], [NSNumber numberWithInt:OSX_F6],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_F7], [NSNumber numberWithInt:OSX_F7],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_F8], [NSNumber numberWithInt:OSX_F8],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_F9], [NSNumber numberWithInt:OSX_F9],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_F10], [NSNumber numberWithInt:OSX_F10],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_F11], [NSNumber numberWithInt:OSX_F11],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_F12], [NSNumber numberWithInt:OSX_F12],

                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_DELETE], [NSNumber numberWithInt:OSX_ForwardDelete],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_HELP], [NSNumber numberWithInt:OSX_Help],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_META], [NSNumber numberWithInt:OSX_Command],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_BACK_QUOTE], [NSNumber numberWithInt:OSX_kVK_ANSI_Grave],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_QUOTE], [NSNumber numberWithInt:OSX_kVK_ANSI_Quote],

                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_F13], [NSNumber numberWithInt:OSX_F13],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_F14], [NSNumber numberWithInt:OSX_F14],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_F15], [NSNumber numberWithInt:OSX_F15],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_F16], [NSNumber numberWithInt:OSX_F16],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_F17], [NSNumber numberWithInt:OSX_F17],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_F18], [NSNumber numberWithInt:OSX_F18],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_F19], [NSNumber numberWithInt:OSX_F19],
                [NSNumber numberWithInt:java_awt_event_KeyEvent_VK_F20], [NSNumber numberWithInt:OSX_F20],
                nil
        ];

        [osx2javaMap retain];
    }

    id val = [osx2javaMap objectForKey : [NSNumber numberWithInt : osxKeyCode]];
    if (nil != val)
        return [val intValue];

    return java_awt_event_KeyEvent_VK_UNDEFINED;
}

JNIEXPORT jstring JNICALL Java_java_awt_desktop_SystemHotkey_osxKeyCodeDescription(JNIEnv* env, jclass clazz, jint osxKeyCode) {
    static NSDictionary * osxCode2DescMap = nil;
    if (osxCode2DescMap == nil) {
        osxCode2DescMap = [NSDictionary dictionaryWithObjectsAndKeys:
                @"A", [NSNumber numberWithInt:kVK_ANSI_A],
                @"S", [NSNumber numberWithInt:kVK_ANSI_S],
                @"D", [NSNumber numberWithInt:kVK_ANSI_D],
                @"F", [NSNumber numberWithInt:kVK_ANSI_F],
                @"H", [NSNumber numberWithInt:kVK_ANSI_H],
                @"G", [NSNumber numberWithInt:kVK_ANSI_G],
                @"Z", [NSNumber numberWithInt:kVK_ANSI_Z],
                @"X", [NSNumber numberWithInt:kVK_ANSI_X],
                @"C", [NSNumber numberWithInt:kVK_ANSI_C],
                @"V", [NSNumber numberWithInt:kVK_ANSI_V],
                @"B", [NSNumber numberWithInt:kVK_ANSI_B],
                @"Q", [NSNumber numberWithInt:kVK_ANSI_Q],
                @"W", [NSNumber numberWithInt:kVK_ANSI_W],
                @"E", [NSNumber numberWithInt:kVK_ANSI_E],
                @"R", [NSNumber numberWithInt:kVK_ANSI_R],
                @"Y", [NSNumber numberWithInt:kVK_ANSI_Y],
                @"T", [NSNumber numberWithInt:kVK_ANSI_T],
                @"1", [NSNumber numberWithInt:kVK_ANSI_1],
                @"2", [NSNumber numberWithInt:kVK_ANSI_2],
                @"3", [NSNumber numberWithInt:kVK_ANSI_3],
                @"4", [NSNumber numberWithInt:kVK_ANSI_4],
                @"6", [NSNumber numberWithInt:kVK_ANSI_6],
                @"5", [NSNumber numberWithInt:kVK_ANSI_5],
                @"Equal", [NSNumber numberWithInt:kVK_ANSI_Equal],
                @"9", [NSNumber numberWithInt:kVK_ANSI_9],
                @"7", [NSNumber numberWithInt:kVK_ANSI_7],
                @"Minus", [NSNumber numberWithInt:kVK_ANSI_Minus],
                @"8", [NSNumber numberWithInt:kVK_ANSI_8],
                @"0", [NSNumber numberWithInt:kVK_ANSI_0],
                @"RightBracket", [NSNumber numberWithInt:kVK_ANSI_RightBracket],
                @"O", [NSNumber numberWithInt:kVK_ANSI_O],
                @"U", [NSNumber numberWithInt:kVK_ANSI_U],
                @"LeftBracket", [NSNumber numberWithInt:kVK_ANSI_LeftBracket],
                @"I", [NSNumber numberWithInt:kVK_ANSI_I],
                @"P", [NSNumber numberWithInt:kVK_ANSI_P],
                @"L", [NSNumber numberWithInt:kVK_ANSI_L],
                @"J", [NSNumber numberWithInt:kVK_ANSI_J],
                @"Quote", [NSNumber numberWithInt:kVK_ANSI_Quote],
                @"K", [NSNumber numberWithInt:kVK_ANSI_K],
                @"Semicolon", [NSNumber numberWithInt:kVK_ANSI_Semicolon],
                @"Backslash", [NSNumber numberWithInt:kVK_ANSI_Backslash],
                @"Comma", [NSNumber numberWithInt:kVK_ANSI_Comma],
                @"Slash", [NSNumber numberWithInt:kVK_ANSI_Slash],
                @"N", [NSNumber numberWithInt:kVK_ANSI_N],
                @"M", [NSNumber numberWithInt:kVK_ANSI_M],
                @"Period", [NSNumber numberWithInt:kVK_ANSI_Period],
                @"Grave", [NSNumber numberWithInt:kVK_ANSI_Grave],
                @"KeypadDecimal", [NSNumber numberWithInt:kVK_ANSI_KeypadDecimal],
                @"KeypadMultiply", [NSNumber numberWithInt:kVK_ANSI_KeypadMultiply],
                @"KeypadPlus", [NSNumber numberWithInt:kVK_ANSI_KeypadPlus],
                @"KeypadClear", [NSNumber numberWithInt:kVK_ANSI_KeypadClear],
                @"KeypadDivide", [NSNumber numberWithInt:kVK_ANSI_KeypadDivide],
                @"KeypadEnter", [NSNumber numberWithInt:kVK_ANSI_KeypadEnter],
                @"KeypadMinus", [NSNumber numberWithInt:kVK_ANSI_KeypadMinus],
                @"KeypadEquals", [NSNumber numberWithInt:kVK_ANSI_KeypadEquals],
                @"Keypad0", [NSNumber numberWithInt:kVK_ANSI_Keypad0],
                @"Keypad1", [NSNumber numberWithInt:kVK_ANSI_Keypad1],
                @"Keypad2", [NSNumber numberWithInt:kVK_ANSI_Keypad2],
                @"Keypad3", [NSNumber numberWithInt:kVK_ANSI_Keypad3],
                @"Keypad4", [NSNumber numberWithInt:kVK_ANSI_Keypad4],
                @"Keypad5", [NSNumber numberWithInt:kVK_ANSI_Keypad5],
                @"Keypad6", [NSNumber numberWithInt:kVK_ANSI_Keypad6],
                @"Keypad7", [NSNumber numberWithInt:kVK_ANSI_Keypad7],
                @"Keypad8", [NSNumber numberWithInt:kVK_ANSI_Keypad8],
                @"Keypad9", [NSNumber numberWithInt:kVK_ANSI_Keypad9],

                    /* keycodes for keys that are independent of keyboard layout*/
                @"Return", [NSNumber numberWithInt:kVK_Return],
                @"Tab", [NSNumber numberWithInt:kVK_Tab],
                @"Space", [NSNumber numberWithInt:kVK_Space],
                @"Delete", [NSNumber numberWithInt:kVK_Delete],
                @"Escape", [NSNumber numberWithInt:kVK_Escape],
                @"Command", [NSNumber numberWithInt:kVK_Command],
                @"Shift", [NSNumber numberWithInt:kVK_Shift],
                @"CapsLock", [NSNumber numberWithInt:kVK_CapsLock],
                @"Option", [NSNumber numberWithInt:kVK_Option],
                @"Control", [NSNumber numberWithInt:kVK_Control],
                @"RightCommand", [NSNumber numberWithInt:kVK_RightCommand],
                @"RightShift", [NSNumber numberWithInt:kVK_RightShift],
                @"RightOption", [NSNumber numberWithInt:kVK_RightOption],
                @"RightControl", [NSNumber numberWithInt:kVK_RightControl],
                @"Function", [NSNumber numberWithInt:kVK_Function],
                @"F17", [NSNumber numberWithInt:kVK_F17],
                @"VolumeUp", [NSNumber numberWithInt:kVK_VolumeUp],
                @"VolumeDown", [NSNumber numberWithInt:kVK_VolumeDown],
                @"Mute", [NSNumber numberWithInt:kVK_Mute],
                @"F18", [NSNumber numberWithInt:kVK_F18],
                @"F19", [NSNumber numberWithInt:kVK_F19],
                @"F20", [NSNumber numberWithInt:kVK_F20],
                @"F5", [NSNumber numberWithInt:kVK_F5],
                @"F6", [NSNumber numberWithInt:kVK_F6],
                @"F7", [NSNumber numberWithInt:kVK_F7],
                @"F3", [NSNumber numberWithInt:kVK_F3],
                @"F8", [NSNumber numberWithInt:kVK_F8],
                @"F9", [NSNumber numberWithInt:kVK_F9],
                @"F11", [NSNumber numberWithInt:kVK_F11],
                @"F13", [NSNumber numberWithInt:kVK_F13],
                @"F16", [NSNumber numberWithInt:kVK_F16],
                @"F14", [NSNumber numberWithInt:kVK_F14],
                @"F10", [NSNumber numberWithInt:kVK_F10],
                @"F12", [NSNumber numberWithInt:kVK_F12],
                @"F15", [NSNumber numberWithInt:kVK_F15],
                @"Help", [NSNumber numberWithInt:kVK_Help],
                @"Home", [NSNumber numberWithInt:kVK_Home],
                @"PageUp", [NSNumber numberWithInt:kVK_PageUp],
                @"ForwardDelete", [NSNumber numberWithInt:kVK_ForwardDelete],
                @"F4", [NSNumber numberWithInt:kVK_F4],
                @"End", [NSNumber numberWithInt:kVK_End],
                @"F2", [NSNumber numberWithInt:kVK_F2],
                @"PageDown", [NSNumber numberWithInt:kVK_PageDown],
                @"F1", [NSNumber numberWithInt:kVK_F1],
                @"LeftArrow", [NSNumber numberWithInt:kVK_LeftArrow],
                @"RightArrow", [NSNumber numberWithInt:kVK_RightArrow],
                @"DownArrow", [NSNumber numberWithInt:kVK_DownArrow],
                @"UpArrow", [NSNumber numberWithInt:kVK_UpArrow],
                nil
        ];

        [osxCode2DescMap retain];
    }

    NSString * val = [osxCode2DescMap objectForKey : [NSNumber numberWithInt : osxKeyCode]];
    if (val == nil)
        return NULL;

    return (*env)->NewStringUTF(env, val.UTF8String);
}

static NSDictionary * getDefaultParams() {
    static NSDictionary * sid2defaults = nil;
    if (sid2defaults == nil) {
        sid2defaults = [NSDictionary dictionaryWithObjectsAndKeys:
                [DefaultParams create:@"@$b" enabled:NO], @"com.apple.BluetoothFileExchange - Send File To Bluetooth Device - sendFileUsingBluetoothOBEXService",
                [DefaultParams create:@"@^$c" enabled:YES], @"com.apple.ChineseTextConverterService - Convert Text from Simplified to Traditional Chinese - convertTextToTraditionalChinese",
                [DefaultParams create:@"@~^$c" enabled:YES], @"com.apple.ChineseTextConverterService - Convert Text from Traditional to Simplified Chinese - convertTextToSimplifiedChinese",
                [DefaultParams create:@"@$l" enabled:YES], @"com.apple.Safari - Search With %WebSearchProvider@ - searchWithWebSearchProvider",
                [DefaultParams create:@"@*" enabled:NO], @"com.apple.ScriptEditor2 - Script Editor/Get Result of AppleScript - runAsAppleScript",
                [DefaultParams create:@"@$f" enabled:NO], @"com.apple.SpotlightService - SEARCH_WITH_SPOTLIGHT - doSearchWithSpotlight",
                [DefaultParams create:@"@$y" enabled:YES], @"com.apple.Stickies - Make Sticky - makeStickyFromTextService",
                [DefaultParams create:@"@$m" enabled:YES], @"com.apple.Terminal - Open man Page in Terminal - openManPage",
                [DefaultParams create:@"@$a" enabled:YES], @"com.apple.Terminal - Search man Page Index in Terminal - searchManPages",
                nil
        ];

        [sid2defaults retain];
    }

    return sid2defaults;
}

static NSMutableDictionary * createDefaultParams() {
    NSDictionary * sid2defaults = getDefaultParams();
    NSMutableDictionary * result = [NSMutableDictionary dictionaryWithCapacity:[sid2defaults count]];
    [result setDictionary:sid2defaults];
    return result;
}
