/*
 * Copyright (c) 2002, 2025, Oracle and/or its affiliates. All rights reserved.
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

package javax.swing.plaf.synth;

import java.awt.Graphics;
import javax.swing.*;
import javax.swing.text.*;
import javax.swing.plaf.ComponentUI;

/**
 * Provides the Synth L&amp;F UI delegate for
 * {@link javax.swing.JPasswordField}.
 *
 * @author  Shannon Hickey
 * @since 1.7
 */
public class SynthPasswordFieldUI extends SynthTextFieldUI {

    /**
     *
     * Constructs a {@code SynthPasswordFieldUI}.
     */
    public SynthPasswordFieldUI() {}

    /**
     * Creates a UI for a JPasswordField.
     *
     * @param c the JPasswordField
     * @return the UI
     */
    public static ComponentUI createUI(JComponent c) {
        return new SynthPasswordFieldUI();
    }

    /**
     * Fetches the name used as a key to look up properties through the
     * UIManager.  This is used as a prefix to all the standard
     * text properties.
     *
     * @return the name ("PasswordField")
     */
    @Override
    protected String getPropertyPrefix() {
        return "PasswordField";
    }

    /**
     * Installs the necessary properties on the JPasswordField.
     */
    @Override
    protected void installDefaults() {
        super.installDefaults();
        String prefix = getPropertyPrefix();
        Character echoChar = (Character)UIManager.getDefaults().get(prefix + ".echoChar");
        if (echoChar != null) {
            LookAndFeel.installProperty(getComponent(), "echoChar", echoChar);
        }
    }

    /**
     * Creates a view (PasswordView) for an element.
     *
     * @param elem the element
     * @return the view
     */
    @Override
    public View create(Element elem) {
        return new PasswordView(elem);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    void paintBackground(SynthContext context, Graphics g, JComponent c) {
        context.getPainter().paintPasswordFieldBackground(context, g, 0, 0,
                                                c.getWidth(), c.getHeight());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void paintBorder(SynthContext context, Graphics g, int x,
                            int y, int w, int h) {
        context.getPainter().paintPasswordFieldBorder(context, g, x, y, w, h);
    }
}
