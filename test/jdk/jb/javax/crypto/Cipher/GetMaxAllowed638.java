/*
 * Copyright 2000-2018 JetBrains s.r.o.
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

import javax.crypto.Cipher;

/*
 * @test
 * @summary JRE-638 It checks unlimited cryptographic policy was enabled for JBRE by default
 * @run main/othervm GetMaxAllowed638
 */

public class GetMaxAllowed638 {

    public static void main(String[] args) throws Exception {

        int maxKey = Cipher.getMaxAllowedKeyLength("RC5");
        if ( maxKey < Integer.MAX_VALUE) {
            System.out.println("Cipher.getMaxAllowedKeyLength(\"RC5\"): " + maxKey);
            throw new RuntimeException("unlimited policy is set by default, "
                    + Integer.MAX_VALUE + "(Integer.MAX_VALUE) is expected");
        }
    }
}
