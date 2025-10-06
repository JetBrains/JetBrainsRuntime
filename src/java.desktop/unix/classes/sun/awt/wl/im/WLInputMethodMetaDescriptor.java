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

import java.awt.AWTException;
import java.awt.Image;
import java.awt.font.TextAttribute;
import java.awt.im.InputMethodHighlight;
import java.awt.im.spi.InputMethod;
import java.awt.im.spi.InputMethodDescriptor;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
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

    // See java.text.MessageFormat for the formatting syntax
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.im.WLInputMethodMetaDescriptor");


    /** @see sun.awt.wl.WLToolkit#mapInputMethodHighlight(InputMethodHighlight) */
    public static Map<TextAttribute, ?> mapInputMethodHighlight(final InputMethodHighlight highlight) {
        // NB: The implementation is supposed to produce results exactly equal to XToolkit's implementation
        //     for better visual consistency.

        if (highlight == null)
            return null;

        switch (highlight.getState()) {
            case InputMethodHighlight.RAW_TEXT -> {
                if (highlight.isSelected())
                    return imHighlightMapSelectedRawText;
                else
                    return imHighlightMapUnselectedRawText;
            }

            case InputMethodHighlight.CONVERTED_TEXT -> {
                if (highlight.isSelected())
                    return imHighlightMapSelectedConvertedText;
                else
                    return imHighlightMapUnselectedConvertedText;
            }
        }

        return null;
    }


    public static WLInputMethodMetaDescriptor getInstanceIfAvailableOnPlatform() {
        final WLInputMethodMetaDescriptor result;

        if (!ENABLE_NATIVE_IM_SUPPORT) {
            result = null;
        } else {
            // For now there's only 1 possible implementation of IM,
            //   but if/when there are more, this method will have to choose one of them.
            //   It'll be good if the preferable engine can be chosen via a system property.

            final InputMethodDescriptor realImDescriptor = WLInputMethodDescriptorZwpTextInputV3.getInstanceIfAvailableOnPlatform();
            if (realImDescriptor != null) {
                result = new WLInputMethodMetaDescriptor(realImDescriptor);
            } else {
                result = null;
            }
        }

        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("getInstanceIfAvailableOnPlatform(): result={0}, ENABLE_NATIVE_IM_SUPPORT={1}.", result, ENABLE_NATIVE_IM_SUPPORT);
        }

        return result;
    }

    /* java.awt.im.spi.InputMethodDescriptor methods section */

    @Override
    public Locale[] getAvailableLocales() throws AWTException {
        final Locale[] result = realImDescriptor.getAvailableLocales();

        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("getAvailableLocales(): result={0}, this={1}.", Arrays.toString(result), this);
        }

        return result;
    }

    @Override
    public boolean hasDynamicLocaleList() {
        final boolean result = realImDescriptor.hasDynamicLocaleList();

        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("hasDynamicLocaleList(): result={0}, this={1}.", result, this);
        }

        return result;
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
        final InputMethod result = realImDescriptor.createInputMethod();

        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("createInputMethod(): result={0}, this={1}.", result, this);
        }

        return result;
    }


    /* java.lang.Object methods section */

    @Override
    public String toString() {
        return String.format("WLInputMethodMetaDescriptor@%d[realImDescriptor=%s]", System.identityHashCode(this), realImDescriptor);
    }


    /* Implementation details section */

    /**
     * The values are copied from XToolkit's implementation for better visual consistency with AWT on X11.
     *
     * @see #mapInputMethodHighlight(InputMethodHighlight)
     * @see sun.awt.X11InputMethodBase
     */
    private final static Map<TextAttribute, ?> imHighlightMapUnselectedRawText = Map.of(
        TextAttribute.WEIGHT, TextAttribute.WEIGHT_BOLD
    );
    private final static Map<TextAttribute, ?> imHighlightMapUnselectedConvertedText = Map.of(
        TextAttribute.INPUT_METHOD_UNDERLINE, TextAttribute.UNDERLINE_LOW_ONE_PIXEL
    );
    private final static Map<TextAttribute, ?> imHighlightMapSelectedRawText = Map.of(
        TextAttribute.SWAP_COLORS, TextAttribute.SWAP_COLORS_ON
    );
    private final static Map<TextAttribute, ?> imHighlightMapSelectedConvertedText = Map.of(
        TextAttribute.SWAP_COLORS, TextAttribute.SWAP_COLORS_ON
    );

    /**
     * This flag allows disabling ALL integrations with native IMs. The idea is to allow users to disable
     *   unnecessary for them functionality if they face any problems because of it.
     * Therefore, if it's {@code false}, the Toolkit code shouldn't use (directly or indirectly)
     *   any of Wayland's input methods-related APIs (e.g. the "text-input" protocol).
     */
    private final static boolean ENABLE_NATIVE_IM_SUPPORT;

    static {
        boolean enableNativeImSupportInitializer = true;
        try {
            enableNativeImSupportInitializer = Boolean.parseBoolean(System.getProperty("sun.awt.wl.im.enabled", "true"));
        } catch (Exception err) {
            log.severe("Failed to read the value of the system property \"sun.awt.wl.im.enabled\". Assuming the default value(=true).", err);
        }

        ENABLE_NATIVE_IM_SUPPORT = enableNativeImSupportInitializer;
    }


    private final InputMethodDescriptor realImDescriptor;

    private WLInputMethodMetaDescriptor(InputMethodDescriptor realImDescriptor) {
        this.realImDescriptor = Objects.requireNonNull(realImDescriptor, "realImDescriptor");
    }
}
