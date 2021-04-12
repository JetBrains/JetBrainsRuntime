/*
 * Copyright 2000-2021 JetBrains s.r.o.
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

/* @test
 * @summary regression test on JBR-3295 Fix error handling in Toolkit — do not ignore error and pass it as a cause
 * @run main/othervm -Dawt.toolkit=ToolkitExceptionTest ToolkitExceptionTest
*/

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.font.TextAttribute;
import java.awt.im.InputMethodHighlight;
import java.awt.image.ColorModel;
import java.awt.image.ImageObserver;
import java.awt.image.ImageProducer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.util.Map;
import java.util.Properties;

public class ToolkitExceptionTest extends Toolkit {

    public static void main (String[] args) {
        try {
            Toolkit.getDefaultToolkit();
        } catch (Throwable e) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            e.printStackTrace(new PrintStream(bos));
            if (!bos.toString().contains("MyToolkitException")) {
                throw new RuntimeException("Failed");
            }
        }
    }


    public ToolkitExceptionTest() {
        throw new RuntimeException("MyToolkitException");
    }

    @Override
    public Dimension getScreenSize() throws HeadlessException {
        return null;
    }

    @Override
    public int getScreenResolution() throws HeadlessException {
        return 0;
    }

    @Override
    public ColorModel getColorModel() throws HeadlessException {
        return null;
    }

    @Override
    public String[] getFontList() {
        return new String[0];
    }

    @Override
    public FontMetrics getFontMetrics(Font font) {
        return null;
    }

    @Override
    public void sync() {

    }

    @Override
    public Image getImage(String filename) {
        return null;
    }

    @Override
    public Image getImage(URL url) {
        return null;
    }

    @Override
    public Image createImage(String filename) {
        return null;
    }

    @Override
    public Image createImage(URL url) {
        return null;
    }

    @Override
    public boolean prepareImage(Image image, int width, int height, ImageObserver observer) {
        return false;
    }

    @Override
    public int checkImage(Image image, int width, int height, ImageObserver observer) {
        return 0;
    }

    @Override
    public Image createImage(ImageProducer producer) {
        return null;
    }

    @Override
    public Image createImage(byte[] imagedata, int imageoffset, int imagelength) {
        return null;
    }

    @Override
    public PrintJob getPrintJob(Frame frame, String jobtitle, Properties props) {
        return null;
    }

    @Override
    public void beep() {

    }

    @Override
    public Clipboard getSystemClipboard() throws HeadlessException {
        return null;
    }

    @Override
    protected EventQueue getSystemEventQueueImpl() {
        return null;
    }

    @Override
    public boolean isModalityTypeSupported(Dialog.ModalityType modalityType) {
        return false;
    }

    @Override
    public boolean isModalExclusionTypeSupported(Dialog.ModalExclusionType modalExclusionType) {
        return false;
    }

    @Override
    public Map<TextAttribute, ?> mapInputMethodHighlight(InputMethodHighlight highlight) throws HeadlessException {
        return null;
    }
}
