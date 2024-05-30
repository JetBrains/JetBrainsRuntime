/*
 * Copyright 2000-2024 JetBrains s.r.o.
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

package com.jetbrains;

import java.awt.*;

/**
 * Extensions to the AWT {@code FileDialog} that allow clients fully use a native file chooser
 * on supported platforms (currently macOS and Windows; the latter requires setting
 * {@code sun.awt.windows.useCommonItemDialog} property to {@code true}).
 */
public interface JBRFileDialog {

    /*CONST com.jetbrains.desktop.JBRFileDialog.*_HINT*/

    static JBRFileDialog get(FileDialog dialog) {
        if (JBRFileDialogService.INSTANCE == null) return null;
        else return JBRFileDialogService.INSTANCE.getFileDialog(dialog);
    }

    /**
     * Set file dialog hints:
     * <ul>
     *     <li>SELECT_FILES_HINT, SELECT_DIRECTORIES_HINT - whether to select files, directories, or both;
     *     if neither of the two is set, the behavior is platform-specific</li>
     *     <li>CREATE_DIRECTORIES_HINT - whether to allow creating directories or not (macOS)</li>
     * </ul>
     */
    void setHints(int hints);

    /**
     * @see #setHints(int) 
     */
    int getHints();

    /*CONST com.jetbrains.desktop.JBRFileDialog.*_KEY*/

    /**
     * Change text of UI elements (Windows).
     * Supported keys:
     * <ul>
     *     <li>OPEN_FILE_BUTTON_KEY - "open" button when a file is selected in the list</li>
     *     <li>OPEN_DIRECTORY_BUTTON_KEY - "open" button when a directory is selected in the list</li>
     *     <li>ALL_FILES_COMBO_KEY - "all files" item in the file filter combo box</li>
     * </ul>
     */
    void setLocalizationString(String key, String text);

    /** @deprecated use {@link #setLocalizationString} */
    @Deprecated(forRemoval = true)
    void setLocalizationStrings(String openButtonText, String selectFolderButtonText);

    /**
     * Set file filter - a set of file extensions for files to be visible (Windows)
     * or not greyed out (macOS), and a name for the file filter combo box (Windows).
     */
    void setFileFilterExtensions(String fileFilterDescription, String[] fileFilterExtensions);
}

interface JBRFileDialogService {
    JBRFileDialogService INSTANCE = JBR.getService(JBRFileDialogService.class);
    JBRFileDialog getFileDialog(FileDialog dialog);
}
