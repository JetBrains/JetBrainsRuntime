/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, JetBrains s.r.o.. All rights reserved.
 *
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

#include <jni.h>
#include <jni_util.h>
#include <jvm_md.h>
#include <sizecalc.h>

#if defined(MACOSX)
#define DISABLE_FONTCONFIG
#endif

#ifndef DISABLE_FONTCONFIG

#if defined(__linux__)
#include <string.h>
#endif /* __linux__ */

#include <dlfcn.h>
#include <fontconfig/fontconfig.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <strings.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <unistd.h>

#include "fontconfigmanager.h"

#ifndef HEADLESS
#include <awt.h>
#else
/* locks ought to be included from awt.h */
#define AWT_LOCK()
#define AWT_UNLOCK()
#endif /* !HEADLESS */

#define FONTCONFIG_DLL_VERSIONED VERSIONED_JNI_LIB_NAME("fontconfig", "1")
#define FONTCONFIG_DLL JNI_LIB_NAME("fontconfig")

#if defined( __linux__)
/* All the known interesting locations we have discovered on
 * various flavors of Linux
 */
static char *fullLinuxFontPath[] = {
    "/usr/X11R6/lib/X11/fonts/TrueType",  /* RH 7.1+ */
    "/usr/X11R6/lib/X11/fonts/truetype",  /* SuSE */
    "/usr/X11R6/lib/X11/fonts/tt",
    "/usr/X11R6/lib/X11/fonts/TTF",
    "/usr/X11R6/lib/X11/fonts/OTF",       /* RH 9.0 (but empty!) */
    "/usr/share/fonts/ja/TrueType",       /* RH 7.2+ */
    "/usr/share/fonts/truetype",
    "/usr/share/fonts/ko/TrueType",       /* RH 9.0 */
    "/usr/share/fonts/zh_CN/TrueType",    /* RH 9.0 */
    "/usr/share/fonts/zh_TW/TrueType",    /* RH 9.0 */
    "/var/lib/defoma/x-ttcidfont-conf.d/dirs/TrueType", /* Debian */
    "/usr/X11R6/lib/X11/fonts/Type1",
    "/usr/share/fonts/default/Type1",     /* RH 9.0 */
    NULL, /* terminates the list */
};
#elif defined(_AIX)
static char *fullAixFontPath[] = {
    "/usr/lpp/X11/lib/X11/fonts/Type1",    /* from X11.fnt.iso_T1  */
    "/usr/lpp/X11/lib/X11/fonts/TrueType", /* from X11.fnt.ucs.ttf */
    NULL, /* terminates the list */
};
#endif

typedef FcConfig* (*FcInitLoadConfigFuncType)();
typedef FcPattern* (*FcPatternBuildFuncType)(FcPattern *orig, ...);
typedef FcObjectSet* (*FcObjectSetFuncType)(const char *first, ...);
typedef FcFontSet* (*FcFontListFuncType)(FcConfig *config, FcPattern *p, FcObjectSet *os);
typedef FcResult (*FcPatternGetBoolFuncType)(const FcPattern *p, const char *object, int n, FcBool *b);
typedef FcResult (*FcPatternGetIntegerFuncType)(const FcPattern *p, const char *object, int n, int *i);
typedef FcResult (*FcPatternGetStringFuncType)(const FcPattern *p, const char *object, int n, FcChar8 ** s);
typedef FcChar8* (*FcStrDirnameFuncType)(const FcChar8 *file);
typedef void (*FcPatternDestroyFuncType)(FcPattern *p);
typedef void (*FcObjectSetDestroyFuncType)(FcObjectSet *os);
typedef void (*FcFontSetDestroyFuncType)(FcFontSet *s);
typedef FcPattern* (*FcNameParseFuncType)(const FcChar8 *name);
typedef FcBool (*FcPatternAddStringFuncType)(FcPattern *p, const char *object, const FcChar8 *s);
typedef FcBool (*FcPatternAddDoubleFuncType)(FcPattern *p, const char *object, double v);
typedef void (*FcDefaultSubstituteFuncType)(FcPattern *p);
typedef FcBool (*FcConfigSubstituteFuncType)(FcConfig *config, FcPattern *p, FcMatchKind kind);
typedef FcPattern* (*FcFontMatchFuncType)(FcConfig *config, FcPattern *p, FcResult *result);
typedef FcFontSet* (*FcFontSetCreateFuncType)();
typedef FcBool (*FcFontSetAddFuncType)(FcFontSet *s, FcPattern *font);
typedef FcResult (*FcPatternGetCharSetFuncType)(FcPattern *p, const char *object, int n, FcCharSet **c);
typedef FcFontSet* (*FcFontSortFuncType)(FcConfig *config, FcPattern *p, FcBool trim, FcCharSet **csp, FcResult *result);
typedef FcCharSet* (*FcCharSetUnionFuncType)(const FcCharSet *a, const FcCharSet *b);
typedef FcCharSet* (*FcCharSetDestroyFuncType)(FcCharSet *fcs);
typedef FcChar32 (*FcCharSetSubtractCountFuncType)(const FcCharSet *a, const FcCharSet *b);
typedef int (*FcGetVersionFuncType)();
typedef FcStrList* (*FcConfigGetCacheDirsFuncType)(FcConfig *config);
typedef FcChar8* (*FcStrListNextFuncType)(FcStrList *list);
typedef FcChar8* (*FcStrListDoneFuncType)(FcStrList *list);
typedef FcChar8* (*FcPatternFormatFuncType)(FcPattern *pat, const FcChar8 *format);
typedef void (*FcStrFreeFuncType)(FcChar8 *str);

