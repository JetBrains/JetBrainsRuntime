/*
 * Copyright 2021-2023 JetBrains s.r.o.
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

/*
 * @test
 * @summary check serialization and backward compatibility of Font
 */

import java.awt.*;
import java.io.*;
import java.util.Base64;

public class FontSerialization {

    /** Read the Font object from Base64 string. */
    private static Font fontDeserialization(String s) throws IOException, ClassNotFoundException {
        byte[] data = Base64.getDecoder().decode(s);
        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data));
        Font font = (Font) ois.readObject();
        ois.close();
        return font;
    }

    /** Write the Font to a Base64 string. */
    private static String fontSerialization(Font o) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(o);
        oos.close();
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private static void checkFont(String data, String name) throws IOException, ClassNotFoundException {
        Font font = fontDeserialization(data);
        if (!font.getFontName().equals(name)) {
            throw new Error("deserialized Font name doesn't match");
        }
        Font reconstructedFont = fontDeserialization(fontSerialization(font));
        if ((reconstructedFont.hashCode() != font.hashCode()) || (!reconstructedFont.equals(font))) {
            throw new Error("secondarily deserialized Font doesn't match with original");
        }
    }

    public static void main(String[] args) throws Exception {
        checkFont("rO0ABXNyAA1qYXZhLmF3dC5Gb250xaE15szeVnMDAAZJABlmb250U2VyaWFsaXplZERhdGFWZXJzaW9uRgAJcG9pbnRTaXplSQAEc2l6ZUkABXN0eWxlTAAUZlJlcXVlc3RlZEF0dHJpYnV0ZXN0ABVMamF2YS91dGlsL0hhc2h0YWJsZTtMAARuYW1ldAASTGphdmEvbGFuZy9TdHJpbmc7eHAAAAABP4AAAAAAAAEAAAAAcHQAC0RlamFWdSBTYW5zeA==", "DejaVu Sans");
        checkFont("rO0ABXNyAA1qYXZhLmF3dC5Gb250xaE15szeVnMDAAZJABlmb250U2VyaWFsaXplZERhdGFWZXJzaW9uRgAJcG9pbnRTaXplSQAEc2l6ZUkABXN0eWxlTAAUZlJlcXVlc3RlZEF0dHJpYnV0ZXN0ABVMamF2YS91dGlsL0hhc2h0YWJsZTtMAARuYW1ldAASTGphdmEvbGFuZy9TdHJpbmc7eHAAAAABP4AAAAAAAAEAAAAAcHQAD0Ryb2lkIFNhbnMgTW9ub3g=", "Droid Sans Mono");
        checkFont("rO0ABXNyAA1qYXZhLmF3dC5Gb250xaE15szeVnMDAAZJABlmb250U2VyaWFsaXplZERhdGFWZXJzaW9uRgAJcG9pbnRTaXplSQAEc2l6ZUkABXN0eWxlTAAUZlJlcXVlc3RlZEF0dHJpYnV0ZXN0ABVMamF2YS91dGlsL0hhc2h0YWJsZTtMAARuYW1ldAASTGphdmEvbGFuZy9TdHJpbmc7eHAAAAABP4AAAAAAAAEAAAAAcHQAEUZpcmEgQ29kZSBSZWd1bGFyeA==", "Fira Code Regular");
        checkFont("rO0ABXNyAA1qYXZhLmF3dC5Gb250xaE15szeVnMDAAZJABlmb250U2VyaWFsaXplZERhdGFWZXJzaW9uRgAJcG9pbnRTaXplSQAEc2l6ZUkABXN0eWxlTAAUZlJlcXVlc3RlZEF0dHJpYnV0ZXN0ABVMamF2YS91dGlsL0hhc2h0YWJsZTtMAARuYW1ldAASTGphdmEvbGFuZy9TdHJpbmc7eHAAAAABP4AAAAAAAAEAAAAAcHQAFUpldEJyYWlucyBNb25vIEl0YWxpY3g=", "JetBrains Mono Italic");
    }

}
