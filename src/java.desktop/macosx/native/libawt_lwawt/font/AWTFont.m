/*
 * Copyright (c) 2011, 2013, Oracle and/or its affiliates. All rights reserved.
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

#import "java_awt_Font.h"
#import "sun_awt_PlatformFont.h"
#import "sun_awt_FontDescriptor.h"
#import "sun_font_CFont.h"
#import "sun_font_CFontManager.h"

#import "AWTFont.h"
#import "AWTStrike.h"
#import "CoreTextSupport.h"
#import "JNIUtilities.h"

@implementation AWTFont

- (id) initWithFont:(NSFont *)font fallbackBase:(NSFont *)fallbackBaseFont {
    self = [super init];
    if (self) {
        fFont = [font retain];
        fNativeCGFont = CTFontCopyGraphicsFont((CTFontRef)font, NULL);
        fFallbackBase = [fallbackBaseFont retain];
    }
    return self;
}

- (void) dealloc {
    [fFallbackBase release];
    fFallbackBase = nil;

    [fFont release];
    fFont = nil;

    if (fNativeCGFont) {
        CGFontRelease(fNativeCGFont);
    fNativeCGFont = NULL;
    }

    [super dealloc];
}

- (void) finalize {
    if (fNativeCGFont) {
        CGFontRelease(fNativeCGFont);
    fNativeCGFont = NULL;
    }
    [super finalize];
}

static NSString* uiName = nil;
static NSString* uiBoldName = nil;

+ (AWTFont *) awtFontForName:(NSString *)name
                       style:(int)style
{
    // create font with family & size
    NSFont *nsFont = nil;
    NSFont *nsFallbackBase = nil;

    if ((uiName != nil && [name isEqualTo:uiName]) ||
        (uiBoldName != nil && [name isEqualTo:uiBoldName])) {
        if (style & java_awt_Font_BOLD) {
            nsFont = [NSFont boldSystemFontOfSize:1.0];
        } else {
            nsFont = [NSFont systemFontOfSize:1.0];
        }
        nsFallbackBase = [NSFont fontWithName:@"Lucida Grande" size:1.0];
#ifdef DEBUG
        NSLog(@"nsFont-name is : %@", nsFont.familyName);
        NSLog(@"nsFont-family is : %@", nsFont.fontName);
        NSLog(@"nsFont-desc-name is : %@", nsFont.fontDescriptor.postscriptName);
#endif


    } else {
           nsFont = [NSFont fontWithName:name size:1.0];
    }

    if (nsFont == nil) {
        // if can't get font of that name, substitute system default font
        nsFont = [NSFont fontWithName:@"Lucida Grande" size:1.0];
#ifdef DEBUG
        NSLog(@"needed to substitute Lucida Grande for: %@", name);
#endif
    }

    // create an italic style (if one is installed)
    if (style & java_awt_Font_ITALIC) {
        nsFont = [[NSFontManager sharedFontManager] convertFont:nsFont toHaveTrait:NSItalicFontMask];
    }

    // create a bold style (if one is installed)
    if (style & java_awt_Font_BOLD) {
        nsFont = [[NSFontManager sharedFontManager] convertFont:nsFont toHaveTrait:NSBoldFontMask];
    }

    return [[[AWTFont alloc] initWithFont:nsFont fallbackBase:nsFallbackBase] autorelease];
}

+ (NSFont *) nsFontForJavaFont:(jobject)javaFont env:(JNIEnv *)env {
    if (javaFont == NULL) {
#ifdef DEBUG
        NSLog(@"nil font");
#endif
        return nil;
    }

    DECLARE_CLASS_RETURN(jc_Font, "java/awt/Font", nil);

    // obtain the Font2D
    DECLARE_METHOD_RETURN(jm_Font_getFont2D, jc_Font, "getFont2D", "()Lsun/font/Font2D;", nil);
    jobject font2d = (*env)->CallObjectMethod(env, javaFont, jm_Font_getFont2D);
    CHECK_EXCEPTION();
    if (font2d == NULL) {
#ifdef DEBUG
        NSLog(@"nil font2d");
#endif
        return nil;
    }

    // if it's not a CFont, it's likely one of TTF or OTF fonts
    // from the Sun rendering loops
    DECLARE_CLASS_RETURN(jc_CFont, "sun/font/CFont", nil);
    if (!(*env)->IsInstanceOf(env, font2d, jc_CFont)) {
#ifdef DEBUG
        NSLog(@"font2d !instanceof CFont");
#endif
        return nil;
    }

    DECLARE_METHOD_RETURN(jm_CFont_getFontStrike, jc_CFont, "getStrike", "(Ljava/awt/Font;)Lsun/font/FontStrike;", nil);
    jobject fontStrike = (*env)->CallObjectMethod(env, font2d, jm_CFont_getFontStrike, javaFont);
    CHECK_EXCEPTION();
    DECLARE_CLASS_RETURN(jc_CStrike, "sun/font/CStrike", nil);
    if (!(*env)->IsInstanceOf(env, fontStrike, jc_CStrike)) {
#ifdef DEBUG
        NSLog(@"fontStrike !instanceof CStrike");
#endif
        return nil;
    }

    DECLARE_METHOD_RETURN(jm_CStrike_nativeStrikePtr, jc_CStrike, "getNativeStrikePtr", "()J", nil);
    jlong awtStrikePtr = (*env)->CallLongMethod(env, fontStrike, jm_CStrike_nativeStrikePtr);
    CHECK_EXCEPTION();
    if (awtStrikePtr == 0L) {
#ifdef DEBUG
        NSLog(@"nil nativeFontPtr from CFont");
#endif
        return nil;
    }

    AWTStrike *strike = (AWTStrike *)jlong_to_ptr(awtStrikePtr);

    return [NSFont fontWithName:[strike->fAWTFont->fFont fontName] matrix:(CGFloat *)(&(strike->fAltTx))];
}

@end


#pragma mark --- Font Discovery and Loading ---

static NSArray* sFilteredFonts = nil;
static NSDictionary* sFontFamilyTable = nil;

static NSString*
GetFamilyNameForFontName(NSString* fontname)
{
    return [sFontFamilyTable objectForKey:fontname];
}

static void addFont(CTFontUIFontType uiType,
                    NSMutableArray *allFonts,
                    NSMutableDictionary* fontFamilyTable) {

        CTFontRef font = CTFontCreateUIFontForLanguage(uiType, 0.0, NULL);
        if (font == NULL) {
            return;
        }
        CTFontDescriptorRef desc = CTFontCopyFontDescriptor(font);
        if (desc == NULL) {
            CFRelease(font);
            return;
        }
        CFStringRef family = CTFontDescriptorCopyAttribute(desc, kCTFontFamilyNameAttribute);
        if (family == NULL) {
            CFRelease(desc);
            CFRelease(font);
            return;
        }
        CFStringRef name = CTFontDescriptorCopyAttribute(desc, kCTFontNameAttribute);
        if (name == NULL) {
            CFRelease(family);
            CFRelease(desc);
            CFRelease(font);
            return;
        }
        if (uiType == kCTFontUIFontSystem) {
            uiName = (NSString*)name;
        }
        if (uiType == kCTFontUIFontEmphasizedSystem) {
            uiBoldName = (NSString*)name;
        }
        [allFonts addObject:name];
        [fontFamilyTable setObject:family forKey:name];
#ifdef DEBUG
        NSLog(@"name is : %@", (NSString*)name);
        NSLog(@"family is : %@", (NSString*)family);
#endif
        CFRelease(family);
        CFRelease(name);
        CFRelease(desc);
        CFRelease(font);
}

static NSDictionary* prebuiltFamilyNames() {
    return @{
             @"..SFCompactDisplay-Regular" : @".SF Compact Display",
             @"..SFCompactRounded-Regular" : @".SF Compact Rounded",
             @"..SFCompactText-Italic" : @".SF Compact Text",
             @"..SFCompactText-Regular" : @".SF Compact Text",
             @".AlBayanPUA" : @".Al Bayan PUA",
             @".AlBayanPUA-Bold" : @".Al Bayan PUA",
             @".AlNilePUA" : @".Al Nile PUA",
             @".AlNilePUA-Bold" : @".Al Nile PUA",
             @".AlTarikhPUA" : @".Al Tarikh PUA",
             @".AppleColorEmojiUI" : @".Apple Color Emoji UI",
             @".AppleSDGothicNeoI-Bold" : @".Apple SD Gothic NeoI",
             @".AppleSDGothicNeoI-ExtraBold" : @".Apple SD Gothic NeoI",
             @".AppleSDGothicNeoI-Heavy" : @".Apple SD Gothic NeoI",
             @".AppleSDGothicNeoI-Light" : @".Apple SD Gothic NeoI",
             @".AppleSDGothicNeoI-Medium" : @".Apple SD Gothic NeoI",
             @".AppleSDGothicNeoI-Regular" : @".Apple SD Gothic NeoI",
             @".AppleSDGothicNeoI-SemiBold" : @".Apple SD Gothic NeoI",
             @".AppleSDGothicNeoI-Thin" : @".Apple SD Gothic NeoI",
             @".AppleSDGothicNeoI-UltraLight" : @".Apple SD Gothic NeoI",
             @".ArabicUIDisplay-Black" : @".Arabic UI Display",
             @".ArabicUIDisplay-Bold" : @".Arabic UI Display",
             @".ArabicUIDisplay-Heavy" : @".Arabic UI Display",
             @".ArabicUIDisplay-Light" : @".Arabic UI Display",
             @".ArabicUIDisplay-Medium" : @".Arabic UI Display",
             @".ArabicUIDisplay-Regular" : @".Arabic UI Display",
             @".ArabicUIDisplay-Semibold" : @".Arabic UI Display",
             @".ArabicUIDisplay-Thin" : @".Arabic UI Display",
             @".ArabicUIDisplay-Ultralight" : @".Arabic UI Display",
             @".ArabicUIText-Bold" : @".Arabic UI Text",
             @".ArabicUIText-Heavy" : @".Arabic UI Text",
             @".ArabicUIText-Light" : @".Arabic UI Text",
             @".ArabicUIText-Medium" : @".Arabic UI Text",
             @".ArabicUIText-Regular" : @".Arabic UI Text",
             @".ArabicUIText-Semibold" : @".Arabic UI Text",
             @".ArialHebrewDeskInterface" : @".Arial Hebrew Desk Interface",
             @".ArialHebrewDeskInterface-Bold" : @".Arial Hebrew Desk Interface",
             @".ArialHebrewDeskInterface-Light" : @".Arial Hebrew Desk Interface",
             @".BaghdadPUA" : @".Baghdad PUA",
             @".BeirutPUA" : @".Beirut PUA",
             @".DamascusPUA" : @".Damascus PUA",
             @".DamascusPUABold" : @".Damascus PUA",
             @".DamascusPUALight" : @".Damascus PUA",
             @".DamascusPUAMedium" : @".Damascus PUA",
             @".DamascusPUASemiBold" : @".Damascus PUA",
             @".DecoTypeNaskhPUA" : @".DecoType Naskh PUA",
             @".DiwanKufiPUA" : @".Diwan Kufi PUA",
             @".FarahPUA" : @".Farah PUA",
             @".GeezaProInterface" : @".Geeza Pro Interface",
             @".GeezaProInterface-Bold" : @".Geeza Pro Interface",
             @".GeezaProInterface-Light" : @".Geeza Pro Interface",
             @".GeezaProPUA" : @".Geeza Pro PUA",
             @".GeezaProPUA-Bold" : @".Geeza Pro PUA",
             @".HelveticaNeueDeskInterface-Bold" : @".Helvetica Neue DeskInterface",
             @".HelveticaNeueDeskInterface-BoldItalic" : @".Helvetica Neue DeskInterface",
             @".HelveticaNeueDeskInterface-Heavy" : @".Helvetica Neue DeskInterface",
             @".HelveticaNeueDeskInterface-Italic" : @".Helvetica Neue DeskInterface",
             @".HelveticaNeueDeskInterface-Light" : @".Helvetica Neue DeskInterface",
             @".HelveticaNeueDeskInterface-MediumItalicP4" : @".Helvetica Neue DeskInterface",
             @".HelveticaNeueDeskInterface-MediumP4" : @".Helvetica Neue DeskInterface",
             @".HelveticaNeueDeskInterface-Regular" : @".Helvetica Neue DeskInterface",
             @".HelveticaNeueDeskInterface-Thin" : @".Helvetica Neue DeskInterface",
             @".HelveticaNeueDeskInterface-UltraLightP2" : @".Helvetica Neue DeskInterface",
             @".HiraKakuInterface-W0" : @".Hiragino Kaku Gothic Interface",
             @".HiraKakuInterface-W1" : @".Hiragino Kaku Gothic Interface",
             @".HiraKakuInterface-W2" : @".Hiragino Kaku Gothic Interface",
             @".HiraKakuInterface-W3" : @".Hiragino Kaku Gothic Interface",
             @".HiraKakuInterface-W4" : @".Hiragino Kaku Gothic Interface",
             @".HiraKakuInterface-W5" : @".Hiragino Kaku Gothic Interface",
             @".HiraKakuInterface-W6" : @".Hiragino Kaku Gothic Interface",
             @".HiraKakuInterface-W7" : @".Hiragino Kaku Gothic Interface",
             @".HiraKakuInterface-W8" : @".Hiragino Kaku Gothic Interface",
             @".HiraKakuInterface-W9" : @".Hiragino Kaku Gothic Interface",
             @".HiraginoSansGBInterface-W3" : @".Hiragino Sans GB Interface",
             @".HiraginoSansGBInterface-W6" : @".Hiragino Sans GB Interface",
             @".Keyboard" : @".Keyboard",
             @".KufiStandardGKPUA" : @".KufiStandardGK PUA",
             @".LucidaGrandeUI" : @".Lucida Grande UI",
             @".LucidaGrandeUI-Bold" : @".Lucida Grande UI",
             @".MunaPUA" : @".Muna PUA",
             @".MunaPUABlack" : @".Muna PUA",
             @".MunaPUABold" : @".Muna PUA",
             @".NadeemPUA" : @".Nadeem PUA",
             @".NewYork-Black" : @".New York",
             @".NewYork-BlackItalic" : @".New York",
             @".NewYork-Bold" : @".New York",
             @".NewYork-BoldItalic" : @".New York",
             @".NewYork-Heavy" : @".New York",
             @".NewYork-HeavyItalic" : @".New York",
             @".NewYork-Medium" : @".New York",
             @".NewYork-MediumItalic" : @".New York",
             @".NewYork-Regular" : @".New York",
             @".NewYork-RegularItalic" : @".New York",
             @".NewYork-Semibold" : @".New York",
             @".NewYork-SemiboldItalic" : @".New York",
             @".NotoNastaliqUrduUI" : @".Noto Nastaliq Urdu UI",
             @".NotoNastaliqUrduUI-Bold" : @".Noto Nastaliq Urdu UI",
             @".PingFangHK-Light" : @".PingFang HK",
             @".PingFangHK-Medium" : @".PingFang HK",
             @".PingFangHK-Regular" : @".PingFang HK",
             @".PingFangHK-Semibold" : @".PingFang HK",
             @".PingFangHK-Thin" : @".PingFang HK",
             @".PingFangHK-Ultralight" : @".PingFang HK",
             @".PingFangSC-Light" : @".PingFang SC",
             @".PingFangSC-Medium" : @".PingFang SC",
             @".PingFangSC-Regular" : @".PingFang SC",
             @".PingFangSC-Semibold" : @".PingFang SC",
             @".PingFangSC-Thin" : @".PingFang SC",
             @".PingFangSC-Ultralight" : @".PingFang SC",
             @".PingFangTC-Light" : @".PingFang TC",
             @".PingFangTC-Medium" : @".PingFang TC",
             @".PingFangTC-Regular" : @".PingFang TC",
             @".PingFangTC-Semibold" : @".PingFang TC",
             @".PingFangTC-Thin" : @".PingFang TC",
             @".PingFangTC-Ultralight" : @".PingFang TC",
             @".SFCompactDisplay-Black" : @".SF Compact Display",
             @".SFCompactDisplay-Bold" : @".SF Compact Display",
             @".SFCompactDisplay-Heavy" : @".SF Compact Display",
             @".SFCompactDisplay-Light" : @".SF Compact Display",
             @".SFCompactDisplay-Medium" : @".SF Compact Display",
             @".SFCompactDisplay-Regular" : @".SF Compact Display",
             @".SFCompactDisplay-Semibold" : @".SF Compact Display",
             @".SFCompactDisplay-Thin" : @".SF Compact Display",
             @".SFCompactDisplay-Ultralight" : @".SF Compact Display",
             @".SFCompactRounded-Black" : @".SF Compact Rounded",
             @".SFCompactRounded-Bold" : @".SF Compact Rounded",
             @".SFCompactRounded-Heavy" : @".SF Compact Rounded",
             @".SFCompactRounded-Light" : @".SF Compact Rounded",
             @".SFCompactRounded-Medium" : @".SF Compact Rounded",
             @".SFCompactRounded-Regular" : @".SF Compact Rounded",
             @".SFCompactRounded-Semibold" : @".SF Compact Rounded",
             @".SFCompactRounded-Thin" : @".SF Compact Rounded",
             @".SFCompactRounded-Ultralight" : @".SF Compact Rounded",
             @".SFCompactText-Bold" : @".SF Compact Text",
             @".SFCompactText-BoldG1" : @".SF Compact Text",
             @".SFCompactText-BoldG2" : @".SF Compact Text",
             @".SFCompactText-BoldG3" : @".SF Compact Text",
             @".SFCompactText-BoldItalic" : @".SF Compact Text",
             @".SFCompactText-BoldItalicG1" : @".SF Compact Text",
             @".SFCompactText-BoldItalicG2" : @".SF Compact Text",
             @".SFCompactText-BoldItalicG3" : @".SF Compact Text",
             @".SFCompactText-Heavy" : @".SF Compact Text",
             @".SFCompactText-HeavyItalic" : @".SF Compact Text",
             @".SFCompactText-Italic" : @".SF Compact Text",
             @".SFCompactText-Light" : @".SF Compact Text",
             @".SFCompactText-LightItalic" : @".SF Compact Text",
             @".SFCompactText-Medium" : @".SF Compact Text",
             @".SFCompactText-MediumItalic" : @".SF Compact Text",
             @".SFCompactText-Regular" : @".SF Compact Text",
             @".SFCompactText-RegularG1" : @".SF Compact Text",
             @".SFCompactText-RegularG2" : @".SF Compact Text",
             @".SFCompactText-RegularG3" : @".SF Compact Text",
             @".SFCompactText-RegularItalic" : @".SF Compact Text",
             @".SFCompactText-RegularItalicG1" : @".SF Compact Text",
             @".SFCompactText-RegularItalicG2" : @".SF Compact Text",
             @".SFCompactText-RegularItalicG3" : @".SF Compact Text",
             @".SFCompactText-Semibold" : @".SF Compact Text",
             @".SFCompactText-SemiboldItalic" : @".SF Compact Text",
             @".SFCompactText-Thin" : @".SF Compact Text",
             @".SFCompactText-ThinItalic" : @".SF Compact Text",
             @".SFCompactText-Ultralight" : @".SF Compact Text",
             @".SFCompactText-UltralightItalic" : @".SF Compact Text",
             @".SFNSDisplay" : @".SF NS Display",
             @".SFNSDisplay-Black" : @".SF NS Display",
             @".SFNSDisplay-BlackItalic" : @".SF NS Display",
             @".SFNSDisplay-Bold" : @".SF NS Display",
             @".SFNSDisplay-BoldItalic" : @".SF NS Display",
             @".SFNSDisplay-Heavy" : @".SF NS Display",
             @".SFNSDisplay-HeavyItalic" : @".SF NS Display",
             @".SFNSDisplay-Italic" : @".SF NS Display",
             @".SFNSDisplay-Light" : @".SF NS Display",
             @".SFNSDisplay-LightItalic" : @".SF NS Display",
             @".SFNSDisplay-Medium" : @".SF NS Display",
             @".SFNSDisplay-MediumItalic" : @".SF NS Display",
             @".SFNSDisplay-Semibold" : @".SF NS Display",
             @".SFNSDisplay-SemiboldItalic" : @".SF NS Display",
             @".SFNSDisplay-Thin" : @".SF NS Display",
             @".SFNSDisplay-ThinG1" : @".SF NS Display",
             @".SFNSDisplay-ThinG2" : @".SF NS Display",
             @".SFNSDisplay-ThinG3" : @".SF NS Display",
             @".SFNSDisplay-ThinG4" : @".SF NS Display",
             @".SFNSDisplay-ThinItalic" : @".SF NS Display",
             @".SFNSDisplay-Ultralight" : @".SF NS Display",
             @".SFNSDisplay-UltralightItalic" : @".SF NS Display",

             @".SFNS-Black" : @".SF NS",
             @".SFNS-BlackItalic" : @".SF NS",
             @".SFNS-Bold" : @".SF NS",
             @".SFNS-BoldG1" : @".SF NS",
             @".SFNS-BoldG2" : @".SF NS",
             @".SFNS-BoldG3" : @".SF NS",
             @".SFNS-BoldG4" : @".SF NS",
             @".SFNS-BoldItalic" : @".SF NS",
             @".SFNS-Heavy" : @".SF NS",
             @".SFNS-HeavyG1" : @".SF NS",
             @".SFNS-HeavyG2" : @".SF NS",
             @".SFNS-HeavyG3" : @".SF NS",
             @".SFNS-HeavyG4" : @".SF NS",
             @".SFNS-HeavyItalic" : @".SF NS",
             @".SFNS-Light" : @".SF NS",
             @".SFNS-LightG1" : @".SF NS",
             @".SFNS-LightG2" : @".SF NS",
             @".SFNS-LightG3" : @".SF NS",
             @".SFNS-LightG4" : @".SF NS",
             @".SFNS-LightItalic" : @".SF NS",
             @".SFNS-Medium" : @".SF NS",
             @".SFNS-MediumG1" : @".SF NS",
             @".SFNS-MediumG2" : @".SF NS",
             @".SFNS-MediumG3" : @".SF NS",
             @".SFNS-MediumG4" : @".SF NS",
             @".SFNS-MediumItalic" : @".SF NS",
             @".SFNS-Regular" : @".SF NS",
             @".SFNS-RegularG1" : @".SF NS",
             @".SFNS-RegularG2" : @".SF NS",
             @".SFNS-RegularG3" : @".SF NS",
             @".SFNS-RegularG4" : @".SF NS",
             @".SFNS-RegularItalic" : @".SF NS",
             @".SFNS-Semibold" : @".SF NS",
             @".SFNS-SemiboldG1" : @".SF NS",
             @".SFNS-SemiboldG2" : @".SF NS",
             @".SFNS-SemiboldG3" : @".SF NS",
             @".SFNS-SemiboldG4" : @".SF NS",
             @".SFNS-SemiboldItalic" : @".SF NS",
             @".SFNS-Thin" : @".SF NS",
             @".SFNS-ThinG1" : @".SF NS",
             @".SFNS-ThinG2" : @".SF NS",
             @".SFNS-ThinG3" : @".SF NS",
             @".SFNS-ThinG4" : @".SF NS",
             @".SFNS-ThinItalic" : @".SF NS",
             @".SFNS-Ultralight" : @".SF NS",
             @".SFNS-UltralightG1" : @".SF NS",
             @".SFNS-UltralightG2" : @".SF NS",
             @".SFNS-UltralightG3" : @".SF NS",
             @".SFNS-UltralightG4" : @".SF NS",
             @".SFNS-UltralightItalic" : @".SF NS",
             @".SFNS-Ultrathin" : @".SF NS",
             @".SFNS-UltrathinG1" : @".SF NS",
             @".SFNS-UltrathinG2" : @".SF NS",
             @".SFNS-UltrathinG3" : @".SF NS",
             @".SFNS-UltrathinG4" : @".SF NS",
             @".SFNS-UltrathinItalic" : @".SF NS",
             @".SFNSDisplayCondensed-Black" : @".SF NS Display Condensed",
             @".SFNSDisplayCondensed-Bold" : @".SF NS Display Condensed",
             @".SFNSDisplayCondensed-Heavy" : @".SF NS Display Condensed",
             @".SFNSDisplayCondensed-Light" : @".SF NS Display Condensed",
             @".SFNSDisplayCondensed-Medium" : @".SF NS Display Condensed",
             @".SFNSDisplayCondensed-Regular" : @".SF NS Display Condensed",
             @".SFNSDisplayCondensed-Semibold" : @".SF NS Display Condensed",
             @".SFNSDisplayCondensed-Thin" : @".SF NS Display Condensed",
             @".SFNSDisplayCondensed-Ultralight" : @".SF NS Display Condensed",
             @".SFNSMono-Bold" : @".SF NS Mono",
             @".SFNSMono-BoldItalic" : @".SF NS Mono",
             @".SFNSMono-Heavy" : @".SF NS Mono",
             @".SFNSMono-HeavyItalic" : @".SF NS Mono",
             @".SFNSMono-Light" : @".SF NS Mono",
             @".SFNSMono-LightItalic" : @".SF NS Mono",
             @".SFNSMono-Medium" : @".SF NS Mono",
             @".SFNSMono-MediumItalic" : @".SF NS Mono",
             @".SFNSMono-Regular" : @".SF NS Mono",
             @".SFNSMono-RegularItalic" : @".SF NS Mono",
             @".SFNSMono-Semibold" : @".SF NS Mono",
             @".SFNSMono-SemiboldItalic" : @".SF NS Mono",
             @".SFNSRounded-Black" : @".SF NS Rounded",
             @".SFNSRounded-Bold" : @".SF NS Rounded",
             @".SFNSRounded-BoldG1" : @".SF NS Rounded",
             @".SFNSRounded-BoldG2" : @".SF NS Rounded",
             @".SFNSRounded-BoldG3" : @".SF NS Rounded",
             @".SFNSRounded-BoldG4" : @".SF NS Rounded",
             @".SFNSRounded-Heavy" : @".SF NS Rounded",
             @".SFNSRounded-HeavyG1" : @".SF NS Rounded",
             @".SFNSRounded-HeavyG2" : @".SF NS Rounded",
             @".SFNSRounded-HeavyG3" : @".SF NS Rounded",
             @".SFNSRounded-HeavyG4" : @".SF NS Rounded",
             @".SFNSRounded-Light" : @".SF NS Rounded",
             @".SFNSRounded-LightG1" : @".SF NS Rounded",
             @".SFNSRounded-LightG2" : @".SF NS Rounded",
             @".SFNSRounded-LightG3" : @".SF NS Rounded",
             @".SFNSRounded-LightG4" : @".SF NS Rounded",
             @".SFNSRounded-Medium" : @".SF NS Rounded",
             @".SFNSRounded-MediumG1" : @".SF NS Rounded",
             @".SFNSRounded-MediumG2" : @".SF NS Rounded",
             @".SFNSRounded-MediumG3" : @".SF NS Rounded",
             @".SFNSRounded-MediumG4" : @".SF NS Rounded",
             @".SFNSRounded-Regular" : @".SF NS Rounded",
             @".SFNSRounded-RegularG1" : @".SF NS Rounded",
             @".SFNSRounded-RegularG2" : @".SF NS Rounded",
             @".SFNSRounded-RegularG3" : @".SF NS Rounded",
             @".SFNSRounded-RegularG4" : @".SF NS Rounded",
             @".SFNSRounded-Semibold" : @".SF NS Rounded",
             @".SFNSRounded-SemiboldG1" : @".SF NS Rounded",
             @".SFNSRounded-SemiboldG2" : @".SF NS Rounded",
             @".SFNSRounded-SemiboldG3" : @".SF NS Rounded",
             @".SFNSRounded-SemiboldG4" : @".SF NS Rounded",
             @".SFNSRounded-Thin" : @".SF NS Rounded",
             @".SFNSRounded-ThinG1" : @".SF NS Rounded",
             @".SFNSRounded-ThinG2" : @".SF NS Rounded",
             @".SFNSRounded-ThinG3" : @".SF NS Rounded",
             @".SFNSRounded-ThinG4" : @".SF NS Rounded",
             @".SFNSRounded-Ultralight" : @".SF NS Rounded",
             @".SFNSRounded-UltralightG1" : @".SF NS Rounded",
             @".SFNSRounded-UltralightG2" : @".SF NS Rounded",
             @".SFNSRounded-UltralightG3" : @".SF NS Rounded",
             @".SFNSRounded-UltralightG4" : @".SF NS Rounded",
             @".SFNSRounded-Ultrathin" : @".SF NS Rounded",
             @".SFNSRounded-UltrathinG1" : @".SF NS Rounded",
             @".SFNSRounded-UltrathinG2" : @".SF NS Rounded",
             @".SFNSRounded-UltrathinG3" : @".SF NS Rounded",
             @".SFNSRounded-UltrathinG4" : @".SF NS Rounded",
             @".SFNSSymbols-Black" : @".SF NS Symbols",
             @".SFNSSymbols-Bold" : @".SF NS Symbols",
             @".SFNSSymbols-Heavy" : @".SF NS Symbols",
             @".SFNSSymbols-Light" : @".SF NS Symbols",
             @".SFNSSymbols-Medium" : @".SF NS Symbols",
             @".SFNSSymbols-Regular" : @".SF NS Symbols",
             @".SFNSSymbols-Semibold" : @".SF NS Symbols",
             @".SFNSSymbols-Thin" : @".SF NS Symbols",
             @".SFNSSymbols-Ultralight" : @".SF NS Symbols",
             @".SFNSText" : @".SF NS Text",
             @".SFNSText-Bold" : @".SF NS Text",
             @".SFNSText-BoldItalic" : @".SF NS Text",
             @".SFNSText-Heavy" : @".SF NS Text",
             @".SFNSText-HeavyItalic" : @".SF NS Text",
             @".SFNSText-Italic" : @".SF NS Text",
             @".SFNSText-Light" : @".SF NS Text",
             @".SFNSText-LightItalic" : @".SF NS Text",
             @".SFNSText-Medium" : @".SF NS Text",
             @".SFNSText-MediumItalic" : @".SF NS Text",
             @".SFNSText-Semibold" : @".SF NS Text",
             @".SFNSText-SemiboldItalic" : @".SF NS Text",
             @".SFNSTextCondensed-Bold" : @".SF NS Text Condensed",
             @".SFNSTextCondensed-Heavy" : @".SF NS Text Condensed",
             @".SFNSTextCondensed-Light" : @".SF NS Text Condensed",
             @".SFNSTextCondensed-Medium" : @".SF NS Text Condensed",
             @".SFNSTextCondensed-Regular" : @".SF NS Text Condensed",
             @".SFNSTextCondensed-Semibold" : @".SF NS Text Condensed",
             @".SanaPUA" : @".Sana PUA",
             @".SavoyeLetPlainCC" : @".Savoye LET CC.",
             @"AlBayan" : @"Al Bayan",
             @"AlBayan-Bold" : @"Al Bayan",
             @"AlNile" : @"Al Nile",
             @"AlNile-Bold" : @"Al Nile",
             @"AlTarikh" : @"Al Tarikh",
             @"AmericanTypewriter" : @"American Typewriter",
             @"AmericanTypewriter-Bold" : @"American Typewriter",
             @"AmericanTypewriter-Condensed" : @"American Typewriter",
             @"AmericanTypewriter-CondensedBold" : @"American Typewriter",
             @"AmericanTypewriter-CondensedLight" : @"American Typewriter",
             @"AmericanTypewriter-Light" : @"American Typewriter",
             @"AmericanTypewriter-Semibold" : @"American Typewriter",
             @"AndaleMono" : @"Andale Mono",
             @"Apple-Chancery" : @"Apple Chancery",
             @"AppleBraille" : @"Apple Braille",
             @"AppleBraille-Outline6Dot" : @"Apple Braille",
             @"AppleBraille-Outline8Dot" : @"Apple Braille",
             @"AppleBraille-Pinpoint6Dot" : @"Apple Braille",
             @"AppleBraille-Pinpoint8Dot" : @"Apple Braille",
             @"AppleColorEmoji" : @"Apple Color Emoji",
             @"AppleGothic" : @"AppleGothic",
             @"AppleMyungjo" : @"AppleMyungjo",
             @"AppleSDGothicNeo-Bold" : @"Apple SD Gothic Neo",
             @"AppleSDGothicNeo-ExtraBold" : @"Apple SD Gothic Neo",
             @"AppleSDGothicNeo-Heavy" : @"Apple SD Gothic Neo",
             @"AppleSDGothicNeo-Light" : @"Apple SD Gothic Neo",
             @"AppleSDGothicNeo-Medium" : @"Apple SD Gothic Neo",
             @"AppleSDGothicNeo-Regular" : @"Apple SD Gothic Neo",
             @"AppleSDGothicNeo-SemiBold" : @"Apple SD Gothic Neo",
             @"AppleSDGothicNeo-Thin" : @"Apple SD Gothic Neo",
             @"AppleSDGothicNeo-UltraLight" : @"Apple SD Gothic Neo",
             @"AppleSymbols" : @"Apple Symbols",
             @"AquaKana" : @".Aqua Kana",
             @"AquaKana-Bold" : @".Aqua Kana",
             @"Arial-Black" : @"Arial Black",
             @"Arial-BoldItalicMT" : @"Arial",
             @"Arial-BoldMT" : @"Arial",
             @"Arial-ItalicMT" : @"Arial",
             @"ArialHebrew" : @"Arial Hebrew",
             @"ArialHebrew-Bold" : @"Arial Hebrew",
             @"ArialHebrew-Light" : @"Arial Hebrew",
             @"ArialHebrewScholar" : @"Arial Hebrew Scholar",
             @"ArialHebrewScholar-Bold" : @"Arial Hebrew Scholar",
             @"ArialHebrewScholar-Light" : @"Arial Hebrew Scholar",
             @"ArialMT" : @"Arial",
             @"ArialNarrow" : @"Arial Narrow",
             @"ArialNarrow-Bold" : @"Arial Narrow",
             @"ArialNarrow-BoldItalic" : @"Arial Narrow",
             @"ArialNarrow-Italic" : @"Arial Narrow",
             @"ArialRoundedMTBold" : @"Arial Rounded MT Bold",
             @"ArialUnicodeMS" : @"Arial Unicode MS",
             @"Athelas-Bold" : @"Athelas",
             @"Athelas-BoldItalic" : @"Athelas",
             @"Athelas-Italic" : @"Athelas",
             @"Athelas-Regular" : @"Athelas",
             @"Avenir-Black" : @"Avenir",
             @"Avenir-BlackOblique" : @"Avenir",
             @"Avenir-Book" : @"Avenir",
             @"Avenir-BookOblique" : @"Avenir",
             @"Avenir-Heavy" : @"Avenir",
             @"Avenir-HeavyOblique" : @"Avenir",
             @"Avenir-Light" : @"Avenir",
             @"Avenir-LightOblique" : @"Avenir",
             @"Avenir-Medium" : @"Avenir",
             @"Avenir-MediumOblique" : @"Avenir",
             @"Avenir-Oblique" : @"Avenir",
             @"Avenir-Roman" : @"Avenir",
             @"AvenirNext-Bold" : @"Avenir Next",
             @"AvenirNext-BoldItalic" : @"Avenir Next",
             @"AvenirNext-DemiBold" : @"Avenir Next",
             @"AvenirNext-DemiBoldItalic" : @"Avenir Next",
             @"AvenirNext-Heavy" : @"Avenir Next",
             @"AvenirNext-HeavyItalic" : @"Avenir Next",
             @"AvenirNext-Italic" : @"Avenir Next",
             @"AvenirNext-Medium" : @"Avenir Next",
             @"AvenirNext-MediumItalic" : @"Avenir Next",
             @"AvenirNext-Regular" : @"Avenir Next",
             @"AvenirNext-UltraLight" : @"Avenir Next",
             @"AvenirNext-UltraLightItalic" : @"Avenir Next",
             @"AvenirNextCondensed-Bold" : @"Avenir Next Condensed",
             @"AvenirNextCondensed-BoldItalic" : @"Avenir Next Condensed",
             @"AvenirNextCondensed-DemiBold" : @"Avenir Next Condensed",
             @"AvenirNextCondensed-DemiBoldItalic" : @"Avenir Next Condensed",
             @"AvenirNextCondensed-Heavy" : @"Avenir Next Condensed",
             @"AvenirNextCondensed-HeavyItalic" : @"Avenir Next Condensed",
             @"AvenirNextCondensed-Italic" : @"Avenir Next Condensed",
             @"AvenirNextCondensed-Medium" : @"Avenir Next Condensed",
             @"AvenirNextCondensed-MediumItalic" : @"Avenir Next Condensed",
             @"AvenirNextCondensed-Regular" : @"Avenir Next Condensed",
             @"AvenirNextCondensed-UltraLight" : @"Avenir Next Condensed",
             @"AvenirNextCondensed-UltraLightItalic" : @"Avenir Next Condensed",
             @"Ayuthaya" : @"Ayuthaya",
             @"Baghdad" : @"Baghdad",
             @"BanglaMN" : @"Bangla MN",
             @"BanglaMN-Bold" : @"Bangla MN",
             @"BanglaSangamMN" : @"Bangla Sangam MN",
             @"BanglaSangamMN-Bold" : @"Bangla Sangam MN",
             @"Baskerville" : @"Baskerville",
             @"Baskerville-Bold" : @"Baskerville",
             @"Baskerville-BoldItalic" : @"Baskerville",
             @"Baskerville-Italic" : @"Baskerville",
             @"Baskerville-SemiBold" : @"Baskerville",
             @"Baskerville-SemiBoldItalic" : @"Baskerville",
             @"Beirut" : @"Beirut",
             @"BigCaslon-Medium" : @"Big Caslon",
             @"BodoniOrnamentsITCTT" : @"Bodoni Ornaments",
             @"BodoniSvtyTwoITCTT-Bold" : @"Bodoni 72",
             @"BodoniSvtyTwoITCTT-Book" : @"Bodoni 72",
             @"BodoniSvtyTwoITCTT-BookIta" : @"Bodoni 72",
             @"BodoniSvtyTwoOSITCTT-Bold" : @"Bodoni 72 Oldstyle",
             @"BodoniSvtyTwoOSITCTT-Book" : @"Bodoni 72 Oldstyle",
             @"BodoniSvtyTwoOSITCTT-BookIt" : @"Bodoni 72 Oldstyle",
             @"BodoniSvtyTwoSCITCTT-Book" : @"Bodoni 72 Smallcaps",
             @"BradleyHandITCTT-Bold" : @"Bradley Hand",
             @"BrushScriptMT" : @"Brush Script MT",
             @"Calibri" : @"Calibri",
             @"Calibri-Bold" : @"Calibri",
             @"Calibri-BoldItalic" : @"Calibri",
             @"Calibri-Italic" : @"Calibri",
             @"Chalkboard" : @"Chalkboard",
             @"Chalkboard-Bold" : @"Chalkboard",
             @"ChalkboardSE-Bold" : @"Chalkboard SE",
             @"ChalkboardSE-Light" : @"Chalkboard SE",
             @"ChalkboardSE-Regular" : @"Chalkboard SE",
             @"Chalkduster" : @"Chalkduster",
             @"Charter-Black" : @"Charter",
             @"Charter-BlackItalic" : @"Charter",
             @"Charter-Bold" : @"Charter",
             @"Charter-BoldItalic" : @"Charter",
             @"Charter-Italic" : @"Charter",
             @"Charter-Roman" : @"Charter",
             @"Cochin" : @"Cochin",
             @"Cochin-Bold" : @"Cochin",
             @"Cochin-BoldItalic" : @"Cochin",
             @"Cochin-Italic" : @"Cochin",
             @"ComicSansMS" : @"Comic Sans MS",
             @"ComicSansMS-Bold" : @"Comic Sans MS",
             @"Copperplate" : @"Copperplate",
             @"Copperplate-Bold" : @"Copperplate",
             @"Copperplate-Light" : @"Copperplate",
             @"CorsivaHebrew" : @"Corsiva Hebrew",
             @"CorsivaHebrew-Bold" : @"Corsiva Hebrew",
             @"Courier" : @"Courier",
             @"Courier-Bold" : @"Courier",
             @"Courier-BoldOblique" : @"Courier",
             @"Courier-Oblique" : @"Courier",
             @"CourierNewPS-BoldItalicMT" : @"Courier New",
             @"CourierNewPS-BoldMT" : @"Courier New",
             @"CourierNewPS-ItalicMT" : @"Courier New",
             @"CourierNewPSMT" : @"Courier New",
             @"DINAlternate-Bold" : @"DIN Alternate",
             @"DINCondensed-Bold" : @"DIN Condensed",
             @"Damascus" : @"Damascus",
             @"DamascusBold" : @"Damascus",
             @"DamascusLight" : @"Damascus",
             @"DamascusMedium" : @"Damascus",
             @"DamascusSemiBold" : @"Damascus",
             @"DecoTypeNaskh" : @"DecoType Naskh",
             @"DevanagariMT" : @"Devanagari MT",
             @"DevanagariMT-Bold" : @"Devanagari MT",
             @"DevanagariSangamMN" : @"Devanagari Sangam MN",
             @"DevanagariSangamMN-Bold" : @"Devanagari Sangam MN",
             @"Didot" : @"Didot",
             @"Didot-Bold" : @"Didot",
             @"Didot-Italic" : @"Didot",
             @"DiwanKufi" : @"Diwan Kufi",
             @"DiwanMishafi" : @"Mishafi",
             @"DiwanMishafiGold" : @"Mishafi Gold",
             @"DiwanThuluth" : @"Diwan Thuluth",
             @"EuphemiaUCAS" : @"Euphemia UCAS",
             @"EuphemiaUCAS-Bold" : @"Euphemia UCAS",
             @"EuphemiaUCAS-Italic" : @"Euphemia UCAS",
             @"Farah" : @"Farah",
             @"Farisi" : @"Farisi",
             @"Futura-Bold" : @"Futura",
             @"Futura-CondensedExtraBold" : @"Futura",
             @"Futura-CondensedMedium" : @"Futura",
             @"Futura-Medium" : @"Futura",
             @"Futura-MediumItalic" : @"Futura",
             @"GB18030Bitmap" : @"GB18030 Bitmap",
             @"Galvji" : @"Galvji",
             @"Galvji-Bold" : @"Galvji",
             @"Galvji-BoldOblique" : @"Galvji",
             @"Galvji-Oblique" : @"Galvji",
             @"GeezaPro" : @"Geeza Pro",
             @"GeezaPro-Bold" : @"Geeza Pro",
             @"Geneva" : @"Geneva",
             @"Georgia" : @"Georgia",
             @"Georgia-Bold" : @"Georgia",
             @"Georgia-BoldItalic" : @"Georgia",
             @"Georgia-Italic" : @"Georgia",
             @"GillSans" : @"Gill Sans",
             @"GillSans-Bold" : @"Gill Sans",
             @"GillSans-BoldItalic" : @"Gill Sans",
             @"GillSans-Italic" : @"Gill Sans",
             @"GillSans-Light" : @"Gill Sans",
             @"GillSans-LightItalic" : @"Gill Sans",
             @"GillSans-SemiBold" : @"Gill Sans",
             @"GillSans-SemiBoldItalic" : @"Gill Sans",
             @"GillSans-UltraBold" : @"Gill Sans",
             @"GujaratiMT" : @"Gujarati MT",
             @"GujaratiMT-Bold" : @"Gujarati MT",
             @"GujaratiSangamMN" : @"Gujarati Sangam MN",
             @"GujaratiSangamMN-Bold" : @"Gujarati Sangam MN",
             @"GurmukhiMN" : @"Gurmukhi MN",
             @"GurmukhiMN-Bold" : @"Gurmukhi MN",
             @"GurmukhiSangamMN" : @"Gurmukhi Sangam MN",
             @"GurmukhiSangamMN-Bold" : @"Gurmukhi Sangam MN",
             @"Helvetica" : @"Helvetica",
             @"Helvetica-Bold" : @"Helvetica",
             @"Helvetica-BoldOblique" : @"Helvetica",
             @"Helvetica-Light" : @"Helvetica",
             @"Helvetica-LightOblique" : @"Helvetica",
             @"Helvetica-Oblique" : @"Helvetica",
             @"HelveticaLTMM" : @".Helvetica LT MM",
             @"HelveticaNeue" : @"Helvetica Neue",
             @"HelveticaNeue-Bold" : @"Helvetica Neue",
             @"HelveticaNeue-BoldItalic" : @"Helvetica Neue",
             @"HelveticaNeue-CondensedBlack" : @"Helvetica Neue",
             @"HelveticaNeue-CondensedBold" : @"Helvetica Neue",
             @"HelveticaNeue-Italic" : @"Helvetica Neue",
             @"HelveticaNeue-Light" : @"Helvetica Neue",
             @"HelveticaNeue-LightItalic" : @"Helvetica Neue",
             @"HelveticaNeue-Medium" : @"Helvetica Neue",
             @"HelveticaNeue-MediumItalic" : @"Helvetica Neue",
             @"HelveticaNeue-Thin" : @"Helvetica Neue",
             @"HelveticaNeue-ThinItalic" : @"Helvetica Neue",
             @"HelveticaNeue-UltraLight" : @"Helvetica Neue",
             @"HelveticaNeue-UltraLightItalic" : @"Helvetica Neue",
             @"Herculanum" : @"Herculanum",
             @"HiraKakuPro-W3" : @"Hiragino Kaku Gothic Pro",
             @"HiraKakuPro-W6" : @"Hiragino Kaku Gothic Pro",
             @"HiraKakuProN-W3" : @"Hiragino Kaku Gothic ProN",
             @"HiraKakuProN-W6" : @"Hiragino Kaku Gothic ProN",
             @"HiraKakuStd-W8" : @"Hiragino Kaku Gothic Std",
             @"HiraKakuStdN-W8" : @"Hiragino Kaku Gothic StdN",
             @"HiraMaruPro-W4" : @"Hiragino Maru Gothic Pro",
             @"HiraMaruProN-W4" : @"Hiragino Maru Gothic ProN",
             @"HiraMinPro-W3" : @"Hiragino Mincho Pro",
             @"HiraMinPro-W6" : @"Hiragino Mincho Pro",
             @"HiraMinProN-W3" : @"Hiragino Mincho ProN",
             @"HiraMinProN-W6" : @"Hiragino Mincho ProN",
             @"HiraginoSans-W0" : @"Hiragino Sans",
             @"HiraginoSans-W1" : @"Hiragino Sans",
             @"HiraginoSans-W2" : @"Hiragino Sans",
             @"HiraginoSans-W3" : @"Hiragino Sans",
             @"HiraginoSans-W4" : @"Hiragino Sans",
             @"HiraginoSans-W5" : @"Hiragino Sans",
             @"HiraginoSans-W6" : @"Hiragino Sans",
             @"HiraginoSans-W7" : @"Hiragino Sans",
             @"HiraginoSans-W8" : @"Hiragino Sans",
             @"HiraginoSans-W9" : @"Hiragino Sans",
             @"HiraginoSansGB-W3" : @"Hiragino Sans GB",
             @"HiraginoSansGB-W6" : @"Hiragino Sans GB",
             @"HoeflerText-Black" : @"Hoefler Text",
             @"HoeflerText-BlackItalic" : @"Hoefler Text",
             @"HoeflerText-Italic" : @"Hoefler Text",
             @"HoeflerText-Ornaments" : @"Hoefler Text",
             @"HoeflerText-Regular" : @"Hoefler Text",
             @"ITFDevanagari-Bold" : @"ITF Devanagari",
             @"ITFDevanagari-Book" : @"ITF Devanagari",
             @"ITFDevanagari-Demi" : @"ITF Devanagari",
             @"ITFDevanagari-Light" : @"ITF Devanagari",
             @"ITFDevanagari-Medium" : @"ITF Devanagari",
             @"ITFDevanagariMarathi-Bold" : @"ITF Devanagari Marathi",
             @"ITFDevanagariMarathi-Book" : @"ITF Devanagari Marathi",
             @"ITFDevanagariMarathi-Demi" : @"ITF Devanagari Marathi",
             @"ITFDevanagariMarathi-Light" : @"ITF Devanagari Marathi",
             @"ITFDevanagariMarathi-Medium" : @"ITF Devanagari Marathi",
             @"Impact" : @"Impact",
             @"InaiMathi" : @"InaiMathi",
             @"InaiMathi-Bold" : @"InaiMathi",
             @"IowanOldStyle-Black" : @"Iowan Old Style",
             @"IowanOldStyle-BlackItalic" : @"Iowan Old Style",
             @"IowanOldStyle-Bold" : @"Iowan Old Style",
             @"IowanOldStyle-BoldItalic" : @"Iowan Old Style",
             @"IowanOldStyle-Italic" : @"Iowan Old Style",
             @"IowanOldStyle-Roman" : @"Iowan Old Style",
             @"IowanOldStyle-Titling" : @"Iowan Old Style",
             @"Kailasa" : @"Kailasa",
             @"Kailasa-Bold" : @"Kailasa",
             @"KannadaMN" : @"Kannada MN",
             @"KannadaMN-Bold" : @"Kannada MN",
             @"KannadaSangamMN" : @"Kannada Sangam MN",
             @"KannadaSangamMN-Bold" : @"Kannada Sangam MN",
             @"Kefa-Bold" : @"Kefa",
             @"Kefa-Regular" : @"Kefa",
             @"KhmerMN" : @"Khmer MN",
             @"KhmerMN-Bold" : @"Khmer MN",
             @"KhmerSangamMN" : @"Khmer Sangam MN",
             @"KohinoorBangla-Bold" : @"Kohinoor Bangla",
             @"KohinoorBangla-Light" : @"Kohinoor Bangla",
             @"KohinoorBangla-Medium" : @"Kohinoor Bangla",
             @"KohinoorBangla-Regular" : @"Kohinoor Bangla",
             @"KohinoorBangla-Semibold" : @"Kohinoor Bangla",
             @"KohinoorDevanagari-Bold" : @"Kohinoor Devanagari",
             @"KohinoorDevanagari-Light" : @"Kohinoor Devanagari",
             @"KohinoorDevanagari-Medium" : @"Kohinoor Devanagari",
             @"KohinoorDevanagari-Regular" : @"Kohinoor Devanagari",
             @"KohinoorDevanagari-Semibold" : @"Kohinoor Devanagari",
             @"KohinoorGujarati-Bold" : @"Kohinoor Gujarati",
             @"KohinoorGujarati-Light" : @"Kohinoor Gujarati",
             @"KohinoorGujarati-Medium" : @"Kohinoor Gujarati",
             @"KohinoorGujarati-Regular" : @"Kohinoor Gujarati",
             @"KohinoorGujarati-Semibold" : @"Kohinoor Gujarati",
             @"KohinoorTelugu-Bold" : @"Kohinoor Telugu",
             @"KohinoorTelugu-Light" : @"Kohinoor Telugu",
             @"KohinoorTelugu-Medium" : @"Kohinoor Telugu",
             @"KohinoorTelugu-Regular" : @"Kohinoor Telugu",
             @"KohinoorTelugu-Semibold" : @"Kohinoor Telugu",
             @"Kokonor" : @"Kokonor",
             @"Krungthep" : @"Krungthep",
             @"KufiStandardGK" : @"KufiStandardGK",
             @"LaoMN" : @"Lao MN",
             @"LaoMN-Bold" : @"Lao MN",
             @"LaoSangamMN" : @"Lao Sangam MN",
             @"LastResort" : @".LastResort",
             @"LucidaGrande" : @"Lucida Grande",
             @"LucidaGrande-Bold" : @"Lucida Grande",
             @"Luminari-Regular" : @"Luminari",
             @"MalayalamMN" : @"Malayalam MN",
             @"MalayalamMN-Bold" : @"Malayalam MN",
             @"MalayalamSangamMN" : @"Malayalam Sangam MN",
             @"MalayalamSangamMN-Bold" : @"Malayalam Sangam MN",
             @"Marion-Bold" : @"Marion",
             @"Marion-Italic" : @"Marion",
             @"Marion-Regular" : @"Marion",
             @"MarkerFelt-Thin" : @"Marker Felt",
             @"MarkerFelt-Wide" : @"Marker Felt",
             @"Menlo-Bold" : @"Menlo",
             @"Menlo-BoldItalic" : @"Menlo",
             @"Menlo-Italic" : @"Menlo",
             @"Menlo-Regular" : @"Menlo",
             @"MicrosoftSansSerif" : @"Microsoft Sans Serif",
             @"Monaco" : @"Monaco",
             @"MonotypeGurmukhi" : @"Gurmukhi MT",
             @"Mshtakan" : @"Mshtakan",
             @"MshtakanBold" : @"Mshtakan",
             @"MshtakanBoldOblique" : @"Mshtakan",
             @"MshtakanOblique" : @"Mshtakan",
             @"MuktaMahee-Bold" : @"Mukta Mahee",
             @"MuktaMahee-ExtraBold" : @"Mukta Mahee",
             @"MuktaMahee-ExtraLight" : @"Mukta Mahee",
             @"MuktaMahee-Light" : @"Mukta Mahee",
             @"MuktaMahee-Medium" : @"Mukta Mahee",
             @"MuktaMahee-Regular" : @"Mukta Mahee",
             @"MuktaMahee-SemiBold" : @"Mukta Mahee",
             @"Muna" : @"Muna",
             @"MunaBlack" : @"Muna",
             @"MunaBold" : @"Muna",
             @"MyanmarMN" : @"Myanmar MN",
             @"MyanmarMN-Bold" : @"Myanmar MN",
             @"MyanmarSangamMN" : @"Myanmar Sangam MN",
             @"MyanmarSangamMN-Bold" : @"Myanmar Sangam MN",
             @"Nadeem" : @"Nadeem",
             @"NewPeninimMT" : @"New Peninim MT",
             @"NewPeninimMT-Bold" : @"New Peninim MT",
             @"NewPeninimMT-BoldInclined" : @"New Peninim MT",
             @"NewPeninimMT-Inclined" : @"New Peninim MT",
             @"Noteworthy-Bold" : @"Noteworthy",
             @"Noteworthy-Light" : @"Noteworthy",
             @"NotoNastaliqUrdu" : @"Noto Nastaliq Urdu",
             @"NotoNastaliqUrdu-Bold" : @"Noto Nastaliq Urdu",
             @"NotoSansArmenian-Black" : @"Noto Sans Armenian",
             @"NotoSansArmenian-Bold" : @"Noto Sans Armenian",
             @"NotoSansArmenian-ExtraBold" : @"Noto Sans Armenian",
             @"NotoSansArmenian-ExtraLight" : @"Noto Sans Armenian",
             @"NotoSansArmenian-Light" : @"Noto Sans Armenian",
             @"NotoSansArmenian-Medium" : @"Noto Sans Armenian",
             @"NotoSansArmenian-Regular" : @"Noto Sans Armenian",
             @"NotoSansArmenian-SemiBold" : @"Noto Sans Armenian",
             @"NotoSansArmenian-Thin" : @"Noto Sans Armenian",
             @"NotoSansAvestan-Regular" : @"Noto Sans Avestan",
             @"NotoSansBamum-Regular" : @"Noto Sans Bamum",
             @"NotoSansBatak-Regular" : @"Noto Sans Batak",
             @"NotoSansBrahmi-Regular" : @"Noto Sans Brahmi",
             @"NotoSansBuginese-Regular" : @"Noto Sans Buginese",
             @"NotoSansBuhid-Regular" : @"Noto Sans Buhid",
             @"NotoSansCarian-Regular" : @"Noto Sans Carian",
             @"NotoSansChakma-Regular" : @"Noto Sans Chakma",
             @"NotoSansCham-Regular" : @"Noto Sans Cham",
             @"NotoSansCoptic-Regular" : @"Noto Sans Coptic",
             @"NotoSansCuneiform-Regular" : @"Noto Sans Cuneiform",
             @"NotoSansCypriot-Regular" : @"Noto Sans Cypriot",
             @"NotoSansEgyptianHieroglyphs-Regular" : @"Noto Sans Egyptian Hieroglyphs",
             @"NotoSansGlagolitic-Regular" : @"Noto Sans Glagolitic",
             @"NotoSansGothic-Regular" : @"Noto Sans Gothic",
             @"NotoSansHanunoo-Regular" : @"Noto Sans Hanunoo",
             @"NotoSansImperialAramaic-Regular" : @"Noto Sans Imperial Aramaic",
             @"NotoSansInscriptionalPahlavi-Regular" : @"Noto Sans Inscriptional Pahlavi",
             @"NotoSansInscriptionalParthian-Regular" : @"Noto Sans Inscriptional Parthian",
             @"NotoSansJavanese-Regular" : @"Noto Sans Javanese",
             @"NotoSansKaithi-Regular" : @"Noto Sans Kaithi",
             @"NotoSansKannada-Black" : @"Noto Sans Kannada",
             @"NotoSansKannada-Bold" : @"Noto Sans Kannada",
             @"NotoSansKannada-ExtraBold" : @"Noto Sans Kannada",
             @"NotoSansKannada-ExtraLight" : @"Noto Sans Kannada",
             @"NotoSansKannada-Light" : @"Noto Sans Kannada",
             @"NotoSansKannada-Medium" : @"Noto Sans Kannada",
             @"NotoSansKannada-Regular" : @"Noto Sans Kannada",
             @"NotoSansKannada-SemiBold" : @"Noto Sans Kannada",
             @"NotoSansKannada-Thin" : @"Noto Sans Kannada",
             @"NotoSansKayahLi-Regular" : @"Noto Sans Kayah Li",
             @"NotoSansKharoshthi-Regular" : @"Noto Sans Kharoshthi",
             @"NotoSansLepcha-Regular" : @"Noto Sans Lepcha",
             @"NotoSansLimbu-Regular" : @"Noto Sans Limbu",
             @"NotoSansLinearB-Regular" : @"Noto Sans Linear B",
             @"NotoSansLisu-Regular" : @"Noto Sans Lisu",
             @"NotoSansLycian-Regular" : @"Noto Sans Lycian",
             @"NotoSansLydian-Regular" : @"Noto Sans Lydian",
             @"NotoSansMandaic-Regular" : @"Noto Sans Mandaic",
             @"NotoSansMeeteiMayek-Regular" : @"Noto Sans Meetei Mayek",
             @"NotoSansMongolian" : @"Noto Sans Mongolian",
             @"NotoSansMyanmar-Black" : @"Noto Sans Myanmar",
             @"NotoSansMyanmar-Bold" : @"Noto Sans Myanmar",
             @"NotoSansMyanmar-ExtraBold" : @"Noto Sans Myanmar",
             @"NotoSansMyanmar-ExtraLight" : @"Noto Sans Myanmar",
             @"NotoSansMyanmar-Light" : @"Noto Sans Myanmar",
             @"NotoSansMyanmar-Medium" : @"Noto Sans Myanmar",
             @"NotoSansMyanmar-Regular" : @"Noto Sans Myanmar",
             @"NotoSansMyanmar-SemiBold" : @"Noto Sans Myanmar",
             @"NotoSansMyanmar-Thin" : @"Noto Sans Myanmar",
             @"NotoSansNKo-Regular" : @"Noto Sans NKo",
             @"NotoSansNewTaiLue-Regular" : @"Noto Sans New Tai Lue",
             @"NotoSansOgham-Regular" : @"Noto Sans Ogham",
             @"NotoSansOlChiki-Regular" : @"Noto Sans Ol Chiki",
             @"NotoSansOldItalic-Regular" : @"Noto Sans Old Italic",
             @"NotoSansOldPersian-Regular" : @"Noto Sans Old Persian",
             @"NotoSansOldSouthArabian-Regular" : @"Noto Sans Old South Arabian",
             @"NotoSansOldTurkic-Regular" : @"Noto Sans Old Turkic",
             @"NotoSansOriya" : @"Noto Sans Oriya",
             @"NotoSansOriya-Bold" : @"Noto Sans Oriya",
             @"NotoSansOsmanya-Regular" : @"Noto Sans Osmanya",
             @"NotoSansPhagsPa-Regular" : @"Noto Sans PhagsPa",
             @"NotoSansPhoenician-Regular" : @"Noto Sans Phoenician",
             @"NotoSansRejang-Regular" : @"Noto Sans Rejang",
             @"NotoSansRunic-Regular" : @"Noto Sans Runic",
             @"NotoSansSamaritan-Regular" : @"Noto Sans Samaritan",
             @"NotoSansSaurashtra-Regular" : @"Noto Sans Saurashtra",
             @"NotoSansShavian-Regular" : @"Noto Sans Shavian",
             @"NotoSansSundanese-Regular" : @"Noto Sans Sundanese",
             @"NotoSansSylotiNagri-Regular" : @"Noto Sans Syloti Nagri",
             @"NotoSansSyriac-Regular" : @"Noto Sans Syriac",
             @"NotoSansTagalog-Regular" : @"Noto Sans Tagalog",
             @"NotoSansTagbanwa-Regular" : @"Noto Sans Tagbanwa",
             @"NotoSansTaiLe-Regular" : @"Noto Sans Tai Le",
             @"NotoSansTaiTham" : @"Noto Sans Tai Tham",
             @"NotoSansTaiViet-Regular" : @"Noto Sans Tai Viet",
             @"NotoSansThaana-Regular" : @"Noto Sans Thaana",
             @"NotoSansTifinagh-Regular" : @"Noto Sans Tifinagh",
             @"NotoSansUgaritic-Regular" : @"Noto Sans Ugaritic",
             @"NotoSansVai-Regular" : @"Noto Sans Vai",
             @"NotoSansYi-Regular" : @"Noto Sans Yi",
             @"NotoSansZawgyi-Black" : @"Noto Sans Zawgyi",
             @"NotoSansZawgyi-Bold" : @"Noto Sans Zawgyi",
             @"NotoSansZawgyi-ExtraBold" : @"Noto Sans Zawgyi",
             @"NotoSansZawgyi-ExtraLight" : @"Noto Sans Zawgyi",
             @"NotoSansZawgyi-Light" : @"Noto Sans Zawgyi",
             @"NotoSansZawgyi-Medium" : @"Noto Sans Zawgyi",
             @"NotoSansZawgyi-Regular" : @"Noto Sans Zawgyi",
             @"NotoSansZawgyi-SemiBold" : @"Noto Sans Zawgyi",
             @"NotoSansZawgyi-Thin" : @"Noto Sans Zawgyi",
             @"NotoSerifBalinese-Regular" : @"Noto Serif Balinese",
             @"NotoSerifMyanmar-Black" : @"Noto Serif Myanmar",
             @"NotoSerifMyanmar-Bold" : @"Noto Serif Myanmar",
             @"NotoSerifMyanmar-ExtraBold" : @"Noto Serif Myanmar",
             @"NotoSerifMyanmar-ExtraLight" : @"Noto Serif Myanmar",
             @"NotoSerifMyanmar-Light" : @"Noto Serif Myanmar",
             @"NotoSerifMyanmar-Medium" : @"Noto Serif Myanmar",
             @"NotoSerifMyanmar-Regular" : @"Noto Serif Myanmar",
             @"NotoSerifMyanmar-SemiBold" : @"Noto Serif Myanmar",
             @"NotoSerifMyanmar-Thin" : @"Noto Serif Myanmar",
             @"Optima-Bold" : @"Optima",
             @"Optima-BoldItalic" : @"Optima",
             @"Optima-ExtraBlack" : @"Optima",
             @"Optima-Italic" : @"Optima",
             @"Optima-Regular" : @"Optima",
             @"OriyaMN" : @"Oriya MN",
             @"OriyaMN-Bold" : @"Oriya MN",
             @"OriyaSangamMN" : @"Oriya Sangam MN",
             @"OriyaSangamMN-Bold" : @"Oriya Sangam MN",
             @"PTMono-Bold" : @"PT Mono",
             @"PTMono-Regular" : @"PT Mono",
             @"PTSans-Bold" : @"PT Sans",
             @"PTSans-BoldItalic" : @"PT Sans",
             @"PTSans-Caption" : @"PT Sans Caption",
             @"PTSans-CaptionBold" : @"PT Sans Caption",
             @"PTSans-Italic" : @"PT Sans",
             @"PTSans-Narrow" : @"PT Sans Narrow",
             @"PTSans-NarrowBold" : @"PT Sans Narrow",
             @"PTSans-Regular" : @"PT Sans",
             @"PTSerif-Bold" : @"PT Serif",
             @"PTSerif-BoldItalic" : @"PT Serif",
             @"PTSerif-Caption" : @"PT Serif Caption",
             @"PTSerif-CaptionItalic" : @"PT Serif Caption",
             @"PTSerif-Italic" : @"PT Serif",
             @"PTSerif-Regular" : @"PT Serif",
             @"Palatino-Bold" : @"Palatino",
             @"Palatino-BoldItalic" : @"Palatino",
             @"Palatino-Italic" : @"Palatino",
             @"Palatino-Roman" : @"Palatino",
             @"Papyrus" : @"Papyrus",
             @"Papyrus-Condensed" : @"Papyrus",
             @"Phosphate-Inline" : @"Phosphate",
             @"Phosphate-Solid" : @"Phosphate",
             @"PingFangHK-Light" : @"PingFang HK",
             @"PingFangHK-Medium" : @"PingFang HK",
             @"PingFangHK-Regular" : @"PingFang HK",
             @"PingFangHK-Semibold" : @"PingFang HK",
             @"PingFangHK-Thin" : @"PingFang HK",
             @"PingFangHK-Ultralight" : @"PingFang HK",
             @"PingFangSC-Light" : @"PingFang SC",
             @"PingFangSC-Medium" : @"PingFang SC",
             @"PingFangSC-Regular" : @"PingFang SC",
             @"PingFangSC-Semibold" : @"PingFang SC",
             @"PingFangSC-Thin" : @"PingFang SC",
             @"PingFangSC-Ultralight" : @"PingFang SC",
             @"PingFangTC-Light" : @"PingFang TC",
             @"PingFangTC-Medium" : @"PingFang TC",
             @"PingFangTC-Regular" : @"PingFang TC",
             @"PingFangTC-Semibold" : @"PingFang TC",
             @"PingFangTC-Thin" : @"PingFang TC",
             @"PingFangTC-Ultralight" : @"PingFang TC",
             @"PlantagenetCherokee" : @"Plantagenet Cherokee",
             @"Raanana" : @"Raanana",
             @"RaananaBold" : @"Raanana",
             @"Rockwell-Bold" : @"Rockwell",
             @"Rockwell-BoldItalic" : @"Rockwell",
             @"Rockwell-Italic" : @"Rockwell",
             @"Rockwell-Regular" : @"Rockwell",
             @"SFMono-Bold" : @"SF Mono",
             @"SFMono-BoldItalic" : @"SF Mono",
             @"SFMono-Regular" : @"SF Mono",
             @"SFMono-RegularItalic" : @"SF Mono",
             @"STHeitiSC-Light" : @"Heiti SC",
             @"STHeitiSC-Medium" : @"Heiti SC",
             @"STHeitiTC-Light" : @"Heiti TC",
             @"STHeitiTC-Medium" : @"Heiti TC",
             @"STIXGeneral-Bold" : @"STIXGeneral",
             @"STIXGeneral-BoldItalic" : @"STIXGeneral",
             @"STIXGeneral-Italic" : @"STIXGeneral",
             @"STIXGeneral-Regular" : @"STIXGeneral",
             @"STIXIntegralsD-Bold" : @"STIXIntegralsD",
             @"STIXIntegralsD-Regular" : @"STIXIntegralsD",
             @"STIXIntegralsSm-Bold" : @"STIXIntegralsSm",
             @"STIXIntegralsSm-Regular" : @"STIXIntegralsSm",
             @"STIXIntegralsUp-Bold" : @"STIXIntegralsUp",
             @"STIXIntegralsUp-Regular" : @"STIXIntegralsUp",
             @"STIXIntegralsUpD-Bold" : @"STIXIntegralsUpD",
             @"STIXIntegralsUpD-Regular" : @"STIXIntegralsUpD",
             @"STIXIntegralsUpSm-Bold" : @"STIXIntegralsUpSm",
             @"STIXIntegralsUpSm-Regular" : @"STIXIntegralsUpSm",
             @"STIXNonUnicode-Bold" : @"STIXNonUnicode",
             @"STIXNonUnicode-BoldItalic" : @"STIXNonUnicode",
             @"STIXNonUnicode-Italic" : @"STIXNonUnicode",
             @"STIXNonUnicode-Regular" : @"STIXNonUnicode",
             @"STIXSizeFiveSym-Regular" : @"STIXSizeFiveSym",
             @"STIXSizeFourSym-Bold" : @"STIXSizeFourSym",
             @"STIXSizeFourSym-Regular" : @"STIXSizeFourSym",
             @"STIXSizeOneSym-Bold" : @"STIXSizeOneSym",
             @"STIXSizeOneSym-Regular" : @"STIXSizeOneSym",
             @"STIXSizeThreeSym-Bold" : @"STIXSizeThreeSym",
             @"STIXSizeThreeSym-Regular" : @"STIXSizeThreeSym",
             @"STIXSizeTwoSym-Bold" : @"STIXSizeTwoSym",
             @"STIXSizeTwoSym-Regular" : @"STIXSizeTwoSym",
             @"STIXVariants-Bold" : @"STIXVariants",
             @"STIXVariants-Regular" : @"STIXVariants",
             @"STSong" : @"STSong",
             @"STSongti-SC-Black" : @"Songti SC",
             @"STSongti-SC-Bold" : @"Songti SC",
             @"STSongti-SC-Light" : @"Songti SC",
             @"STSongti-SC-Regular" : @"Songti SC",
             @"STSongti-TC-Bold" : @"Songti TC",
             @"STSongti-TC-Light" : @"Songti TC",
             @"STSongti-TC-Regular" : @"Songti TC",
             @"Sana" : @"Sana",
             @"Sathu" : @"Sathu",
             @"SavoyeLetPlain" : @"Savoye LET",
             @"Seravek" : @"Seravek",
             @"Seravek-Bold" : @"Seravek",
             @"Seravek-BoldItalic" : @"Seravek",
             @"Seravek-ExtraLight" : @"Seravek",
             @"Seravek-ExtraLightItalic" : @"Seravek",
             @"Seravek-Italic" : @"Seravek",
             @"Seravek-Light" : @"Seravek",
             @"Seravek-LightItalic" : @"Seravek",
             @"Seravek-Medium" : @"Seravek",
             @"Seravek-MediumItalic" : @"Seravek",
             @"ShreeDev0714" : @"Shree Devanagari 714",
             @"ShreeDev0714-Bold" : @"Shree Devanagari 714",
             @"ShreeDev0714-BoldItalic" : @"Shree Devanagari 714",
             @"ShreeDev0714-Italic" : @"Shree Devanagari 714",
             @"SignPainter-HouseScript" : @"SignPainter",
             @"SignPainter-HouseScriptSemibold" : @"SignPainter",
             @"Silom" : @"Silom",
             @"SinhalaMN" : @"Sinhala MN",
             @"SinhalaMN-Bold" : @"Sinhala MN",
             @"SinhalaSangamMN" : @"Sinhala Sangam MN",
             @"SinhalaSangamMN-Bold" : @"Sinhala Sangam MN",
             @"Skia-Regular" : @"Skia",
             @"Skia-Regular_Black" : @"Skia",
             @"Skia-Regular_Black-Condensed" : @"Skia",
             @"Skia-Regular_Black-Extended" : @"Skia",
             @"Skia-Regular_Bold" : @"Skia",
             @"Skia-Regular_Condensed" : @"Skia",
             @"Skia-Regular_Extended" : @"Skia",
             @"Skia-Regular_Light" : @"Skia",
             @"Skia-Regular_Light-Condensed" : @"Skia",
             @"Skia-Regular_Light-Extended" : @"Skia",
             @"SnellRoundhand" : @"Snell Roundhand",
             @"SnellRoundhand-Black" : @"Snell Roundhand",
             @"SnellRoundhand-Bold" : @"Snell Roundhand",
             @"SukhumvitSet-Bold" : @"Sukhumvit Set",
             @"SukhumvitSet-Light" : @"Sukhumvit Set",
             @"SukhumvitSet-Medium" : @"Sukhumvit Set",
             @"SukhumvitSet-SemiBold" : @"Sukhumvit Set",
             @"SukhumvitSet-Text" : @"Sukhumvit Set",
             @"SukhumvitSet-Thin" : @"Sukhumvit Set",
             @"Superclarendon-Black" : @"Superclarendon",
             @"Superclarendon-BlackItalic" : @"Superclarendon",
             @"Superclarendon-Bold" : @"Superclarendon",
             @"Superclarendon-BoldItalic" : @"Superclarendon",
             @"Superclarendon-Italic" : @"Superclarendon",
             @"Superclarendon-Light" : @"Superclarendon",
             @"Superclarendon-LightItalic" : @"Superclarendon",
             @"Superclarendon-Regular" : @"Superclarendon",
             @"Symbol" : @"Symbol",
             @"Tahoma" : @"Tahoma",
             @"Tahoma-Bold" : @"Tahoma",
             @"TamilMN" : @"Tamil MN",
             @"TamilMN-Bold" : @"Tamil MN",
             @"TamilSangamMN" : @"Tamil Sangam MN",
             @"TamilSangamMN-Bold" : @"Tamil Sangam MN",
             @"TeluguMN" : @"Telugu MN",
             @"TeluguMN-Bold" : @"Telugu MN",
             @"TeluguSangamMN" : @"Telugu Sangam MN",
             @"TeluguSangamMN-Bold" : @"Telugu Sangam MN",
             @"Thonburi" : @"Thonburi",
             @"Thonburi-Bold" : @"Thonburi",
             @"Thonburi-Light" : @"Thonburi",
             @"Times-Bold" : @"Times",
             @"Times-BoldItalic" : @"Times",
             @"Times-Italic" : @"Times",
             @"Times-Roman" : @"Times",
             @"TimesLTMM" : @".Times LT MM",
             @"TimesNewRomanPS-BoldItalicMT" : @"Times New Roman",
             @"TimesNewRomanPS-BoldMT" : @"Times New Roman",
             @"TimesNewRomanPS-ItalicMT" : @"Times New Roman",
             @"TimesNewRomanPSMT" : @"Times New Roman",
             @"Trattatello" : @"Trattatello",
             @"Trebuchet-BoldItalic" : @"Trebuchet MS",
             @"TrebuchetMS" : @"Trebuchet MS",
             @"TrebuchetMS-Bold" : @"Trebuchet MS",
             @"TrebuchetMS-Italic" : @"Trebuchet MS",
             @"Verdana" : @"Verdana",
             @"Verdana-Bold" : @"Verdana",
             @"Verdana-BoldItalic" : @"Verdana",
             @"Verdana-Italic" : @"Verdana",
             @"Waseem" : @"Waseem",
             @"WaseemLight" : @"Waseem",
             @"Webdings" : @"Webdings",
             @"Wingdings-Regular" : @"Wingdings",
             @"Wingdings2" : @"Wingdings 2",
             @"Wingdings3" : @"Wingdings 3",
             @"ZapfDingbatsITC" : @"Zapf Dingbats",
             @"Zapfino" : @"Zapfino",

             // JetBrains fonts
             @"DroidSans" : @"Droid Sans",
             @"DroidSans-Bold" : @"Droid Sans",
             @"DroidSansMono" : @"Droid Sans Mono",
             @"DroidSansMonoDotted" : @"Droid Sans Mono Dotted",
             @"DroidSansMonoSlashed" : @"Droid Sans Mono Slashed",
             @"DroidSerif" : @"Droid Serif",
             @"DroidSerif-Bold" : @"Droid Serif",
             @"DroidSerif-BoldItalic" : @"Droid Serif",
             @"DroidSerif-Italic" : @"Droid Serif",
             @"FiraCode-Bold" : @"Fira Code",
             @"FiraCode-Light" : @"Fira Code",
             @"FiraCode-Medium" : @"Fira Code",
             @"FiraCode-Regular" : @"Fira Code",
             @"FiraCode-Retina" : @"Fira Code",
             @"Inconsolata" : @"Inconsolata",
             @"JetBrainsMono-Bold" : @"JetBrains Mono",
             @"JetBrainsMono-Regular" : @"JetBrains Mono",
             @"JetBrainsMono-Italic" : @"JetBrains Mono",
             @"JetBrainsMono-BoldItalic" : @"JetBrains Mono",
             @"Roboto-Light" : @"Roboto",
             @"Roboto-Thin" : @"Roboto",
             @"SourceCodePro-Bold" : @"Source Code Pro",
             @"SourceCodePro-BoldIt" : @"Source Code Pro",
             @"SourceCodePro-It" : @"Source Code Pro",
             @"SourceCodePro-Regular" : @"Source Code Pro",
             };
}

static bool
isVisibleFamily(NSString *familyName) {
    return familyName != nil &&
    ([familyName characterAtIndex:0] != '.' ||
     [familyName isEqualToString:@".SF NS Text"] ||
     [familyName isEqualToString:@".SF NS Display"] ||
     // Catalina
     [familyName isEqualToString:@".SF NS Mono"] ||
     [familyName isEqualToString:@".SF NS"]);
}

static NSArray*
GetFilteredFonts()
{
    if (sFilteredFonts == nil) {
        NSFontManager *fontManager = [NSFontManager sharedFontManager];
        NSUInteger fontCount = [[fontManager availableFonts] count];

        NSMutableArray *allFonts = [[NSMutableArray alloc] initWithCapacity:fontCount];
        NSMutableDictionary* fontFamilyTable = [[NSMutableDictionary alloc] initWithCapacity:fontCount];
        NSArray *allFamilies = [fontManager availableFontFamilies];

        NSUInteger familyCount = [allFamilies count];

        NSUInteger familyIndex;
        for (familyIndex = 0; familyIndex < familyCount; familyIndex++) {
            NSString *family = [allFamilies objectAtIndex:familyIndex];

            if ((family == nil) || [family characterAtIndex:0] == '.') {
                continue;
            }

            NSArray *fontFaces = [fontManager availableMembersOfFontFamily:family];
            NSUInteger faceCount = [fontFaces count];

            NSUInteger faceIndex;
            for (faceIndex = 0; faceIndex < faceCount; faceIndex++) {
                NSString* face = [[fontFaces objectAtIndex:faceIndex] objectAtIndex:0];
                if (face != nil) {
                    [allFonts addObject:face];
                    [fontFamilyTable setObject:family forKey:face];
                }
            }
        }

        /*
         * JavaFX registers these fonts and so JDK needs to do so as well.
         * If this isn't done we will have mis-matched rendering, since
         * although these may include fonts that are enumerated normally
         * they also demonstrably includes fonts that are not.
         */
        addFont(kCTFontUIFontSystem, allFonts, fontFamilyTable);
        addFont(kCTFontUIFontEmphasizedSystem, allFonts, fontFamilyTable);

        sFilteredFonts = allFonts;
        sFontFamilyTable = fontFamilyTable;
    }

    return sFilteredFonts;
}

