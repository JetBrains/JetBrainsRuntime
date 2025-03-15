/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2025, JetBrains s.r.o.. All rights reserved.
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

#include <stdlib.h>
#include <pthread.h>

#include "AccelTexturePoolLock_pthread.h"
#include "Trace.h"

#define TRACE_LOCK  0


/* lock API implementation */
ATexturePoolLockPrivPtr* AccelTexturePoolLock_InitImpl(void) {
    pthread_mutex_t *l = (pthread_mutex_t*)malloc(sizeof(pthread_mutex_t));
    CHECK_NULL_LOG_RETURN(l, NULL, "AccelTexturePoolLock_initImpl: could not allocate pthread_mutex_t");

    // create new default pthread mutex (non recursive):
    int status = pthread_mutex_init(l, NULL);
    if (status != 0) {
      return NULL;
    }
    if (TRACE_LOCK) J2dRlsTraceLn1(J2D_TRACE_VERBOSE, "AccelTexturePoolLock_initImpl: lock=%p", l);
    return (ATexturePoolLockPrivPtr*)l;
}

void AccelTexturePoolLock_DisposeImpl(ATexturePoolLockPrivPtr *lock) {
    pthread_mutex_t* l = (pthread_mutex_t*)lock;
    if (TRACE_LOCK) J2dRlsTraceLn1(J2D_TRACE_VERBOSE, "AccelTexturePoolLock_DisposeImpl: lock=%p", l);
    pthread_mutex_destroy(l);
    free(l);
}

inline void AccelTexturePoolLock_LockImpl(ATexturePoolLockPrivPtr *lock) {
    pthread_mutex_t* l = (pthread_mutex_t*)lock;
    if (TRACE_LOCK) J2dRlsTraceLn1(J2D_TRACE_VERBOSE, "AccelTexturePoolLock_lockImpl: lock=%p", l);
    pthread_mutex_lock(l);
    if (TRACE_LOCK) J2dRlsTraceLn1(J2D_TRACE_VERBOSE, "AccelTexturePoolLock_lockImpl: lock=%p - locked", l);
}

inline void AccelTexturePoolLock_UnlockImpl(ATexturePoolLockPrivPtr *lock) {
    pthread_mutex_t* l = (pthread_mutex_t*)lock;
    if (TRACE_LOCK) J2dRlsTraceLn1(J2D_TRACE_VERBOSE, "AccelTexturePoolLock_unlockImpl: lock=%p", l);
    pthread_mutex_unlock(l);
    if (TRACE_LOCK) J2dRlsTraceLn1(J2D_TRACE_VERBOSE, "AccelTexturePoolLock_unlockImpl: lock=%p - unlocked", l);
}