static FcInitLoadConfigFuncType fcInitLoadConfig;
static FcPatternBuildFuncType fcPatternBuild;
static FcObjectSetFuncType fcObjectSetBuild;
static FcFontListFuncType fcFontList;
static FcStrDirnameFuncType fcStrDirname;
static FcObjectSetDestroyFuncType fcObjectSetDestroy;
static FcPatternGetBoolFuncType fcPatternGetBool;
static FcPatternGetIntegerFuncType fcPatternGetInteger;
static FcNameParseFuncType fcNameParse;
static FcPatternAddStringFuncType fcPatternAddString;
static FcPatternAddDoubleFuncType fcPatternAddDouble;
static FcConfigSubstituteFuncType fcConfigSubstitute;
static FcDefaultSubstituteFuncType  fcDefaultSubstitute;
static FcFontMatchFuncType fcFontMatch;
static FcPatternGetStringFuncType fcPatternGetString;
static FcPatternDestroyFuncType fcPatternDestroy;
static FcPatternGetCharSetFuncType fcPatternGetCharSet;
static FcFontSortFuncType fcFontSort;
static FcFontSetDestroyFuncType fcFontSetDestroy;
static FcCharSetUnionFuncType fcCharSetUnion;
static FcCharSetDestroyFuncType fcCharSetDestroy;
static FcCharSetSubtractCountFuncType fcCharSetSubtractCount;
static FcGetVersionFuncType fcGetVersion;
static FcConfigGetCacheDirsFuncType fcConfigGetCacheDirs;
static FcStrListNextFuncType fcStrListNext;
static FcStrListDoneFuncType fcStrListDone;
static FcPatternFormatFuncType fcPatternFormat;
static FcStrFreeFuncType fcStrFree;

static void *libfontconfig = NULL;

static void closeFontConfig() {

    if (libfontconfig != NULL) {
        dlclose(libfontconfig);
        libfontconfig = NULL;
    }
}

