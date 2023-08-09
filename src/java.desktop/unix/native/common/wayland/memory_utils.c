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

#include "memory_utils.h"

#include <unistd.h>
#include <time.h>
#include <string.h>
#include <sys/mman.h>
#include <stdlib.h>
#include <errno.h>
#include <fcntl.h>

static void
RandomName(char *buf) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    long r = ts.tv_nsec;
    while (*buf) {
        *buf++ = 'A' + (r & 15) + (r & 16) * 2;
        r >>= 5;
    }
}

static int
CreateSharedMemoryFile(const char* baseName) {
    // constructing the full name of the form /baseName-XXXXXX
    int baseLen = strlen(baseName);
    char *name = (char*) malloc(baseLen + 9);
    if (!name)
        return -1;
    name[0] = '/';
    strcpy(name + 1, baseName);
    strcpy(name + baseLen + 1, "-XXXXXX");

    int retries = 100;
    do {
        RandomName(name + baseLen + 2);
        --retries;
        int fd = shm_open(name, O_RDWR | O_CREAT | O_EXCL, 0600);
        if (fd >= 0) {
            shm_unlink(name);
            free(name);
            return fd;
        }
    } while (retries > 0 && errno == EEXIST);
    free(name);
    return -1;
}

int
AllocateSharedMemoryFile(size_t size, const char* baseName) {
    int fd = CreateSharedMemoryFile(baseName);
    if (fd < 0)
        return -1;
    int ret;
    do {
        ret = ftruncate(fd, size);
    } while (ret < 0 && errno == EINTR);
    if (ret < 0) {
        close(fd);
        return -1;
    }
    return fd;
}
