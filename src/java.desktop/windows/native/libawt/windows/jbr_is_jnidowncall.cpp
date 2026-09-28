/*
 * Copyright (c) 2026, JetBrains s.r.o.. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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


#include "jbr_is_jnidowncall.h"

#include <cstring> // std::strstr, std::size_t

bool jbr_is_jni_downcall(const char * const funcSigMacro, const char * const functionMacro) noexcept
{
    try
    {
        //
        // The implementation considers the passed __FUNCSIG__ to describe a JNI downcall function if
        // 1. It matches the mask: ... FUNCTION-NAME ... "(" ... "JNIEnv" ... "jobject"|"jclass" ...
        // AND
        // 2. FUNCTION-NAME starts with "Java_" and does not contain ":".
        //
        // Of course, such an approach is not completely false-positive proof, but:
        // 1. The chances of having a function matching these conditions but not being an actual JNI downcall are
        //    extremely low taking into account the OpenJDK codestyle, type system, etc.
        // 2. The approach is (expected to be) false-negative proof (i.e. returns true to every single actual JNI downcall)
        //
        // The rules stem from the following facts:
        // 1. JNI specification https://docs.oracle.com/en/java/javase/11/docs/specs/jni/design.html:
        //   1.1. Any native method must be prefixed with "Java_"
        //   1.2. The first parameter must be JNIEnv*
        //   1.3. The second parameter must be jobject or jclass
        // 2. The function name of a standalone function can not contain ":" characters, it's only present in class methods (e.g. "MyClass::myMethod")
        //

        // Note: how __FUNCSIG__ is rendered for the function "Java_sun_awt_windows_WToolkit_initIDs" under different front-ends:
        // * MSVC: void __cdecl Java_sun_awt_windows_WToolkit_initIDs(struct JNIEnv_ *,class _jclass *)
        // * clang-cl: void __cdecl Java_sun_awt_windows_WToolkit_initIDs(JNIEnv *, jclass)

        // Step 1: checking that the function name starts with "Java_"
        if (std::strstr(functionMacro, "Java_") != functionMacro)
        {
            // the function name does not start with "Java_"
            return false;
        }

        // Step 2: checking that there are no ":" in the function name
        if (std::strstr(functionMacro, ":") != nullptr)
        {
            // there is a ":" character in the function name
            return false;
        }

        // Step 3: checking that there is a "(" after the function name
        const char* const nameInsideSigPtr = std::strstr(funcSigMacro, functionMacro);
        if (nameInsideSigPtr == nullptr)
        {
            // __FUNCSIG__ does not contain __FUNCTION__ for some reason
            return false;
        }

        const std::size_t nameLen = std::strlen(functionMacro);
        const char * const sigAfterFuncNamePtr = nameInsideSigPtr + nameLen;
        const char * const sigParamStartPtr = std::strstr(sigAfterFuncNamePtr, "(");
        if (sigParamStartPtr == nullptr)
        {
            // __FUNCSIG__ does not contain "(" after the function name
            return false;
        }

        // Step 4: checking that there is a "JNIEnv" after the "("
        const char* const jniEnvAfterSigParamStartPtr = std::strstr(sigParamStartPtr, "JNIEnv");
        if (jniEnvAfterSigParamStartPtr == nullptr)
        {
            // __FUNCSIG__ does not contain a "JNIEnv" parameter, which must be present in every JNI downcall
            return false;
        }

        // Step 5: checking that there is a "jclass" or "jobject" after the "JNIEnv"
        const char* jobjectOrjclassAfterJniEnvPtr = std::strstr(jniEnvAfterSigParamStartPtr, "jobject");
        if (jobjectOrjclassAfterJniEnvPtr == nullptr)
        {
            jobjectOrjclassAfterJniEnvPtr = std::strstr(jniEnvAfterSigParamStartPtr, "jclass");
        }
        if (jobjectOrjclassAfterJniEnvPtr == nullptr)
        {
            // __FUNCSIG__ does not contain a "jobject" or a "jclass" parameter after the JNIEnv
            return false;
        }

        return true;
    }
    catch (...)
    {
    }
    return false;
}