#pragma mark --- sun.font.CFontManager JNI ---

static OSStatus CreateFSRef(FSRef *myFSRefPtr, NSString *inPath)
{
    return FSPathMakeRef((UInt8 *)[inPath fileSystemRepresentation],
                         myFSRefPtr, NULL);
}

/*
 * Class:     sun_font_CFontManager
 * Method:    loadNativeFonts
 * Signature: ()V
 */
JNIEXPORT void JNICALL
Java_sun_font_CFontManager_loadNativeFonts
    (JNIEnv *env, jobject jthis)
{
    DECLARE_CLASS(jc_CFontManager, "sun/font/CFontManager");
    DECLARE_METHOD(jm_registerFont, jc_CFontManager, "registerFont", "(Ljava/lang/String;Ljava/lang/String;)V");

    jint num = 0;

JNI_COCOA_ENTER(env);

    NSArray *filteredFonts = GetFilteredFonts();
    num = (jint)[filteredFonts count];

    jint i;
    for (i = 0; i < num; i++) {
        NSString *fontname = [filteredFonts objectAtIndex:i];
        jobject jFontName = NSStringToJavaString(env, fontname);
        jobject jFontFamilyName =
            NSStringToJavaString(env, GetFamilyNameForFontName(fontname));

        (*env)->CallVoidMethod(env, jthis, jm_registerFont, jFontName, jFontFamilyName);
        CHECK_EXCEPTION();
        (*env)->DeleteLocalRef(env, jFontName);
        (*env)->DeleteLocalRef(env, jFontFamilyName);
    }

JNI_COCOA_EXIT(env);
}

