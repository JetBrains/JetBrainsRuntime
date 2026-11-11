/*
 * Copyright 2026 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

/**
 * @test
 * @summary The Skia scaler of a system font (CFont) keeps the font's native AWTFont alive as long as a context of it
 *          is: a strike's context outlives the font (it is disposed after the render queue is flushed, and a draw
 *          queued before may still look up a fallback glyph through it), while the font's own Disposer record
 *          releases the AWTFont when the font is collected. The scaler read the released AWTFont. Run in a child
 *          JVM with NSZombieEnabled, so that a message to the released object traps instead of reading freed memory
 *          by luck.
 * @requires os.family == "mac"
 * @modules java.desktop/sun.font:+open
 * @run main/othervm NativeFontLifetimeTest
 */
public class NativeFontLifetimeTest {
    static final int MISSING_CODE_POINT = 0x4E2D;   // 中: Menlo lacks it, CoreText's fallback from Menlo has it

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            child();
            return;
        }
        ProcessBuilder builder = new ProcessBuilder(List.of(
                new File(System.getProperty("java.home"), "bin/java").getPath(),
                "--add-opens", "java.desktop/sun.font=ALL-UNNAMED",
                "-Djava.awt.headless=true", "-Dsun.java2d.font.scaler=skia",
                "-cp", System.getProperty("test.classes", "."), NativeFontLifetimeTest.class.getName(), "child"));
        builder.environment().put("NSZombieEnabled", "YES");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        System.out.println(output);
        if (exit != 0 || !output.contains("fallback advance ")) {
            throw new RuntimeException("the child failed (exit " + exit + "): the scaler read the font's released AWTFont");
        }
    }

    static void child() throws Exception {
        new java.awt.Font("Menlo", java.awt.Font.PLAIN, 13).getFontName();   // the font manager and its natives
        Class<?> cfontClass = Class.forName("sun.font.CFont");
        Constructor<?> hidden = cfontClass.getConstructor(String.class, String.class, String.class);
        Object font = hidden.newInstance("Menlo-Regular", "Menlo", null);   // not registered, so collectable
        Method skiaScaler = cfontClass.getDeclaredMethod("skiaScaler");
        skiaScaler.setAccessible(true);
        Object scaler = skiaScaler.invoke(font);
        if (scaler == null) throw new RuntimeException("no Skia scaler for Menlo: the test does not reach it");
        Class<?> scalerClass = scaler.getClass();
        Method createContext = scalerClass.getDeclaredMethod("createScalerContext",
                double[].class, int.class, int.class, float.class, float.class, int.class);
        createContext.setAccessible(true);
        long context = (Long) createContext.invoke(scaler, new double[] {13, 0, 0, 13}, 2, 1, 1f, 0f, -1);
        if (context == 0) throw new RuntimeException("no scaler context");

        // the strike's part: the context and the scaler stay, the font goes (its AWTFont released by its Disposer record)
        WeakReference<Object> collected = new WeakReference<>(font);
        font = null;
        for (int i = 0; i < 100 && collected.get() != null; i++) {
            System.gc();
            Thread.sleep(20);
        }
        if (collected.get() != null) throw new RuntimeException("the font was not collected");
        for (int i = 0; i < 10; i++) {   // the Disposer thread
            System.gc();
            Thread.sleep(50);
        }

        Method advance = scalerClass.getDeclaredMethod("getGlyphAdvanceNative", long.class, int.class);
        advance.setAccessible(true);
        float width = (Float) advance.invoke(null, context, -MISSING_CODE_POINT);   // CFont's mapper: -codePoint
        System.out.println("fallback advance " + width);
        if (!(width > 0)) throw new RuntimeException("no fallback glyph for U+4E2D after the font was collected");
        Method dispose = scalerClass.getDeclaredMethod("disposeContext", long.class);
        dispose.setAccessible(true);
        dispose.invoke(null, context);
    }
}
