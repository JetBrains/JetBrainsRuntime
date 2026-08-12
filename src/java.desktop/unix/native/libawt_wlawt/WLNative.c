// Copyright 2026 JetBrains s.r.o.
// DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
//
// This code is free software; you can redistribute it and/or modify it
// under the terms of the GNU General Public License version 2 only, as
// published by the Free Software Foundation.  Oracle designates this
// particular file as subject to the "Classpath" exception as provided
// by Oracle in the LICENSE file that accompanied this code.
//
// This code is distributed in the hope that it will be useful, but WITHOUT
// ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
// FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
// version 2 for more details (a copy is included in the LICENSE file that
// accompanied this code).
//
// You should have received a copy of the GNU General Public License version
// 2 along with this work; if not, write to the Free Software Foundation,
// Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
//
// Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
// or visit www.oracle.com if you need additional information or have any
// questions.

#include "sun_awt_wl_protocol_WLNative.h"

#include "jlong_md.h"
#include "jni_util.h"

#include <fcntl.h>
#include <poll.h>
#include <unistd.h>

#include <errno.h>
#include <string.h>
#include <stddef.h>

#define NAMED_CONSTANTS(X) \
    /* poll */ \
    X(POLLIN) \
    X(POLLOUT) \
    X(POLLERR) \
    X(POLLHUP) \

struct NamedConstant
{
    const char* name;
    int value;
};

static const struct NamedConstant namedConstants[] = {
#define NAMED_CONSTANT_DEF(name) { #name, name },
    NAMED_CONSTANTS(NAMED_CONSTANT_DEF)
#undef NAMED_CONSTANT_DEF
};
static const size_t namedConstantsCount = sizeof(namedConstants) / sizeof(namedConstants[0]);

/*
 * Class:     sun_awt_wl_protocol_WLNative
 * Method:    nativeGetConstant
 * Signature: (J)I
 */
JNIEXPORT jint JNICALL Java_sun_awt_wl_protocol_WLNative_nativeGetConstant(JNIEnv* env, jclass clazz, jlong namePtr)
{
    (void)env;
    (void)clazz;

    const char* name = jlong_to_ptr(namePtr);

    for (size_t i = 0; i < namedConstantsCount; ++i) {
        struct NamedConstant constant = namedConstants[i];
        if (0 == strcmp(constant.name, name)) {
            return constant.value;
        }
    }

    JNU_ThrowInternalError(env, "unknown named constant requested");

    return 0;
}

/*
 * Class:     sun_awt_wl_protocol_WLNative
 * Method:    nativeCloseFd
 * Signature: (I)V
 */
JNIEXPORT void JNICALL Java_sun_awt_wl_protocol_WLNative_nativeCloseFd(JNIEnv *env, jclass clazz, jint fd)
{
    (void)env;
    (void)clazz;
    close(fd);
}

/*
 * Class:     sun_awt_wl_protocol_WLNative
 * Method:    nativeFdNonBlock
 * Signature: (I)I
 */
JNIEXPORT jint JNICALL Java_sun_awt_wl_protocol_WLNative_nativeFdNonBlock(JNIEnv *env, jclass clazz, jint fd)
{
    (void)env;
    (void)clazz;

    errno = 0;
    int flags = fcntl(fd, F_GETFL);
    if (flags == -1 && errno != 0) {
        return -errno;
    }

    int ret = fcntl(fd, F_SETFL, flags | O_NONBLOCK);
    if (ret == -1) {
        return -errno;
    }

    return 0;
}

static int setCloexec(int fd)
{
    errno = 0;
    int flags = fcntl(fd, F_GETFD);
    if (flags == -1 && errno != 0) {
        return -errno;
    }

    int ret = fcntl(fd, F_SETFD, flags | FD_CLOEXEC);
    if (ret == -1) {
        return -errno;
    }

    return 0;
}

/*
 * Class:     sun_awt_wl_protocol_WLNative
 * Method:    nativeCreatePipe
 * Signature: (J)I
 */
JNIEXPORT jint JNICALL Java_sun_awt_wl_protocol_WLNative_nativeCreatePipe(JNIEnv *env, jclass clazz, jlong fdsPtr)
{
    (void)env;
    (void)clazz;

    int *fds = jlong_to_ptr(fdsPtr);
    int ret = pipe(fds);
    if (ret == -1) {
        return -errno;
    }

    int err = setCloexec(fds[0]);
    if (err == 0) {
        err = setCloexec(fds[1]);
    }
    if (err != 0) {
        close(fds[0]);
        close(fds[1]);
        return err;
    }

    return 0;
}

/*
 * Class:     sun_awt_wl_protocol_WLNative
 * Method:    nativePoll
 * Signature: (JJ)I
 */
JNIEXPORT jint JNICALL Java_sun_awt_wl_protocol_WLNative_nativePoll(JNIEnv *env, jclass clazz, jlong pollfdsPtr, jlong nFds)
{
    (void)env;
    (void)clazz;

    struct pollfd *fds = jlong_to_ptr(pollfdsPtr);
    nfds_t n = (nfds_t)nFds;

    while (1) {
        int ret = poll(fds, n, -1);
        if (ret == -1) {
            if (errno == EINTR) continue;
            return -errno;
        }
        return 0;
    }
}
