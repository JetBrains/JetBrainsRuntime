/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
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

/**
 * @test
 * @bug 8335803
 * @summary Test the Cipher.init methods to handle un-extractable RSA key
 * @run main/othervm InvalidKeyExceptionTest ENCRYPT_MODE
 * @run main/othervm InvalidKeyExceptionTest DECRYPT_MODE
 * @author Alexey Bakhtin
 */

import java.security.InvalidKeyException;
import java.security.PrivateKey;
import javax.crypto.Cipher;

public class InvalidKeyExceptionTest {

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new Exception("Encryption mode required");
        }

        int mode = 0;
        switch(args[0]) {
            case "ENCRYPT_MODE":
                mode = Cipher.ENCRYPT_MODE;
                break;
            case "DECRYPT_MODE":
                mode = Cipher.DECRYPT_MODE;
                break;
        }

        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding", "SunJCE");
        try {
            c.init(mode, new PrivateKey() {
                @Override
                public String getAlgorithm() {
                return "RSA";
                }
                @Override
                public String getFormat() {
                    return null;
                }
                @Override
                public byte[] getEncoded() {
                    return null;
                }
            });
        } catch (InvalidKeyException ike) {
            // expected exception
            return;
        } catch (Exception e) {
            throw new RuntimeException("Unexpected exception: " + e);
        }
        new RuntimeException("InvalidKeyException should be thown");
    }
}
