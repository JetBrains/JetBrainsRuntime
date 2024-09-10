#import "SystemHotkey.h"

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

enum LOG_LEVEL {
    LL_TRACE,
    LL_DEBUG,
    LL_INFO,
    LL_WARNING,
    LL_ERROR
};

void plog(int logLevel, const char *formatMsg, ...) {
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

struct SymbolicHotKey {
    int uid;
    const char* id;
    const char* description;
    bool defaultEnabled;
    int defaultChar;
    int defaultKey;
    int defaultMods;
};

static const struct SymbolicHotKey symbolicHotKeys[] = {
        {7, "FocusMenuBar", "Move focus to the menu bar", YES, 65535, 120, 8650752},
        {8, "FocusDock", "Move focus to the Dock", YES, 65535, 99, 8650752},
        {9, "FocusActiveWindow", "Move focus to active or next window", YES, 65535, 118, 8650752},
        {10, "FocusToolbar", "Move focus to window toolbar", YES, 65535, 96, 8650752},
        {11, "FocusFloatingWindow", "Move focus to floating window", YES, 65535, 97, 8650752},
        {12, "ToggleKeyboardAccess", "Turn keyboard access on or off", YES, 65535, 122, 8650752},
        {13, "ChangeTabMode", "Change the way Tab moves focus", YES, 65535, 98, 8650752},
        {15, "ToggleZoom", "Zoom: Turn zoom on or off", NO, 56, 28, 1572864},
        {17, "ZoomIn", "Zoom: Zoom in", NO, 61, 24, 1572864},
        {19, "ZoomOut", "Zoom: Zoom out", NO, 45, 27, 1572864},
        {21, "InvertColors", "Invert colors", YES, 56, 28, 1835008},
        {23, "ToggleZoomImageSmoothing", "Zoom: Turn image smoothing on or off", NO, 92, 42, 1572864},
        {25, "IncreaseContrast", "Increase contrast", NO, 46, 47, 1835008},
        {26, "DecreaseContrast", "Decrease contrast", NO, 44, 43, 1835008},
        {27, "FocusNextApplicationWindow", "Move focus to the next window in application", YES, 96, 50, 1048576},
        {28, "ScreenshotToFile", "Save picture of screen as a file", YES, 51, 20, 1179648},
        {29, "ScreenshotToClipboard", "Copy picture of screen to the clipboard", YES, 51, 20, 1441792},
        {30, "ScreenshotAreaToFile", "Save picture of selected area as a file", YES, 52, 21, 1179648},
        {31, "ScreenshotAreaToClipboard", "Copy picture of selected area to the clipboard", YES, 52, 21,
                1441792},
        {32, "ShowAllWindows", "Mission Control", YES, 65535, 126, 8650752},
        {33, "ShowApplicationWindows", "Application windows", YES, 65535, 125, 8650752},
        {36, "ShowDesktop", "Show desktop", YES, 65535, 103, 8388608},
        {52, "ToggleDockHiding", "Turn Dock hiding on/off", YES, 100, 2, 1572864},
        {53, "DecreaseBrightness", "Decrease display brightness", YES, 65535, 107, 8388608},
        {54, "IncreaseBrightness", "Increase display brightness", YES, 65535, 113, 8388608},
        {55, "OpenDisplaySettings1", "Open display settings", YES, 65535, 107, 8912896},
        {56, "OpenDisplaySettings2", "Open display settings", YES, 65535, 113, 8912896},
        {57, "FocusStatusMenu", "Move focus to the status menus", YES, 65535, 100, 8650752},
        {59, "ToggleVoiceOver", "Turn VoiceOver on or off", YES, 65535, 96, 9437184},
        {60, "SelectPreviousInputSource", "Select the previous input source", YES, 32, 49, 262144},
        {61, "SelectNextInputSource", "Select next source in Input menu", YES, 32, 49, 786432},
        {64, "ShowSpotlight", "Show Spotlight Search", YES, 32, 49, 1048576},
        {65, "ShowFinderSearch", "Show Finder search window", YES, 32, 49, 1572864},
        {79, "SwitchToDesktopLeft", "Move left a space", NO, 65535, 123, 8650752},
        {81, "SwitchToDesktopRight", "Move right a space", NO, 65535, 124, 8650752},
        {118, "SwitchToDesktop1", "Switch to Desktop 1", NO, 65535, 18, 262144},
        {119, "SwitchToDesktop2", "Switch to Desktop 2", NO, 65535, 19, 262144},
        {120, "SwitchToDesktop3", "Switch to Desktop 3", NO, 65535, 20, 262144},
        {121, "SwitchToDesktop4", "Switch to Desktop 4", NO, 65535, 21, 262144},
        {160, "ShowLaunchpad", "Show Launchpad", NO, 65535, 65535, 0},
        {162, "ShowAccessibilityControls", "Show Accessibility controls", YES, 65535, 96, 9961472},
        {163, "ShowNotificationCenter", "Show Notification Centre", NO, 65535, 65535, 0},
        {175, "ToggleDoNotDisturb", "Turn Do Not Disturb on/off", YES, 65535, 65535, 0},
        {179, "ToggleZoomFocusFollowing", "Zoom: Turn focus following on or off", NO, 65535, 65535, 0},
        {184, "ScreenshotOptions", "Screenshot and recording options", YES, 53, 23, 1179648},
        {190, "OpenQuickNote", "Quick note", YES, 113, 12, 8388608},
        {222, "ToggleStageManager", "Turn Stage Manager on/off", NO, 65535, 65535, 0},
        {223, "TogglePresenterOverlayLarge", "Turn Presenter Overlay (large) on or off", YES, 65535, 65535, 0},
        {224, "TogglePresenterOverlaySmall", "Turn Presenter Overlay (small) on or off", YES, 65535, 65535, 0},
        {225, "ToggleLiveSpeech", "LiveSpeech: Turn Live Speech on or off", YES, 65535, 65535, 0},
        {226, "ToggleLiveSpeechVisibility", "LiveSpeech: Toggle visibility", YES, 65535, 65535, 0},
        {227, "PauseOrResumeLiveSpeech", "LiveSpeech: Pause or resume speech", YES, 65535, 65535, 0},
        {228, "CancelLiveSpeech", "LiveSpeech: Cancel speech", YES, 65535, 65535, 0},
        {229, "ToggleLiveSpeechPhrases", "LiveSpeech: Hide or show phrases", YES, 65535, 65535, 0},
        {230, "ToggleSpeakSelection", "Turn speak selection on or off", YES, 65535, 65535, 0},
        {231, "ToggleSpeakItemUnderPointer", "Turn speak item under the pointer on or off", YES, 65535, 65535,
                0},
        {232, "ToggleTypingFeedback", "Turn typing feedback on or off", YES, 65535, 65535, 0},
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

static NSMutableDictionary * createPbsDefaultParams();

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

static void visitServicesShortcut(SystemHotkeyVisitor visitorBlock, NSString *key_equivalent, NSString *desc) {
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

    const char* utf8 = [desc UTF8String];

    visitorBlock(-1, keyChar.UTF8String, modifiers, utf8, utf8, -1);
}

struct ShortcutInfo {
    NSString *character;
    int mask;
    int keyCode;
    bool loaded;
    bool enabled;
};

static struct ShortcutInfo nextWindowInApplicationShortcut = {
        .loaded = false,
};
const int shortcutUid_NextWindowInApplication = 27;

static void reloadNextWindowInApplicationShortcut() {
    // Should be called from the main thread

    if (nextWindowInApplicationShortcut.character != nil) {
        [nextWindowInApplicationShortcut.character release];
        nextWindowInApplicationShortcut.character = nil;
    }

    [SystemHotkey readAllHotkeys:(
            ^bool(int vkeyCode, const char *keyCharStr, int jmodifiers, const char* idStr, const char *descriptionStr, int hotkeyUid) {
                if (hotkeyUid != shortcutUid_NextWindowInApplication)
                    return true;

                if (keyCharStr != NULL) {
                    nextWindowInApplicationShortcut.character = [[NSString stringWithFormat:@"%s", keyCharStr] retain];
                }

                if (vkeyCode != -1) {
                    nextWindowInApplicationShortcut.keyCode = vkeyCode;
                }

                nextWindowInApplicationShortcut.mask = javaModifiers2NS(jmodifiers);
                nextWindowInApplicationShortcut.enabled = true;
                return false;
            }
    )];

    nextWindowInApplicationShortcut.loaded = true;
}

@implementation SystemHotkey
+ (void)addChangeListener {
    NSUserDefaults *symbolicHotKeys = [[NSUserDefaults alloc] initWithSuiteName:@"com.apple.symbolichotkeys"];
    [symbolicHotKeys addObserver:self forKeyPath:@"AppleSymbolicHotKeys" options:NSKeyValueObservingOptionNew
                         context:nil];

    NSUserDefaults *pbsHotKeys = [[NSUserDefaults alloc] initWithSuiteName:@"pbs"];
    [pbsHotKeys addObserver:self forKeyPath:@"NSServicesStatus" options:NSKeyValueObservingOptionNew context:nil];
}

+ (void)observeValueForKeyPath:(NSString *)keyPath ofObject:(id)object change:(NSDictionary<NSKeyValueChangeKey, id> *)change context:(void *)context {
    // Called when AppleSymbolicHotKeys or NSServicesStatus change.
    // This method can be called from any thread.

    if ([keyPath isEqualToString:@"AppleSymbolicHotKeys"]) {
        [ThreadUtilities performOnMainThreadNowOrLater:^() {
            reloadNextWindowInApplicationShortcut();
        }];
    }

    JNIEnv* env = [ThreadUtilities getJNIEnv];
    DECLARE_CLASS(jc_SystemHotkey, "java/awt/desktop/SystemHotkey");
    DECLARE_STATIC_METHOD(jsm_onChange, jc_SystemHotkey, "onChange", "()V");
    (*env)->CallStaticVoidMethod(env, jc_SystemHotkey, jsm_onChange);
    CHECK_EXCEPTION();
}

+ (void)readAppleSymbolicHotkeys:(SystemHotkeyVisitor)visitorBlock {
    @try {
        NSDictionary<NSString *, id> *shk = [[NSUserDefaults standardUserDefaults] persistentDomainForName:@"com.apple.symbolichotkeys"];
        id hotkeys = nil;
        if (shk != nil) {
            hotkeys = [shk valueForKey:@"AppleSymbolicHotKeys"];
        }

        if (hotkeys != nil && ![hotkeys isKindOfClass:[NSDictionary class]]) {
            plog(LL_DEBUG, "object for key 'AppleSymbolicHotKeys' isn't NSDictionary (class=%s)",
                 [[hotkeys className] UTF8String]);
            hotkeys = nil;
        }

        int numIds = sizeof(symbolicHotKeys) / sizeof(symbolicHotKeys[0]);

        for (int i = 0; i < numIds; ++i) {
            struct SymbolicHotKey* hotkey = &symbolicHotKeys[i];
            if (!hotkey->id) {
                continue;
            }

            bool enabled = hotkey->defaultEnabled;
            int character = hotkey->defaultChar;
            int key = hotkey->defaultKey;
            int mods = hotkey->defaultMods;

            if (hotkeys != nil) {
                id hotkeyDesc = ((NSDictionary *) hotkeys)[[NSString stringWithFormat:@"%d", hotkey->uid]];

                if (hotkeyDesc != nil && ![hotkeyDesc isKindOfClass:[NSDictionary class]]) {
                    plog(LL_DEBUG, "hotkey descriptor '%s' isn't instance of NSDictionary (class=%s)",
                         toCString(hotkeyDesc),
                         [[hotkeyDesc className] UTF8String]);
                    hotkeyDesc = nil;
                }

                if (hotkeyDesc != nil) {
                    id objEnabled = ((NSDictionary *) hotkeyDesc)[@"enabled"];
                    enabled = objEnabled != nil && [objEnabled boolValue] == YES;

                    if (!enabled) {
                        continue;
                    }

                    id value = ((NSDictionary *) hotkeyDesc)[@"value"];

                    if (value != nil && ![value isKindOfClass:[NSDictionary class]]) {
                        plog(LL_DEBUG, "property 'value' %s isn't instance of NSDictionary (class=%s)",
                             toCString(value),
                             [[value className] UTF8String]);
                        value = nil;
                    }

                    if (value != nil) {
                        id params = ((NSDictionary *) value)[@"parameters"];

                        if (params != nil && ![params isKindOfClass:[NSArray class]]) {
                            plog(LL_DEBUG, "property 'parameters' %s isn't instance of NSArray (class=%s)",
                                 toCString(params),
                                 [[params className] UTF8String]);
                        }

                        if (params != nil) {
                            if ([(NSArray *) params count] >= 3) {
                                int *intParams[3] = {&character, &key, &mods};
                                for (int j = 0; j < 3; ++j) {
                                    id param = ((NSArray *) params)[j];
                                    if (param == nil) {
                                        continue;
                                    }
                                    if ([param isKindOfClass:[NSNumber class]]) {
                                        plog(LL_DEBUG, "parameter %d is not NSNumber (%s)", j,
                                             [[param className] UTF8String]);
                                        break;
                                    }
                                    *intParams[j] = [(NSNumber *) param intValue];
                                }
                            } else {
                                plog(LL_DEBUG, "too small length of parameters %d", [(NSArray *) params count]);
                            }
                        }
                    }
                }
            }

            if (!enabled) {
                continue;
            }

            if (character == 0xFFFF && key == 0xFFFF) {
                continue;
            }

            char keyCharBuf[64];
            const char* keyCharStr = NULL;
            if (character != 0 && character != 0xFFFF) {
                unichar uchar = (unichar)character;
                const char* utf8 = [[NSString stringWithCharacters:&uchar length:1] UTF8String];
                strncpy(keyCharBuf, utf8, sizeof(keyCharBuf));
                keyCharBuf[sizeof(keyCharBuf) - 1] = '\0';
                keyCharStr = keyCharBuf;
            }

            visitorBlock(key, keyCharStr, symbolicHotKeysModifiers2java(mods), hotkey->id, hotkey->description, hotkey->uid);
        }
    }
    @catch (NSException *exception) {
        NSLog(@"readAppleSymbolicHotkeys: catched exception, reason '%@'", exception.reason);
    }
}

+ (void)readPbsHotkeys:(SystemHotkeyVisitor)visitorBlock {
    @try {
        NSMutableDictionary *allDefParams = createPbsDefaultParams();
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

+ (void)readAllHotkeys:(SystemHotkeyVisitor)visitorBlock {
    // read from com.apple.symbolichotkeys.plist (domain with custom (user defined) shortcuts)
    [self readAppleSymbolicHotkeys:visitorBlock];

    // read from Pbs (domain with services shortcuts)
    [self readPbsHotkeys:visitorBlock];
}
@end

bool isSystemShortcut_NextWindowInApplication(NSUInteger modifiersMask, int keyCode, NSString *chars) {
    // Should be called from the main thread

    if (!nextWindowInApplicationShortcut.loaded) {
        reloadNextWindowInApplicationShortcut();
    }

    if (!nextWindowInApplicationShortcut.enabled) {
        return false;
    }

    int ignoredModifiers = NSAlphaShiftKeyMask | NSFunctionKeyMask | NSNumericPadKeyMask | NSHelpKeyMask;
    // Ignore Shift because of JBR-4899.
    if (!(nextWindowInApplicationShortcut.mask & NSShiftKeyMask)) {
        ignoredModifiers |= NSShiftKeyMask;
    }
    if ((nextWindowInApplicationShortcut.mask & ~ignoredModifiers) == nextWindowInApplicationShortcut.mask) {
        return nextWindowInApplicationShortcut.keyCode == keyCode || [chars isEqualToString:nextWindowInApplicationShortcut.character];
    }

    return false;
}

JNIEXPORT void JNICALL Java_java_awt_desktop_SystemHotkeyReader_readSystemHotkeys(JNIEnv *env, jobject reader) {
    jclass clsReader = (*env)->GetObjectClass(env, reader);
    jmethodID methodAdd = (*env)->GetMethodID(env, clsReader, "add", "(ILjava/lang/String;ILjava/lang/String;Ljava/lang/String;)V");

    [SystemHotkey readAllHotkeys:(
            ^bool(int vkeyCode, const char *keyCharStr, int jmodifiers, const char* idStr, const char *descriptionStr, int hotkeyUid) {
                jstring jkeyChar = keyCharStr == NULL ? NULL : (*env)->NewStringUTF(env, keyCharStr);
                jstring jid = idStr == NULL ? NULL : (*env)->NewStringUTF(env, idStr);
                jstring jdesc = descriptionStr == NULL ? NULL : (*env)->NewStringUTF(env, descriptionStr);
                (*env)->CallVoidMethod(
                        env, reader, methodAdd, (jint) vkeyCode, jkeyChar, (jint) jmodifiers, jid, jdesc
                );
                return true;
            }
    )];
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

static NSDictionary * getPbsDefaultParams() {
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

static NSMutableDictionary * createPbsDefaultParams() {
    NSDictionary * sid2defaults = getPbsDefaultParams();
    NSMutableDictionary * result = [NSMutableDictionary dictionaryWithCapacity:[sid2defaults count]];
    [result setDictionary:sid2defaults];
    return result;
}