/*
 * Class:     Java_sun_font_CFontManager_loadNativeDirFonts
 * Method:    loadNativeDirFonts
 * Signature: (Ljava/lang/String;)V;
 */
JNIEXPORT void JNICALL
Java_sun_font_CFontManager_loadNativeDirFonts
(JNIEnv *env, jclass clz, jstring filename)
{
JNI_COCOA_ENTER(env);

    NSString *path = JavaStringToNSString(env, filename);
    NSURL *url = [NSURL fileURLWithPath:(NSString *)path];
    bool res = CTFontManagerRegisterFontsForURL((CFURLRef)url, kCTFontManagerScopeProcess, nil);
#ifdef DEBUG
    NSLog(@"path is : %@", (NSString*)path);
    NSLog(@"url is : %@", (NSString*)url);
    printf("res is %d\n", res);
#endif
JNI_COCOA_EXIT(env);
}

#pragma mark --- sun.font.CFont JNI ---

/*
 * Class:     sun_font_CFont
 * Method:    getPlatformFontPtrNative
 * Signature: (JI)[B
 */
JNIEXPORT jlong JNICALL
Java_sun_font_CFont_getCGFontPtrNative
    (JNIEnv *env, jclass clazz,
     jlong awtFontPtr)
{
    AWTFont *awtFont = (AWTFont *)jlong_to_ptr(awtFontPtr);
    return (jlong)(awtFont->fNativeCGFont);
}

