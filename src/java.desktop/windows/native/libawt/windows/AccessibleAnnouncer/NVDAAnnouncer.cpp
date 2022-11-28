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

//
// Created by Artem.Semenov on 28.11.2022.
//

#include "NVDAAnnouncer.h"
#include "nvdaController.h"
#include "IJawsApi.h"
#include "javax_swing_AccessibleAnnouncer.h"

bool NVDAAnnounce(JNIEnv *env, jstring str, jint priority)
{
    error_status_t nvdaStatus = nvdaController_testIfRunning();
    if (nvdaStatus == 0) return false;
    const jchar *jchars = env->GetStringChars(str, NULL);
    BSTR param = SysAllocString(jchars);
    if (priority == javax_swing_AccessibleAnnouncer_ANNOUNCE_WITH_INTERRUPTING_CURRENT_OUTPUT) {
        nvdaController_cancelSpeech();
    }
    nvdaStatus = nvdaController_speakText(param);
    if (nvdaStatus == 0) return false;

    return true;
}