void openFontConfig() {

    char *homeEnv;
    static char *homeEnvStr = "HOME="; /* must be static */

    /* Private workaround to not use fontconfig library.
     * May be useful during testing/debugging
     */
    char *useFC = getenv("USE_J2D_FONTCONFIG");
    if (useFC != NULL && !strcmp(useFC, "no")) {
        return;
    }

#if defined(_AIX)
    /* On AIX, fontconfig is not a standard package supported by IBM.
     * instead it has to be installed from the "AIX Toolbox for Linux Applications"
     * site http://www-03.ibm.com/systems/power/software/aix/linux/toolbox/alpha.html
     * and will be installed under /opt/freeware/lib/libfontconfig.a.
     * Notice that the archive contains the real 32- and 64-bit shared libraries.
     * We first try to load 'libfontconfig.so' from the default library path in the
     * case the user has installed a private version of the library and if that
     * doesn't succeed, we try the version from /opt/freeware/lib/libfontconfig.a
     */
    libfontconfig = dlopen("libfontconfig.so", RTLD_LOCAL|RTLD_LAZY);
    if (libfontconfig == NULL) {
        libfontconfig = dlopen("/opt/freeware/lib/libfontconfig.a(libfontconfig.so.1)", RTLD_MEMBER|RTLD_LOCAL|RTLD_LAZY);
        if (libfontconfig == NULL) {
            return;
        }
    }
#else
    /* 64 bit sparc should pick up the right version from the lib path.
     * New features may be added to libfontconfig, this is expected to
     * be compatible with old features, but we may need to start
     * distinguishing the library version, to know whether to expect
     * certain symbols - and functionality - to be available.
     * Also add explicit search for .so.1 in case .so symlink doesn't exist.
     */
    libfontconfig = dlopen(FONTCONFIG_DLL_VERSIONED, RTLD_LOCAL|RTLD_LAZY);
    if (libfontconfig == NULL) {
        libfontconfig = dlopen(FONTCONFIG_DLL, RTLD_LOCAL|RTLD_LAZY);
        if (libfontconfig == NULL) {
            return;
        }
    }
#endif

    /* Version 1.0 of libfontconfig crashes if HOME isn't defined in
     * the environment. This should generally never happen, but we can't
     * control it, and can't control the version of fontconfig, so iff
     * its not defined we set it to an empty value which is sufficient
     * to prevent a crash. I considered unsetting it before exit, but
     * it doesn't appear to work on Solaris, so I will leave it set.
     */
    homeEnv = getenv("HOME");
    if (homeEnv == NULL) {
        putenv(homeEnvStr);
    }

    fcPatternBuild = (FcPatternBuildFuncType)dlsym(libfontconfig, "FcPatternBuild");
    fcObjectSetBuild = (FcObjectSetFuncType)dlsym(libfontconfig, "FcObjectSetBuild");
    fcFontList = (FcFontListFuncType)dlsym(libfontconfig, "FcFontList");
    fcStrDirname = (FcStrDirnameFuncType)dlsym(libfontconfig, "FcStrDirname");
    fcObjectSetDestroy = (FcObjectSetDestroyFuncType)dlsym(libfontconfig, "FcObjectSetDestroy");
    fcPatternGetBool = (FcPatternGetBoolFuncType) dlsym(libfontconfig, "FcPatternGetBool");
    fcPatternGetInteger = (FcPatternGetIntegerFuncType)dlsym(libfontconfig, "FcPatternGetInteger");
    fcNameParse = (FcNameParseFuncType)dlsym(libfontconfig, "FcNameParse");
    fcPatternAddString = (FcPatternAddStringFuncType)dlsym(libfontconfig, "FcPatternAddString");
    fcPatternAddDouble = (FcPatternAddDoubleFuncType)dlsym(libfontconfig, "FcPatternAddDouble");
    fcConfigSubstitute = (FcConfigSubstituteFuncType)dlsym(libfontconfig, "FcConfigSubstitute");
    fcDefaultSubstitute = (FcDefaultSubstituteFuncType)dlsym(libfontconfig, "FcDefaultSubstitute");
    fcFontMatch = (FcFontMatchFuncType)dlsym(libfontconfig, "FcFontMatch");
    fcPatternGetString = (FcPatternGetStringFuncType)dlsym(libfontconfig, "FcPatternGetString");
    fcPatternDestroy = (FcPatternDestroyFuncType)dlsym(libfontconfig, "FcPatternDestroy");
    fcPatternGetCharSet = (FcPatternGetCharSetFuncType)dlsym(libfontconfig, "FcPatternGetCharSet");
    fcFontSort = (FcFontSortFuncType)dlsym(libfontconfig, "FcFontSort");
    fcFontSetDestroy = (FcFontSetDestroyFuncType)dlsym(libfontconfig, "FcFontSetDestroy");
    fcCharSetUnion = (FcCharSetUnionFuncType)dlsym(libfontconfig, "FcCharSetUnion");
    fcCharSetDestroy = (FcCharSetDestroyFuncType)dlsym(libfontconfig, "FcCharSetDestroy");
    fcCharSetSubtractCount = (FcCharSetSubtractCountFuncType)dlsym(libfontconfig, "FcCharSetSubtractCount");
    fcGetVersion = (FcGetVersionFuncType)dlsym(libfontconfig, "FcGetVersion");
    fcConfigGetCacheDirs = (FcConfigGetCacheDirsFuncType)dlsym(libfontconfig, "FcConfigGetCacheDirs");
    fcStrListNext = (FcStrListNextFuncType)dlsym(libfontconfig, "FcStrListNext");
    fcStrListDone = (FcStrListDoneFuncType)dlsym(libfontconfig, "FcStrListDone");
    fcPatternFormat = (FcPatternFormatFuncType)dlsym(libfontconfig, "FcPatternFormat");
    fcStrFree = (FcStrFreeFuncType)dlsym(libfontconfig, "FcStrFree");

    if (fcPatternBuild == NULL || fcObjectSetBuild == NULL || fcFontList == NULL || fcStrDirname == NULL ||
        fcObjectSetDestroy == NULL || fcPatternGetBool == NULL || fcPatternGetInteger == NULL || fcNameParse == NULL ||
        fcPatternAddString == NULL || fcConfigSubstitute == NULL || fcDefaultSubstitute == NULL || fcFontMatch == NULL ||
        fcPatternGetString == NULL || fcPatternDestroy == NULL || fcPatternGetCharSet == NULL || fcFontSort == NULL ||
        fcFontSetDestroy == NULL || fcCharSetUnion == NULL || fcCharSetDestroy == NULL || fcCharSetSubtractCount == NULL ||
        fcGetVersion == NULL || fcConfigGetCacheDirs == NULL || fcStrListNext == NULL || fcStrListDone == NULL ||
        fcPatternAddDouble == NULL || fcPatternFormat == NULL || fcStrFree == NULL) {
        closeFontConfig();
    }
}

static bool usingFontConfig() {
    return (libfontconfig != NULL) ? true : false;
}

JNIEXPORT void JNICALL
JNI_OnUnload(JavaVM *vm, void *reserved) {
    closeFontConfig();
}

/* These are copied from sun.awt.SunHints.
 * Consider initialising them as ints using JNI for more robustness.
 */
