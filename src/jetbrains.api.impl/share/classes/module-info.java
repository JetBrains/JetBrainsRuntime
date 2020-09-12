/*
 * Copyright 2000-2020 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.jetbrains.JBRService;
import com.jetbrains.impl.SampleJBRApiImpl;

module jetbrains.api.impl {

    requires jetbrains.api;

    // We probably don't want to add `provides with` for each version of API, so we just provide `JBRService`
    // implementation, `JBRService#load` will take care of the rest
    provides JBRService with SampleJBRApiImpl;

}