#import <Foundation/Foundation.h>
#import <Carbon/Carbon.h>
#import <Cocoa/Cocoa.h>

#import "CRobotKeyCode.h"
#import "java_awt_event_KeyEvent.h"

#include <jni.h>
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
    vsnprintf(buf, bufSize, formatMsg, args);
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

static NSString * getAppleSymbolicHotKeysDescription(int hotKeyId) {
    static NSDictionary * hotkeyId2DescMap = nil;
    if (hotkeyId2DescMap == nil) {
        hotkeyId2DescMap = [NSDictionary dictionaryWithObjectsAndKeys:
                @"Move focus to the menu bar", [NSNumber numberWithInt:7],
                @"Move focus to the Dock", [NSNumber numberWithInt:8],
                @"Move focus to active or next window", [NSNumber numberWithInt:9],
                @"Move focus to window toolbar", [NSNumber numberWithInt:10],
                @"Move focus to floating window", [NSNumber numberWithInt:11],
                @"Change the way Tab moves focus", [NSNumber numberWithInt:13],
                @"Turn zoom on or off", [NSNumber numberWithInt:15],
                @"Zoom in", [NSNumber numberWithInt:17],
                @"Zoom out", [NSNumber numberWithInt:19],
                @"Reverse Black and White", [NSNumber numberWithInt:21],
                @"Turn image smoothing on or off", [NSNumber numberWithInt:23],
                @"Increase Contrast", [NSNumber numberWithInt:25],
                @"Decrease Contrast", [NSNumber numberWithInt:26],
                @"Move focus to the next window in application", [NSNumber numberWithInt:27],
                @"Save picture of screen as file", [NSNumber numberWithInt:28],
                @"Copy picture of screen to clipboard", [NSNumber numberWithInt:29],
                @"Save picture of selected area as file", [NSNumber numberWithInt:30],
                @"Copy picture of selected area to clipboard", [NSNumber numberWithInt:31],
                @"All Windows", [NSNumber numberWithInt:32],
                @"Application Windows", [NSNumber numberWithInt:33],
                @"All Windows (Slow)", [NSNumber numberWithInt:34],
                @"Application Windows (Slow)", [NSNumber numberWithInt:35],
                @"Desktop", [NSNumber numberWithInt:36],
                @"Desktop (Slow)", [NSNumber numberWithInt:37],
                @"Move focus to the window drawer", [NSNumber numberWithInt:51],
                @"Turn Dock Hiding On/Off", [NSNumber numberWithInt:52],
                @"Move focus to the status menus", [NSNumber numberWithInt:57],
                @"Turn VoiceOver on / off", [NSNumber numberWithInt:59],
                @"Select the previous input source", [NSNumber numberWithInt:60],
                @"Select the next source in the Input Menu", [NSNumber numberWithInt:61],
                @"Dashboard", [NSNumber numberWithInt:62],
                @"Dashboard (Slow)", [NSNumber numberWithInt:63],
                @"Show Spotlight search field", [NSNumber numberWithInt:64],
                @"Show Spotlight window", [NSNumber numberWithInt:65],
                @"Dictionary MouseOver", [NSNumber numberWithInt:70],
                @"Hide and show Front Row", [NSNumber numberWithInt:73],
                @"Activate Spaces", [NSNumber numberWithInt:75],
                @"Activate Spaces (Slow)", [NSNumber numberWithInt:76],
                @"Spaces Left", [NSNumber numberWithInt:79],
                @"Spaces Right", [NSNumber numberWithInt:81],
                @"Spaces Down", [NSNumber numberWithInt:83],
                @"Spaces Up", [NSNumber numberWithInt:85],
                @"Show Help Menu", [NSNumber numberWithInt:91],
                @"Show Help Menu", [NSNumber numberWithInt:92],
                @"Show Help Menu", [NSNumber numberWithInt:98],
                @"Switch to Space 1", [NSNumber numberWithInt:118],
                @"Switch to Space 2", [NSNumber numberWithInt:119],
                @"Switch to Space 3", [NSNumber numberWithInt:120],
                @"Switch to Space 4", [NSNumber numberWithInt:121],
                @"Show Launchpad", [NSNumber numberWithInt:160],
                @"Show Accessibility Controls", [NSNumber numberWithInt:162],
                @"Show Notification Center", [NSNumber numberWithInt:163],
                @"Turn Do-Not-Disturb On/Off", [NSNumber numberWithInt:175],
                @"Turn focus following On/Off", [NSNumber numberWithInt:179],
                nil
        ];

        [hotkeyId2DescMap retain];
    }

    return [hotkeyId2DescMap objectForKey : [NSNumber numberWithInt : hotKeyId]];
}

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

typedef bool (^ Visitor)(int, const char *, int, const char *, int);

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

    visitorBlock(-1, keyChar.UTF8String, modifiers, desc.UTF8String, -1);
}

