#import <Foundation/Foundation.h>
#import <Carbon/Carbon.h>

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

void plogImpl(jobject platformLogger, int logLevel, const char * msg) {
    if (!jvm)
        return;
    if (logLevel < LL_TRACE || logLevel > LL_ERROR || msg == NULL)
        return;

    const char * methodName = "finest";
    switch (logLevel) {
        case LL_DEBUG: methodName = "fine"; break;
        case LL_INFO: methodName = "info"; break;
        case LL_WARNING: methodName = "warning"; break;
        case LL_ERROR: methodName = "severe"; break;
    }

    JNIEnv* env;
    jstring jstr;
    (*jvm)->AttachCurrentThreadAsDaemon(jvm, (void**)&env, NULL);
    jstr = (*env)->NewStringUTF(env, msg);
    JNU_CallMethodByName(env, NULL, platformLogger, methodName, "(Ljava/lang/String;)V", jstr);
    (*env)->DeleteLocalRef(env, jstr);
}

void plog(int logLevel, const char *formatMsg, ...) {
    // TODO: check whether current logLevel is enabled in PlatformLogger
    static jobject loggerObject = NULL;

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
    }

    va_list args;
    va_start(args, formatMsg);
    const int bufSize = 512;
    char buf[bufSize];
    vsnprintf(buf, bufSize, formatMsg, args);
    va_end(args);

    plogImpl(loggerObject, logLevel, buf);
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

static int NSModifiers2java(int mask) {
    int result = 0;
    if (mask & shiftKey)
        result |= AWT_SHIFT_DOWN_MASK;
    if (mask & controlKey)
        result |= AWT_CTRL_DOWN_MASK;
    if (mask & optionKey)
        result |= AWT_ALT_DOWN_MASK;
    if (mask & shiftKey)
        result |= AWT_META_DOWN_MASK;
    return result;
}

void Java_java_awt_desktop_SystemHotkeyReader_readSystemHotkeys(JNIEnv* env, jobject reader) {
    jclass clsReader = (*env)->GetObjectClass(env, reader);
    jmethodID methodAdd = (*env)->GetMethodID(env, clsReader, "add", "(ZILjava/lang/String;ILjava/lang/String;Ljava/lang/String;)V");

    // 1. read from com.apple.symbolichotkeys.plist (domain with custom (user defined) shortcuts)
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
            jstring jsource = (*env)->NewStringUTF(env, "com.apple.symbolichotkeys");
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

                NSDictionary * value = objValue;
                id objParams = value[@"parameters"];
                if (![objParams isKindOfClass:[NSArray class]]) {
                    plog(LL_DEBUG, "property 'parameters' %s isn't instance of NSArray (class=%s)", toCString(objParams), [objParams className].UTF8String);
                    continue;
                }

                NSArray *parameters = objParams;
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

                jstring jkeyChar = NULL;
                if (asciiCode >= 0 && asciiCode <= 0xFF) {
                    char keyCharBuf[64];
                    sprintf(keyCharBuf, "%c", asciiCode);
                    jkeyChar = (*env)->NewStringUTF(env, keyCharBuf);
                }

                (*env)->CallVoidMethod(
                        env, reader, methodAdd, (jboolean)enabled, (jint)vkeyCode,
                        jkeyChar, (jint)symbolicHotKeysModifiers2java(modifiers),
                        jsource, (*env)->NewStringUTF(env, [hkNumber UTF8String])
                );
            }
        }
    } else {
        plog(LL_DEBUG, "domain com.apple.symbolichotkeys doesn't exist");
    }

    // 2. read from Pbs (domain with services shortcuts)
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
            jstring jsource = (*env)->NewStringUTF(env, "pbs");
            for (NSString *key in services) {
                id value = services[key];
                if (![value isKindOfClass:[NSDictionary class]]) {
                    plog(LL_DEBUG, "'%s' isn't instance of NSDictionary (class=%s)", toCString(value), [value className].UTF8String);
                    continue;
                }
                NSDictionary<NSString *, id> *sdict = value;
                NSString *key_equivalent = sdict[@"key_equivalent"];
                if (!key_equivalent)
                    continue;

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

                (*env)->CallVoidMethod(
                        env, reader, methodAdd, true, -1,
                        (*env)->NewStringUTF(env, keyChar.UTF8String), modifiers,
                        jsource, (*env)->NewStringUTF(env, key.UTF8String)
                );
            }
        }
    }

    // 3. read from core services
    CFArrayRef registeredHotKeys;
    if(CopySymbolicHotKeys(&registeredHotKeys) == noErr) {
        jstring jsource = (*env)->NewStringUTF(env, "CopySymbolicHotKeys");
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

            (*env)->CallVoidMethod(
                    env, reader, methodAdd, enabled, (int)vkeyCode,
                    NULL, NSModifiers2java(keyModifiers),
                    jsource, NULL
            );
        }

        CFRelease(registeredHotKeys);
    }
}