#define TEXT_AA_OFF 1
#define TEXT_AA_ON  2
#define TEXT_AA_LCD_HRGB 4
#define TEXT_AA_LCD_HBGR 5
#define TEXT_AA_LCD_VRGB 6
#define TEXT_AA_LCD_VBGR 7

typedef FcResult (*FcPatternGetValueFuncType)(const FcPattern *p, const char *object, int n, void *b);

static void setRenderingFontHintsField(FcPatternGetValueFuncType fcPatternGetValue, FcPattern* matchPattern,
                                       const char* property, int* value) {
    if (FcResultMatch != (*fcPatternGetValue)(matchPattern, property, 0, value)) {
        *value = -1;
    }
}

JNIEXPORT int setupRenderingFontHints
(const char* fcName, const char* locale, double size, RenderingFontHints *renderingFontHints) {

    FcPattern *pattern, *matchPattern;
    FcResult result;

    if (fcName == NULL) {
        return -1;
    }

    pattern = (*fcNameParse)((FcChar8 *)fcName);
    if (locale != NULL) {
        (*fcPatternAddString)(pattern, FC_LANG, (unsigned char*)locale);
    }
    if (size != 0) {
        (*fcPatternAddDouble)(pattern, FC_SIZE, size);
    }
    (*fcConfigSubstitute)(NULL, pattern, FcMatchPattern);
    (*fcDefaultSubstitute)(pattern);
    matchPattern = (*fcFontMatch)(NULL, pattern, &result);
    /* Perhaps should call FcFontRenderPrepare() here as some pattern
     * elements might change as a result of that call, but I'm not seeing
     * any difference in testing.
     */
    if (matchPattern) {
        // Extract values from result
        setRenderingFontHintsField((FcPatternGetValueFuncType)(fcPatternGetBool), matchPattern, FC_HINTING,
                                   &renderingFontHints->fcHinting);
        setRenderingFontHintsField((FcPatternGetValueFuncType)(fcPatternGetInteger), matchPattern, FC_HINT_STYLE,
                                   &renderingFontHints->fcHintStyle);
        setRenderingFontHintsField((FcPatternGetValueFuncType)(fcPatternGetBool), matchPattern, FC_ANTIALIAS,
                                   &renderingFontHints->fcAntialias);
        setRenderingFontHintsField((FcPatternGetValueFuncType)(fcPatternGetBool), matchPattern, FC_AUTOHINT,
                                   &renderingFontHints->fcAutohint);
        setRenderingFontHintsField((FcPatternGetValueFuncType)(fcPatternGetInteger), matchPattern, FC_RGBA,
                                   &renderingFontHints->fcRGBA);
        setRenderingFontHintsField((FcPatternGetValueFuncType)(fcPatternGetInteger), matchPattern, FC_LCD_FILTER,
                                   &renderingFontHints->fcLCDFilter);
        
        (*fcPatternDestroy)(matchPattern);
    }
    (*fcPatternDestroy)(pattern);

    return 0;
}

#endif

JNIEXPORT char **getFontConfigLocations() {

#ifdef DISABLE_FONTCONFIG
    return NULL;
#else
    if (usingFontConfig() == false) {
        return NULL;
    }

    char **fontdirs;
    int numdirs = 0;

    FcPattern *pattern;
    FcObjectSet *objset;
    FcFontSet *fontSet;
    int i, f, found;

    /* Make calls into the fontconfig library to build a search for
     * outline fonts, and to get the set of full file paths from the matches.
     * This set is returned from the call to fcFontList(..)
     * We allocate an array of char* pointers sufficient to hold all
     * the matches + 1 extra which ensures there will be a NULL after all
     * valid entries.
     * We call fcStrDirname strip the file name from the path, and
     * check if we have yet seen this directory. If not we add a pointer to
     * it into our array of char*. Note that fcStrDirname returns newly
     * allocated storage so we can use this in the return char** value.
     * Finally we clean up, freeing allocated resources, and return the
     * array of unique directories.
     */
    pattern = (*fcPatternBuild)(NULL, FC_OUTLINE, FcTypeBool, FcTrue, NULL);
    objset = (*fcObjectSetBuild)(FC_FILE, NULL);
    fontSet = (*fcFontList)(NULL, pattern, objset);
    if (fontSet == NULL) {
        /* fcFontList() may return NULL if fonts are not installed. */
        fontdirs = NULL;
    } else {
        fontdirs = (char**)calloc(fontSet->nfont+1, sizeof(char*));
        if (fontdirs == NULL) {
            (*fcFontSetDestroy)(fontSet);
            goto cleanup;
        }
        for (f=0; f < fontSet->nfont; f++) {
            FcChar8 *file;
            FcChar8 *dir;
            if ((*fcPatternGetString)(fontSet->fonts[f], FC_FILE, 0, &file) == FcResultMatch) {
                dir = (*fcStrDirname)(file);
                found = 0;
                for (i=0;i<numdirs; i++) {
                    if (strcmp(fontdirs[i], (char*)dir) == 0) {
                        found = 1;
                        break;
                    }
                }
                if (!found) {
                    fontdirs[numdirs++] = (char*)dir;
                } else {
                    free((char*)dir);
                }
            }
        }
        /* Free fontset if one was returned */
        (*fcFontSetDestroy)(fontSet);
    }

    cleanup:
    /* Free memory and close the ".so" */
    (*fcObjectSetDestroy)(objset);
    (*fcPatternDestroy)(pattern);
    return fontdirs;
#endif
}

