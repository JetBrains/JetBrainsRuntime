/*
 * Copyright 2000-2017 JetBrains s.r.o.
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
 * @summary regression test on JRE-624 CThreading.isAppKit() fails to detect main app thread if it was renamed
 * @compile -XDignore.symbol.file IsAppKit.java
 * @run main/othervm IsAppKit 
 */

import javax.swing.*;
import java.util.concurrent.*;
import sun.lwawt.macosx.*;

/*
 * Description: The test checks detection of the main application thread
 *
 */

public class IsAppKit {
    public static void main(String[] args) throws Exception {

        final CountDownLatch counter = new CountDownLatch(1);

        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame();
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.pack();
            frame.setVisible(true);
            counter.countDown();
        });

        counter.await();

        CThreading.privilegedExecuteOnAppKit(() -> {
            String name = Thread.currentThread().getName();
            if (!"AWT-AppKit".equals(name)) {
                throw new RuntimeException("Unexpected thread name: " + name);
            }

            Thread.currentThread().setName("Some other thread name");
            return null;
        });


        String name = CThreading.privilegedExecuteOnAppKit(() -> {
            return Thread.currentThread().getName();
        });

        if (!"AWT-AppKit".equals(name)) {
            throw new RuntimeException("Unexpected thread name: " + name);
        }
    }
}
