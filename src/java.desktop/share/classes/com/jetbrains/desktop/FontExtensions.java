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

import java.awt.*;
import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

public class FontExtensions {
    private interface FontExtension {
        FontExtension INSTANCE = (FontExtension) JBRApi.internalServiceBuilder(MethodHandles.lookup())
                .withStatic("getFeatures", "getFeatures", "java.awt.Font").build();

        TreeMap<String, Integer> getFeatures(Font font);
    }

    public static String featuresToString(Map<String, Integer> features) {
        return features.entrySet().stream().map(feature -> (feature.getKey() + "=" + feature.getValue())).
                collect(Collectors.joining(";"));
    }

    public static TreeMap<String, Integer> getFeatures(Font font) {
        return FontExtension.INSTANCE.getFeatures(font);
    }
}
