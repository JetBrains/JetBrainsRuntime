/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2022, JetBrains s.r.o.. All rights reserved.
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


#ifndef NVDA_CLIENT_H
#define NVDA_CLIENT_H

#include <stddef.h> // wchar_t      NOLINT(modernize-deprecated-headers)


class NVDAClient final
{
public:
    /**
     * @return nullptr if the NVDA client has failed to initialize
     */
    static NVDAClient* getInstance();

public: // forbids copies and movements
    NVDAClient(const NVDAClient&) = delete;
    NVDAClient(NVDAClient&&) = delete;

    NVDAClient& operator=(const NVDAClient&) = delete;
    NVDAClient& operator=(NVDAClient&&) = delete;

public: // NVDA accessors
    // TODO: reconsider the data type is used for error reporting (maybe the bool isn't informative enough)

    bool testIfRunning() const;
    bool speakText(const wchar_t* text) const;
    bool cancelSpeech() const;
    bool brailleMessage(const wchar_t* text) const;

private:
    bool isInitialized_;

private: // ctors/dtor (NVDA RAII initialization)
    NVDAClient();
    ~NVDAClient() noexcept;
};


#endif // ndef NVDA_CLIENT_H