static const char* _getVirtualCodeDescription(int code) {
    switch (code) {
        case kVK_ANSI_A: return "A";
        case kVK_ANSI_S: return "S";
        case kVK_ANSI_D: return "D";
        case kVK_ANSI_F: return "F";
        case kVK_ANSI_H: return "H";
        case kVK_ANSI_G: return "G";
        case kVK_ANSI_Z: return "Z";
        case kVK_ANSI_X: return "X";
        case kVK_ANSI_C: return "C";
        case kVK_ANSI_V: return "V";
        case kVK_ANSI_B: return "B";
        case kVK_ANSI_Q: return "Q";
        case kVK_ANSI_W: return "W";
        case kVK_ANSI_E: return "E";
        case kVK_ANSI_R: return "R";
        case kVK_ANSI_Y: return "Y";
        case kVK_ANSI_T: return "T";
        case kVK_ANSI_1: return "1";
        case kVK_ANSI_2: return "2";
        case kVK_ANSI_3: return "3";
        case kVK_ANSI_4: return "4";
        case kVK_ANSI_6: return "6";
        case kVK_ANSI_5: return "5";
        case kVK_ANSI_Equal: return "Equal";
        case kVK_ANSI_9: return "9";
        case kVK_ANSI_7: return "7";
        case kVK_ANSI_Minus: return "Minus";
        case kVK_ANSI_8: return "8";
        case kVK_ANSI_0: return "0";
        case kVK_ANSI_RightBracket: return "RightBracket";
        case kVK_ANSI_O: return "O";
        case kVK_ANSI_U: return "U";
        case kVK_ANSI_LeftBracket: return "LeftBracket";
        case kVK_ANSI_I: return "I";
        case kVK_ANSI_P: return "P";
        case kVK_ANSI_L: return "L";
        case kVK_ANSI_J: return "J";
        case kVK_ANSI_Quote: return "Quote";
        case kVK_ANSI_K: return "K";
        case kVK_ANSI_Semicolon: return "Semicolon";
        case kVK_ANSI_Backslash: return "Backslash";
        case kVK_ANSI_Comma: return "Comma";
        case kVK_ANSI_Slash: return "Slash";
        case kVK_ANSI_N: return "N";
        case kVK_ANSI_M: return "M";
        case kVK_ANSI_Period: return "Period";
        case kVK_ANSI_Grave: return "Grave";
        case kVK_ANSI_KeypadDecimal: return "KeypadDecimal";
        case kVK_ANSI_KeypadMultiply: return "KeypadMultiply";
        case kVK_ANSI_KeypadPlus: return "KeypadPlus";
        case kVK_ANSI_KeypadClear: return "KeypadClear";
        case kVK_ANSI_KeypadDivide: return "KeypadDivide";
        case kVK_ANSI_KeypadEnter: return "KeypadEnter";
        case kVK_ANSI_KeypadMinus: return "KeypadMinus";
        case kVK_ANSI_KeypadEquals: return "KeypadEquals";
        case kVK_ANSI_Keypad0: return "Keypad0";
        case kVK_ANSI_Keypad1: return "Keypad1";
        case kVK_ANSI_Keypad2: return "Keypad2";
        case kVK_ANSI_Keypad3: return "Keypad3";
        case kVK_ANSI_Keypad4: return "Keypad4";
        case kVK_ANSI_Keypad5: return "Keypad5";
        case kVK_ANSI_Keypad6: return "Keypad6";
        case kVK_ANSI_Keypad7: return "Keypad7";
        case kVK_ANSI_Keypad8: return "Keypad8";
        case kVK_ANSI_Keypad9: return "Keypad9";

            /* keycodes for keys that are independent of keyboard layout*/
        case kVK_Return: return "Return";
        case kVK_Tab: return "Tab";
        case kVK_Space: return "Space";
        case kVK_Delete: return "Delete";
        case kVK_Escape: return "Escape";
        case kVK_Command: return "Command";
        case kVK_Shift: return "Shift";
        case kVK_CapsLock: return "CapsLock";
        case kVK_Option: return "Option";
        case kVK_Control: return "Control";
        case kVK_RightCommand: return "RightCommand";
        case kVK_RightShift: return "RightShift";
        case kVK_RightOption: return "RightOption";
        case kVK_RightControl: return "RightControl";
        case kVK_Function: return "Function";
        case kVK_F17: return "F17";
        case kVK_VolumeUp: return "VolumeUp";
        case kVK_VolumeDown: return "VolumeDown";
        case kVK_Mute: return "Mute";
        case kVK_F18: return "F18";
        case kVK_F19: return "F19";
        case kVK_F20: return "F20";
        case kVK_F5: return "F5";
        case kVK_F6: return "F6";
        case kVK_F7: return "F7";
        case kVK_F3: return "F3";
        case kVK_F8: return "F8";
        case kVK_F9: return "F9";
        case kVK_F11: return "F11";
        case kVK_F13: return "F13";
        case kVK_F16: return "F16";
        case kVK_F14: return "F14";
        case kVK_F10: return "F10";
        case kVK_F12: return "F12";
        case kVK_F15: return "F15";
        case kVK_Help: return "Help";
        case kVK_Home: return "Home";
        case kVK_PageUp: return "PageUp";
        case kVK_ForwardDelete: return "ForwardDelete";
        case kVK_F4: return "F4";
        case kVK_End: return "End";
        case kVK_F2: return "F2";
        case kVK_PageDown: return "PageDown";
        case kVK_F1: return "F1";
        case kVK_LeftArrow: return "LeftArrow";
        case kVK_RightArrow: return "RightArrow";
        case kVK_DownArrow: return "DownArrow";
        case kVK_UpArrow: return "UpArrow";
    };
    return NULL;
}

jstring Java_java_awt_desktop_SystemHotkey_virtualCodeDescription(JNIEnv* env, jclass clazz, jint code) {
    const char* desc = _getVirtualCodeDescription(code);
    if (desc == NULL)
        return NULL;

    return (*env)->NewStringUTF(env, desc);
}