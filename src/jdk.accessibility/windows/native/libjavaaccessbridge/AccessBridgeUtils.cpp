// Copyright 2023 JetBrains s.r.o.
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


#include "AccessBridgeUtils.h"
#include "AccessBridgeDebug.h"  // PrintDebugString
#include <wchar.h>              // wmemset

std::size_t AccessBridgeUtils::CopyJavaStringToWCharBuffer(
    JNIEnv &env,
    const jstring javaString,
    wchar_t * const buffer,
    const std::size_t bufferCapacityInWChars,
    const bool replaceLastCharWith0IfNoSpace
) {
    using ULL = unsigned long long;
    PrintDebugString(
        " [INFO]: In AccessBridgeUtils::CopyJavaStringToWCharBuffer"
        "(env=%p, javaString=%p, buffer=%p, bufferCapacityInWChars=%llu, replaceLastCharWith0IfNoSpace=%s):",
        &env, javaString, buffer, ULL{ bufferCapacityInWChars }, replaceLastCharWith0IfNoSpace ? "true" : "false"
    );

    if (bufferCapacityInWChars < 1) {
        PrintDebugString(" [WARN]:   bufferCapacityInWChars < 1 ; returning.");
        return 0;
    }
    if (buffer == nullptr) {
        PrintDebugString("[ERROR]:   buffer is null ; returning.");
        return 0;
    }

    std::size_t jCharsCopied = 0;
    if (javaString == nullptr) {
        PrintDebugString("[ERROR]:   javaString is null.");
    } else {
        const jsize javaStringLength = env.GetStringLength(javaString);

        if (env.ExceptionCheck() == JNI_TRUE) {
            PrintDebugString("[ERROR]:   a java exception occurred while getting the length of javaString.");
        } else if (javaStringLength < 1) {
            PrintDebugString(" [WARN]:   the length of javaString (%ld) < 1.", long{javaStringLength});
        } else {
            const std::size_t jsl = (std::size_t)javaStringLength;
            const jsize jCharsToCopy = (jsize)( (bufferCapacityInWChars < jsl) ? bufferCapacityInWChars : jsl );

            //assert( (jCharsToCopy >= 0) );

            static_assert(
                sizeof(wchar_t) == sizeof(jchar),
                "sizeof(wchar_t) must be equal to sizeof(jchar) to be able to copy the content of javaString"
            );
            env.GetStringRegion(javaString, 0, jCharsToCopy, reinterpret_cast<jchar*>(buffer));

            if (env.ExceptionCheck() == JNI_TRUE) {
                PrintDebugString("[ERROR]:   a java exception occurred while obtaining the content of javaString.");
            } else {
                jCharsCopied = (std::size_t)jCharsToCopy;
            }
        }
    }

    if (jCharsCopied < bufferCapacityInWChars) {
        (void)wmemset(buffer + jCharsCopied, 0, bufferCapacityInWChars - jCharsCopied);
    } else if (replaceLastCharWith0IfNoSpace) {
        buffer[bufferCapacityInWChars - 1] = 0;
    }

    //assert( (jCharsCopied <= bufferCapacityInWChars) );

    return jCharsCopied;
}
