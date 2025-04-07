/*
 * Java ATK Wrapper for GNOME
 * Copyright (C) 2009 Sun Microsystems Inc.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package org.GNOME.Accessibility;

import javax.accessibility.*;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import javax.swing.text.*;
import java.lang.ref.WeakReference;
import java.awt.EventQueue;

public class AtkEditableText extends AtkText {

    private WeakReference<AccessibleEditableText> _acc_edt_text;

    private AtkEditableText(AccessibleContext ac) {
        super(ac);

        assert EventQueue.isDispatchThread();

        _acc_edt_text = new WeakReference<AccessibleEditableText>(ac.getAccessibleEditableText());
    }

    // JNI upcalls section

    private static AtkEditableText create_atk_editable_text(AccessibleContext ac) {
        return AtkUtil.invokeInSwingAndWait(() -> {
            return new AtkEditableText(ac);
        }, null);
    }

    private void set_text_contents(String s) {
        AccessibleEditableText acc_edt_text = _acc_edt_text.get();
        if (acc_edt_text == null)
            return;

        AtkUtil.invokeInSwing(() -> {
            acc_edt_text.setTextContents(s);
        });
    }

    private void insert_text(String s, int position) {
        AccessibleEditableText acc_edt_text = _acc_edt_text.get();
        if (acc_edt_text == null)
            return;

        if (position < 0)
            position = 0;
        final int rightPosition = position;
        AtkUtil.invokeInSwing(() -> {
            acc_edt_text.insertTextAtIndex(rightPosition, s);
        });
    }

    private void copy_text(int start, int end) {
        AccessibleEditableText acc_edt_text = _acc_edt_text.get();
        if (acc_edt_text == null)
            return;

        int n = AtkUtil.invokeInSwingAndWait(() -> {
            return acc_edt_text.getCharCount();
        }, -1);

        final int rightStart = getRightStart(start);
        final int rightEnd = getRightEnd(rightStart, end, n);
        AtkUtil.invokeInSwing(() -> {
            String s = acc_edt_text.getTextRange(rightStart, rightEnd);
            if (s != null) {
                StringSelection stringSel = new StringSelection(s);
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(stringSel, stringSel);
            }
        });
    }

    private void cut_text(int start, int end) {
        AccessibleEditableText acc_edt_text = _acc_edt_text.get();
        if (acc_edt_text == null)
            return;

        AtkUtil.invokeInSwing(() -> {
            acc_edt_text.cut(start, end);
        });
    }

    private void delete_text(int start, int end) {
        AccessibleEditableText acc_edt_text = _acc_edt_text.get();
        if (acc_edt_text == null)
            return;

        AtkUtil.invokeInSwing(() -> {
            acc_edt_text.delete(start, end);
        });
    }

    private void paste_text(int position) {
        AccessibleEditableText acc_edt_text = _acc_edt_text.get();
        if (acc_edt_text == null)
            return;

        AtkUtil.invokeInSwing(() -> {
            acc_edt_text.paste(position);
        });
    }

    /**
     * Sets run attributes for the text between two indices.
     *
     * @param as    the AttributeSet for the text
     * @param start the start index of the text as an int
     * @param end   the end index for the text as an int
     * @return whether set_run_attributes was called
     * TODO return is a bit presumptious. This should ideally include a check for whether
     *      attributes were set.
     */
    private boolean set_run_attributes(AttributeSet as, int start, int end) {
        AccessibleEditableText acc_edt_text = _acc_edt_text.get();
        if (acc_edt_text == null)
            return false;

        return AtkUtil.invokeInSwingAndWait(() -> {
            acc_edt_text.setAttributes(start, end, as);
            return true;
        }, false);
    }

}
