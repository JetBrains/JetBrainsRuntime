/*
 * Copyright 2026 JetBrains s.r.o.
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

import sun.awt.datatransfer.DataTransferer;

import java.awt.GraphicsEnvironment;
import java.awt.datatransfer.DataFlavor;
import java.util.LinkedHashSet;

/*
 * @test
 * bug JBR-10207
 * @summary WLDataTransferer must offer bare text/html MIME type for HTML clipboard content
 * @requires os.family == "linux"
 * @modules java.desktop/sun.awt.datatransfer
 * @run main/othervm DataTransferHtmlClipboardMimeTest
 */

public class DataTransferHtmlClipboardMimeTest {

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("Headless environment, skipping test");
            return;
        }

        System.out.println("Toolkit = " + System.getProperty("awt.toolkit.name"));

        DataTransferer transferer = DataTransferer.getInstance();
        if (transferer == null) {
            throw new RuntimeException("DataTransferer.getInstance() returned null");
        }

        // text/html with String representation class — this is what IntelliJ uses
        // for rich-text (syntax-highlighted) clipboard content.
        DataFlavor htmlStringFlavor = new DataFlavor("text/html;class=java.lang.String");

        LinkedHashSet<String> natives = transferer.getPlatformMappingsForFlavor(htmlStringFlavor);
        System.out.println("Platform mappings for " + htmlStringFlavor.getMimeType() + ":");
        for (String n : natives) {
            System.out.println("  " + n);
        }

        // The bare "text/html" MIME type must be offered so that Wayland consumers
        // (Chrome, Firefox, Google Slides, etc.) can request rich text content.
        // XDataTransferer adds this; WLDataTransferer must do the same.
        boolean hasBareHtml = natives.contains("text/html");
        if (!hasBareHtml) {
            throw new RuntimeException(
                    "FAIL: getPlatformMappingsForFlavor(text/html;class=java.lang.String) "
                    + "does not include bare \"text/html\" MIME type. "
                    + "Offered types: " + natives);
        }

        // Also verify that charset-parameterized variants are present (existing behavior).
        boolean hasCharsetVariant = natives.stream()
                .anyMatch(n -> n.startsWith("text/html;charset="));
        if (!hasCharsetVariant) {
            throw new RuntimeException(
                    "FAIL: no charset-parameterized text/html variants found. "
                    + "Offered types: " + natives);
        }

        System.out.println("PASS: bare \"text/html\" and charset variants are all offered");
    }
}
