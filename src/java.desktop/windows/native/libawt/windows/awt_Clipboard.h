/*
 * Copyright (c) 1996, 2020, Oracle and/or its affiliates. All rights reserved.
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

#ifndef AWT_CLIPBOARD_H
#define AWT_CLIPBOARD_H

#include "awt.h"


/************************************************************************
 * AwtClipboard class
 */

class AwtClipboard {
private:
    static BOOL isGettingOwnership;
    static volatile BOOL isClipboardViewerRegistered;
    static volatile jmethodID handleContentsChangedMID;

public:
    static jmethodID lostSelectionOwnershipMID;
    static jobject theCurrentClipboard;

    INLINE static void GetOwnership() {
        AwtClipboard::isGettingOwnership = TRUE;
        VERIFY(EmptyClipboard());
        AwtClipboard::isGettingOwnership = FALSE;
    }

    INLINE static BOOL IsGettingOwnership() {
        return isGettingOwnership;
    }

    static void LostOwnership(JNIEnv *env);
    static void WmClipboardUpdate(JNIEnv *env);
    static void RegisterClipboardViewer(JNIEnv *env, jobject jclipboard);
    static void UnregisterClipboardViewer(JNIEnv *env);

    // ================= IDEA-316996 AWT clipboard extra logging facilities =================
public:
    template<size_t WCharsCapacity>
    class FixedString;

    template<typename T>
    struct FormatArray
    {
        const T* const arr;
        const size_t arrLength;
    };

public:
    static void initializeLogging(JNIEnv* env, jclass WClipboardCls);

public:
    template<typename T>
    static FormatArray<T> fmtArr(const T* arr, size_t arrLength) { return {arr, arrLength}; }

public:
    template<typename Arg1, typename... Args>
    static void logSevere(const Arg1& arg1, const Args&... args);

    template<size_t WCharsCapacity>
    static void logSevere(const FixedString<WCharsCapacity>& completedString);


    template<typename Arg1, typename... Args>
    static void logWarning(const Arg1& arg1, const Args&... args);

    template<size_t WCharsCapacity>
    static void logWarning(const FixedString<WCharsCapacity>& completedString);


    template<typename Arg1, typename... Args>
    static void logInfo(const Arg1& arg1, const Args&... args);

    template<size_t WCharsCapacity>
    static void logInfo(const FixedString<WCharsCapacity>& completedString);

private:
    static volatile jmethodID logSevereMID;
    static volatile jmethodID logWarningMID;
    static volatile jmethodID logInfoMID;
    // ======================================================================================
};

#endif /* AWT_CLIPBOARD_H */
