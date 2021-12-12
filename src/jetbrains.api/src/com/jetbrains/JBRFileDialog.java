/*
 * Copyright 2000-2021 JetBrains s.r.o.
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

package com.jetbrains;

import java.awt.*;

public interface JBRFileDialog {

    /*CONST com.jetbrains.desktop.JBRFileDialog.*_HINT*/

    static JBRFileDialog get(FileDialog dialog) {
        if (JBRFileDialogService.INSTANCE == null) return null;
        else return JBRFileDialogService.INSTANCE.getFileDialog(dialog);
    }

    /**
     * Set JBR-specific file dialog hints:
     * <ul>
     *     <li>SELECT_FILES_HINT, SELECT_DIRECTORIES_HINT - Whether to select files, directories or both
     *     (used when common file dialogs are enabled on Windows, or on macOS),
     *     both unset bits are treated as a default platform-specific behavior</li>
     *     <li>CREATE_DIRECTORIES_HINT - Whether to allow creating directories or not (used on macOS)</li>
     * </ul>
     */
    void setHints(int hints);

    /**
     * @see #setHints(int) 
     */
    int getHints();

    void setLocalizationStrings(String openButtonText, String selectFolderButtonText);
}

interface JBRFileDialogService {
    JBRFileDialogService INSTANCE = JBR.getService(JBRFileDialogService.class);
    JBRFileDialog getFileDialog(FileDialog dialog);
}
