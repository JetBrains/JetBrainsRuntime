/*
 * Copyright 2000-2019 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * @test
 * @bug 8220231
 * @summary Cache HarfBuzz face object for same font's text layout calls
 * @comment Test layout operations for the same font performed simultaneously
 *          from multiple threads
 */

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

public class FontLayoutStressTest {
    private static final int NUMBER_OF_THREADS =
            Runtime.getRuntime().availableProcessors() * 2;
    private static final long TIME_TO_RUN_NS = 1_000_000_000; // 1 second
    private static final Font FONT = new Font(Font.SERIF, Font.PLAIN, 12);
    private static final FontRenderContext FRC = new FontRenderContext(null,
            false, false);
    private static final char[] TEXT = "Lorem ipsum dolor sit amet, ..."
            .toCharArray();

    private static double doLayout() {
        GlyphVector gv = FONT.layoutGlyphVector(FRC, TEXT, 0, TEXT.length,
                Font.LAYOUT_LEFT_TO_RIGHT);
        return gv.getGlyphPosition(gv.getNumGlyphs()).getX();
    }

    public static void main(String[] args) throws Throwable {
        double expectedWidth = doLayout();
        AtomicReference<Throwable> throwableRef = new AtomicReference<>();
        CyclicBarrier barrier = new CyclicBarrier(NUMBER_OF_THREADS);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < NUMBER_OF_THREADS; i++) {
            Thread thread = new Thread(() -> {
                try {
                    barrier.await();
                    long timeToStop = System.nanoTime() + TIME_TO_RUN_NS;
                    while (System.nanoTime() < timeToStop) {
                        double width = doLayout();
                        if (width != expectedWidth) {
                            throw new RuntimeException(
                                    "Unexpected layout result");
                        }
                    }
                } catch (Throwable e) {
                    throwableRef.set(e);
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        Throwable throwable = throwableRef.get();
        if (throwable != null) {
            throw throwable;
        }
    }
}
