/*
 * Copyright 2000-2020 JetBrains s.r.o.
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

import javax.swing.*;
import java.awt.*;

/**
 * @test
 * @summary Regression test for JBR-2210 (JVM crashes on Windows with Japanese locale and UTF-8 enconding)
 * @run main/othervm -Duser.language=ja -Duser.region=JP -Dfile.encoding=UTF-8 JapaneseLocaleTest
 */

/*
 * Description:
 * Test checks that the JVM does not crash on with Japanese locale and UTF-8 enconding by JFrame creation.
 */

public class JapaneseLocaleTest {
    private static JFrame frame;
    public static void main(String[] args) throws Exception{
        try {
            EventQueue.invokeAndWait(JapaneseLocaleTest::initFrame);
        }
        finally {
            EventQueue.invokeAndWait(() ->frame.dispose());
        }
    }

    private static void initFrame(){
        frame = new JFrame();
        frame.setVisible(true);
    }
}