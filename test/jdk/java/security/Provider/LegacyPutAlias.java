/*
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
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

import static java.lang.System.out;

import java.security.Provider;


/**
 * @test
 * @bug 8250787
 * @summary Ensure that aliases added with Provider.put work for services
 * regardless which method was use to register the service,  Provider.put
 * or Provider.putService.
 */
public class LegacyPutAlias {
    private static final String LEGACY_ALGO = "SRLegacy";
    private static final String MODERN_ALGO = "SRModern";
    private static final String LEGACY_ALIAS = "AliasLegacy";
    private static final String MODERN_ALIAS = "AliasModern";

    public static void main(String[] args) {
        checkAlias(LEGACY_ALGO, LEGACY_ALIAS);
        checkAlias(MODERN_ALGO, MODERN_ALIAS);
    }

    private static void checkAlias(String algo, String alias) {
        out.println("Checking alias " + alias + " for " + algo);
        Provider p = new CustomProvider();
        p.put("Alg.Alias.SecureRandom." + alias, algo);
        validate(p, algo, alias);
        out.println("=> Test Passed");
    }

    private static void validate(Provider p, String algo, String alias) {
        Provider.Service s = p.getService("SecureRandom", alias);
        if (s == null) {
            throw new RuntimeException("Failed alias " + alias + " check, " +
                    "exp: " + algo + ", got null");
        }
        if (!algo.equals(s.getAlgorithm())) {
            throw new RuntimeException("Failed alias " + alias + " check, " +
                    "exp: " + algo + ", got " + s.getAlgorithm());
        }
    }


    private static final String SR_IMPLCLASS =
            "sun.security.provider.SecureRandom";
    private static class CustomProvider extends Provider {
        private static class CustomService extends Provider.Service {
            CustomService(Provider p, String type, String algo, String cName) {
                super(p, type, algo, cName, null, null);
            }
        }

        CustomProvider() {
            super("CP", 1.0, "test provider that registers two services, " +
                    "one with put and one with putService");

            putService(new CustomService(this, "SecureRandom",
                    MODERN_ALGO, SR_IMPLCLASS));
            put("SecureRandom." + LEGACY_ALGO, SR_IMPLCLASS);
        }
    }
}