/*
 * Class:     sun_font_CFont
 * Method:    getTableBytesNative
 * Signature: (JI)[B
 */
JNIEXPORT jbyteArray JNICALL
Java_sun_font_CFont_getTableBytesNative
    (JNIEnv *env, jclass clazz,
     jlong awtFontPtr, jint jtag)
{
    jbyteArray jbytes = NULL;
JNI_COCOA_ENTER(env);

    CTFontTableTag tag = (CTFontTableTag)jtag;
    int i, found = 0;
    AWTFont *awtFont = (AWTFont *)jlong_to_ptr(awtFontPtr);
    NSFont* nsFont = awtFont->fFont;
    CTFontRef ctfont = (CTFontRef)nsFont;
    CFArrayRef tagsArray =
        CTFontCopyAvailableTables(ctfont, kCTFontTableOptionNoOptions);
    CFIndex numTags = CFArrayGetCount(tagsArray);
    for (i=0; i<numTags; i++) {
        if (tag ==
            (CTFontTableTag)(uintptr_t)CFArrayGetValueAtIndex(tagsArray, i)) {
            found = 1;
            break;
        }
    }
    CFRelease(tagsArray);
    if (!found) {
        return NULL;
    }
    CFDataRef table = CTFontCopyTable(ctfont, tag, kCTFontTableOptionNoOptions);
    if (table == NULL) {
        return NULL;
    }

    char *tableBytes = (char*)(CFDataGetBytePtr(table));
    size_t tableLength = CFDataGetLength(table);
    if (tableBytes == NULL || tableLength == 0) {
        CFRelease(table);
        return NULL;
    }

    jbytes = (*env)->NewByteArray(env, (jsize)tableLength);
    if (jbytes == NULL) {
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, jbytes, 0,
                               (jsize)tableLength,
                               (jbyte*)tableBytes);
    CFRelease(table);

JNI_COCOA_EXIT(env);

    return jbytes;
}