void readSystemHotkeysImpl(Visitor visitorBlock) {
    // 1. read from com.apple.symbolichotkeys.plist (domain with custom (user defined) shortcuts)
    @try {

    NSUserDefaults * defaults = [NSUserDefaults standardUserDefaults];
    NSDictionary<NSString *,id> * shk = [defaults persistentDomainForName:@"com.apple.symbolichotkeys"];
    if (shk != nil) {
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
        id hotkeys = [shk valueForKey:@"AppleSymbolicHotKeys"];
        if (hotkeys == nil)
            plog(LL_DEBUG, "key AppleSymbolicHotKeys doesn't exist in domain com.apple.symbolichotkeys");
        else if (![hotkeys isKindOfClass:[NSDictionary class]])
            plog(LL_DEBUG, "object for key 'AppleSymbolicHotKeys' isn't NSDictionary (class=%s)", [hotkeys className].UTF8String);
        else {
            for (id keyObj in hotkeys) {
                if (![keyObj isKindOfClass:[NSString class]]) {
                    plog(LL_DEBUG, "key '%s' isn't instance of NSString (class=%s)", toCString(keyObj), [keyObj className].UTF8String);
                    continue;
                }
                NSString *hkNumber = keyObj;
                id hkDesc = hotkeys[hkNumber];
                if (![hkDesc isKindOfClass:[NSDictionary class]]) {
                    plog(LL_DEBUG, "hotkey descriptor '%s' isn't instance of NSDictionary (class=%s)", toCString(hkDesc), [hkDesc className].UTF8String);
                    continue;
                }
                NSDictionary<id, id> *sdict = hkDesc;
                id objValue = sdict[@"value"];
                if (objValue == nil)
                    continue;

                if (![objValue isKindOfClass:[NSDictionary class]]) {
                    plog(LL_DEBUG, "property 'value' %s isn't instance of NSDictionary (class=%s)", toCString(objValue), [objValue className].UTF8String);
                    continue;
                }

                id objEnabled = sdict[@"enabled"];
                BOOL enabled = objEnabled != nil && [objEnabled boolValue] == YES;

                if (!enabled)
                    continue;

                NSDictionary * value = objValue;
                id objParams = value[@"parameters"];
                if (![objParams isKindOfClass:[NSArray class]]) {
                    plog(LL_DEBUG, "property 'parameters' %s isn't instance of NSArray (class=%s)", toCString(objParams), [objParams className].UTF8String);
                    continue;
                }

                NSArray *parameters = objParams;
                if ([parameters count] < 3) {
                    plog(LL_DEBUG, "too small lenght of parameters %d", [parameters count]);
                    continue;
                }

                id p0 = parameters[0];
                id p1 = parameters[1];
                id p2 = parameters[2];

                if (![p0 isKindOfClass:[NSNumber class]] || ![p1 isKindOfClass:[NSNumber class]] || ![p2 isKindOfClass:[NSNumber class]]) {
                    plog(LL_DEBUG, "some of parameters isn't instance of NSNumber (%s, %s, %s)", [p0 className].UTF8String, [p1 className].UTF8String, [p2 className].UTF8String);
                    continue;
                }

                //parameter 1: ASCII code of the character (or 65535 - hex 0xFFFF - for non-ASCII characters).
                //parameter 2: the keyboard key code for the character.
                //Parameter 3: the sum of the control, command, shift and option keys. these are bits 17-20 in binary: shift is bit 17, control is bit 18, option is bit 19, and command is bit 20.
                //      0x020000 => "Shift",
                //      0x040000 => "Control",
                //      0x080000 => "Option",
                //      0x100000 => "Command"

                int asciiCode = p0 == nil ? 0xFFFF : [p0 intValue];
                int vkeyCode = p1 == nil ? -1 : [p1 intValue];
                int modifiers = p2 == nil ? 0 : [p2 intValue];

                char keyCharBuf[64];
                const char * keyCharStr = keyCharBuf;
                if (asciiCode >= 0 && asciiCode <= 0xFF) {
                    sprintf(keyCharBuf, "%c", asciiCode);
                } else
                    keyCharStr = NULL;
                NSString * description = getAppleSymbolicHotKeysDescription([hkNumber intValue]);
                visitorBlock(vkeyCode, keyCharStr, symbolicHotKeysModifiers2java(modifiers), description == nil ? NULL : description.UTF8String, [hkNumber intValue]);
            }
        }
    } else {
        plog(LL_DEBUG, "domain com.apple.symbolichotkeys doesn't exist");
    }

    // 2. read from Pbs (domain with services shortcuts)
    NSMutableDictionary * allDefParams = createDefaultParams();
    NSDictionary<NSString *,id> * pbs = [defaults persistentDomainForName:@"pbs"];
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
                    plog(LL_DEBUG, "'%s' isn't instance of NSDictionary (class=%s)", toCString(value), [value className].UTF8String);
                    continue;
                }
                // NOTE: unchanged default params will not appear here, check allDefParams at the end of this loop
                DefaultParams * defParams = [allDefParams objectForKey:key];
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
    for (NSString* key in allDefParams) {
        DefaultParams * defParams = allDefParams[key];
        if (!defParams.enabled) {
            continue;
        }
        visitServicesShortcut(visitorBlock, defParams.key_equivalent, key);
    }

#ifdef USE_CARBON_CopySymbolicHotKeys
    // 3. read from core services
    CFArrayRef registeredHotKeys;
    if(CopySymbolicHotKeys(&registeredHotKeys) == noErr) {
        CFIndex count = CFArrayGetCount(registeredHotKeys);
        for(CFIndex i = 0; i < count; i++) {
            CFDictionaryRef hotKeyInfo = CFArrayGetValueAtIndex(registeredHotKeys, i);
            CFNumberRef hotKeyCode = CFDictionaryGetValue(hotKeyInfo, kHISymbolicHotKeyCode);
            CFNumberRef hotKeyModifiers = CFDictionaryGetValue(hotKeyInfo, kHISymbolicHotKeyModifiers);
            CFBooleanRef hotKeyEnabled = CFDictionaryGetValue(hotKeyInfo, kHISymbolicHotKeyEnabled);

            int64_t vkeyCode = -1;
            CFNumberGetValue(hotKeyCode, kCFNumberSInt64Type, &vkeyCode);
            int64_t keyModifiers = 0;
            CFNumberGetValue(hotKeyModifiers, kCFNumberSInt64Type, &keyModifiers);
            Boolean enabled = CFBooleanGetValue(hotKeyEnabled);
            if (!enabled)
                continue;

            visitorBlock(vkeyCode, NULL, NSModifiers2java(keyModifiers), NULL, -1);
        }

        CFRelease(registeredHotKeys);
    }
#endif // USE_CARBON_CopySymbolicHotKeys
    }
    @catch (NSException *exception) {
        NSLog(@"readSystemHotkeys: catched exception, reason '%@'", exception.reason);
    }
}

