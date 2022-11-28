// Copyright 2000-2022 JetBrains s.r.o.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.


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