/*
 * Class:     sun_font_CFont
 * Method:    initNativeFont
 * Signature: (Ljava/lang/String;I)J
 */
JNIEXPORT jlong JNICALL
Java_sun_font_CFont_createNativeFont
    (JNIEnv *env, jclass clazz,
     jstring nativeFontName, jint style)
{
    AWTFont *awtFont = nil;

JNI_COCOA_ENTER(env);

    awtFont =
        [AWTFont awtFontForName:JavaStringToNSString(env, nativeFontName)
         style:style]; // autoreleased

    if (awtFont) {
        CFRetain(awtFont); // GC
    }

JNI_COCOA_EXIT(env);

    return ptr_to_jlong(awtFont);
}

/*
 * Class:     sun_font_CFont
 * Method:    getWidthNative
 * Signature: (J)F
 */
JNIEXPORT jfloat JNICALL
Java_sun_font_CFont_getWidthNative
    (JNIEnv *env, jobject cfont, jlong awtFontPtr)
{
    float widthVal;
JNI_COCOA_ENTER(env);

    AWTFont *awtFont = (AWTFont *)jlong_to_ptr(awtFontPtr);
    NSFont* nsFont = awtFont->fFont;
    NSFontDescriptor *fontDescriptor = nsFont.fontDescriptor;
    NSDictionary *fontTraits = [fontDescriptor objectForKey : NSFontTraitsAttribute];
    NSNumber *width = [fontTraits objectForKey : NSFontWidthTrait];
    widthVal = (float)[width floatValue];

JNI_COCOA_EXIT(env);
   return (jfloat)widthVal;
}

