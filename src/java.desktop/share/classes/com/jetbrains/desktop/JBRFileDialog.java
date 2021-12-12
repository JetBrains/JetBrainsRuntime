package com.jetbrains.desktop;

import java.io.Serial;
import java.io.Serializable;
import java.lang.annotation.Native;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.awt.*;

public class JBRFileDialog implements Serializable {

    @Serial
    private static final long serialVersionUID = -9154712118353824660L;

    private static final VarHandle getter;
    static {
        try {
            getter = MethodHandles.privateLookupIn(FileDialog.class, MethodHandles.lookup())
                    .findVarHandle(FileDialog.class, "jbrDialog", JBRFileDialog.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new Error(e);
        }
    }
    public static JBRFileDialog get(FileDialog dialog) {
        return (JBRFileDialog) getter.get(dialog);
    }

    /**
     * Whether to select files, directories or both (used when common file dialogs are enabled on Windows, or on macOS)
     */
    @Native public static final int SELECT_FILES_HINT = 1, SELECT_DIRECTORIES_HINT = 2;
    /**
     * Whether to allow creating directories or not (used on macOS)
     */
    @Native public static final int CREATE_DIRECTORIES_HINT = 4;

    public int hints = CREATE_DIRECTORIES_HINT;

    /**
     * Text for "Open" button (used when common file dialogs are enabled on
     * Windows).
     */
    public String openButtonText;

    /**
     * Text for "Select Folder" button (used when common file dialogs are
     * enabled on Windows).
     */
    public String selectFolderButtonText;

    public void setHints(int hints) {
        this.hints = hints;
    }
    public int getHints() {
        return hints;
    }

    public void setLocalizationStrings(String openButtonText, String selectFolderButtonText) {
        this.openButtonText = openButtonText;
        this.selectFolderButtonText = selectFolderButtonText;
    }

}
