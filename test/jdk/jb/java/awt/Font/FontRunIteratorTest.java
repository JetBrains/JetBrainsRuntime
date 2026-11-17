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

/*
 * @test
 * @summary JBR-10592 Check that tabs and line breaks do not split text into separate shaping runs
 * @modules java.desktop/sun.font
 * @run junit/othervm ${test.main.class}
 */

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sun.font.Font2D;
import sun.font.FontRunIterator;
import sun.font.FontSubstitution;
import sun.font.FontUtilities;

import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class FontRunIteratorTest {
    private static final String INVISIBLE = "\t\n\u000b\u000c\r\u0085\u2028\u2029";

    @ParameterizedTest
    @ValueSource(strings = {"a", "\u4e2d", "\uD83D\uDE00"})
    void invisibleCharsStayInRun(String visible) {
        String text = visible + "\r\n" + visible + "\n\t" + visible + INVISIBLE;
        int slot = runs(visible).getFirst().slot();
        assertEquals(List.of(new FontRun(text.length(), slot)), runs(text));
    }

    @Test
    void invisibleCharsOnly() {
        assertEquals(List.of(new FontRun(INVISIBLE.length(), 0)), runs(INVISIBLE));
    }

    @Test
    void fontChangeSplitsRuns() {
        int latin = runs("a").getFirst().slot();
        int cjk = runs("\u4e2d").getFirst().slot();
        assumeTrue(latin != cjk, "No fallback font for CJK");
        assertEquals(
                List.of(
                        new FontRun(2, latin),
                        new FontRun(4, cjk),
                        new FontRun(5, latin)
                ),
                runs("a\n\u4e2d\nb")
        );
    }

    private record FontRun(int limit, int slot) {
    }

    private static List<FontRun> runs(String text) {
        Font2D font = FontUtilities.getFont2D(new Font(Font.DIALOG, Font.PLAIN, 12));
        if (font instanceof FontSubstitution substitution) {
            font = substitution.getCompositeFont2D();
        }
        char[] chars = text.toCharArray();
        FontRunIterator iterator = new FontRunIterator();
        iterator.init(font, chars, 0, chars.length);
        List<FontRun> runs = new ArrayList<>();
        while (iterator.next(chars.length)) {
            runs.add(new FontRun(iterator.getPos(), iterator.getSlot()));
        }
        return runs;
    }
}
