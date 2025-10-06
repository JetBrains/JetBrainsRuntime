/*
 * Copyright 2025 JetBrains s.r.o.
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

package sun.awt.wl.im;

import sun.awt.wl.im.text_input_unstable_v3.WLInputMethodDescriptorZwpTextInputV3;
import sun.util.logging.PlatformLogger;

import java.awt.*;
import java.awt.im.spi.InputMethod;
import java.awt.im.spi.InputMethodDescriptor;
import java.util.Locale;
import java.util.Objects;


/**
 * Since Wayland compositors may support multiple IM protocols,
 *   this class is responsible for choosing one specific among them,
 *   and the corresponding "real" implementation of {@code InputMethodDescriptor} from a subpackage.
 */
public final class WLInputMethodMetaDescriptor implements InputMethodDescriptor {
    // NB: the class loading routine has to be as fast and not demanding on resources as possible.
    //     Also, it has to succeed in any practically possible situation.
    //     E.g. if the Wayland compositor doesn't support any known IM protocol,
    //     it mustn't prevent this class from being loaded successfully.
    //     Ideally, nothing additional should happen at the loading time.
    //
    //     This class is directly used by WLToolkit to find and instantiate an InputMethod implementation.

    // TODO: add logging everywhere
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.im.WLInputMethodMetaDescriptor");


    public static WLInputMethodMetaDescriptor getInstanceIfAvailableOnPlatform() {
        // For now there's only 1 possible implementation of IM,
        //   but if/when there are more, this method will have to choose one of them.
        //   It'll be good if the preferable engine can be chosen via a system property.

        final InputMethodDescriptor realImDescriptor = WLInputMethodDescriptorZwpTextInputV3.getInstanceIfAvailableOnPlatform();
        if (realImDescriptor != null) {
            return new WLInputMethodMetaDescriptor(realImDescriptor);
        }
        return null;
    }

    /* java.awt.im.spi.InputMethodDescriptor methods section */

    @Override
    public Locale[] getAvailableLocales() throws AWTException {
        return realImDescriptor.getAvailableLocales();
    }

    @Override
    public boolean hasDynamicLocaleList() {
        return realImDescriptor.hasDynamicLocaleList();
    }

    @Override
    public String getInputMethodDisplayName(Locale inputLocale, Locale displayLanguage) {
        return realImDescriptor.getInputMethodDisplayName(inputLocale, displayLanguage);
    }

    @Override
    public Image getInputMethodIcon(Locale inputLocale) {
        return realImDescriptor.getInputMethodIcon(inputLocale);
    }

    @Override
    public InputMethod createInputMethod() throws Exception {
        return realImDescriptor.createInputMethod();
    }


    /* java.lang.Object methods section */

    @Override
    public String toString() {
        return String.format("WLInputMethodMetaDescriptor@%d[realImDescriptor=%s]", System.identityHashCode(this), realImDescriptor);
    }


    /* Implementation details section */

    private final InputMethodDescriptor realImDescriptor;

    private WLInputMethodMetaDescriptor(InputMethodDescriptor realImDescriptor) {
        this.realImDescriptor = Objects.requireNonNull(realImDescriptor, "realImDescriptor");
    }
}
