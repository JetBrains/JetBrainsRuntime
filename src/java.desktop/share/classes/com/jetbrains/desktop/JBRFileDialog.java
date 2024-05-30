package com.jetbrains.desktop;

import java.io.Serial;
import java.io.Serializable;
import java.lang.annotation.Native;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.awt.*;
import java.util.Objects;

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

    @Native public static final int SELECT_FILES_HINT = 1, SELECT_DIRECTORIES_HINT = 2;
    @Native public static final int CREATE_DIRECTORIES_HINT = 4;

    public static final String OPEN_FILE_BUTTON_KEY = "jbrFileDialogOpenFile";
    public static final String OPEN_DIRECTORY_BUTTON_KEY = "jbrFileDialogSelectDir";
    public static final String ALL_FILES_COMBO_KEY = "jbrFileDialogAllFiles";

    public int hints = CREATE_DIRECTORIES_HINT;
    public String openButtonText;
    public String selectFolderButtonText;
    public String allFilesFilterDescription;
    public String fileFilterDescription;
    public String[] fileFilterExtensions;

    public void setHints(int hints) {
        this.hints = hints;
    }
    public int getHints() {
        return hints;
    }

    public void setLocalizationString(String key, String text) {
        Objects.requireNonNull(key);
        switch (key) {
            case OPEN_FILE_BUTTON_KEY -> openButtonText = text;
            case OPEN_DIRECTORY_BUTTON_KEY -> selectFolderButtonText = text;
            case ALL_FILES_COMBO_KEY -> allFilesFilterDescription = text;
            default -> throw new IllegalArgumentException("unrecognized key: " + key);
        }
    }

    @Deprecated(forRemoval = true)
    public void setLocalizationStrings(String openButtonText, String selectFolderButtonText) {
        setLocalizationString(OPEN_FILE_BUTTON_KEY, openButtonText);
        setLocalizationString(OPEN_DIRECTORY_BUTTON_KEY, selectFolderButtonText);
    }

    public void setFileFilterExtensions(String fileFilterDescription, String[] fileFilterExtensions) {
        this.fileFilterDescription = fileFilterDescription;
        this.fileFilterExtensions = fileFilterExtensions;
    }
}
