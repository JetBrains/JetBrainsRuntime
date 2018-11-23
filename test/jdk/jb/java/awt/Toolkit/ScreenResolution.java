/*
 * Copyright 2017 JetBrains s.r.o.
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


import java.awt.Toolkit;

/* @test
 * @summary checks that screen resolution is in acceptable range
 */

public class ScreenResolution {

    public static void main(String[] args) throws Exception {
        int pixelPerInch = java.awt.Toolkit.getDefaultToolkit().getScreenResolution();

        if (pixelPerInch <= 0 || pixelPerInch > 1000)
            throw new RuntimeException("Invalid resolution: " + pixelPerInch);
    }
}
