/*
 * Copyright 2000-2024 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

import java.awt.*;
import java.io.*;

/**
 * @test
 * @summary DetectingOSThemeTest checks that JBR could correctly detect OS theme and notify about theme changing in time
 * @run main DetectingOSThemeTest
 * @requires (os.family == "linux")
 */
public class DetectingOSThemeTest {
    private static final int TIME_TO_WAIT = 2000;
    private static final String LIGHT_THEME_NAME = "Light";
    private static final String DARK_THEME_NAME = "Dark";
    private static final String UNDEFINED_THEME_NAME = "Undefined";
    private static boolean isKDE = false;

    private static String currentTheme() {
        Boolean val = (Boolean) Toolkit.getDefaultToolkit().getDesktopProperty("awt.os.theme.isDark");
        if (val == null) {
            return UNDEFINED_THEME_NAME;
        }
        return (val) ? DARK_THEME_NAME : LIGHT_THEME_NAME;
    }

    private static void setOsDarkTheme(String val) throws Exception {
        if (isKDE) {
            if (val.equals(DARK_THEME_NAME)) {
                Runtime.getRuntime().exec("plasma-apply-colorscheme BreezeDark");
            } else {
                Runtime.getRuntime().exec("plasma-apply-colorscheme BreezeLight");
            }
        } else {
            if (val.equals(DARK_THEME_NAME)) {
                Runtime.getRuntime().exec("gsettings set org.gnome.desktop.interface gtk-theme 'Adwaita-dark'");
                Runtime.getRuntime().exec("gsettings set org.gnome.desktop.interface color-scheme 'prefer-dark'");
            } else {
                Runtime.getRuntime().exec("gsettings set org.gnome.desktop.interface gtk-theme 'Adwaita'");
                Runtime.getRuntime().exec("gsettings set org.gnome.desktop.interface color-scheme 'default'");
            }
        }
    }

    private static String currentTheme = null;

    public static void main(String[] args) throws Exception {
        isKDE = "KDE".equals(System.getenv("XDG_CURRENT_DESKTOP"));
        currentTheme = currentTheme();
        if (currentTheme.equals(UNDEFINED_THEME_NAME)) {
            throw new RuntimeException("Test Failed! Cannot detect current OS theme");
        }

        String initialTheme = currentTheme;
        try {
            setOsDarkTheme(LIGHT_THEME_NAME);
            Thread.sleep(TIME_TO_WAIT);
            if (!currentTheme().equals(LIGHT_THEME_NAME)) {
                throw new RuntimeException("Test Failed! Initial OS theme supposed to be Light");
            }

            String[] themesOrder = {DARK_THEME_NAME, LIGHT_THEME_NAME, DARK_THEME_NAME};
            Toolkit.getDefaultToolkit().addPropertyChangeListener("awt.os.theme.isDark", evt -> {
                currentTheme = currentTheme();
            });

            for (String nextTheme : themesOrder) {
                setOsDarkTheme(nextTheme);
                Thread.sleep(TIME_TO_WAIT);

                if (!currentTheme().equals(nextTheme)) {
                    throw new RuntimeException("Test Failed! OS theme which was set doesn't match with detected");
                }
                if (!currentTheme.equals(nextTheme)) {
                    throw new RuntimeException("Test Failed! Changing OS theme was not detected");
                }
            }
        } finally {
            setOsDarkTheme(initialTheme);
        }
    }
}