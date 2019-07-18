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
 * Enumerates macOS keyboard layouts covered by this test
 */
public enum Layout {

    ABC                 ("ABC", Layout_ABC.values()),
    US_INTERNATIONAL_PC ("USInternational-PC", Layout_US_INTERNATIONAL_PC.values()),
    SPANISH_ISO         ("Spanish-ISO", Layout_SPANISH_ISO.values()),
    FRENCH_PC           ("French-PC", Layout_FRENCH_PC.values()),
    GERMAN              ("German", Layout_GERMAN.values()),

    ;

    // Real macOS keyboard layout name without "com.apple.keylayout." prefix
    private String name;
    // Array of test keys for the layout
    private LayoutKey[] layoutKeys;

    Layout(String name, LayoutKey[] layoutKeys) {
        this.name = name;
        this.layoutKeys = layoutKeys;
    }

    // Return array of test keys for the layout
    LayoutKey[] getLayoutKeys() {
        return layoutKeys;
    }

    @Override
    public String toString() {
        return "com.apple.keylayout." + name;
    }
}