/*
 * Class:     sun_font_CFont
 * Method:    getWeightNative
 * Signature: (J)F
 */
JNIEXPORT jfloat JNICALL
Java_sun_font_CFont_getWeightNative
    (JNIEnv *env, jobject cfont, jlong awtFontPtr)
{
    float weightVal;
JNI_COCOA_ENTER(env);

    AWTFont *awtFont = (AWTFont *)jlong_to_ptr(awtFontPtr);
    NSFont* nsFont = awtFont->fFont;
    NSFontDescriptor *fontDescriptor = nsFont.fontDescriptor;
    NSDictionary *fontTraits = [fontDescriptor objectForKey : NSFontTraitsAttribute];
    NSNumber *weight = [fontTraits objectForKey : NSFontWeightTrait];
    weightVal = (float)[weight floatValue];

JNI_COCOA_EXIT(env);
   return (jfloat)weightVal;
}

/*
 * Class:     sun_font_CFont
 * Method:    disposeNativeFont
 * Signature: (J)V
 */
JNIEXPORT void JNICALL
Java_sun_font_CFont_disposeNativeFont
    (JNIEnv *env, jclass clazz, jlong awtFontPtr)
{
JNI_COCOA_ENTER(env);

    if (awtFontPtr) {
        CFRelease((AWTFont *)jlong_to_ptr(awtFontPtr)); // GC
    }

JNI_COCOA_EXIT(env);
}


#pragma mark --- Miscellaneous JNI ---

#ifndef HEADLESS
/*
 * Class:     sun_awt_PlatformFont
 * Method:    initIDs
 * Signature: ()V
 */
JNIEXPORT void JNICALL
Java_sun_awt_PlatformFont_initIDs
    (JNIEnv *env, jclass cls)
{
}

/*
 * Class:     sun_awt_FontDescriptor
 * Method:    initIDs
 * Signature: ()V
 */
JNIEXPORT void JNICALL
Java_sun_awt_FontDescriptor_initIDs
    (JNIEnv *env, jclass cls)
{
}
#endif

static CFStringRef EMOJI_FONT_NAME = CFSTR("Apple Color Emoji");

bool IsEmojiFont(CTFontRef font)
{
    CFStringRef name = CTFontCopyFullName(font);
    if (name == NULL) return false;
    bool isFixedColor = CFStringCompare(name, EMOJI_FONT_NAME, 0) == kCFCompareEqualTo;
    CFRelease(name);
    return isFixedColor;
}
