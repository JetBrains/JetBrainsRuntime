/*
 * Copyright 2000-2019 JetBrains s.r.o.
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

/*
 * Common interface for all test layouts.
 * Keyboard layouts are declared as a separate enums to allow testing of different key sets for different layouts.
 * As layouts are declared as enums, they cannot extend any other class, but need to have some common functionality.
 */
public interface LayoutKey {

    // Return Key object containing common key functionality
    Key getKey();
}
