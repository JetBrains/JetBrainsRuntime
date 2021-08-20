/*
 * Copyright (c) 1998, 2022, Oracle and/or its affiliates. All rights reserved.
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

#if defined(__linux__)
#include <string.h>
#endif /* __linux__ */
#include <stdio.h>
#include <stdlib.h>
#include <strings.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <unistd.h>

#include "fontconfigmanager.h"

#include <jni.h>
#include <jni_util.h>
#include <jvm_md.h>
#include <sizecalc.h>
#ifndef HEADLESS
#include <X11/Xlib.h>
#include <awt.h>
#else
/* locks ought to be included from awt.h */
#define AWT_LOCK()
#define AWT_UNLOCK()
#endif /* !HEADLESS */

#ifndef HEADLESS
extern Display *awt_display;
#endif /* !HEADLESS */

#define FONTCONFIG_DLL_VERSIONED VERSIONED_JNI_LIB_NAME("fontconfig", "1")
#define FONTCONFIG_DLL JNI_LIB_NAME("fontconfig")

#define MAXFDIRS 512    /* Max number of directories that contain fonts */

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

typedef struct {
    const char *name[MAXFDIRS];
    int  num;
} fDirRecord, *fDirRecordPtr;

#ifdef XAWT

/*
 * Returns True if display is local, False of it's remote.
 */
jboolean isDisplayLocal(JNIEnv *env) {
    static jboolean isLocal = False;
    static jboolean isLocalSet = False;

    if (! isLocalSet) {
      jclass geCls = (*env)->FindClass(env, "java/awt/GraphicsEnvironment");
      CHECK_NULL_RETURN(geCls, JNI_FALSE);
      jmethodID getLocalGE = (*env)->GetStaticMethodID(env, geCls,
                                                 "getLocalGraphicsEnvironment",
                                           "()Ljava/awt/GraphicsEnvironment;");
      CHECK_NULL_RETURN(getLocalGE, JNI_FALSE);
      jobject ge = (*env)->CallStaticObjectMethod(env, geCls, getLocalGE);
      JNU_CHECK_EXCEPTION_RETURN(env, JNI_FALSE);

      jclass sgeCls = (*env)->FindClass(env,
                                        "sun/java2d/SunGraphicsEnvironment");
      CHECK_NULL_RETURN(sgeCls, JNI_FALSE);
      if ((*env)->IsInstanceOf(env, ge, sgeCls)) {
        jmethodID isDisplayLocal = (*env)->GetMethodID(env, sgeCls,
                                                       "isDisplayLocal",
                                                       "()Z");
        JNU_CHECK_EXCEPTION_RETURN(env, JNI_FALSE);
        isLocal = (*env)->CallBooleanMethod(env, ge, isDisplayLocal);
        JNU_CHECK_EXCEPTION_RETURN(env, JNI_FALSE);
      } else {
        isLocal = True;
      }
      isLocalSet = True;
    }

    return isLocal;
}

static char **getX11FontPath ()
{
    char **x11Path, **fontdirs;
    int i, pos, slen, nPaths;

    x11Path = XGetFontPath (awt_display, &nPaths);

    /* This isn't ever going to be perfect: the font path may contain
     * much we aren't interested in, but the cost should be moderate
     * Exclude all directories that contain the strings "Speedo","/F3/",
     * "75dpi", "100dpi", "misc" or "bitmap", or don't begin with a "/",
     * the last of which should exclude font servers.
     * Also exclude the user specific ".gnome*" directories which
     * aren't going to contain the system fonts we need.
     * Hopefully we are left only with Type1 and TrueType directories.
     * It doesn't matter much if there are extraneous directories, it'll just
     * cost us a little wasted effort upstream.
     */
    fontdirs = (char**)calloc(nPaths+1, sizeof(char*));
    if (fontdirs == NULL) {
        return NULL;
    }
    pos = 0;
    for (i=0; i < nPaths; i++) {
        if (x11Path[i][0] != '/') {
            continue;
        }
        if (strstr(x11Path[i], "/75dpi") != NULL) {
            continue;
        }
        if (strstr(x11Path[i], "/100dpi") != NULL) {
            continue;
        }
        if (strstr(x11Path[i], "/misc") != NULL) {
            continue;
        }
        if (strstr(x11Path[i], "/Speedo") != NULL) {
            continue;
        }
        if (strstr(x11Path[i], ".gnome") != NULL) {
            continue;
        }
        fontdirs[pos] = strdup(x11Path[i]);
        slen = strlen(fontdirs[pos]);
        if (slen > 0 && fontdirs[pos][slen-1] == '/') {
            fontdirs[pos][slen-1] = '\0'; /* null out trailing "/"  */
        }
        pos++;
    }

    XFreeFontPath(x11Path);
    if (pos == 0) {
        free(fontdirs);
        fontdirs = NULL;
    }
    return fontdirs;
}


#endif /* XAWT */

#if defined(__linux__)
/* from awt_LoadLibrary.c */
JNIEXPORT jboolean JNICALL AWTIsHeadless();
#endif

/* This eliminates duplicates, at a non-linear but acceptable cost
 * since the lists are expected to be reasonably short, and then
 * deletes references to non-existent directories, and returns
 * a single path consisting of unique font directories.
 */
