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

import sun.awt.im.InputMethodAdapter;
import sun.util.logging.PlatformLogger;

import java.awt.*;
import java.awt.im.spi.InputMethodContext;
import java.util.Locale;


final class WLInputMethodZwpTextInputV3 extends InputMethodAdapter {

    // TODO: add logging everywhere
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.im.text_input_unstable_v3.WLInputMethodZwpTextInputV3");


    /* sun.awt.im.InputMethodAdapter methods section */

    @Override
    protected Component getClientComponent() {
        // TODO: implement
        return super.getClientComponent();
    }

    @Override
    protected boolean haveActiveClient() {
        // TODO: implement
        return super.haveActiveClient();
    }

    @Override
    protected void setAWTFocussedComponent(Component component) {
        // TODO: implement
        super.setAWTFocussedComponent(component);
    }

    @Override
    protected boolean supportsBelowTheSpot() {
        // TODO: implement
        return super.supportsBelowTheSpot();
    }

    @Override
    protected void stopListening() {
        // TODO: implement
        super.stopListening();
    }

    @Override
    public void notifyClientWindowChange(Rectangle location) {
        // TODO: implement
        super.notifyClientWindowChange(location);
    }

    @Override
    public void reconvert() {
        // TODO: implement
        super.reconvert();
    }

    @Override
    public void disableInputMethod() {
        // TODO: implement
    }

    @Override
    public String getNativeInputMethodInfo() {
        // TODO: implement
        return "";
    }


    /* java.awt.im.spi.InputMethod methods section */

    @Override
    public void setInputMethodContext(InputMethodContext context) {
        // TODO: implement
    }

    @Override
    public boolean setLocale(Locale locale) {
        // TODO: implement
        return false;
    }

    @Override
    public Locale getLocale() {
        // TODO: implement
        return null;
    }

    @Override
    public void setCharacterSubsets(Character.Subset[] subsets) {
        // TODO: implement
    }

    @Override
    public void setCompositionEnabled(boolean enable) {
        // TODO: implement
    }

    @Override
    public boolean isCompositionEnabled() {
        // TODO: implement
        return false;
    }

    @Override
    public void dispatchEvent(AWTEvent event) {
        // TODO: implement
    }

    @Override
    public void activate() {
        // TODO: implement
    }

    @Override
    public void deactivate(boolean isTemporary) {
        // TODO: implement
    }

    @Override
    public void hideWindows() {
        // TODO: implement
    }

    @Override
    public void removeNotify() {
        // TODO: implement
    }

    @Override
    public void endComposition() {
        // TODO: implement
    }

    @Override
    public void dispose() {
        // TODO: implement
    }

    @Override
    public Object getControlObject() {
        // TODO: implement
        return null;
    }


    /* java.lang.Object methods section */

    @Override
    public String toString() {
        // TODO: implement
        return super.toString();
    }


    /* Implementation details section */


    /* JNI downcalls section */


    /* JNI upcalls section */
}
