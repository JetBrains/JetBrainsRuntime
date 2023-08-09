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

#include "splashscreen_impl.h"

#include <sys/time.h>
#include <iconv.h>
#include <langinfo.h>
#include <locale.h>
#include <fcntl.h>
#include <poll.h>
#include <sizecalc.h>
#include <pthread.h>
#include <unistd.h>

const int POLL_EVENT_TIMEOUT = 50;

void SplashEventLoop(Splash * splash);
bool SplashCreateWindow(Splash * splash);
void SplashRedrawWindow(Splash * splash);
void SplashUpdateCursor(Splash * splash);
void SplashSetup(Splash * splash);
void SplashUpdateShape(Splash * splash);
bool SplashReconfigureNow(Splash * splash);

bool FlushEvents(Splash * splash);
bool DispatchEvents(Splash * splash);
int GetDisplayFD(Splash * splash);

/* Could use npt but decided to cut down on linked code size */
char*
SplashConvertStringAlloc(const char* in, int* size) {
    const char     *codeset;
    const char     *codeset_out;
    iconv_t         cd;
    size_t          rc;
    char           *buf = NULL, *out;
    size_t          bufSize, inSize, outSize;
    const char* old_locale;

    if (!in) {
        return NULL;
    }
    old_locale = setlocale(LC_ALL, "");

    codeset = nl_langinfo(CODESET);
    if ( codeset == NULL || codeset[0] == 0 ) {
        goto done;
    }
    /* we don't need BOM in output so we choose native BE or LE encoding here */
    codeset_out = (platformByteOrder()==BYTE_ORDER_MSBFIRST) ?
        "UCS-2BE" : "UCS-2LE";

    cd = iconv_open(codeset_out, codeset);
    if (cd == (iconv_t)-1 ) {
        goto done;
    }
    inSize = strlen(in);
    buf = SAFE_SIZE_ARRAY_ALLOC(malloc, inSize, 2);
    if (!buf) {
        return NULL;
    }
    bufSize = inSize*2; // need 2 bytes per char for UCS-2, this is
                        // 2 bytes per source byte max
    out = buf; outSize = bufSize;
    /* linux iconv wants char** source and solaris wants const char**...
       cast to void* */
    rc = iconv(cd, (void*)&in, &inSize, &out, &outSize);
    iconv_close(cd);

    if (rc == (size_t)-1) {
        free(buf);
        buf = NULL;
    } else {
        if (size) {
            *size = (bufSize-outSize)/2; /* bytes to wchars */
        }
    }
done:
    setlocale(LC_ALL, old_locale);
    return buf;
}

void
SplashEventLoop(Splash * splash) {
    struct pollfd pfd[2];
    int rc;
    unsigned lastCursorUpdate;

    pfd[0].fd = splash->controlpipe[0];
    pfd[0].events = POLLIN | POLLPRI;
    pfd[0].revents = 0;

    pfd[1].fd = GetDisplayFD(splash);
    pfd[1].events = POLLIN | POLLPRI;
    pfd[1].revents = 0;

    lastCursorUpdate = SplashTime();

    while (true) {
        if (!FlushEvents(splash)) {
            break;
        }

        if (splash->isVisible > 0 && splash->currentFrame >= 0 && SplashIsStillLooping(splash) &&
            SplashTime() >= splash->time + splash->frames[splash->currentFrame].delay) {
                SplashNextFrame(splash);
                SplashUpdateShape(splash);
                SplashRedrawWindow(splash);
        }

        SplashUnlock(splash);
        rc = poll(pfd, 2, POLL_EVENT_TIMEOUT);
        SplashLock(splash);

        if (SplashTime() - lastCursorUpdate > 100) {
            SplashUpdateCursor(splash);
            lastCursorUpdate = SplashTime();
        }

        if (rc <= 0) {
            continue;
        }

        if (pfd[1].revents) {
            if (!DispatchEvents(splash)) {
                break;
            }
        }

        if (pfd[0].revents) {
            char buf;
            if (read(splash->controlpipe[0], &buf, sizeof(buf)) > 0) {
                switch (buf) {
                    case SPLASHCTL_UPDATE:
                        if (splash->isVisible > 0) {
                            SplashRedrawWindow(splash);
                        }
                        break;
                    case SPLASHCTL_RECONFIGURE:
                        if (splash->isVisible > 0) {
                            if (!SplashReconfigureNow(splash)) {
                                return;
                            }
                        }
                        break;
                    case SPLASHCTL_QUIT:
                        return;
                }
            }
        }
    }
}

unsigned
SplashTime(void) {
    struct timeval tv;
    struct timezone tz;
    unsigned long long msec;

    gettimeofday(&tv, &tz);
    msec = (unsigned long long) tv.tv_sec * 1000 + (unsigned long long) tv.tv_usec / 1000;

    return (unsigned) msec;
}

void
SplashPThreadDestructor(void *data) {
    Splash *splash = data;

    if (splash) {
        SplashCleanup(splash);
    }
}

void *
SplashScreenThread(void *data) {
    Splash *splash = data;

    SplashLock(splash);
    pipe(splash->controlpipe);
    fcntl(splash->controlpipe[0], F_SETFL, fcntl(splash->controlpipe[0], F_GETFL, 0) | O_NONBLOCK);
    splash->time = SplashTime();
    bool init = SplashCreateWindow(splash);
    fflush(stdout);
    if (init) {
        SplashSetup(splash);
        SplashRedrawWindow(splash);
        SplashEventLoop(splash);
    }
    SplashUnlock(splash);
    SplashDone(splash);

    splash->isVisible=-1;
    return 0;
}

void
SplashCreateThread(Splash * splash) {
    pthread_t thr;
    pthread_attr_t attr;

    int rslt = pthread_attr_init(&attr);
    if (rslt != 0) return;
    rslt = pthread_create(&thr, &attr, SplashScreenThread, (void *) splash);
    if (rslt != 0) {
        fprintf(stderr, "Could not create SplashScreen thread, error number:%d\n", rslt);
    }
    pthread_attr_destroy(&attr);
}

void
sendctl(Splash * splash, char code) {
    if (splash && splash->controlpipe[1]) {
        write(splash->controlpipe[1], &code, 1);
    }
}

void
SplashLock(Splash * splash) {
    pthread_mutex_lock(&splash->lock);
}

void
SplashUnlock(Splash * splash) {
    pthread_mutex_unlock(&splash->lock);
}

void
SplashClosePlatform(Splash * splash) {
    sendctl(splash, SPLASHCTL_QUIT);
}

void
SplashUpdate(Splash * splash) {
    sendctl(splash, SPLASHCTL_UPDATE);
}

void
SplashReconfigure(Splash * splash) {
    sendctl(splash, SPLASHCTL_RECONFIGURE);
}

JNIEXPORT jboolean
SplashGetScaledImageName(const char* jarName, const char* fileName,
                           float *scaleFactor, char *scaledImgName,
                           const size_t scaledImageNameLength) {
    *scaleFactor = 1;
#ifndef __linux__
    return JNI_FALSE;
#endif
    *scaleFactor = (float) getNativeScaleFactor(NULL, 1);
    return GetScaledImageName(fileName, scaledImgName, scaleFactor, scaledImageNameLength);
}