JNIEXPORT jint JNICALL
Java_sun_font_FontConfigManager_getFontConfigVersion
        (JNIEnv *env, jclass obj) {

#ifdef DISABLE_FONTCONFIG
    return 0;
#else
    if (usingFontConfig() == false) {
        return 0;
    }

    return (*fcGetVersion)();
#endif
}

JNIEXPORT void JNICALL
Java_sun_font_FontConfigManager_setupFontConfigFonts
(JNIEnv *env, jclass obj, jstring localeStr, jobject fcInfoObj,
 jobjectArray fcCompFontArray,  jboolean includeFallbacks) {

#ifdef DISABLE_FONTCONFIG
    return;
#else
    if (usingFontConfig() == false) {
        return;
    }

    int i, arrlen;
    jobject fcCompFontObj;
    jstring fcNameStr, jstr;
    const char *locale, *fcName;
    FcPattern *pattern;
    FcResult result;
    jfieldID fcNameID, fcFirstFontID, fcAllFontsID, fcVersionID, fcCacheDirsID;
    jfieldID familyNameID, styleNameID, fullNameID, fontFileID;
    jmethodID fcFontCons;
    char* debugMinGlyphsStr = getenv("J2D_DEBUG_MIN_GLYPHS");
    jclass fcInfoClass;
    jclass fcCompFontClass;
    jclass fcFontClass;

    CHECK_NULL(fcInfoObj);
    CHECK_NULL(fcCompFontArray);

    CHECK_NULL(fcInfoClass = (*env)->FindClass(env, "sun/font/FontConfigManager$FontConfigInfo"));
    CHECK_NULL(fcCompFontClass = (*env)->FindClass(env, "sun/font/FontConfigManager$FcCompFont"));
    CHECK_NULL(fcFontClass = (*env)->FindClass(env, "sun/font/FontConfigManager$FontConfigFont"));
    CHECK_NULL(fcVersionID = (*env)->GetFieldID(env, fcInfoClass, "fcVersion", "I"));
    CHECK_NULL(fcCacheDirsID = (*env)->GetFieldID(env, fcInfoClass, "cacheDirs", "[Ljava/lang/String;"));
    CHECK_NULL(fcNameID = (*env)->GetFieldID(env, fcCompFontClass, "fcName", "Ljava/lang/String;"));
    CHECK_NULL(fcFirstFontID = (*env)->GetFieldID(env, fcCompFontClass, "firstFont", "Lsun/font/FontConfigManager$FontConfigFont;"));
    CHECK_NULL(fcAllFontsID = (*env)->GetFieldID(env, fcCompFontClass, "allFonts", "[Lsun/font/FontConfigManager$FontConfigFont;"));
    CHECK_NULL(fcFontCons = (*env)->GetMethodID(env, fcFontClass, "<init>", "()V"));
    CHECK_NULL(familyNameID = (*env)->GetFieldID(env, fcFontClass, "familyName", "Ljava/lang/String;"));
    CHECK_NULL(styleNameID = (*env)->GetFieldID(env, fcFontClass, "styleStr", "Ljava/lang/String;"));
    CHECK_NULL(fullNameID = (*env)->GetFieldID(env, fcFontClass, "fullName", "Ljava/lang/String;"));
    CHECK_NULL(fontFileID = (*env)->GetFieldID(env, fcFontClass, "fontFile", "Ljava/lang/String;"));

    (*env)->SetIntField(env, fcInfoObj, fcVersionID, (*fcGetVersion)());

    /* Optionally get the cache dir locations. This isn't
     * available until v 2.4.x, but this is OK since on those later versions
     * we can check the time stamps on the cache dirs to see if we
     * are out of date. There are a couple of assumptions here. First
     * that the time stamp on the directory changes when the contents are
     * updated. Secondly that the locations don't change. The latter is
     * most likely if a new version of fontconfig is installed, but we also
     * invalidate the cache if we detect that. Arguably even that is "rare",
     * and most likely is tied to an OS upgrade which gets a new file anyway.
     */
    if (fcStrListNext != NULL && fcStrListDone != NULL &&
        fcConfigGetCacheDirs != NULL) {

        FcStrList* cacheDirs;
        FcChar8* cacheDir;
        int cnt = 0;
        jobject cacheDirArray = (*env)->GetObjectField(env, fcInfoObj, fcCacheDirsID);
        int max = (*env)->GetArrayLength(env, cacheDirArray);

        cacheDirs = (*fcConfigGetCacheDirs)(NULL);
        if (cacheDirs != NULL) {
            while ((cnt < max) && (cacheDir = (*fcStrListNext)(cacheDirs))) {
                jstr = (*env)->NewStringUTF(env, (const char*)cacheDir);
                if (IS_NULL(jstr)) {
                    (*fcStrListDone)(cacheDirs);
                    return;
                }
                (*env)->SetObjectArrayElement(env, cacheDirArray, cnt++, jstr);
                (*env)->DeleteLocalRef(env, jstr);
            }
            (*fcStrListDone)(cacheDirs);
        }
    }

    locale = (*env)->GetStringUTFChars(env, localeStr, 0);
    if (locale == NULL) {
        (*env)->ExceptionClear(env);
        JNU_ThrowOutOfMemoryError(env, "Could not create locale");
        return;
    }

    arrlen = (*env)->GetArrayLength(env, fcCompFontArray);
    for (i=0; i<arrlen; i++) {
        FcFontSet* fontset;
        int fn, j, fontCount, nfonts;
        unsigned int minGlyphs;
        FcChar8 **family, **styleStr, **fullname, **file;
        jarray fcFontArr = NULL;
        FcCharSet *unionCharset = NULL;
        FcCharSet *prevUnionCharset = NULL;

        fcCompFontObj = (*env)->GetObjectArrayElement(env, fcCompFontArray, i);
        fcNameStr =
            (jstring)((*env)->GetObjectField(env, fcCompFontObj, fcNameID));
        fcName = (*env)->GetStringUTFChars(env, fcNameStr, 0);
        if (fcName == NULL) {
            (*env)->DeleteLocalRef(env, fcCompFontObj);
            (*env)->DeleteLocalRef(env, fcNameStr);
            continue;
        }
        pattern = (*fcNameParse)((FcChar8 *)fcName);
        (*env)->ReleaseStringUTFChars(env, fcNameStr, (const char*)fcName);
        (*env)->DeleteLocalRef(env, fcNameStr);
        if (pattern == NULL) {
            if (locale) {
                (*env)->ReleaseStringUTFChars(env, localeStr, (const char*)locale);
            }
            return;
        }

        /* locale may not usually be necessary as fontconfig appears to apply
         * this anyway based on the user's environment. However we want
         * to use the value of the JDK startup locale so this should take
         * care of it.
         */
        if (locale != NULL) {
            (*fcPatternAddString)(pattern, FC_LANG, (unsigned char*)locale);
        }
        (*fcConfigSubstitute)(NULL, pattern, FcMatchPattern);
        (*fcDefaultSubstitute)(pattern);
        fontset = (*fcFontSort)(NULL, pattern, FcTrue, NULL, &result);
        if (fontset == NULL) {
            (*fcPatternDestroy)(pattern);
            if (locale) {
                (*env)->ReleaseStringUTFChars(env, localeStr, (const char*)locale);
            }
            return;
        }

        /* fontconfig returned us "nfonts". If we are just getting the
         * first font, we set nfont to zero. Otherwise we use "nfonts".
         * Next create separate C arrays of length nfonts for family file etc.
         * Inspect the returned fonts and the ones we like (adds enough glyphs)
         * are added to the arrays and we increment 'fontCount'.
         */
        nfonts = fontset->nfont;
        family   = (FcChar8**)calloc(nfonts, sizeof(FcChar8*));
        styleStr = (FcChar8**)calloc(nfonts, sizeof(FcChar8*));
        fullname = (FcChar8**)calloc(nfonts, sizeof(FcChar8*));
        file     = (FcChar8**)calloc(nfonts, sizeof(FcChar8*));
        if (family == NULL || styleStr == NULL ||
            fullname == NULL || file == NULL) {
            if (family != NULL) {
                free(family);
            }
            if (styleStr != NULL) {
                free(styleStr);
            }
            if (fullname != NULL) {
                free(fullname);
            }
            if (file != NULL) {
                free(file);
            }
            (*fcPatternDestroy)(pattern);
            (*fcFontSetDestroy)(fontset);
            if (locale) {
                (*env)->ReleaseStringUTFChars(env, localeStr, (const char*)locale);
            }
            return;
        }
        fontCount = 0;
        minGlyphs = 20;
        if (debugMinGlyphsStr != NULL) {
            int val = minGlyphs;
            sscanf(debugMinGlyphsStr, "%5d", &val);
            if (val >= 0 && val <= 65536) {
                minGlyphs = val;
            }
        }

        for (j=0; j<nfonts; j++) {
            FcPattern *fontPattern = fontset->fonts[j];
            FcChar8 *fontformat;
            FcCharSet *charset = NULL;

            fontformat = NULL;
            (*fcPatternGetString)(fontPattern, FC_FONTFORMAT, 0, &fontformat);
            /* We only want TrueType fonts but some Linuxes still depend
             * on Type 1 fonts for some Locale support, so we'll allow
             * them there.
             */
            if (fontformat != NULL
                && (strcmp((char*)fontformat, "TrueType") != 0)
#if defined(__linux__) || defined(_AIX)
                && (strcmp((char*)fontformat, "Type 1") != 0)
                && (strcmp((char*)fontformat, "CFF") != 0)
#endif
             ) {
                continue;
            }
            result = (*fcPatternGetCharSet)(fontPattern, FC_CHARSET, 0, &charset);
            if (result != FcResultMatch) {
                free(family);
                free(fullname);
                free(styleStr);
                free(file);
                (*fcPatternDestroy)(pattern);
                (*fcFontSetDestroy)(fontset);
                if (prevUnionCharset != NULL) {
                    (*fcCharSetDestroy)(prevUnionCharset);
                }
                if (locale) {
                    (*env)->ReleaseStringUTFChars(env, localeStr, (const char*)locale);
                }
                return;
            }

            /* We don't want 20 or 30 fonts, so once we hit 10 fonts,
             * then require that they really be adding value. Too many
             * adversely affects load time for minimal value-add.
             * This is still likely far more than we've had in the past.
             */
            if (j==10) {
                minGlyphs = 50;
            }
            if (unionCharset == NULL) {
                unionCharset = charset;
            } else {
                if ((*fcCharSetSubtractCount)(charset, unionCharset)
                    > minGlyphs) {
                    unionCharset = (* fcCharSetUnion)(unionCharset, charset);
                    if (prevUnionCharset != NULL) {
                      (*fcCharSetDestroy)(prevUnionCharset);
                    }
                    prevUnionCharset = unionCharset;
                } else {
                    continue;
                }
            }

            fontCount++; // found a font we will use.
            (*fcPatternGetString)(fontPattern, FC_FILE, 0, &file[j]);
            (*fcPatternGetString)(fontPattern, FC_FAMILY, 0, &family[j]);
            (*fcPatternGetString)(fontPattern, FC_STYLE, 0, &styleStr[j]);
            (*fcPatternGetString)(fontPattern, FC_FULLNAME, 0, &fullname[j]);
            if (!includeFallbacks) {
                break;
            }
            if (fontCount == 254) {
                break; // CompositeFont will only use up to 254 slots from here.
            }
        }

        // Release last instance of CharSet union
        if (prevUnionCharset != NULL) {
          (*fcCharSetDestroy)(prevUnionCharset);
        }

        /* Once we get here 'fontCount' is the number of returned fonts
         * we actually want to use, so we create 'fcFontArr' of that length.
         * The non-null entries of "family[]" etc are those fonts.
         * Then loop again over all nfonts adding just those non-null ones
         * to 'fcFontArr'. If its null (we didn't want the font)
         * then we don't enter the main body.
         * So we should never get more than 'fontCount' entries.
         */
        if (includeFallbacks) {
            fcFontArr =
                (*env)->NewObjectArray(env, fontCount, fcFontClass, NULL);
            if (IS_NULL(fcFontArr)) {
                free(family);
                free(fullname);
                free(styleStr);
                free(file);
                (*fcPatternDestroy)(pattern);
                (*fcFontSetDestroy)(fontset);
                if (locale) {
                    (*env)->ReleaseStringUTFChars(env, localeStr, (const char*)locale);
                }
                return;
            }
            (*env)->SetObjectField(env,fcCompFontObj, fcAllFontsID, fcFontArr);
        }
        fn=0;

        for (j=0;j<nfonts;j++) {
            if (family[j] != NULL) {
                jobject fcFont = (*env)->NewObject(env, fcFontClass, fcFontCons);
                if (IS_NULL(fcFont)) break;
                jstr = (*env)->NewStringUTF(env, (const char*)family[j]);
                if (IS_NULL(jstr)) break;
                (*env)->SetObjectField(env, fcFont, familyNameID, jstr);
                (*env)->DeleteLocalRef(env, jstr);
                if (file[j] != NULL) {
                    jstr = (*env)->NewStringUTF(env, (const char*)file[j]);
                    if (IS_NULL(jstr)) break;
                    (*env)->SetObjectField(env, fcFont, fontFileID, jstr);
                    (*env)->DeleteLocalRef(env, jstr);
                }
                if (styleStr[j] != NULL) {
                    jstr = (*env)->NewStringUTF(env, (const char*)styleStr[j]);
                    if (IS_NULL(jstr)) break;
                    (*env)->SetObjectField(env, fcFont, styleNameID, jstr);
                    (*env)->DeleteLocalRef(env, jstr);
                }
                if (fullname[j] != NULL) {
                    jstr = (*env)->NewStringUTF(env, (const char*)fullname[j]);
                    if (IS_NULL(jstr)) break;
                    (*env)->SetObjectField(env, fcFont, fullNameID, jstr);
                    (*env)->DeleteLocalRef(env, jstr);
                }
                if (fn==0) {
                    (*env)->SetObjectField(env, fcCompFontObj,
                                           fcFirstFontID, fcFont);
                }
                if (includeFallbacks) {
                    (*env)->SetObjectArrayElement(env, fcFontArr, fn++,fcFont);
                } else {
                    (*env)->DeleteLocalRef(env, fcFont);
                    break;
                }
                (*env)->DeleteLocalRef(env, fcFont);
            }
        }
        if (includeFallbacks) {
            (*env)->DeleteLocalRef(env, fcFontArr);
        }
        (*env)->DeleteLocalRef(env, fcCompFontObj);
        (*fcFontSetDestroy)(fontset);
        (*fcPatternDestroy)(pattern);
        free(family);
        free(styleStr);
        free(fullname);
        free(file);
    }

    /* release resources and close the ".so" */

    if (locale) {
        (*env)->ReleaseStringUTFChars(env, localeStr, (const char*)locale);
    }
#endif
}

