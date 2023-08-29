/*
 * Copyright 2000-2023 JetBrains s.r.o.
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

package com.jetbrains.desktop;

import com.jetbrains.internal.JBRApi;

import java.lang.invoke.MethodHandles;

/**
 * This class contains mapping between JBR API interfaces and implementation in {@code java.desktop} module.
 */
public class JBRApiModule {
    static {
        JBRApi.registerModule(MethodHandles.lookup(), JBRApiModule.class.getModule()::addExports)
                .service("com.jetbrains.ExtendedGlyphCache")
                    .withStatic("getSubpixelResolution", "getSubpixelResolution", "sun.font.FontUtilities")
                .service("com.jetbrains.JBRFileDialogService")
                    .withStatic("getFileDialog", "get", "com.jetbrains.desktop.JBRFileDialog")
                .proxy("com.jetbrains.JBRFileDialog", "com.jetbrains.desktop.JBRFileDialog")
                .service("com.jetbrains.CustomWindowDecoration", "java.awt.Window$CustomWindowDecoration")
                .service("com.jetbrains.RoundedCornersManager")
                    .withStatic("setRoundedCorners", "setRoundedCorners", "sun.lwawt.macosx.CPlatformWindow",
                                "sun.awt.windows.WWindowPeer")
                .service("com.jetbrains.DesktopActions")
                    .withStatic("setHandler", "setDesktopActionsHandler", "java.awt.Desktop")
                .clientProxy("java.awt.Desktop$DesktopActionsHandler", "com.jetbrains.DesktopActions$Handler")
                .service("com.jetbrains.ProjectorUtils")
                    .withStatic("overrideGraphicsEnvironment", "overrideLocalGraphicsEnvironment", "java.awt.GraphicsEnvironment")
                    .withStatic("setLocalGraphicsEnvironmentProvider", "setLocalGraphicsEnvironmentProvider", "java.awt.GraphicsEnvironment")
                .service("com.jetbrains.AccessibleAnnouncer")
                    .withStatic("announce", "announce", "sun.swing.AccessibleAnnouncer")
                .service("com.jetbrains.GraphicsUtils")
                    .withStatic("createConstrainableGraphics", "create", "com.jetbrains.desktop.JBRGraphicsDelegate")
                .clientProxy("com.jetbrains.desktop.ConstrainableGraphics2D", "com.jetbrains.GraphicsUtils$ConstrainableGraphics2D")
                .service("com.jetbrains.WindowDecorations", "java.awt.Window$WindowDecorations")
                .proxy("com.jetbrains.WindowDecorations$CustomTitleBar", "java.awt.Window$CustomTitleBar")
                .service("com.jetbrains.FontExtensions")
                    .withStatic("getSubpixelResolution", "getSubpixelResolution", "sun.font.FontUtilities")
                    .withStatic("deriveFontWithFeatures", "deriveFont", "java.awt.Font")
                    .withStatic("getFeaturesAsString", "getFeaturesAsString", "com.jetbrains.desktop.FontExtensions")
                .clientProxy("java.awt.Font$Features", "com.jetbrains.FontExtensions$Features")
                .service("com.jetbrains.WindowMove", "java.awt.Window$WindowMoveService")
        ;
    }
}