static char* mergePaths(char **p1, char **p2, char **p3, jboolean noType1) {

    int len1=0, len2=0, len3=0, totalLen=0, numDirs=0,
        currLen, i, j, found, pathLen=0;
    char **ptr, **fontdirs;
    char *fontPath = NULL;

    if (p1 != NULL) {
        ptr = p1;
        while (*ptr++ != NULL) len1++;
    }
    if (p2 != NULL) {
        ptr = p2;

        while (*ptr++ != NULL) len2++;
    }
    if (p3 != NULL) {
        ptr = p3;
        while (*ptr++ != NULL) len3++;
    }
    totalLen = len1+len2+len3;
    fontdirs = (char**)calloc(totalLen, sizeof(char*));
    if (fontdirs == NULL) {
        return NULL;
    }

    for (i=0; i < len1; i++) {
        if (noType1 && strstr(p1[i], "Type1") != NULL) {
            continue;
        }
        fontdirs[numDirs++] = p1[i];
    }

    currLen = numDirs; /* only compare against previous path dirs */
    for (i=0; i < len2; i++) {
        if (noType1 && strstr(p2[i], "Type1") != NULL) {
            continue;
        }
        found = 0;
        for (j=0; j < currLen; j++) {
            if (strcmp(fontdirs[j], p2[i]) == 0) {
                found = 1;
                break;
            }
        }
        if (!found) {
           fontdirs[numDirs++] = p2[i];
        }
    }

    currLen = numDirs; /* only compare against previous path dirs */
    for (i=0; i < len3; i++) {
        if (noType1 && strstr(p3[i], "Type1") != NULL) {
            continue;
        }
        found = 0;
        for (j=0; j < currLen; j++) {
            if (strcmp(fontdirs[j], p3[i]) == 0) {
                found = 1;
                break;
            }
        }
        if (!found) {
           fontdirs[numDirs++] = p3[i];
        }
    }

    /* Now fontdirs contains unique dirs and numDirs records how many.
     * What we don't know is if they all exist. On reflection I think
     * this isn't an issue, so for now I will return all these locations,
     * converted to one string */
    for (i=0; i<numDirs; i++) {
        pathLen += (strlen(fontdirs[i]) + 1);
    }
    if (pathLen > 0 && (fontPath = malloc(pathLen))) {
        *fontPath = '\0';
        for (i = 0; i<numDirs; i++) {
            if (i != 0) {
                strcat(fontPath, ":");
            }
            strcat(fontPath, fontdirs[i]);
        }
    }
    free (fontdirs);

    return fontPath;
}

/*
 * The goal of this function is to find all "system" fonts which
 * are needed by the JRE to display text in supported locales etc, and
 * to support APIs which allow users to enumerate all system fonts and use
 * them from their Java applications.
 * The preferred mechanism is now using the new "fontconfig" library
 * This exists on newer versions of Linux and Solaris (S10 and above)
 * The library is dynamically located. The results are merged with
 * a set of "known" locations and with the X11 font path, if running in
 * a local X11 environment.
 * The hardwired paths are built into the JDK binary so as new font locations
 * are created on a host platform for them to be located by the JRE they will
 * need to be added ito the host's font configuration database, typically
 * /etc/fonts/local.conf, and to ensure that directory contains a fonts.dir
 * NB: Fontconfig also depends heavily for performance on the host O/S
 * maintaining up to date caches.
 * This is consistent with the requirements of the desktop environments
 * on these OSes.
 * This also frees us from X11 APIs as JRE is required to function in
 * a "headless" mode where there is no Xserver.
 */
static char *getPlatformFontPathChars(JNIEnv *env, jboolean noType1, jboolean isX11) {

    char **fcdirs = NULL, **x11dirs = NULL, **knowndirs = NULL, *path = NULL;

    /* As of 1.5 we try to use fontconfig on both Solaris and Linux.
     * If its not available NULL is returned.
     */
    fcdirs = getFontConfigLocations();

#if defined(__linux__)
    knowndirs = fullLinuxFontPath;
#elif defined(_AIX)
    knowndirs = fullAixFontPath;
#endif
    /* REMIND: this code requires to be executed when the GraphicsEnvironment
     * is already initialised. That is always true, but if it were not so,
     * this code could throw an exception and the fontpath would fail to
     * be initialised.
     */
#ifdef XAWT
    if (isX11) { // The following only works in an x11 environment.
#if defined(__linux__)
    /* There's no headless build on linux ... */
    if (!AWTIsHeadless()) { /* .. so need to call a function to check */
#endif
      /* Using the X11 font path to locate font files is now a fallback
       * useful only if fontconfig failed, or is incomplete. So we could
       * remove this code completely and the consequences should be rare
       * and non-fatal. If this happens, then the calling Java code can
       * be modified to no longer require that the AWT lock (the X11GE)
       * be initialised prior to calling this code.
       */
    AWT_LOCK();
    if (isDisplayLocal(env)) {
        x11dirs = getX11FontPath();
    }
    AWT_UNLOCK();
#if defined(__linux__)
    }
#endif
    }
#endif /* XAWT */
    path = mergePaths(fcdirs, x11dirs, knowndirs, noType1);
    if (fcdirs != NULL) {
        char **p = fcdirs;
        while (*p != NULL)  free(*p++);
        free(fcdirs);
    }

    if (x11dirs != NULL) {
        char **p = x11dirs;
        while (*p != NULL) free(*p++);
        free(x11dirs);
    }

    return path;
}

JNIEXPORT jstring JNICALL Java_sun_awt_FcFontManager_getFontPathNative
(JNIEnv *env, jobject thiz, jboolean noType1, jboolean isX11) {
    jstring ret;
    static char *ptr = NULL; /* retain result across calls */

    if (ptr == NULL) {
        ptr = getPlatformFontPathChars(env, noType1, isX11);
    }
    ret = (*env)->NewStringUTF(env, ptr);
    return ret;
}
