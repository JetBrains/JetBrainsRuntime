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

public class AtkEditableText extends AtkText {

  WeakReference<AccessibleEditableText> _acc_edt_text;

  public AtkEditableText (AccessibleContext ac) {
    super(ac);
    _acc_edt_text = new WeakReference<AccessibleEditableText>(ac.getAccessibleEditableText());
  }

  public static AtkEditableText createAtkEditableText(AccessibleContext ac){
      return AtkUtil.invokeInSwing ( () -> { return new AtkEditableText(ac); }, null);
  }

  public void set_text_contents (String s) {
      AccessibleEditableText acc_edt_text = _acc_edt_text.get();
      if (acc_edt_text == null)
          return;

      AtkUtil.invokeInSwing( () -> {
          acc_edt_text.setTextContents(s);
      });
  }

    public void insert_text (String s, int position) {
        AccessibleEditableText acc_edt_text = _acc_edt_text.get();
        if (acc_edt_text == null)
            return;

        if (position < 0)
            position = 0;
        final int rightPosition = position;
        AtkUtil.invokeInSwing( () -> { acc_edt_text.insertTextAtIndex(rightPosition, s); });
    }

    public void copy_text (int start, int end) {
        AccessibleEditableText acc_edt_text = _acc_edt_text.get();
        if (acc_edt_text == null)
            return;

        int n = acc_edt_text.getCharCount();
        if (start < 0) {
            start = 0;
        }
        if (end > n || end == -1) {
            end = n;
        } else if (end < -1) {
            end = 0;
        }
        final int rightStart = start;
        final int rightEnd = end;
        AtkUtil.invokeInSwing ( () -> {
            String s = acc_edt_text.getTextRange(rightStart, rightEnd);
            if (s != null) {
                StringSelection stringSel = new StringSelection(s);
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(stringSel, stringSel);
            }
        });
    }

    public void cut_text (int start, int end) {
        AccessibleEditableText acc_edt_text = _acc_edt_text.get();
        if (acc_edt_text == null)
            return;

        AtkUtil.invokeInSwing( () -> { acc_edt_text.cut(start, end); });
    }

    public void delete_text (int start, int end) {
        AccessibleEditableText acc_edt_text = _acc_edt_text.get();
        if (acc_edt_text == null)
            return;

        AtkUtil.invokeInSwing( () -> { acc_edt_text.delete(start, end); });
    }

    public void paste_text (int position) {
        AccessibleEditableText acc_edt_text = _acc_edt_text.get();
        if (acc_edt_text == null)
            return;

        AtkUtil.invokeInSwing( () -> { acc_edt_text.paste(position); });
    }

    /**
    * Sets run attributes for the text between two indices.
    *
    * @param as the AttributeSet for the text
    * @param start the start index of the text as an int
    * @param end the end index for the text as an int
    * @return whether setRunAttributes was called
    * TODO return is a bit presumptious. This should ideally include a check for whether
    *      attributes were set.
    */
    public boolean setRunAttributes(AttributeSet as, int start, int end) {
        AccessibleEditableText acc_edt_text = _acc_edt_text.get();
        if (acc_edt_text == null)
            return false;

        return AtkUtil.invokeInSwing( () -> {
            acc_edt_text.setAttributes(start, end, as);
            return true;
        }, false);
    }

}
