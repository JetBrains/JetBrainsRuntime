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

package sun.awt.wl.im.text_input_unstable_v3;

import sun.awt.wl.WLToolkit;
import sun.util.logging.PlatformLogger;

import java.awt.AWTException;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.im.spi.InputMethod;
import java.awt.im.spi.InputMethodDescriptor;
import java.util.Locale;


public final class WLInputMethodDescriptorZwpTextInputV3 implements InputMethodDescriptor {

    // See java.text.MessageFormat for the formatting syntax
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.im.text_input_unstable_v3.WLInputMethodDescriptorZwpTextInputV3");


    public static boolean isAvailableOnPlatform() {
        return initAndGetIsAvailableOnPlatform();
    }

    public static WLInputMethodDescriptorZwpTextInputV3 getInstanceIfAvailableOnPlatform() {
        if (!isAvailableOnPlatform()) {
            return null;
        }
        return new WLInputMethodDescriptorZwpTextInputV3();
    }


    /* java.awt.im.spi.InputMethodDescriptor methods section */

    @Override
    public Locale[] getAvailableLocales() throws AWTException {
        ensureIsAvailableOnPlatform();
        return getAvailableLocalesInternal();
    }

    @Override
    public boolean hasDynamicLocaleList() {
        // Since the return value of {@link #getAvailableLocales()} doesn't currently change over time,
        //   it doesn't make sense to return true here.
        return false;
    }

    @Override
    public String getInputMethodDisplayName(Locale inputLocale, Locale displayLanguage) {
        assert isAvailableOnPlatform();

        // This is how it's implemented in all other Toolkits.
        //
        // We ignore the input locale.
        // When displaying for the default locale, rely on the localized AWT properties;
        //   for any other locale, fall back to English.
        String name = "System Input Methods";
        if (Locale.getDefault().equals(displayLanguage)) {
            name = Toolkit.getProperty("AWT.HostInputMethodDisplayName", name);
        }
        return name;
    }

    @Override
    public Image getInputMethodIcon(Locale inputLocale) {
        return null;
    }

    @Override
    public InputMethod createInputMethod() throws Exception {
        // NB: we should avoid returning null from this method because the calling code isn't really ready to get null

        ensureIsAvailableOnPlatform();

        final var result = new WLInputMethodZwpTextInputV3();

        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("createInputMethod(): result={0}.", result);
        }

        return result;
    }


    /* java.lang.Object methods section */

    @Override
    public String toString() {
        return String.format("WLInputMethodDescriptorZwpTextInputV3@%d", System.identityHashCode(this));
    }


    /* Implementation details section */

    /** Used as the return value for {@link #getAvailableLocales()}. */
    private volatile static Locale toolkitStartupLocale = null;

    private volatile static Boolean isAvailableOnPlatform = null;


    static Locale[] getAvailableLocalesInternal() {
        // This is how it's implemented in XToolkit.
        //
        // A better implementation would be obtaining all currently installed and enabled input sources
        //   (like on GNOME Settings -> Keyboard -> Input Sources) and mapping them to locales.
        // However, there seem no universal Wayland API for that, so it seems can't be implemented reliably.
        //
        // So leaving as is at the moment.
        //
        // TODO: research how to implement this better along with {@link #hasDynamicLocaleList}

        return new Locale[]{ (Locale)initAndGetToolkitStartupLocale().clone() };
    }

    private static Locale initAndGetToolkitStartupLocale() {
        if (toolkitStartupLocale == null) {
            synchronized (WLInputMethodDescriptorZwpTextInputV3.class) {
                if (toolkitStartupLocale == null) {
                    toolkitStartupLocale = WLToolkit.getStartupLocale();
                }
            }
        }

        if (log.isLoggable(PlatformLogger.Level.CONFIG)) {
            log.config("initAndGetToolkitStartupLocale(): toolkitStartupLocale={0}.", toolkitStartupLocale);
        }

        return toolkitStartupLocale;
    }

    private static boolean initAndGetIsAvailableOnPlatform() {
        if (isAvailableOnPlatform == null) {
            synchronized (WLInputMethodDescriptorZwpTextInputV3.class) {
                try {
                    if (isAvailableOnPlatform == null) {
                        isAvailableOnPlatform = checkIfAvailableOnPlatform();
                    }
                } catch (Exception err) {
                    if (log.isLoggable(PlatformLogger.Level.WARNING)) {
                        log.warning("Failed to check whether the IM protocol is supported on the system", err);
                    }
                    isAvailableOnPlatform = false;
                }
            }
        }

        if (log.isLoggable(PlatformLogger.Level.CONFIG)) {
            log.config("initAndGetIsAvailableOnPlatform(): isAvailableOnPlatform={0}.", isAvailableOnPlatform);
        }

        return isAvailableOnPlatform;
    }

    private static void ensureIsAvailableOnPlatform() throws AWTException {
        if (!isAvailableOnPlatform()) {
            throw new AWTException("sun.awt.wl.im.text_input_unstable_v3.WLInputMethodZwpTextInputV3 is not supported on this system");
        }
    }


    private WLInputMethodDescriptorZwpTextInputV3() {
        assert isAvailableOnPlatform();

        initAndGetToolkitStartupLocale();
    }


    /* JNI downcalls section */

    /**
     * This method checks if {@link WLInputMethodZwpTextInputV3} can function on this system.
     * Basically, it means the Wayland compositor supports a minimal sufficient subset of the required protocols
     *   (currently the set only includes the "text-input-unstable-v3" protocol).
     *
     * @return true if {@link WLInputMethodZwpTextInputV3} can function on this system, false otherwise.
     * @see <a href="https://wayland.app/protocols/text-input-unstable-v3">text-input-unstable-v3</a>
     */
    private static native boolean checkIfAvailableOnPlatform();
}