JNIEXPORT jint JNICALL
Java_sun_font_FontConfigManager_getFontConfigAASettings
        (JNIEnv *env, jclass obj, jstring fcNameStr, jstring localeStr) {

#ifdef DISABLE_FONTCONFIG
    return -1;
#else
    if (usingFontConfig() == false) {
        return -1;
    }

    int rgba = 0;
    const char *locale=NULL, *fcName=NULL;

    if (fcNameStr == NULL || localeStr == NULL) {
        return -1;
    }

    fcName = (*env)->GetStringUTFChars(env, fcNameStr, 0);
    locale = (*env)->GetStringUTFChars(env, localeStr, 0);
    int status = 0;
    RenderingFontHints renderingFontHints;
    if (fcName && locale) {
        status = setupRenderingFontHints(fcName, locale, 0, &renderingFontHints);
    } else {
        status = -1;
    }

    if (locale) {
        (*env)->ReleaseStringUTFChars(env, localeStr, (const char*)locale);
    }
    if (fcName) {
        (*env)->ReleaseStringUTFChars(env, fcNameStr, (const char*)fcName);
    }

    if (status) {
        return status;
    }

    if (renderingFontHints.fcAntialias == FcFalse) {
        return TEXT_AA_OFF;
    } else if (renderingFontHints.fcRGBA <= FC_RGBA_UNKNOWN || renderingFontHints.fcRGBA >= FC_RGBA_NONE) {
        return TEXT_AA_ON;
    } else {
        switch (renderingFontHints.fcRGBA) {
            case FC_RGBA_RGB : return TEXT_AA_LCD_HRGB;
            case FC_RGBA_BGR : return TEXT_AA_LCD_HBGR;
            case FC_RGBA_VRGB : return TEXT_AA_LCD_VRGB;
            case FC_RGBA_VBGR : return TEXT_AA_LCD_VBGR;
            default : return TEXT_AA_LCD_HRGB; // should not get here.
        }
    }
#endif
}