bool isSystemShortcut_NextWindowInApplication(NSUInteger modifiersMask, int keyCode, NSString * chars) {
    const int shortcutUid_NextWindowInApplication = 27;
    static NSString * shortcutCharacter = nil;
    static int shortcutMask = 0;
    static int shortcutKeyCode = -1;
    if (shortcutCharacter == nil && shortcutKeyCode == -1) {
        readSystemHotkeysImpl(
            ^bool(int vkeyCode, const char * keyCharStr, int jmodifiers, const char * descriptionStr, int hotkeyUid) {
                if (hotkeyUid != shortcutUid_NextWindowInApplication)
                    return true;

                if (keyCharStr != NULL) {
                    shortcutCharacter = [[NSString stringWithFormat:@"%s", keyCharStr] retain];
                }

                if (vkeyCode != -1) {
                    shortcutKeyCode = vkeyCode;
                }

                shortcutMask = javaModifiers2NS(jmodifiers);
                return false;
            }
        );
        if (shortcutCharacter == nil && shortcutKeyCode == -1) {
            shortcutCharacter = @"`";
            shortcutMask = NSCommandKeyMask;
        }
    }

    int ignoredModifiers = NSAlphaShiftKeyMask | NSFunctionKeyMask | NSNumericPadKeyMask | NSHelpKeyMask;
    // Ignore Shift because of JBR-4899.
    if (!(shortcutMask & NSShiftKeyMask)) {
        ignoredModifiers |= NSShiftKeyMask;
    }
    if ((modifiersMask & ~ignoredModifiers) == shortcutMask) {
        return shortcutKeyCode == keyCode || [chars isEqualToString:shortcutCharacter];
    }

    return false;
}

JNIEXPORT void JNICALL Java_java_awt_desktop_SystemHotkeyReader_readSystemHotkeys(JNIEnv* env, jobject reader) {
    jclass clsReader = (*env)->GetObjectClass(env, reader);
    jmethodID methodAdd = (*env)->GetMethodID(env, clsReader, "add", "(ILjava/lang/String;ILjava/lang/String;)V");

    readSystemHotkeysImpl(
        ^bool(int vkeyCode, const char * keyCharStr, int jmodifiers, const char * descriptionStr, int hotkeyUid){
            jstring jkeyChar = keyCharStr == NULL ? NULL : (*env)->NewStringUTF(env, keyCharStr);
            jstring jdesc = descriptionStr == NULL ? NULL : (*env)->NewStringUTF(env, descriptionStr);
            (*env)->CallVoidMethod(
                    env, reader, methodAdd, (jint)vkeyCode, jkeyChar, (jint)jmodifiers, jdesc
            );
            return true;
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