JNIEXPORT jstring JNICALL
Java_sun_font_FontConfigManager_getFontProperty
        (JNIEnv *env, jclass obj, jstring query, jstring property) {

#ifdef DISABLE_FONTCONFIG
    return NULL;
#else
    if (usingFontConfig() == false) {
        return NULL;
    }

    const char *queryPtr = NULL;
    const char *propertyPtr = NULL;
    FcChar8 *fontFamily = NULL;
    FcChar8 *fontPath = NULL;
    jstring res = NULL;

    queryPtr = (*env)->GetStringUTFChars(env, query, 0);
    propertyPtr = (*env)->GetStringUTFChars(env, property, 0);
    if (queryPtr == NULL || propertyPtr == NULL) {
        goto cleanup;
    }

    FcPattern *pattern = (*fcNameParse)((FcChar8 *) queryPtr);
    if (pattern == NULL) {
        goto cleanup;
    }
    (*fcConfigSubstitute)(NULL, pattern, FcMatchScan);
    (*fcDefaultSubstitute)(pattern);

    FcResult fcResult;
    FcPattern *match = (*fcFontMatch)(0, pattern, &fcResult);
    if (match == NULL || fcResult != FcResultMatch) {
        goto cleanup;
    }
    fontFamily = (*fcPatternFormat)(match, (FcChar8*) "%{family}");
    if (fontFamily == NULL) {
        goto cleanup;
    }
    // result of foundFontName could be set of families, so we left only first family
    char *commaPos = strchr((char *) fontFamily, ',');
    if (commaPos != NULL) {
        *commaPos = '\0';
    }
    if (strstr(queryPtr, (char *) fontFamily) == NULL) {
        goto cleanup;
    }

    fontPath = (*fcPatternFormat)(match, (FcChar8*) propertyPtr);
    if (fontPath == NULL) {
        goto cleanup;
    }
    res = (*env)->NewStringUTF(env, (char *) fontPath);

cleanup:
    if (fontPath) {
        (*fcStrFree)(fontPath);
    }
    if (fontFamily) {
        (*fcStrFree)(fontFamily);
    }
    if (propertyPtr) {
        (*env)->ReleaseStringUTFChars(env, property, (const char*)propertyPtr);
    }
    if (queryPtr) {
        (*env)->ReleaseStringUTFChars(env, query, (const char*)queryPtr);
    }

    return res;
#endif
}