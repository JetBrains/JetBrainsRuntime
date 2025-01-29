/*
 * Copyright (c) 2018, 2023, Oracle and/or its affiliates. All rights reserved.
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

//
// Please run in othervm mode.  SunJSSE does not support dynamic system
// properties, no way to re-use system properties in samevm/agentvm mode.
//

import java.io.ByteArrayInputStream;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import javax.net.ssl.*;

/**
 * SSLContext template to speed up JSSE tests.
 */
public abstract class SSLContextTemplate {

    /*
     * =======================================
     * Certificates and keys used in the test.
     */
    // Trusted certificates.
    Cert[] TRUSTED_CERTS = {
            Cert.CA_ECDSA_SECP256R1,
            Cert.CA_RSA_2048,
            Cert.CA_DSA_2048 };

    // End entity certificate.
    Cert[] END_ENTITY_CERTS = {
            Cert.EE_ECDSA_SECP256R1,
            Cert.EE_RSA_2048,
            Cert.EE_EC_RSA_SECP256R1,
            Cert.EE_DSA_2048 };

    /*
     * Create an instance of SSLContext for client use.
     */
    protected SSLContext createClientSSLContext() throws Exception {
        return createSSLContext(
                createClientTrustManager(),
                createClientKeyManager(),
                getClientContextParameters());
    }

    /*
     * Create an instance of SSLContext for server use.
     */
    protected SSLContext createServerSSLContext() throws Exception {
        return createSSLContext(
                createServerTrustManager(),
                createServerKeyManager(),
                getServerContextParameters());
    }

    private SSLContext createSSLContext(TrustManager trustManager,
                                        KeyManager keyManager,
                                        ContextParameters params) throws Exception {
        SSLContext context = SSLContext.getInstance(params.contextProtocol);
        context.init(
            new KeyManager[] {keyManager},
            new TrustManager[] {trustManager},
            null);
        return  context;
    }

    /**
     * Creates a TrustManager with TRUSTED_CERTS and client context parameters
     */
    protected TrustManager createClientTrustManager() throws Exception {
        return createTrustManager(TRUSTED_CERTS, getClientContextParameters());
    }

    /**
     * Creates a TrustManager with TRUSTED_CERTS and server context parameters
     */
    protected TrustManager createServerTrustManager() throws Exception {
        return createTrustManager(TRUSTED_CERTS, getServerContextParameters());
    }

    /**
     * Creates a TrustManager with the given array of trusted certs and
     * context parameters.
     */
    protected TrustManager createTrustManager(Cert[] trustedCerts,
                                              ContextParameters params) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        ByteArrayInputStream is;

        KeyStore ts = KeyStore.getInstance("JKS");
        ts.load(null, null);

        if (trustedCerts != null && trustedCerts.length != 0) {
            Certificate[] trustedCert = new Certificate[trustedCerts.length];
            for (int i = 0; i < trustedCerts.length; i++) {
                is = new ByteArrayInputStream(trustedCerts[i].certStr.getBytes());
                try {
                    trustedCert[i] = cf.generateCertificate(is);
                } finally {
                    is.close();
                }

                ts.setCertificateEntry(
                        "trusted-cert-" + trustedCerts[i].name(), trustedCert[i]);
            }
        }

        // Create an SSLContext object.
        TrustManagerFactory tmf =
                TrustManagerFactory.getInstance(params.tmAlgorithm);
        tmf.init(ts);
        return tmf.getTrustManagers()[0];
    }

    /**
     * Create a key manager with Cert.END_ENTITY_CERTS and the client
     * context parameters.
     */
    protected KeyManager createClientKeyManager() throws Exception {
        return createKeyManager(END_ENTITY_CERTS, getClientContextParameters());
    }

    /**
     * Create a key manager with Cert.END_ENTITY_CERTS and the server
     * context parameters
     */
    protected KeyManager createServerKeyManager() throws Exception {
        return createKeyManager(END_ENTITY_CERTS, getServerContextParameters());
    }

    /**
     * Creates a KeyManager with the given end-entity Cert's and context
     * parameters
     */
    protected KeyManager createKeyManager(Cert[] endEntityCerts,
                                          ContextParameters params) throws Exception {
        KeyStore ks = null;     // key store
        char[] passphrase = "passphrase".toCharArray();

        if (endEntityCerts == null || endEntityCerts.length == 0) {
            return null;

        } else {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            ks = KeyStore.getInstance("JKS");
            ks.load(null, null);

            for (Cert endEntityCert : endEntityCerts) {
                // generate the private key.
                PKCS8EncodedKeySpec priKeySpec = new PKCS8EncodedKeySpec(
                        Base64.getMimeDecoder().decode(endEntityCert.privKeyStr));
                KeyFactory kf =
                        KeyFactory.getInstance(
                                endEntityCert.keyAlgo);
                PrivateKey priKey = kf.generatePrivate(priKeySpec);

                // generate certificate chain
                ByteArrayInputStream is = new ByteArrayInputStream(
                        endEntityCert.certStr.getBytes());
                Certificate keyCert = null;
                try {
                    keyCert = cf.generateCertificate(is);
                } finally {
                    is.close();
                }

                Certificate[] chain = new Certificate[]{keyCert};

                // import the key entry.
                ks.setKeyEntry("cert-" + endEntityCert.name(),
                        priKey, passphrase, chain);
            }

            KeyManagerFactory kmf =
                    KeyManagerFactory.getInstance(params.kmAlgorithm);
            kmf.init(ks, passphrase);
            return kmf.getKeyManagers()[0];
        }
    }

    /*
     * Create an instance of SSLContext with the specified trust/key materials.
     */
    protected SSLContext createSSLContext(
            Cert[] trustedCerts,
            Cert[] endEntityCerts,
            ContextParameters params) throws Exception {

        // Generate certificate from cert string.
        TrustManager tm = createTrustManager(trustedCerts, params);

        KeyManager km = createKeyManager(endEntityCerts, params);

        SSLContext context = SSLContext.getInstance(params.contextProtocol);
        context.init(km == null ? null : new KeyManager[]{km}, new TrustManager[]{tm}, null);
        return context;
    }

    /*
     * The parameters used to configure SSLContext.
     */
    static final class ContextParameters {
        final String contextProtocol;
        final String tmAlgorithm;
        final String kmAlgorithm;

        ContextParameters(String contextProtocol,
                String tmAlgorithm, String kmAlgorithm) {

            this.contextProtocol = contextProtocol;
            this.tmAlgorithm = tmAlgorithm;
            this.kmAlgorithm = kmAlgorithm;
        }
    }

    /*
     * Get the client side parameters of SSLContext.
     */
    protected ContextParameters getClientContextParameters() {
        return new ContextParameters("TLS", "PKIX", "NewSunX509");
    }

    /*
     * Get the server side parameters of SSLContext.
     */
    protected ContextParameters getServerContextParameters() {
        return new ContextParameters("TLS", "PKIX", "NewSunX509");
    }


    enum Cert {

        CA_ECDSA_SECP256R1(
                "EC",
                // SHA256withECDSA, curve secp256r1
                // Validity
                //     Not Before: May 22 07:18:16 2018 GMT
                //     Not After : May 17 07:18:16 2038 GMT
                // Subject Key Identifier:
                //     60:CF:BD:73:FF:FA:1A:30:D2:A4:EC:D3:49:71:46:EF:1A:35:A0:86
                "-----BEGIN CERTIFICATE-----\n" +
                "MIIBvjCCAWOgAwIBAgIJAIvFG6GbTroCMAoGCCqGSM49BAMCMDsxCzAJBgNVBAYT\n" +
                "AlVTMQ0wCwYDVQQKDARKYXZhMR0wGwYDVQQLDBRTdW5KU1NFIFRlc3QgU2VyaXZj\n" +
                "ZTAeFw0xODA1MjIwNzE4MTZaFw0zODA1MTcwNzE4MTZaMDsxCzAJBgNVBAYTAlVT\n" +
                "MQ0wCwYDVQQKDARKYXZhMR0wGwYDVQQLDBRTdW5KU1NFIFRlc3QgU2VyaXZjZTBZ\n" +
                "MBMGByqGSM49AgEGCCqGSM49AwEHA0IABBz1WeVb6gM2mh85z3QlvaB/l11b5h0v\n" +
                "LIzmkC3DKlVukZT+ltH2Eq1oEkpXuf7QmbM0ibrUgtjsWH3mULfmcWmjUDBOMB0G\n" +
                "A1UdDgQWBBRgz71z//oaMNKk7NNJcUbvGjWghjAfBgNVHSMEGDAWgBRgz71z//oa\n" +
                "MNKk7NNJcUbvGjWghjAMBgNVHRMEBTADAQH/MAoGCCqGSM49BAMCA0kAMEYCIQCG\n" +
                "6wluh1r2/T6L31mZXRKf9JxeSf9pIzoLj+8xQeUChQIhAJ09wAi1kV8yePLh2FD9\n" +
                "2YEHlSQUAbwwqCDEVB5KxaqP\n" +
                "-----END CERTIFICATE-----",
                "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg/HcHdoLJCdq3haVd\n" +
                "XZTSKP00YzM3xX97l98vGL/RI1KhRANCAAQc9VnlW+oDNpofOc90Jb2gf5ddW+Yd\n" +
                "LyyM5pAtwypVbpGU/pbR9hKtaBJKV7n+0JmzNIm61ILY7Fh95lC35nFp"),

        CA_ECDSA_SECP384R1(
                "EC",
                // SHA384withECDSA, curve secp384r1
                // Validity
                //     Not Before: Jun 24 08:15:06 2019 GMT
                //     Not After : Jun 19 08:15:06 2039 GMT
                // Subject Key Identifier:
                //     0a:93:a9:a0:bf:e7:d5:48:9d:4f:89:15:c6:51:98:80:05:51:4e:4e
                "-----BEGIN CERTIFICATE-----\n" +
                "MIICCDCCAY6gAwIBAgIUCpOpoL/n1UidT4kVxlGYgAVRTk4wCgYIKoZIzj0EAwMw\n" +
                "OzELMAkGA1UEBhMCVVMxDTALBgNVBAoMBEphdmExHTAbBgNVBAsMFFN1bkpTU0Ug\n" +
                "VGVzdCBTZXJpdmNlMB4XDTE5MDYyNDA4MTUwNloXDTM5MDYxOTA4MTUwNlowOzEL\n" +
                "MAkGA1UEBhMCVVMxDTALBgNVBAoMBEphdmExHTAbBgNVBAsMFFN1bkpTU0UgVGVz\n" +
                "dCBTZXJpdmNlMHYwEAYHKoZIzj0CAQYFK4EEACIDYgAENVQN1wXWFdgC6u/dDdiC\n" +
                "y+WtMTF66oL/0BSm+1ZqsogamzCryawOcHgiuXgWzx5CQ3LuOC+tDFyXpGfHuCvb\n" +
                "dkzxPrP5n9NrR8/uRPe5l1KOUbchviU8z9cTP+LZxnZDo1MwUTAdBgNVHQ4EFgQU\n" +
                "SktSFArR1p/5mXV0kyo0RxIVa/UwHwYDVR0jBBgwFoAUSktSFArR1p/5mXV0kyo0\n" +
                "RxIVa/UwDwYDVR0TAQH/BAUwAwEB/zAKBggqhkjOPQQDAwNoADBlAjBZvoNmq3/v\n" +
                "RD2gBTyvxjS9h0rsMRLHDnvul/KWngytwGPTOBo0Y8ixQXSjdKoc3rkCMQDkiNgx\n" +
                "IDxuHedmrLQKIPnVcthTmwv7//jHiqGoKofwChMo2a1P+DQdhszmeHD/ARQ=\n" +
                "-----END CERTIFICATE-----",
                "MIG2AgEAMBAGByqGSM49AgEGBSuBBAAiBIGeMIGbAgEBBDChlbt0NF8oIKODSxn2\n" +
                "WXCXuJm3z78LRkzYQS3Nx5NMjei5ytkFZz4qvD4XXMWlTEyhZANiAAQ1VA3XBdYV\n" +
                "2ALq790N2ILL5a0xMXrqgv/QFKb7VmqyiBqbMKvJrA5weCK5eBbPHkJDcu44L60M\n" +
                "XJekZ8e4K9t2TPE+s/mf02tHz+5E97mXUo5RtyG+JTzP1xM/4tnGdkM="),

        CA_ECDSA_SECP521R1(
                "EC",
                // SHA512withECDSA, curve secp521r1
                // Validity
                //     Not Before: Jun 24 08:15:06 2019 GMT
                //     Not After : Jun 19 08:15:06 2039 GMT
                // Subject Key Identifier:
                //     25:ca:68:76:6d:29:17:9b:71:78:45:2d:d4:c6:e4:5d:fe:25:ff:90
                "-----BEGIN CERTIFICATE-----\n" +
                "MIICUzCCAbSgAwIBAgIUJcpodm0pF5txeEUt1MbkXf4l/5AwCgYIKoZIzj0EAwQw\n" +
                "OzELMAkGA1UEBhMCVVMxDTALBgNVBAoMBEphdmExHTAbBgNVBAsMFFN1bkpTU0Ug\n" +
                "VGVzdCBTZXJpdmNlMB4XDTE5MDYyNDA4MTUwNloXDTM5MDYxOTA4MTUwNlowOzEL\n" +
                "MAkGA1UEBhMCVVMxDTALBgNVBAoMBEphdmExHTAbBgNVBAsMFFN1bkpTU0UgVGVz\n" +
                "dCBTZXJpdmNlMIGbMBAGByqGSM49AgEGBSuBBAAjA4GGAAQAmFD5VmB2MdyJ6k+E\n" +
                "eP4JncrE65ySL07gVmFwnr8otOt3NtRAyzmviMNNXXjo5R5NqNjKP4pr92JjT0sO\n" +
                "D65yngkBtH151Ev/fiKPLxkXL9GzfKdWHVhDX7Zg6DUydzukzZV2/dIyloAIqwlz\n" +
                "QVKJqT7RypDufdng8hnE9YfKo6ypZiujUzBRMB0GA1UdDgQWBBRAIrxa7WqtqUCe\n" +
                "HFuKREDC92spvTAfBgNVHSMEGDAWgBRAIrxa7WqtqUCeHFuKREDC92spvTAPBgNV\n" +
                "HRMBAf8EBTADAQH/MAoGCCqGSM49BAMEA4GMADCBiAJCAe22iirZnODCmlpxcv57\n" +
                "3g5BEE60C+dtYmTqR4DtFyDaTRQ5CFf4ZxvQPIbD+SXi5Cbrl6qtrZG0cjUihPkC\n" +
                "Hi1hAkIAiEcO7nMPgQLny+GrciojfN+bZXME/dPz6KHBm/89f8Me+jawVnv6y+df\n" +
                "2Sbafh1KV6ntWQtB4bK3MXV8Ym9Eg1I=\n" +
                "-----END CERTIFICATE-----",
                "MIHuAgEAMBAGByqGSM49AgEGBSuBBAAjBIHWMIHTAgEBBEIAV8dZszV6+nLw3LeA\n" +
                "Q+qLJLGaqyjlsQkaopCPcmoRdy1HX6AzB/YnKsPkHp/9DQN6A2JgUhFG5B0XvKSk\n" +
                "BqNNuSGhgYkDgYYABACYUPlWYHYx3InqT4R4/gmdysTrnJIvTuBWYXCevyi063c2\n" +
                "1EDLOa+Iw01deOjlHk2o2Mo/imv3YmNPSw4PrnKeCQG0fXnUS/9+Io8vGRcv0bN8\n" +
                "p1YdWENftmDoNTJ3O6TNlXb90jKWgAirCXNBUompPtHKkO592eDyGcT1h8qjrKlm\n" +
                "Kw=="),

        CA_RSA_2048(
                "RSA",
                // SHA256withRSA, 2048 bits
                // Validity
                //     Not Before: May 22 07:18:16 2018 GMT
                //     Not After : May 17 07:18:16 2038 GMT
                // Subject Key Identifier:
                //     0D:DD:93:C9:FE:4B:BD:35:B7:E8:99:78:90:FB:DB:5A:3D:DB:15:4C
                "-----BEGIN CERTIFICATE-----\n" +
                "MIIDSTCCAjGgAwIBAgIJAI4ZF3iy8zG+MA0GCSqGSIb3DQEBCwUAMDsxCzAJBgNV\n" +
                "BAYTAlVTMQ0wCwYDVQQKDARKYXZhMR0wGwYDVQQLDBRTdW5KU1NFIFRlc3QgU2Vy\n" +
                "aXZjZTAeFw0xODA1MjIwNzE4MTZaFw0zODA1MTcwNzE4MTZaMDsxCzAJBgNVBAYT\n" +
                "AlVTMQ0wCwYDVQQKDARKYXZhMR0wGwYDVQQLDBRTdW5KU1NFIFRlc3QgU2VyaXZj\n" +
                "ZTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBALpMcY7aWieXDEM1/YJf\n" +
                "JW27b4nRIFZyEYhEloyGsKTuQiiQjc8cqRZFNXe2vwziDB4IyTEl0Hjl5QF6ZaQE\n" +
                "huPzzwvQm1pv64KrRXrmj3FisQK8B5OWLty9xp6xDqsaMRoyObLK+oIb20T5fSlE\n" +
                "evmo1vYjnh8CX0Yzx5Gr5ye6YSEHQvYOWEws8ad17OlyToR2KMeC8w4qo6rs59pW\n" +
                "g7Mxn9vo22ImDzrtAbTbXbCias3xlE0Bp0h5luyf+5U4UgksoL9B9r2oP4GrLNEV\n" +
                "oJk57t8lwaR0upiv3CnS8LcJELpegZub5ggqLY8ZPYFQPjlK6IzLOm6rXPgZiZ3m\n" +
                "RL0CAwEAAaNQME4wHQYDVR0OBBYEFA3dk8n+S701t+iZeJD721o92xVMMB8GA1Ud\n" +
                "IwQYMBaAFA3dk8n+S701t+iZeJD721o92xVMMAwGA1UdEwQFMAMBAf8wDQYJKoZI\n" +
                "hvcNAQELBQADggEBAJTRC3rKUUhVH07/1+stUungSYgpM08dY4utJq0BDk36BbmO\n" +
                "0AnLDMbkwFdHEoqF6hQIfpm7SQTmXk0Fss6Eejm8ynYr6+EXiRAsaXOGOBCzF918\n" +
                "/RuKOzqABfgSU4UBKECLM5bMfQTL60qx+HdbdVIpnikHZOFfmjCDVxoHsGyXc1LW\n" +
                "Jhkht8IGOgc4PMGvyzTtRFjz01kvrVQZ75aN2E0GQv6dCxaEY0i3ypSzjUWAKqDh\n" +
                "3e2OLwUSvumcdaxyCdZAOUsN6pDBQ+8VRG7KxnlRlY1SMEk46QgQYLbPDe/+W/yH\n" +
                "ca4PejicPeh+9xRAwoTpiE2gulfT7Lm+fVM7Ruc=\n" +
                "-----END CERTIFICATE-----",
                "MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQC6THGO2lonlwxD\n" +
                "Nf2CXyVtu2+J0SBWchGIRJaMhrCk7kIokI3PHKkWRTV3tr8M4gweCMkxJdB45eUB\n" +
                "emWkBIbj888L0Jtab+uCq0V65o9xYrECvAeTli7cvcaesQ6rGjEaMjmyyvqCG9tE\n" +
                "+X0pRHr5qNb2I54fAl9GM8eRq+cnumEhB0L2DlhMLPGndezpck6EdijHgvMOKqOq\n" +
                "7OfaVoOzMZ/b6NtiJg867QG0212womrN8ZRNAadIeZbsn/uVOFIJLKC/Qfa9qD+B\n" +
                "qyzRFaCZOe7fJcGkdLqYr9wp0vC3CRC6XoGbm+YIKi2PGT2BUD45SuiMyzpuq1z4\n" +
                "GYmd5kS9AgMBAAECggEAFHSoU2MuWwJ+2jJnb5U66t2V1bAcuOE1g5zkWvG/G5z9\n" +
                "rq6Qo5kmB8f5ovdx6tw3MGUOklLwnRXBG3RxDJ1iokz3AvkY1clMNsDPlDsUrQKF\n" +
                "JSO4QUBQTPSZhnsyfR8XHSU+qJ8Y+ohMfzpVv95BEoCzebtXdVgxVegBlcEmVHo2\n" +
                "kMmkRN+bYNsr8eb2r+b0EpyumS39ZgKYh09+cFb78y3T6IFMGcVJTP6nlGBFkmA/\n" +
                "25pYeCF2tSki08qtMJZQAvKfw0Kviibk7ZxRbJqmc7B1yfnOEHP6ftjuvKl2+RP/\n" +
                "+5P5f8CfIP6gtA0LwSzAqQX/hfIKrGV5j0pCqrD0kQKBgQDeNR6Xi4sXVq79lihO\n" +
                "a1bSeV7r8yoQrS8x951uO+ox+UIZ1MsAULadl7zB/P0er92p198I9M/0Jth3KBuS\n" +
                "zj45mucvpiiGvmQlMKMEfNq4nN7WHOu55kufPswQB2mR4J3xmwI+4fM/nl1zc82h\n" +
                "De8JSazRldJXNhfx0RGFPmgzbwKBgQDWoVXrXLbCAn41oVnWB8vwY9wjt92ztDqJ\n" +
                "HMFA/SUohjePep9UDq6ooHyAf/Lz6oE5NgeVpPfTDkgvrCFVKnaWdwALbYoKXT2W\n" +
                "9FlyJox6eQzrtHAacj3HJooXWuXlphKSizntfxj3LtMR9BmrmRJOfK+SxNOVJzW2\n" +
                "+MowT20EkwKBgHmpB8jdZBgxI7o//m2BI5Y1UZ1KE5vx1kc7VXzHXSBjYqeV9FeF\n" +
                "2ZZLP9POWh/1Fh4pzTmwIDODGT2UPhSQy0zq3O0fwkyT7WzXRknsuiwd53u/dejg\n" +
                "iEL2NPAJvulZ2+AuiHo5Z99LK8tMeidV46xoJDDUIMgTG+UQHNGhK5gNAoGAZn/S\n" +
                "Cn7SgMC0CWSvBHnguULXZO9wH1wZAFYNLL44OqwuaIUFBh2k578M9kkke7woTmwx\n" +
                "HxQTjmWpr6qimIuY6q6WBN8hJ2Xz/d1fwhYKzIp20zHuv5KDUlJjbFfqpsuy3u1C\n" +
                "kts5zwI7pr1ObRbDGVyOdKcu7HI3QtR5qqyjwaUCgYABo7Wq6oHva/9V34+G3Goh\n" +
                "63bYGUnRw2l5BD11yhQv8XzGGZFqZVincD8gltNThB0Dc/BI+qu3ky4YdgdZJZ7K\n" +
                "z51GQGtaHEbrHS5caV79yQ8QGY5mUVH3E+VXSxuIqb6pZq2DH4sTAEFHyncddmOH\n" +
                "zoXBInYwRG9KE/Bw5elhUw=="),

        CA_RSA_512( // for DisabledShortRSAKeys test
                "RSA",
                // md5WithRSAEncryption, 1024 bits
                // Validity
                //      Not Before: Aug 19 01:52:19 2011 GMT
                //      Not After : Jul 29 01:52:19 2032 GMT
                // X509v3 Authority Key Identifier:
                //      keyid:B9:7C:D5:D9:DF:A7:4C:03:AE:FD:0E:27:5B:31:95:6C:C7:F3:75:E1
                //      DirName:/C=US/O=Java/OU=SunJSSE Test Serivce
                //      serial:00
                "-----BEGIN CERTIFICATE-----\n" +
                "MIICkjCCAfugAwIBAgIBADANBgkqhkiG9w0BAQQFADA7MQswCQYDVQQGEwJVUzEN\n" +
                "MAsGA1UEChMESmF2YTEdMBsGA1UECxMUU3VuSlNTRSBUZXN0IFNlcml2Y2UwHhcN\n" +
                "MTEwODE5MDE1MjE5WhcNMzIwNzI5MDE1MjE5WjA7MQswCQYDVQQGEwJVUzENMAsG\n" +
                "A1UEChMESmF2YTEdMBsGA1UECxMUU3VuSlNTRSBUZXN0IFNlcml2Y2UwgZ8wDQYJ\n" +
                "KoZIhvcNAQEBBQADgY0AMIGJAoGBAM8orG08DtF98TMSscjGsidd1ZoN4jiDpi8U\n" +
                "ICz+9dMm1qM1d7O2T+KH3/mxyox7Rc2ZVSCaUD0a3CkhPMnlAx8V4u0H+E9sqso6\n" +
                "iDW3JpOyzMExvZiRgRG/3nvp55RMIUV4vEHOZ1QbhuqG4ebN0Vz2DkRft7+flthf\n" +
                "vDld6f5JAgMBAAGjgaUwgaIwHQYDVR0OBBYEFLl81dnfp0wDrv0OJ1sxlWzH83Xh\n" +
                "MGMGA1UdIwRcMFqAFLl81dnfp0wDrv0OJ1sxlWzH83XhoT+kPTA7MQswCQYDVQQG\n" +
                "EwJVUzENMAsGA1UEChMESmF2YTEdMBsGA1UECxMUU3VuSlNTRSBUZXN0IFNlcml2\n" +
                "Y2WCAQAwDwYDVR0TAQH/BAUwAwEB/zALBgNVHQ8EBAMCAQYwDQYJKoZIhvcNAQEE\n" +
                "BQADgYEALlgaH1gWtoBZ84EW8Hu6YtGLQ/L9zIFmHonUPZwn3Pr//icR9Sqhc3/l\n" +
                "pVTxOINuFHLRz4BBtEylzRIOPzK3tg8XwuLb1zd0db90x3KBCiAL6E6cklGEPwLe\n" +
                "XYMHDn9eDsaq861Tzn6ZwzMgw04zotPMoZN0mVd/3Qca8UJFucE=\n" +
                "-----END CERTIFICATE-----",
                "MIIBVAIBADANBgkqhkiG9w0BAQEFAASCAT4wggE6AgEAAkEAtz2+PTkj3zg9hh0l\n" +
                "xQhcdRiRGX6hvWVH+8biIv62uXS4zxgGqGOUBdkDmvFZzq4kAlw38lx8bLkZxDqJ\n" +
                "sS+xFQIDAQABAkByx/5Oo2hQ/w2q4L8z+NTRlJ3vdl8iIDtC/4XPnfYfnGptnpG6\n" +
                "ZThQRvbMZiai0xHQPQMszvAHjZVme1eDl3EBAiEA3aKJHynPVCEJhpfCLWuMwX5J\n" +
                "1LntwJO7NTOyU5m8rPECIQDTpzn5X44r2rzWBDna/Sx7HW9IWCxNgUD2Eyi2nA7W\n" +
                "ZQIgJerEorw4aCAuzQPxiGu57PB6GRamAihEAtoRTBQlH0ECIQDN08FgTtnesgCU\n" +
                "DFYLLcw1CiHvc7fZw4neBDHCrC8NtQIgA8TOUkGnpCZlQ0KaI8KfKWI+vxFcgFnH\n" +
                "3fnqsTgaUs4="

        ),

        CA_RSA_MD5_512 ( // for ShortRSAKeys512 and ShortRSAKeyGCM tests
                "RSA",
                // Signature Algorithm: md5WithRSAEncryption
                // Issuer: C = US, O = Java, OU = SunJSSE Test Serivce
                // Validity
                //     Not Before: Aug 19 01:52:19 2011 GMT
                //     Not After : Jul 29 01:52:19 2032 GMT
                // Authority Key Identifier:
                //     B9:7C:D5:D9:DF:A7:4C:03:AE:FD:0E:27:5B:31:95:6C:C7:F3:75:E1
                "-----BEGIN CERTIFICATE-----\n" +
                "MIICkjCCAfugAwIBAgIBADANBgkqhkiG9w0BAQQFADA7MQswCQYDVQQGEwJVUzEN\n" +
                "MAsGA1UEChMESmF2YTEdMBsGA1UECxMUU3VuSlNTRSBUZXN0IFNlcml2Y2UwHhcN\n" +
                "MTEwODE5MDE1MjE5WhcNMzIwNzI5MDE1MjE5WjA7MQswCQYDVQQGEwJVUzENMAsG\n" +
                "A1UEChMESmF2YTEdMBsGA1UECxMUU3VuSlNTRSBUZXN0IFNlcml2Y2UwgZ8wDQYJ\n" +
                "KoZIhvcNAQEBBQADgY0AMIGJAoGBAM8orG08DtF98TMSscjGsidd1ZoN4jiDpi8U\n" +
                "ICz+9dMm1qM1d7O2T+KH3/mxyox7Rc2ZVSCaUD0a3CkhPMnlAx8V4u0H+E9sqso6\n" +
                "iDW3JpOyzMExvZiRgRG/3nvp55RMIUV4vEHOZ1QbhuqG4ebN0Vz2DkRft7+flthf\n" +
                "vDld6f5JAgMBAAGjgaUwgaIwHQYDVR0OBBYEFLl81dnfp0wDrv0OJ1sxlWzH83Xh\n" +
                "MGMGA1UdIwRcMFqAFLl81dnfp0wDrv0OJ1sxlWzH83XhoT+kPTA7MQswCQYDVQQG\n" +
                "EwJVUzENMAsGA1UEChMESmF2YTEdMBsGA1UECxMUU3VuSlNTRSBUZXN0IFNlcml2\n" +
                "Y2WCAQAwDwYDVR0TAQH/BAUwAwEB/zALBgNVHQ8EBAMCAQYwDQYJKoZIhvcNAQEE\n" +
                "BQADgYEALlgaH1gWtoBZ84EW8Hu6YtGLQ/L9zIFmHonUPZwn3Pr//icR9Sqhc3/l\n" +
                "pVTxOINuFHLRz4BBtEylzRIOPzK3tg8XwuLb1zd0db90x3KBCiAL6E6cklGEPwLe\n" +
                "XYMHDn9eDsaq861Tzn6ZwzMgw04zotPMoZN0mVd/3Qca8UJFucE=\n" +
                "-----END CERTIFICATE-----",
                ""
        ),

        CA_DSA_2048(
                "DSA",
                // SHA256withDSA, 2048 bits
                // Validity
                //     Not Before: May 22 07:18:18 2018 GMT
                //     Not After : May 17 07:18:18 2038 GMT
                // Subject Key Identifier:
                //     76:66:9E:F7:3B:DD:45:E5:3B:D9:72:3C:3F:F0:54:39:86:31:26:53
                "-----BEGIN CERTIFICATE-----\n" +
                "MIIErjCCBFSgAwIBAgIJAOktYLNCbr02MAsGCWCGSAFlAwQDAjA7MQswCQYDVQQG\n" +
                "EwJVUzENMAsGA1UECgwESmF2YTEdMBsGA1UECwwUU3VuSlNTRSBUZXN0IFNlcml2\n" +
                "Y2UwHhcNMTgwNTIyMDcxODE4WhcNMzgwNTE3MDcxODE4WjA7MQswCQYDVQQGEwJV\n" +
                "UzENMAsGA1UECgwESmF2YTEdMBsGA1UECwwUU3VuSlNTRSBUZXN0IFNlcml2Y2Uw\n" +
                "ggNHMIICOQYHKoZIzjgEATCCAiwCggEBAO5GyPhSm0ze3LSu+gicdULLj05iOfTL\n" +
                "UvZQ29sYz41zmqrLBQbdKiHqgJu2Re9sgTb5suLNjF047TOLPnU3jhPtWm2X8Xzi\n" +
                "VGIcHym/Q/MeZxStt/88seqroI3WOKzIML2GcrishT+lcGrtH36Tf1+ue2Snn3PS\n" +
                "WyxygNqPjllP5uUjYmFLvAf4QLMldkd/D2VxcwsHjB8y5iUZsXezc/LEhRZS/02m\n" +
                "ivqlRw3AMkq/OVe/ZtxFWsP0nsfxEGdZuaUFpppGfixxFvymrB3+J51cTt+pZBDq\n" +
                "D2y0DYfc+88iCs4jwHTfcDIpLb538HBjBj2rEgtQESQmB0ooD/+wsPsCIQC1bYch\n" +
                "gElNtDYL3FgpLgNSUYp7gIWv9ehaC7LO2z7biQKCAQBitvFOnDkUja8NAF7lDpOV\n" +
                "b5ipQ8SicBLW3kQamxhyuyxgZyy/PojZ/oPorkqW/T/A0rhnG6MssEpAtdiwVB+c\n" +
                "rBYGo3bcwmExJhdOJ6dYuKFppPWhCwKMHs9npK+lqBMl8l5j58xlcFeC7ZfGf8GY\n" +
                "GkhFW0c44vEQhMMbac6ZTTP4mw+1t7xJfmDMlLEyIpTXaAAk8uoVLWzQWnR40sHi\n" +
                "ybvS0u3JxQkb7/y8tOOZu8qlz/YOS7lQ6UxUGX27Ce1E0+agfPphetoRAlS1cezq\n" +
                "Wa7r64Ga0nkj1kwkcRqjgTiJx0NwnUXr78VAXFhVF95+O3lfqhvdtEGtkhDGPg7N\n" +
                "A4IBBgACggEBAMmSHQK0w2i+iqUjOPzn0yNEZrzepLlLeQ1tqtn0xnlv5vBAeefD\n" +
                "Pm9dd3tZOjufVWP7hhEz8xPobb1CS4e3vuQiv5UBfhdPL3f3l9T7JMAKPH6C9Vve\n" +
                "OQXE5eGqbjsySbcmseHoYUt1WCSnSda1opX8zchX04e7DhGfE2/L9flpYEoSt8lI\n" +
                "vMNjgOwvKdW3yvPt1/eBBHYNFG5gWPv/Q5KoyCtHS03uqGm4rNc/wZTIEEfd66C+\n" +
                "QRaUltjOaHmtwOdDHaNqwhYZSVOip+Mo+TfyzHFREcdHLapo7ZXqbdYkRGxRR3d+\n" +
                "3DfHaraJO0OKoYlPkr3JMvM/MSGR9AnZOcejUDBOMB0GA1UdDgQWBBR2Zp73O91F\n" +
                "5TvZcjw/8FQ5hjEmUzAfBgNVHSMEGDAWgBR2Zp73O91F5TvZcjw/8FQ5hjEmUzAM\n" +
                "BgNVHRMEBTADAQH/MAsGCWCGSAFlAwQDAgNHADBEAiBzriYE41M2y9Hy5ppkL0Qn\n" +
                "dIlNc8JhXT/PHW7GDtViagIgMko8Qoj9gDGPK3+O9E8DC3wGiiF9CObM4LN387ok\n" +
                "J+g=\n" +
                "-----END CERTIFICATE-----",
                "MIICZQIBADCCAjkGByqGSM44BAEwggIsAoIBAQDuRsj4UptM3ty0rvoInHVCy49O" +
                "Yjn0y1L2UNvbGM+Nc5qqywUG3Soh6oCbtkXvbIE2+bLizYxdOO0ziz51N44T7Vpt" +
                "l/F84lRiHB8pv0PzHmcUrbf/PLHqq6CN1jisyDC9hnK4rIU/pXBq7R9+k39frntk" +
                "p59z0lsscoDaj45ZT+blI2JhS7wH+ECzJXZHfw9lcXMLB4wfMuYlGbF3s3PyxIUW" +
                "Uv9Npor6pUcNwDJKvzlXv2bcRVrD9J7H8RBnWbmlBaaaRn4scRb8pqwd/iedXE7f" +
                "qWQQ6g9stA2H3PvPIgrOI8B033AyKS2+d/BwYwY9qxILUBEkJgdKKA//sLD7AiEA" +
                "tW2HIYBJTbQ2C9xYKS4DUlGKe4CFr/XoWguyzts+24kCggEAYrbxTpw5FI2vDQBe" +
                "5Q6TlW+YqUPEonAS1t5EGpsYcrssYGcsvz6I2f6D6K5Klv0/wNK4ZxujLLBKQLXY" +
                "sFQfnKwWBqN23MJhMSYXTienWLihaaT1oQsCjB7PZ6SvpagTJfJeY+fMZXBXgu2X" +
                "xn/BmBpIRVtHOOLxEITDG2nOmU0z+JsPtbe8SX5gzJSxMiKU12gAJPLqFS1s0Fp0" +
                "eNLB4sm70tLtycUJG+/8vLTjmbvKpc/2Dku5UOlMVBl9uwntRNPmoHz6YXraEQJU" +
                "tXHs6lmu6+uBmtJ5I9ZMJHEao4E4icdDcJ1F6+/FQFxYVRfefjt5X6ob3bRBrZIQ" +
                "xj4OzQQjAiEAsceWOM8do4etxp2zgnoNXV8PUUyqWhz1+0srcKV7FR4="),

        CA_DSA_512(
                "DSA",
                // dsaWithSHA1, 512 bits
                // Validity
                //     Not Before: Feb 16 04:35:46 2016 GMT
                //     Not After : Nov  3 04:35:46 2035 GMT
                // Authority Key Identifier:
                //    5F:8A:9B:3F:93:E0:07:1D:49:F0:12:7C:A8:48:1F:A0:A5:4B:4A:74
                "-----BEGIN CERTIFICATE-----\n" +
                "MIICUjCCAhGgAwIBAgIJAIiDrs/4W8rtMAkGByqGSM44BAMwHzELMAkGA1UEBhMC\n" +
                "VVMxEDAOBgNVBAoTB0V4YW1wbGUwHhcNMTYwMjE2MDQzNTQ2WhcNMzUxMTAzMDQz\n" +
                "NTQ2WjA5MQswCQYDVQQGEwJVUzEQMA4GA1UECgwHRXhhbXBsZTEYMBYGA1UEAwwP\n" +
                "d3d3LmV4YW1wbGUuY29tMIHwMIGoBgcqhkjOOAQBMIGcAkEAs6A0p3TysTtVXGSv\n" +
                "ThR/8GHpbL49KyWRJBMIlmLc5jl/wxJgnL1t07p4YTOEa6ecyTFos04Z8n2GARmp\n" +
                "zYlUywIVAJLDcf4JXhZbguRFSQdWwWhZkh+LAkBLCzh3Xvpmc/5CDqU+QHqDcuSk\n" +
                "5B8+ZHaHRi2KQ00ejilpF2qZpW5JdHe4m3Pggh0MIuaAGX+leM4JKlnObj14A0MA\n" +
                "AkAYb+DYlFgStFhF1ip7rFzY8K6i/3ellkXI2umI/XVwxUQTHSlk5nFOep5Dfzm9\n" +
                "pADJwuSe1qGHsHB5LpMZPVpto4GEMIGBMAkGA1UdEwQCMAAwCwYDVR0PBAQDAgPo\n" +
                "MB0GA1UdDgQWBBT8nsFyccF4q1dtpWE1dkNK5UiXtTAfBgNVHSMEGDAWgBRfips/\n" +
                "k+AHHUnwEnyoSB+gpUtKdDAnBgNVHSUEIDAeBggrBgEFBQcDAQYIKwYBBQUHAwIG\n" +
                "CCsGAQUFBwMDMAkGByqGSM44BAMDMAAwLQIUIcIlxpIwaZXdpMC+U076unR1Mp8C\n" +
                "FQCD/NE8O0xwq57nwFfp7tUvUHYMMA==\n" +
                "-----END CERTIFICATE-----",
                "MIHGAgEAMIGoBgcqhkjOOAQBMIGcAkEAs6A0p3TysTtVXGSvThR/8GHpbL49KyWR\n" +
                "JBMIlmLc5jl/wxJgnL1t07p4YTOEa6ecyTFos04Z8n2GARmpzYlUywIVAJLDcf4J\n" +
                "XhZbguRFSQdWwWhZkh+LAkBLCzh3Xvpmc/5CDqU+QHqDcuSk5B8+ZHaHRi2KQ00e\n" +
                "jilpF2qZpW5JdHe4m3Pggh0MIuaAGX+leM4JKlnObj14BBYCFHB2Wek2g5hpNj5y\n" +
                "RQfCc6CFO0dv"
        ),

        CA_DSA_1024(
                "DSA",
                // dsaWithSHA1, 1024 bits
                // Validity
                //     Not Before: Apr 24 12:25:43 2020 GMT
                //     Not After : Apr 22 12:25:43 2030 GMT
                // Authority Key Identifier:
                //     E1:3C:01:52:EB:D1:38:F7:CF:F1:E3:5E:DB:54:75:7F:5E:AB:2D:36
                "-----BEGIN CERTIFICATE-----\n" +
                "MIIC9TCCArWgAwIBAgIUd52yKk0OxQuxdaYRAfq5VLuF1ZAwCQYHKoZIzjgEAzAu\n" +
                "MQswCQYDVQQGEwJVUzENMAsGA1UECgwESmF2YTEQMA4GA1UECwwHU3VuSlNTRTAe\n" +
                "Fw0yMDA0MjQxMjI1NDJaFw0zMDA0MjIxMjI1NDJaMC4xCzAJBgNVBAYTAlVTMQ0w\n" +
                "CwYDVQQKDARKYXZhMRAwDgYDVQQLDAdTdW5KU1NFMIIBtjCCASsGByqGSM44BAEw\n" +
                "ggEeAoGBAKgyb2XpANq43T8yBf5v0PTBOddLPxd0f0FotASron5rQr86JjBTfgIW\n" +
                "oE4u7nYlO6bp/M4Dw6qZr+HaDu9taIDOj6LL51eUShVsOgS7XZcUzLT8vPnkEDDo\n" +
                "u326x0B7fuNCbMLm+ipM2d4FhLUTt4Qb5TcY6l7dOGHeWiL7nl43AhUAoGr8DY2m\n" +
                "WHZPHk2XbZ5wpaM2lLcCgYBKiFbFFViH/ylHJRPtYtjtJw4ls1scbVP4TRHnKoZc\n" +
                "HPAird1fDYgGC2b0GQNAMABhI+L+ogxS7qakySpJCheuN25AjiSyilygQdlXoWRt\n" +
                "Mggsh8EQZT7iP4V4e9m3xRHzb5ECvsSTdZB1BQMcC90W2Avq+orqgBnr2in9UEd8\n" +
                "qwOBhAACgYAgVWxjYWlWIv7s4BnNMQoPKppi205f3aC6wv6Rqk4BnYYYrFONEmzQ\n" +
                "hzj6lSXfxLpTu4lg2zNeIraZggoS0ztkbZNNADEmAHx+OLshiJJxu2/KfoopJOZg\n" +
                "8ARmuaKOkWbkW9y4hWhfBlVwZbckG3Eibff0xronIXXy7B7UKaccyqNTMFEwHQYD\n" +
                "VR0OBBYEFOE8AVLr0Tj3z/HjXttUdX9eqy02MB8GA1UdIwQYMBaAFOE8AVLr0Tj3\n" +
                "z/HjXttUdX9eqy02MA8GA1UdEwEB/wQFMAMBAf8wCQYHKoZIzjgEAwMvADAsAhRC\n" +
                "YLduLniBEJ51SfBWIkvNW6OG7QIUSKaTY6rgEFDEMoTqOjFChR22nkk=\n" +
                "-----END CERTIFICATE-----",
                "MIIBSgIBADCCASsGByqGSM44BAEwggEeAoGBAKgyb2XpANq43T8yBf5v0PTBOddL\n" +
                "Pxd0f0FotASron5rQr86JjBTfgIWoE4u7nYlO6bp/M4Dw6qZr+HaDu9taIDOj6LL\n" +
                "51eUShVsOgS7XZcUzLT8vPnkEDDou326x0B7fuNCbMLm+ipM2d4FhLUTt4Qb5TcY\n" +
                "6l7dOGHeWiL7nl43AhUAoGr8DY2mWHZPHk2XbZ5wpaM2lLcCgYBKiFbFFViH/ylH\n" +
                "JRPtYtjtJw4ls1scbVP4TRHnKoZcHPAird1fDYgGC2b0GQNAMABhI+L+ogxS7qak\n" +
                "ySpJCheuN25AjiSyilygQdlXoWRtMggsh8EQZT7iP4V4e9m3xRHzb5ECvsSTdZB1\n" +
                "BQMcC90W2Avq+orqgBnr2in9UEd8qwQWAhQ7rSn+WvIxeuZ/CK4p04eMe5JzpA=="),

        CA_ED25519(
                "EdDSA",
                // ED25519
                // Validity
                //     Not Before: May 24 23:32:35 2020 GMT
                //     Not After : May 22 23:32:35 2030 GMT
                // X509v3 Authority Key Identifier:
                //     keyid:06:76:DB:88:EB:61:55:4C:C9:63:41:C2:A0:A8:57:3F:D7:F1:B8:EC
                "-----BEGIN CERTIFICATE-----\n" +
                "MIIByTCCAXugAwIBAgIUCyxKvhErehsygx50JYArsHby9hAwBQYDK2VwMDsxCzAJ\n" +
                "BgNVBAYTAlVTMQ0wCwYDVQQKDARqYXZhMR0wGwYDVQQLDBRTdW5KU1NFIFRlc3Qg\n" +
                "U2VyaXZjZTAeFw0yMDA1MjQyMzMyMzVaFw0zMDA1MjIyMzMyMzVaMDsxCzAJBgNV\n" +
                "BAYTAlVTMQ0wCwYDVQQKDARqYXZhMR0wGwYDVQQLDBRTdW5KU1NFIFRlc3QgU2Vy\n" +
                "aXZjZTAqMAUGAytlcAMhAKdotuYIkH8PYbopSLbaf1BtqUY2d6AbTgK2prMzQ6B3\n" +
                "o4GQMIGNMA8GA1UdEwEB/wQFMAMBAf8wHQYDVR0OBBYEFAZ224jrYVVMyWNBwqCo\n" +
                "Vz/X8bjsMB8GA1UdIwQYMBaAFAZ224jrYVVMyWNBwqCoVz/X8bjsMA4GA1UdDwEB\n" +
                "/wQEAwIBhjAqBgNVHSUBAf8EIDAeBggrBgEFBQcDAwYIKwYBBQUHAwgGCCsGAQUF\n" +
                "BwMJMAUGAytlcANBADVAArvME8xFigFhCCCOTBoy/4ldGkDZQ/GT3Q6xnAP558FU\n" +
                "0G32OprKQZP43D9bmFU0LMgCVM9bHWU+bu/10AU=\n" +
                "-----END CERTIFICATE-----",
                "MC4CAQAwBQYDK2VwBCIEII/VYp8nu/eqq2L5y7/3IzavBgis4LWP6Rikv0N8SpgL"),

        CA_ED448(
                "EdDSA",
                // ED448
                // Validity
                //     Not Before: May 24 23:23:43 2020 GMT
                //     Not After : May 22 23:23:43 2030 GMT
                // X509v3 Authority Key Identifier:
                //     keyid:F5:D5:9D:FB:6F:B7:50:29:DF:F0:B8:83:10:5F:9B:C4:A8:1C:E9:F4
                "-----BEGIN CERTIFICATE-----\n" +
                "MIICFDCCAZSgAwIBAgIUKcmLeKilq0LN40sniBJO7F1gb/owBQYDK2VxMDsxCzAJ\n" +
                "BgNVBAYTAlVTMQ0wCwYDVQQKDARqYXZhMR0wGwYDVQQLDBRTdW5KU1NFIFRlc3Qg\n" +
                "U2VyaXZjZTAeFw0yMDA1MjQyMzIzNDNaFw0zMDA1MjIyMzIzNDNaMDsxCzAJBgNV\n" +
                "BAYTAlVTMQ0wCwYDVQQKDARqYXZhMR0wGwYDVQQLDBRTdW5KU1NFIFRlc3QgU2Vy\n" +
                "aXZjZTBDMAUGAytlcQM6APYP8iSXS8xPVDike5RgCByfTtg4GGtpYfoBtt6G5szA\n" +
                "55ExAKjm03wtk29nEPU2mCHF2QgfBzUrgKOBkDCBjTAPBgNVHRMBAf8EBTADAQH/\n" +
                "MB0GA1UdDgQWBBT11Z37b7dQKd/wuIMQX5vEqBzp9DAfBgNVHSMEGDAWgBT11Z37\n" +
                "b7dQKd/wuIMQX5vEqBzp9DAOBgNVHQ8BAf8EBAMCAYYwKgYDVR0lAQH/BCAwHgYI\n" +
                "KwYBBQUHAwMGCCsGAQUFBwMIBggrBgEFBQcDCTAFBgMrZXEDcwAlRXA2gPb52yV3\n" +
                "MKJErjmKlYSFExj5w5jafbbd0QgI1yDs+qSaZLjQ8ljwabmLDg+KR+167m0djQDI\n" +
                "OOoVuL7bgM0RL836KnuuBzm+gTdPp0gCXy3k9lL0KA0V2YLJHXXzu3suu+7rdgoP\n" +
                "plCh2hWdLgA=\n" +
                "-----END CERTIFICATE-----",
                "MEcCAQAwBQYDK2VxBDsEOd6/hRZqkUyTlJSwdN5gO/HnoWYda1fD83YUm5j6m2Bg\n" +
                "hAQi+QadFsQLD7R6PI/4Q0twXqlKnxU5Ug=="),

        CA_DSA_SHA1_1024( // for SignatureAlgorithms test
                "DSA",
                // Signature Algorithm: dsaWithSHA1
                // Validity
                //   Not Before: Dec  3 13:52:25 2015 GMT
                //   Not After : Nov 12 13:52:25 2036 GMT
                // Authority Key Identifier:
                //     F5:EC:77:2A:2E:59:6C:AA:76:73:3F:E9:04:95:C1:9C:04:69:AE:6E
                "-----BEGIN CERTIFICATE-----\n" +
                "MIIDYTCCAyGgAwIBAgIJAK8/gw6zg/DPMAkGByqGSM44BAMwOzELMAkGA1UEBhMC\n" +
                "VVMxDTALBgNVBAoTBEphdmExHTAbBgNVBAsTFFN1bkpTU0UgVGVzdCBTZXJpdmNl\n" +
                "MB4XDTE1MTIwMzEzNTIyNVoXDTM2MTExMjEzNTIyNVowOzELMAkGA1UEBhMCVVMx\n" +
                "DTALBgNVBAoTBEphdmExHTAbBgNVBAsTFFN1bkpTU0UgVGVzdCBTZXJpdmNlMIIB\n" +
                "uDCCASwGByqGSM44BAEwggEfAoGBAPH+b+GSMX6KS7jXDRevzc464DFG4X+uxu5V\n" +
                "b3U4yhsU8A8cuH4gwin6L/IDkmZQ7N0zC0jRsiGVSMsFETTq10F39pH2eBfUv/hJ\n" +
                "cLfBnIjBEtVqV/dExK88Hul2sZ4mQihQ4issPl7hsroS9EWYicnX0oNAqAB9PO5Y\n" +
                "zKbfpL7TAhUA13WW48rln2UP/LaAgtnzKhqcNtMCgYEA3Rv0GirTbAaor8iURd82\n" +
                "b5FlDTevOCTuq7ZIpfZVV30neS7cBYNet6m/3/4cfUlbbrqhbqIJ2I+I81drnN0Y\n" +
                "lyN4KkuxEcB6OTwfWkIUj6rvPaCQrBH8Q213bDq3HHtYNaP8OoeQUyVXW+SEGADC\n" +
                "J1+z8uqP3lIB6ltdgOiV/GQDgYUAAoGBAOXRppuJSGdt6AiZkb81P1DCUgIUlZFI\n" +
                "J9GxWrjbbHDmGllMwPNhK6dU7LJKJJuYVPW+95rUGlSJEjRqSlHuyHkNb6e3e7qx\n" +
                "tmx1/oIyq+oLult50hBS7uBvLLR0JbIKjBzzkudL8Rjze4G/Wq7KDM2T1JOP49tW\n" +
                "eocCvaC8h8uQo4GtMIGqMB0GA1UdDgQWBBT17HcqLllsqnZzP+kElcGcBGmubjBr\n" +
                "BgNVHSMEZDBigBT17HcqLllsqnZzP+kElcGcBGmubqE/pD0wOzELMAkGA1UEBhMC\n" +
                "VVMxDTALBgNVBAoTBEphdmExHTAbBgNVBAsTFFN1bkpTU0UgVGVzdCBTZXJpdmNl\n" +
                "ggkArz+DDrOD8M8wDwYDVR0TAQH/BAUwAwEB/zALBgNVHQ8EBAMCAQYwCQYHKoZI\n" +
                "zjgEAwMvADAsAhQ6Y1I6LtIEBMqNo8o6GIe4LLEJuwIUbVQUKi8tvtWyRoxm8AFV\n" +
                "0axJYUU=\n" +
                "-----END CERTIFICATE-----",
        ""
        ),

        CA_SHA1_RSA_2048( // for DHEKeySizing.java
                "RSA",
                //        Signature Algorithm: sha1WithRSAEncryption
                //        Issuer: OU = SunJSSE Test Serivce, O = Java, C = US
                //        Validity
                //            Not Before: Sep 18 04:38:31 2013 GMT
                //            Not After : Dec 17 04:38:31 2013 GMT
                "-----BEGIN CERTIFICATE-----\n" +
                "MIIC8jCCAdqgAwIBAgIEUjkuRzANBgkqhkiG9w0BAQUFADA7MR0wGwYDVQQLExRT\n" +
                "dW5KU1NFIFRlc3QgU2VyaXZjZTENMAsGA1UEChMESmF2YTELMAkGA1UEBhMCVVMw\n" +
                "HhcNMTMwOTE4MDQzODMxWhcNMTMxMjE3MDQzODMxWjA7MR0wGwYDVQQLExRTdW5K\n" +
                "U1NFIFRlc3QgU2VyaXZjZTENMAsGA1UEChMESmF2YTELMAkGA1UEBhMCVVMwggEi\n" +
                "MA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQCO+IGeaskJAvEcYc7pCl9neK3E\n" +
                "a28fwWLtChufYNaC9hQfZlUdETWYjV7fZJVJKT/oLzdDNMWuVA0LKXArpI3thLNK\n" +
                "QLXisdF9hKPlZRDazACL9kWUUtJ0FzpEySK4e8wW/z9FuU6e6iO19FbjxAfInJqk\n" +
                "3EDiEhB5g73S2vtvPCxgq2DvWw9TDl/LIqdKG2JCS93koXCCaHmQ7MrIOqHPd+8r\n" +
                "RbGpatXT9qyHKppUv9ATxVygO4rA794mgCFxpT+fkhz+NEB0twTkM65T1hnnOv5n\n" +
                "ZIxkcjBggt85UlZtnP3b9P7SYxsWIa46Oc38Od2f3YejfVg6B+PqPgWNl3+/AgMB\n" +
                "AAEwDQYJKoZIhvcNAQEFBQADggEBAAlrP6DFLRPSy0IgQhcI2i56tR/na8pezSte\n" +
                "ZHcCdaCZPDy4UP8mpLJ9QCjEB5VJv8hPm4xdK7ULnKGOGHgYqDpV2ZHvQlhV1woQ\n" +
                "TZGb/LM3c6kAs0j4j9KM2fq3iYUYexjIkS1KzsziflxMM6igS9BRMBR2LQyU+cYq\n" +
                "YEsFzkF7Aj2ET4v/+tgot9mRr2NioJcaJkdsPDpMU3IKB1cczfu+OuLQ/GCG0Fqu\n" +
                "6ijCeCqfnaAbemHbJeVZZ6Qgka3uC2YMntLBmLkhqEo1d9zGYLoh7oWL77y5ibQZ\n" +
                "LK5/H/zikcu579TWjlDHcqL3arCwBcrtsjSaPrRSWMrWV/6c0qw=\n" +
                "-----END CERTIFICATE-----",
        "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCO+IGeaskJAvEc\n" +
                "Yc7pCl9neK3Ea28fwWLtChufYNaC9hQfZlUdETWYjV7fZJVJKT/oLzdDNMWuVA0L\n" +
                "KXArpI3thLNKQLXisdF9hKPlZRDazACL9kWUUtJ0FzpEySK4e8wW/z9FuU6e6iO1\n" +
                "9FbjxAfInJqk3EDiEhB5g73S2vtvPCxgq2DvWw9TDl/LIqdKG2JCS93koXCCaHmQ\n" +
                "7MrIOqHPd+8rRbGpatXT9qyHKppUv9ATxVygO4rA794mgCFxpT+fkhz+NEB0twTk\n" +
                "M65T1hnnOv5nZIxkcjBggt85UlZtnP3b9P7SYxsWIa46Oc38Od2f3YejfVg6B+Pq\n" +
                "PgWNl3+/AgMBAAECggEAPdb5Ycc4m4A9QBSCRcRpzbyiFLKPh0HDg1n65q4hOtYr\n" +
                "kAVYTVFTSF/lqGS+Ob3w2YIKujQKSUQrvCc5UHdFuHXMgxKIWbymK0+DAMb9SlYw\n" +
                "6lkkcWp9gx9E4dnJ/df2SAAxovvrKMuHlL1SFASHhVtPfH2URvSfUaANLDXxyYOs\n" +
                "8BX0Nr6wazhWjLjXo9yIGnKSvFfB8XisYcA78kEgas43zhmIGCDPqaYyyffOfRbx\n" +
                "pM1KNwGmlN86iWR1CbwA/wwhcMySWQueS+s7cHbpRqZIYJF9jEeELiwi0vxjealS\n" +
                "EMuHYedIRFMWaDIq9XyjrvXamHb0Z25jlXBNZHaM0QKBgQDE9adl+zAezR/n79vw\n" +
                "0XiX2Fx1UEo3ApZHuoA2Q/PcBk+rlKqqQ3IwTcy6Wo648wK7v6Nq7w5nEWcsf0dU\n" +
                "QA2Ng/AJEev/IfF34x7sKGYxtk1gcE0EuSBA3R+ocEZxnNw1Ryd5nUU24s8d4jCP\n" +
                "Mkothnyaim+zE2raDlEtVc0CaQKBgQC509av+02Uq5oMjzbQp5PBJfQFjATOQT15\n" +
                "eefYnVYurkQ1kcVfixkrO2ORhg4SjmI2Z5hJDgGtXdwgidpzkad+R2epS5qLMyno\n" +
                "lQVpY6bMpEZ7Mos0yQygxnm8uNohEcTExOe+nP5fNJVpzBsGmfeyYOhnPQlf6oqf\n" +
                "0cHizedb5wKBgQC/l5LyMil6HOGHlhzmIm3jj7VI7QR0hJC5T6N+phVml8ESUDjA\n" +
                "DYHbmSKouISTRtkG14FY+RiSjCxH7bvuKazFV2289PETquogTA/9e8MFYqfcQwG4\n" +
                "sXi9gBxWlnj/9a2EKiYtOB5nKLR/BlNkSHA93tAA6N+FXEMZwMmYhxk42QKBgAuY\n" +
                "HQgD3PZOsqDf+qKQIhbmAFCsSMx5o5VFtuJ8BpmJA/Z3ruHkMuDQpsi4nX4o5hXQ\n" +
                "5t6AAjjH52kcUMXvK40kdWJJtk3DFnVNfvXxYsHX6hHbuHXFqYUKfSP6QJnZmvZP\n" +
                "9smcz/4usLfWJUWHK740b6upUkFqx9Vq5/b3s9y3AoGAdM5TW7LkkOFsdMGVAUzR\n" +
                "9iXmCWElHTK2Pcp/3yqDBHSfiQx6Yp5ANyPnE9NBM0yauCfOyBB2oxLO4Rdv3Rqk\n" +
                "9V9kyR/YAGr7dJaPcQ7pZX0OpkzgueAOJYPrx5VUzPYUtklYV1ycFZTfKlpFCxT+\n" +
                "Ei6KUo0NXSdUIcB4yib1J10="
        ),


        EE_DSA_SHA1_1024( // for SignatureAlgorithms test
                "DSA",
                // Signature Algorithm: dsaWithSHA1
                // Validity
                //    Not Before: Dec  3 13:52:25 2015 GMT
                //    Not After : Aug 20 13:52:25 2035 GMT
                // Authority Key Identifier:
                //    F5:EC:77:2A:2E:59:6C:AA:76:73:3F:E9:04:95:C1:9C:04:69:AE:6E
                "-----BEGIN CERTIFICATE-----\n" +
                "MIIDKTCCAumgAwIBAgIJAOy5c0b+8stFMAkGByqGSM44BAMwOzELMAkGA1UEBhMC\n" +
                "VVMxDTALBgNVBAoTBEphdmExHTAbBgNVBAsTFFN1bkpTU0UgVGVzdCBTZXJpdmNl\n" +
                "MB4XDTE1MTIwMzEzNTIyNVoXDTM1MDgyMDEzNTIyNVowTzELMAkGA1UEBhMCVVMx\n" +
                "DTALBgNVBAoMBEphdmExHTAbBgNVBAsMFFN1bkpTU0UgVGVzdCBTZXJpdmNlMRIw\n" +
                "EAYDVQQDDAlsb2NhbGhvc3QwggG3MIIBLAYHKoZIzjgEATCCAR8CgYEA8f5v4ZIx\n" +
                "fopLuNcNF6/NzjrgMUbhf67G7lVvdTjKGxTwDxy4fiDCKfov8gOSZlDs3TMLSNGy\n" +
                "IZVIywURNOrXQXf2kfZ4F9S/+Elwt8GciMES1WpX90TErzwe6XaxniZCKFDiKyw+\n" +
                "XuGyuhL0RZiJydfSg0CoAH087ljMpt+kvtMCFQDXdZbjyuWfZQ/8toCC2fMqGpw2\n" +
                "0wKBgQDdG/QaKtNsBqivyJRF3zZvkWUNN684JO6rtkil9lVXfSd5LtwFg163qb/f\n" +
                "/hx9SVtuuqFuognYj4jzV2uc3RiXI3gqS7ERwHo5PB9aQhSPqu89oJCsEfxDbXds\n" +
                "Orcce1g1o/w6h5BTJVdb5IQYAMInX7Py6o/eUgHqW12A6JX8ZAOBhAACgYB+zYqn\n" +
                "jJwG4GZpBIN/6qhzbp0flChsV+Trlu0SL0agAQzb6XdI/4JnO87Pgbxaxh3VNAj3\n" +
                "3+Ghr1NLBuBfTKzJ4j9msWT3EpLupkMyNtXvBYM0iyMrll67lSjMdv++wLEw35Af\n" +
                "/bzVcjGyA5Q0i0cuEzDmHTVfi0OydynbwSLxtKNjMGEwCwYDVR0PBAQDAgPoMB0G\n" +
                "A1UdDgQWBBQXJI8AxM0qsYCbbkIMuI5zJ+nMEDAfBgNVHSMEGDAWgBT17HcqLlls\n" +
                "qnZzP+kElcGcBGmubjASBgNVHREBAf8ECDAGhwR/AAABMAkGByqGSM44BAMDLwAw\n" +
                "LAIUXgyJ0xll4FrZAKXi8bj7Kiz+SA4CFH9WCSZIBYA9lmJkiTgRS7iM/6IC\n" +
                "-----END CERTIFICATE-----",
                "MIIBSwIBADCCASwGByqGSM44BAEwggEfAoGBAPH+b+GSMX6KS7jXDRevzc464DFG\n" +
                "4X+uxu5Vb3U4yhsU8A8cuH4gwin6L/IDkmZQ7N0zC0jRsiGVSMsFETTq10F39pH2\n" +
                "eBfUv/hJcLfBnIjBEtVqV/dExK88Hul2sZ4mQihQ4issPl7hsroS9EWYicnX0oNA\n" +
                "qAB9PO5YzKbfpL7TAhUA13WW48rln2UP/LaAgtnzKhqcNtMCgYEA3Rv0GirTbAao\n" +
                "r8iURd82b5FlDTevOCTuq7ZIpfZVV30neS7cBYNet6m/3/4cfUlbbrqhbqIJ2I+I\n" +
                "81drnN0YlyN4KkuxEcB6OTwfWkIUj6rvPaCQrBH8Q213bDq3HHtYNaP8OoeQUyVX\n" +
                "W+SEGADCJ1+z8uqP3lIB6ltdgOiV/GQEFgIUOiB7J/lrFrNduQ8nDNTe8VspoAI="
        ),

        EE_DSA_SHA224_1024( // for SignatureAlgorithms test
                "DSA",
                // Signature Algorithm: dsa_with_SHA224
                // Validity
                //   Not Before: Dec  3 15:44:39 2015 GMT
                //   Not After : Aug 20 15:44:39 2035 GMT
                // Authority Key Identifier:
                //   F5:EC:77:2A:2E:59:6C:AA:76:73:3F:E9:04:95:C1:9C:04:69:AE:6E
                "-----BEGIN CERTIFICATE-----\n" +
                "MIIDLzCCAuugAwIBAgIJAOy5c0b+8stGMAsGCWCGSAFlAwQDATA7MQswCQYDVQQG\n" +
                "EwJVUzENMAsGA1UEChMESmF2YTEdMBsGA1UECxMUU3VuSlNTRSBUZXN0IFNlcml2\n" +
                "Y2UwHhcNMTUxMjAzMTU0NDM5WhcNMzUwODIwMTU0NDM5WjBPMQswCQYDVQQGEwJV\n" +
                "UzENMAsGA1UECgwESmF2YTEdMBsGA1UECwwUU3VuSlNTRSBUZXN0IFNlcml2Y2Ux\n" +
                "EjAQBgNVBAMMCWxvY2FsaG9zdDCCAbcwggEsBgcqhkjOOAQBMIIBHwKBgQDx/m/h\n" +
                "kjF+iku41w0Xr83OOuAxRuF/rsbuVW91OMobFPAPHLh+IMIp+i/yA5JmUOzdMwtI\n" +
                "0bIhlUjLBRE06tdBd/aR9ngX1L/4SXC3wZyIwRLValf3RMSvPB7pdrGeJkIoUOIr\n" +
                "LD5e4bK6EvRFmInJ19KDQKgAfTzuWMym36S+0wIVANd1luPK5Z9lD/y2gILZ8yoa\n" +
                "nDbTAoGBAN0b9Boq02wGqK/IlEXfNm+RZQ03rzgk7qu2SKX2VVd9J3ku3AWDXrep\n" +
                "v9/+HH1JW266oW6iCdiPiPNXa5zdGJcjeCpLsRHAejk8H1pCFI+q7z2gkKwR/ENt\n" +
                "d2w6txx7WDWj/DqHkFMlV1vkhBgAwidfs/Lqj95SAepbXYDolfxkA4GEAAKBgA81\n" +
                "CJKEv+pwiqYgxtw/9rkQ9748WP3mKrEC06kjUG+94/Z9dQloNFFfj6LiO1bymc5l\n" +
                "6QIR8XCi4Po3N80K3+WxhBGFhY+RkVWTh43JV8epb41aH2qiWErarBwBGEh8LyGT\n" +
                "i30db+Nkz2gfvyz9H/9T0jmYgfLEOlMCusali1qHo2MwYTALBgNVHQ8EBAMCA+gw\n" +
                "HQYDVR0OBBYEFBqSP0S4+X+zOCTEnlp2hbAjV/W5MB8GA1UdIwQYMBaAFPXsdyou\n" +
                "WWyqdnM/6QSVwZwEaa5uMBIGA1UdEQEB/wQIMAaHBH8AAAEwCwYJYIZIAWUDBAMB\n" +
                "AzEAMC4CFQChiRaOnAnsCSJFwdpK22jSxU/mhQIVALgLbj/G39+1Ej8UuSWnEQyU\n" +
                "4DA+\n" +
                "-----END CERTIFICATE-----",
                "MIIBSwIBADCCASwGByqGSM44BAEwggEfAoGBAPH+b+GSMX6KS7jXDRevzc464DFG\n" +
                "4X+uxu5Vb3U4yhsU8A8cuH4gwin6L/IDkmZQ7N0zC0jRsiGVSMsFETTq10F39pH2\n" +
                "eBfUv/hJcLfBnIjBEtVqV/dExK88Hul2sZ4mQihQ4issPl7hsroS9EWYicnX0oNA\n" +
                "qAB9PO5YzKbfpL7TAhUA13WW48rln2UP/LaAgtnzKhqcNtMCgYEA3Rv0GirTbAao\n" +
                "r8iURd82b5FlDTevOCTuq7ZIpfZVV30neS7cBYNet6m/3/4cfUlbbrqhbqIJ2I+I\n" +
                "81drnN0YlyN4KkuxEcB6OTwfWkIUj6rvPaCQrBH8Q213bDq3HHtYNaP8OoeQUyVX\n" +
                "W+SEGADCJ1+z8uqP3lIB6ltdgOiV/GQEFgIUOj9F5mxWd9W1tiLSdsOAt8BUBzE="
        ),

        EE_DSA_SHA256_1024( // for SignatureAlgorithms test
                "DSA",
                // Signature Algorithm: dsa_with_SHA256
                // Validity
                //   Not Before: Dec  3 15:46:51 2015 GMT
                //   Not After : Aug 20 15:46:51 2035 GMT
                // Authority Key Identifier:
                //   F5:EC:77:2A:2E:59:6C:AA:76:73:3F:E9:04:95:C1:9C:04:69:AE:6E
                "-----BEGIN CERTIFICATE-----\n" +
                "MIIDLTCCAuugAwIBAgIJAOy5c0b+8stHMAsGCWCGSAFlAwQDAjA7MQswCQYDVQQG\n" +
                "EwJVUzENMAsGA1UEChMESmF2YTEdMBsGA1UECxMUU3VuSlNTRSBUZXN0IFNlcml2\n" +
                "Y2UwHhcNMTUxMjAzMTU0NjUxWhcNMzUwODIwMTU0NjUxWjBPMQswCQYDVQQGEwJV\n" +
                "UzENMAsGA1UECgwESmF2YTEdMBsGA1UECwwUU3VuSlNTRSBUZXN0IFNlcml2Y2Ux\n" +
                "EjAQBgNVBAMMCWxvY2FsaG9zdDCCAbcwggEsBgcqhkjOOAQBMIIBHwKBgQDx/m/h\n" +
                "kjF+iku41w0Xr83OOuAxRuF/rsbuVW91OMobFPAPHLh+IMIp+i/yA5JmUOzdMwtI\n" +
                "0bIhlUjLBRE06tdBd/aR9ngX1L/4SXC3wZyIwRLValf3RMSvPB7pdrGeJkIoUOIr\n" +
                "LD5e4bK6EvRFmInJ19KDQKgAfTzuWMym36S+0wIVANd1luPK5Z9lD/y2gILZ8yoa\n" +
                "nDbTAoGBAN0b9Boq02wGqK/IlEXfNm+RZQ03rzgk7qu2SKX2VVd9J3ku3AWDXrep\n" +
                "v9/+HH1JW266oW6iCdiPiPNXa5zdGJcjeCpLsRHAejk8H1pCFI+q7z2gkKwR/ENt\n" +
                "d2w6txx7WDWj/DqHkFMlV1vkhBgAwidfs/Lqj95SAepbXYDolfxkA4GEAAKBgEF7\n" +
                "2qiYxGrjX4KCOy0k5nK/RYlgLy4gYDChihQpiaa+fbA5JOBOxPWsh7rdtmJuDrEJ\n" +
                "keacU223+DIhOKC49fa+EvhLNqo6U1oPn8n/yvBsvvnWkcynw5KfNzaLlaPmzugh\n" +
                "v9xl/GhyZNAXc1QUcW3C+ceHVNrKnkfbTKZz5eRSo2MwYTALBgNVHQ8EBAMCA+gw\n" +
                "HQYDVR0OBBYEFNMkPrt40oO9Dpy+bcbQdEvOlNlyMB8GA1UdIwQYMBaAFPXsdyou\n" +
                "WWyqdnM/6QSVwZwEaa5uMBIGA1UdEQEB/wQIMAaHBH8AAAEwCwYJYIZIAWUDBAMC\n" +
                "Ay8AMCwCFCvA2QiKSe/n+6GqSYQwgQ/zL5M9AhQfSiuWdMJKWpgPJKakvzhBUbMb\n" +
                "vA==\n" +
                "-----END CERTIFICATE-----",
                "MIIBSwIBADCCASwGByqGSM44BAEwggEfAoGBAPH+b+GSMX6KS7jXDRevzc464DFG\n" +
                "4X+uxu5Vb3U4yhsU8A8cuH4gwin6L/IDkmZQ7N0zC0jRsiGVSMsFETTq10F39pH2\n" +
                "eBfUv/hJcLfBnIjBEtVqV/dExK88Hul2sZ4mQihQ4issPl7hsroS9EWYicnX0oNA\n" +
                "qAB9PO5YzKbfpL7TAhUA13WW48rln2UP/LaAgtnzKhqcNtMCgYEA3Rv0GirTbAao\n" +
                "r8iURd82b5FlDTevOCTuq7ZIpfZVV30neS7cBYNet6m/3/4cfUlbbrqhbqIJ2I+I\n" +
                "81drnN0YlyN4KkuxEcB6OTwfWkIUj6rvPaCQrBH8Q213bDq3HHtYNaP8OoeQUyVX\n" +
                "W+SEGADCJ1+z8uqP3lIB6ltdgOiV/GQEFgIUQ2WGgg+OO39Aujj0e4lM4pP4/9g="
        ),

        EE_RSA_MD5_512 ( // for ShortRSAKeys512 and ShortRSAKeyGCM tests
                "RSA",
                // md5WithRSAEncryption
                // Validity
                //     Not Before: Nov  7 13:55:52 2011 GMT
                //     Not After : Jul 25 13:55:52 2031 GMT
                // Authority Key Identifier:
                //     B9:7C:D5:D9:DF:A7:4C:03:AE:FD:0E:27:5B:31:95:6C:C7:F3:75:E1
                "-----BEGIN CERTIFICATE-----\n" +
                "MIICNDCCAZ2gAwIBAgIBDDANBgkqhkiG9w0BAQQFADA7MQswCQYDVQQGEwJVUzEN\n" +
                "MAsGA1UEChMESmF2YTEdMBsGA1UECxMUU3VuSlNTRSBUZXN0IFNlcml2Y2UwHhcN\n" +
                "MTExMTA3MTM1NTUyWhcNMzEwNzI1MTM1NTUyWjBPMQswCQYDVQQGEwJVUzENMAsG\n" +
                "A1UEChMESmF2YTEdMBsGA1UECxMUU3VuSlNTRSBUZXN0IFNlcml2Y2UxEjAQBgNV\n" +
                "BAMTCWxvY2FsaG9zdDBcMA0GCSqGSIb3DQEBAQUAA0sAMEgCQQC3Pb49OSPfOD2G\n" +
                "HSXFCFx1GJEZfqG9ZUf7xuIi/ra5dLjPGAaoY5QF2QOa8VnOriQCXDfyXHxsuRnE\n" +
                "OomxL7EVAgMBAAGjeDB2MAsGA1UdDwQEAwID6DAdBgNVHQ4EFgQUXNCJK3/dtCIc\n" +
                "xb+zlA/JINlvs/MwHwYDVR0jBBgwFoAUuXzV2d+nTAOu/Q4nWzGVbMfzdeEwJwYD\n" +
                "VR0lBCAwHgYIKwYBBQUHAwEGCCsGAQUFBwMCBggrBgEFBQcDAzANBgkqhkiG9w0B\n" +
                "AQQFAAOBgQB2qIDUxA2caMPpGtUACZAPRUtrGssCINIfItETXJZCx/cRuZ5sP4D9\n" +
                "N1acoNDn0hCULe3lhXAeTC9NZ97680yJzregQMV5wATjo1FGsKY30Ma+sc/nfzQW\n" +
                "+h/7RhYtoG0OTsiaDCvyhI6swkNJzSzrAccPY4+ZgU8HiDLzZTmM3Q==\n" +
                "-----END CERTIFICATE-----",
                "MIIBVAIBADANBgkqhkiG9w0BAQEFAASCAT4wggE6AgEAAkEAtz2+PTkj3zg9hh0l\n" +
                "xQhcdRiRGX6hvWVH+8biIv62uXS4zxgGqGOUBdkDmvFZzq4kAlw38lx8bLkZxDqJ\n" +
                "sS+xFQIDAQABAkByx/5Oo2hQ/w2q4L8z+NTRlJ3vdl8iIDtC/4XPnfYfnGptnpG6\n" +
                "ZThQRvbMZiai0xHQPQMszvAHjZVme1eDl3EBAiEA3aKJHynPVCEJhpfCLWuMwX5J\n" +
                "1LntwJO7NTOyU5m8rPECIQDTpzn5X44r2rzWBDna/Sx7HW9IWCxNgUD2Eyi2nA7W\n" +
                "ZQIgJerEorw4aCAuzQPxiGu57PB6GRamAihEAtoRTBQlH0ECIQDN08FgTtnesgCU\n" +
                "DFYLLcw1CiHvc7fZw4neBDHCrC8NtQIgA8TOUkGnpCZlQ0KaI8KfKWI+vxFcgFnH\n" +
                "3fnqsTgaUs4="
        ),

        EE_ECDSA_SECP256R1(
                "EC",
                // SHA256withECDSA, curve secp256r1
                // Validity
                //     Not Before: May 22 07:18:16 2018 GMT
                //     Not After : May 17 07:18:16 2038 GMT
                // Authority Key Identifier:
                //     60:CF:BD:73:FF:FA:1A:30:D2:A4:EC:D3:49:71:46:EF:1A:35:A0:86
                "-----BEGIN CERTIFICATE-----\n" +
                "MIIBqjCCAVCgAwIBAgIJAPLY8qZjgNRAMAoGCCqGSM49BAMCMDsxCzAJBgNVBAYT\n" +
                "AlVTMQ0wCwYDVQQKDARKYXZhMR0wGwYDVQQLDBRTdW5KU1NFIFRlc3QgU2VyaXZj\n" +
                "ZTAeFw0xODA1MjIwNzE4MTZaFw0zODA1MTcwNzE4MTZaMFUxCzAJBgNVBAYTAlVT\n" +
                "MQ0wCwYDVQQKDARKYXZhMR0wGwYDVQQLDBRTdW5KU1NFIFRlc3QgU2VyaXZjZTEY\n" +
                "MBYGA1UEAwwPUmVncmVzc2lvbiBUZXN0MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcD\n" +
                "QgAEb+9n05qfXnfHUb0xtQJNS4JeSi6IjOfW5NqchvKnfJey9VkJzR7QHLuOESdf\n" +
                "xlR7q8YIWgih3iWLGfB+wxHiOqMjMCEwHwYDVR0jBBgwFoAUYM+9c//6GjDSpOzT\n" +
                "SXFG7xo1oIYwCgYIKoZIzj0EAwIDSAAwRQIgWpRegWXMheiD3qFdd8kMdrkLxRbq\n" +
                "1zj8nQMEwFTUjjQCIQDRIrAjZX+YXHN9b0SoWWLPUq0HmiFIi8RwMnO//wJIGQ==\n" +
                "-----END CERTIFICATE-----",
                "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgn5K03bpTLjEtFQRa\n" +
                "JUtx22gtmGEvvSUSQdimhGthdtihRANCAARv72fTmp9ed8dRvTG1Ak1Lgl5KLoiM\n" +
                "59bk2pyG8qd8l7L1WQnNHtAcu44RJ1/GVHurxghaCKHeJYsZ8H7DEeI6"),

        EE_ECDSA_SECP384R1(
                "EC",
                // SHA384withECDSA, curve secp384r1
                // Validity
                //     Not Before: Jun 24 08:15:06 2019 GMT
                //     Not After : Jun 19 08:15:06 2039 GMT
                // Authority Key Identifier:
                //     40:2D:AA:EE:66:AA:33:27:AD:9B:5D:52:9B:60:67:6A:2B:AD:52:D2
                "-----BEGIN CERTIFICATE-----\n" +
                "MIICEjCCAZegAwIBAgIUS3F0AqAXWRg07CnbknJzxofyBQMwCgYIKoZIzj0EAwMw\n" +
                "OzELMAkGA1UEBhMCVVMxDTALBgNVBAoMBEphdmExHTAbBgNVBAsMFFN1bkpTU0Ug\n" +
                "VGVzdCBTZXJpdmNlMB4XDTE5MDYyNDA4MTUwNloXDTM5MDYxOTA4MTUwNlowVTEL\n" +
                "MAkGA1UEBhMCVVMxDTALBgNVBAoMBEphdmExHTAbBgNVBAsMFFN1bkpTU0UgVGVz\n" +
                "dCBTZXJpdmNlMRgwFgYDVQQDDA9SZWdyZXNzaW9uIFRlc3QwdjAQBgcqhkjOPQIB\n" +
                "BgUrgQQAIgNiAARqElz8b6T07eyKomIinhztV3/3XBk9bKGtJ0W+JOltjuhMmP/w\n" +
                "G8ASSevpgqgpi6EzpBZaaJxE3zNfkNnxXOZmQi2Ypd1uK0zRdbEOKg0XOcTTZwEj\n" +
                "iLjYmt3O0pwpklijQjBAMB0GA1UdDgQWBBRALaruZqozJ62bXVKbYGdqK61S0jAf\n" +
                "BgNVHSMEGDAWgBRKS1IUCtHWn/mZdXSTKjRHEhVr9TAKBggqhkjOPQQDAwNpADBm\n" +
                "AjEArVDFKf48xijN6huVUJzKCOP0zlWB5Js+DItIkZmLQuhciPLhLIB/rChf3Y4C\n" +
                "xuP4AjEAmfLhQRI0O3pifpYzYSVh2G7/jHNG4eO+2dvgAcU+Lh2IIj/cpLaPFSvL\n" +
                "J8FXY9Nj\n" +
                "-----END CERTIFICATE-----",
                "MIG2AgEAMBAGByqGSM49AgEGBSuBBAAiBIGeMIGbAgEBBDASuI9EtK29APXPipkc\n" +
                "qDA+qwlewMjv/OcjUJ77kP1Vz62oVF9iY9SRIyFIUju8wt+hZANiAARqElz8b6T0\n" +
                "7eyKomIinhztV3/3XBk9bKGtJ0W+JOltjuhMmP/wG8ASSevpgqgpi6EzpBZaaJxE\n" +
                "3zNfkNnxXOZmQi2Ypd1uK0zRdbEOKg0XOcTTZwEjiLjYmt3O0pwpklg="),

        EE_ECDSA_SECP521R1(
                "EC",
                // SHA512withECDSA, curve secp521r1
                // Validity
                //     Not Before: Jun 24 08:15:06 2019 GMT
                //     Not After : Jun 19 08:15:06 2039 GMT
                // Authority Key Identifier:
                //     7B:AA:79:A4:49:DD:59:34:F0:86:6C:51:C7:30:F4:CE:C5:81:8A:28
                "-----BEGIN CERTIFICATE-----\n" +
                "MIICXDCCAb2gAwIBAgIUck4QTsbHNqUfPxfGPJLYbedFPdswCgYIKoZIzj0EAwQw\n" +
                "OzELMAkGA1UEBhMCVVMxDTALBgNVBAoMBEphdmExHTAbBgNVBAsMFFN1bkpTU0Ug\n" +
                "VGVzdCBTZXJpdmNlMB4XDTE5MDYyNDA4MTUwNloXDTM5MDYxOTA4MTUwNlowVTEL\n" +
                "MAkGA1UEBhMCVVMxDTALBgNVBAoMBEphdmExHTAbBgNVBAsMFFN1bkpTU0UgVGVz\n" +
                "dCBTZXJpdmNlMRgwFgYDVQQDDA9SZWdyZXNzaW9uIFRlc3QwgZswEAYHKoZIzj0C\n" +
                "AQYFK4EEACMDgYYABAGa2zDLhYQHHCLI3YBqFYJTzrnDIjzwXrxhcRTS8DYkcrjZ\n" +
                "+Fih1YyNhix0sdjH+3EqElXAHHuVzn3n3hPOtQCWlQCICkErB34S0cvmtRkeW8Fi\n" +
                "hrR5tvJEzEZjPSgwn81kKyhV2L70je6i7Cw884Va8bODckpgw0vTmbQb7T9dupkv\n" +
                "1aNCMEAwHQYDVR0OBBYEFHuqeaRJ3Vk08IZsUccw9M7FgYooMB8GA1UdIwQYMBaA\n" +
                "FEAivFrtaq2pQJ4cW4pEQML3aym9MAoGCCqGSM49BAMEA4GMADCBiAJCAb33KHdY\n" +
                "WDbusORWoY8Euglpd5zsF15hJsk7wtpD5HST1/NWmdCx405w+TV6a9Gr4VPHeaIQ\n" +
                "99i/+f237ALL5p6IAkIBbwwFL1vt3c/bx+niyuffQPNjly80rdC9puqAqriSiboS\n" +
                "efhxjidJ9HLaIRCMEPyd6vAsC8mO8YvL1uCuEQLsiGM=\n" +
                "-----END CERTIFICATE-----",
                "MIHuAgEAMBAGByqGSM49AgEGBSuBBAAjBIHWMIHTAgEBBEIB8C/2OX2Dt9vFszzV\n" +
                "hcAe0CbkMlvu9uQ/L7Vz88heuIj0rUZIPGshvgIJt1hCMT8HZxYHvDa4lbUvqjFB\n" +
                "+zafvPWhgYkDgYYABAGa2zDLhYQHHCLI3YBqFYJTzrnDIjzwXrxhcRTS8DYkcrjZ\n" +
                "+Fih1YyNhix0sdjH+3EqElXAHHuVzn3n3hPOtQCWlQCICkErB34S0cvmtRkeW8Fi\n" +
                "hrR5tvJEzEZjPSgwn81kKyhV2L70je6i7Cw884Va8bODckpgw0vTmbQb7T9dupkv\n" +
                "1Q=="),

        EE_RSA_2048(
                "RSA",
                // SHA256withRSA, 2048 bits
                // Validity
                //     Not Before: May 22 07:18:16 2018 GMT
                //     Not After : May 17 07:18:16 2038 GMT
                // Authority Key Identifier:
                //     0D:DD:93:C9:FE:4B:BD:35:B7:E8:99:78:90:FB:DB:5A:3D:DB:15:4C
                "-----BEGIN CERTIFICATE-----\n" +
                "MIIDNjCCAh6gAwIBAgIJAO2+yPcFryUTMA0GCSqGSIb3DQEBCwUAMDsxCzAJBgNV\n" +
                "BAYTAlVTMQ0wCwYDVQQKDARKYXZhMR0wGwYDVQQLDBRTdW5KU1NFIFRlc3QgU2Vy\n" +
                "aXZjZTAeFw0xODA1MjIwNzE4MTZaFw0zODA1MTcwNzE4MTZaMFUxCzAJBgNVBAYT\n" +
                "AlVTMQ0wCwYDVQQKDARKYXZhMR0wGwYDVQQLDBRTdW5KU1NFIFRlc3QgU2VyaXZj\n" +
                "ZTEYMBYGA1UEAwwPUmVncmVzc2lvbiBUZXN0MIIBIjANBgkqhkiG9w0BAQEFAAOC\n" +
                "AQ8AMIIBCgKCAQEAszfBobWfZIp8AgC6PiWDDavP65mSvgCXUGxACbxVNAfkLhNR\n" +
                "QOsHriRB3X1Q3nvO9PetC6wKlvE9jlnDDj7D+1j1r1CHO7ms1fq8rfcQYdkanDtu\n" +
                "4AlHo8v+SSWX16MIXFRYDj2VVHmyPtgbltcg4zGAuwT746FdLI94uXjJjq1IOr/v\n" +
                "0VIlwE5ORWH5Xc+5Tj+oFWK0E4a4GHDgtKKhn2m72hN56/GkPKGkguP5NRS1qYYV\n" +
                "/EFkdyQMOV8J1M7HaicSft4OL6eKjTrgo93+kHk+tv0Dc6cpVBnalX3TorG8QI6B\n" +
                "cHj1XQd78oAlAC+/jF4pc0mwi0un49kdK9gRfQIDAQABoyMwITAfBgNVHSMEGDAW\n" +
                "gBQN3ZPJ/ku9NbfomXiQ+9taPdsVTDANBgkqhkiG9w0BAQsFAAOCAQEApXS0nKwm\n" +
                "Kp8gpmO2yG1rpd1+2wBABiMU4JZaTqmma24DQ3RzyS+V2TeRb29dl5oTUEm98uc0\n" +
                "GPZvhK8z5RFr4YE17dc04nI/VaNDCw4y1NALXGs+AHkjoPjLyGbWpi1S+gfq2sNB\n" +
                "Ekkjp6COb/cb9yiFXOGVls7UOIjnVZVd0r7KaPFjZhYh82/f4PA/A1SnIKd1+nfH\n" +
                "2yk7mSJNC7Z3qIVDL8MM/jBVwiC3uNe5GPB2uwhd7k5LGAVN3j4HQQGB0Sz+VC1h\n" +
                "92oi6xDa+YBva2fvHuCd8P50DDjxmp9CemC7rnZ5j8egj88w14X44Xjb/Fd/ApG9\n" +
                "e57NnbT7KM+Grw==\n" +
                "-----END CERTIFICATE-----",
                "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCzN8GhtZ9kinwC\n" +
                "ALo+JYMNq8/rmZK+AJdQbEAJvFU0B+QuE1FA6weuJEHdfVDee870960LrAqW8T2O\n" +
                "WcMOPsP7WPWvUIc7uazV+ryt9xBh2RqcO27gCUejy/5JJZfXowhcVFgOPZVUebI+\n" +
                "2BuW1yDjMYC7BPvjoV0sj3i5eMmOrUg6v+/RUiXATk5FYfldz7lOP6gVYrQThrgY\n" +
                "cOC0oqGfabvaE3nr8aQ8oaSC4/k1FLWphhX8QWR3JAw5XwnUzsdqJxJ+3g4vp4qN\n" +
                "OuCj3f6QeT62/QNzpylUGdqVfdOisbxAjoFwePVdB3vygCUAL7+MXilzSbCLS6fj\n" +
                "2R0r2BF9AgMBAAECggEASIkPkMCuw4WdTT44IwERus3IOIYOs2IP3BgEDyyvm4B6\n" +
                "JP/iihDWKfA4zEl1Gqcni1RXMHswSglXra682J4kui02Ov+vzEeJIY37Ibn2YnP5\n" +
                "ZjRT2s9GtI/S2o4hl8A/mQb2IMViFC+xKehTukhV4j5d6NPKk0XzLR7gcMjnYxwn\n" +
                "l21fS6D2oM1xRG/di7sL+uLF8EXLRzfiWDNi12uQv4nwtxPKvuKhH6yzHt7YqMH0\n" +
                "46pmDKDaxV4w1JdycjCb6NrCJOYZygoQobuZqOQ30UZoZsPJrtovkncFr1e+lNcO\n" +
                "+aWDfOLCtTH046dEQh5oCShyXMybNlry/QHsOtHOwQKBgQDh2iIjs+FPpQy7Z3EX\n" +
                "DGEvHYqPjrYO9an2KSRr1m9gzRlWYxKY46WmPKwjMerYtra0GP+TBHrgxsfO8tD2\n" +
                "wUAII6sd1qup0a/Sutgf2JxVilLykd0+Ge4/Cs51tCdJ8EqDV2B6WhTewOY2EGvg\n" +
                "JiKYkeNwgRX/9M9CFSAMAk0hUQKBgQDLJAartL3DoGUPjYtpJnfgGM23yAGl6G5r\n" +
                "NSXDn80BiYIC1p0bG3N0xm3yAjqOtJAUj9jZbvDNbCe3GJfLARMr23legX4tRrgZ\n" +
                "nEdKnAFKAKL01oM+A5/lHdkwaZI9yyv+hgSVdYzUjB8rDmzeVQzo1BT7vXypt2yV\n" +
                "6O1OnUpCbQKBgA/0rzDChopv6KRcvHqaX0tK1P0rYeVQqb9ATNhpf9jg5Idb3HZ8\n" +
                "rrk91BNwdVz2G5ZBpdynFl9G69rNAMJOCM4KZw5mmh4XOEq09Ivba8AHU7DbaTv3\n" +
                "7QL7KnbaUWRB26HHzIMYVh0el6T+KADf8NXCiMTr+bfpfbL3dxoiF3zhAoGAbCJD\n" +
                "Qse1dBs/cKYCHfkSOsI5T6kx52Tw0jS6Y4X/FOBjyqr/elyEexbdk8PH9Ar931Qr\n" +
                "NKMvn8oA4iA/PRrXX7M2yi3YQrWwbkGYWYjtzrzEAdzmg+5eARKAeJrZ8/bg9l3U\n" +
                "ttKaItJsDPlizn8rngy3FsJpR9aSAMK6/+wOiYkCgYEA1tZkI1rD1W9NYZtbI9BE\n" +
                "qlJVFi2PBOJMKNuWdouPX3HLQ72GJSQff2BFzLTELjweVVJ0SvY4IipzpQOHQOBy\n" +
                "5qh/p6izXJZh3IHtvwVBjHoEVplg1b2+I5e3jDCfqnwcQw82dW5SxOJMg1h/BD0I\n" +
                "qAL3go42DYeYhu/WnECMeis="),

        EE_EC_RSA_SECP256R1(
                "EC",
                // SHA256withRSA, curve secp256r1
                // Validity
                //     Not Before: May 22 07:18:16 2018 GMT
                //     Not After : May 21 07:18:16 2028 GMT
                // Authority Key Identifier:
                //     0D:DD:93:C9:FE:4B:BD:35:B7:E8:99:78:90:FB:DB:5A:3D:DB:15:4C
                "-----BEGIN CERTIFICATE-----\n" +
                "MIICazCCAVOgAwIBAgIJAO2+yPcFryUUMA0GCSqGSIb3DQEBCwUAMDsxCzAJBgNV\n" +
                "BAYTAlVTMQ0wCwYDVQQKDARKYXZhMR0wGwYDVQQLDBRTdW5KU1NFIFRlc3QgU2Vy\n" +
                "aXZjZTAeFw0xODA1MjIwNzE4MTZaFw0yODA1MjEwNzE4MTZaMFUxCzAJBgNVBAYT\n" +
                "AlVTMQ0wCwYDVQQKDARKYXZhMR0wGwYDVQQLDBRTdW5KU1NFIFRlc3QgU2VyaXZj\n" +
                "ZTEYMBYGA1UEAwwPUmVncmVzc2lvbiBUZXN0MFkwEwYHKoZIzj0CAQYIKoZIzj0D\n" +
                "AQcDQgAE59MERNTlVZ1eeps8Z3Oue5ZkgQdPtD+WIE6tj3PbIKpxGPDxvfNP959A\n" +
                "yQjEK/ehWQVrCMmNoEkIzY+IIBgB06MjMCEwHwYDVR0jBBgwFoAUDd2Tyf5LvTW3\n" +
                "6Jl4kPvbWj3bFUwwDQYJKoZIhvcNAQELBQADggEBAFOTVEqs70ykhZiIdrEsF1Ra\n" +
                "I3B2rLvwXZk52uSltk2/bzVvewA577ZCoxQ1pL7ynkisPfBN1uVYtHjM1VA3RC+4\n" +
                "+TAK78dnI7otYjWoHp5rvs4l6c/IbOspS290IlNuDUxMErEm5wxIwj+Aukx/1y68\n" +
                "hOyCvHBLMY2c1LskH1MMBbDuS1aI+lnGpToi+MoYObxGcV458vxuT8+wwV8Fkpvd\n" +
                "ll8IIFmeNPRv+1E+lXbES6CSNCVaZ/lFhPgdgYKleN7sfspiz50DG4dqafuEAaX5\n" +
                "xaK1NWXJxTRz0ROH/IUziyuDW6jphrlgit4+3NCzp6vP9hAJQ8Vhcj0n15BKHIQ=\n" +
                "-----END CERTIFICATE-----",
                "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgGVc7hICpmp91jbYe\n" +
                "nrr8nYHD37RZP3VENY+szuA7WjuhRANCAATn0wRE1OVVnV56mzxnc657lmSBB0+0\n" +
                "P5YgTq2Pc9sgqnEY8PG980/3n0DJCMQr96FZBWsIyY2gSQjNj4ggGAHT"),

        EE_RSA_512(
                "RSA",
                // md5WithRSAEncryption, 512 bits
                // Validity
                //      Not Before: Nov  7 13:55:52 2011 GMT
                //      Not After : Jul 25 13:55:52 2031 GMT
                // X509v3 Authority Key Identifier:
                //      B9:7C:D5:D9:DF:A7:4C:03:AE:FD:0E:27:5B:31:95:6C:C7:F3:75:E1
                "-----BEGIN CERTIFICATE-----\n" +
                "MIICNDCCAZ2gAwIBAgIBDDANBgkqhkiG9w0BAQQFADA7MQswCQYDVQQGEwJVUzEN\n" +
                "MAsGA1UEChMESmF2YTEdMBsGA1UECxMUU3VuSlNTRSBUZXN0IFNlcml2Y2UwHhcN\n" +
                "MTExMTA3MTM1NTUyWhcNMzEwNzI1MTM1NTUyWjBPMQswCQYDVQQGEwJVUzENMAsG\n" +
                "A1UEChMESmF2YTEdMBsGA1UECxMUU3VuSlNTRSBUZXN0IFNlcml2Y2UxEjAQBgNV\n" +
                "BAMTCWxvY2FsaG9zdDBcMA0GCSqGSIb3DQEBAQUAA0sAMEgCQQC3Pb49OSPfOD2G\n" +
                "HSXFCFx1GJEZfqG9ZUf7xuIi/ra5dLjPGAaoY5QF2QOa8VnOriQCXDfyXHxsuRnE\n" +
                "OomxL7EVAgMBAAGjeDB2MAsGA1UdDwQEAwID6DAdBgNVHQ4EFgQUXNCJK3/dtCIc\n" +
                "xb+zlA/JINlvs/MwHwYDVR0jBBgwFoAUuXzV2d+nTAOu/Q4nWzGVbMfzdeEwJwYD\n" +
                "VR0lBCAwHgYIKwYBBQUHAwEGCCsGAQUFBwMCBggrBgEFBQcDAzANBgkqhkiG9w0B\n" +
                "AQQFAAOBgQB2qIDUxA2caMPpGtUACZAPRUtrGssCINIfItETXJZCx/cRuZ5sP4D9\n" +
                "N1acoNDn0hCULe3lhXAeTC9NZ97680yJzregQMV5wATjo1FGsKY30Ma+sc/nfzQW\n" +
                "+h/7RhYtoG0OTsiaDCvyhI6swkNJzSzrAccPY4+ZgU8HiDLzZTmM3Q==\n" +
                "-----END CERTIFICATE-----",
                "MIIBVAIBADANBgkqhkiG9w0BAQEFAASCAT4wggE6AgEAAkEAtz2+PTkj3zg9hh0l\n" +
                "xQhcdRiRGX6hvWVH+8biIv62uXS4zxgGqGOUBdkDmvFZzq4kAlw38lx8bLkZxDqJ\n" +
                "sS+xFQIDAQABAkByx/5Oo2hQ/w2q4L8z+NTRlJ3vdl8iIDtC/4XPnfYfnGptnpG6\n" +
                "ZThQRvbMZiai0xHQPQMszvAHjZVme1eDl3EBAiEA3aKJHynPVCEJhpfCLWuMwX5J\n" +
                "1LntwJO7NTOyU5m8rPECIQDTpzn5X44r2rzWBDna/Sx7HW9IWCxNgUD2Eyi2nA7W\n" +
                "ZQIgJerEorw4aCAuzQPxiGu57PB6GRamAihEAtoRTBQlH0ECIQDN08FgTtnesgCU\n" +
                "DFYLLcw1CiHvc7fZw4neBDHCrC8NtQIgA8TOUkGnpCZlQ0KaI8KfKWI+vxFcgFnH\n" +
                "3fnqsTgaUs4="
        ),

        EE_DSA_2048(
                "DSA",
                // SHA256withDSA, 2048 bits
                // Validity
                //     Not Before: May 22 07:18:20 2018 GMT
                //     Not After : May 17 07:18:20 2038 GMT
                // Authority Key Identifier:
                //     76:66:9E:F7:3B:DD:45:E5:3B:D9:72:3C:3F:F0:54:39:86:31:26:53
                "-----BEGIN CERTIFICATE-----\n" +
                "MIIEnDCCBEGgAwIBAgIJAP/jh1qVhNVjMAsGCWCGSAFlAwQDAjA7MQswCQYDVQQG\n" +
                "EwJVUzENMAsGA1UECgwESmF2YTEdMBsGA1UECwwUU3VuSlNTRSBUZXN0IFNlcml2\n" +
                "Y2UwHhcNMTgwNTIyMDcxODIwWhcNMzgwNTE3MDcxODIwWjBVMQswCQYDVQQGEwJV\n" +
                "UzENMAsGA1UECgwESmF2YTEdMBsGA1UECwwUU3VuSlNTRSBUZXN0IFNlcml2Y2Ux\n" +
                "GDAWBgNVBAMMD1JlZ3Jlc3Npb24gVGVzdDCCA0cwggI6BgcqhkjOOAQBMIICLQKC\n" +
                "AQEAmlavgoJrMcjqWRVcDE2dmWAPREgnzQvneEDef68cprDzjSwvOs5QeFyx75ib\n" +
                "ado1e6jO/rW1prCGWHDD1oA/Tn4Pk3vu0nUxzvl1qATc+aJbpUU5Op0bvp6LbCsQ\n" +
                "QslV9FeRh7Eb7bP6gpc/kHCBzEgC1VCK7prccXWy+t6SMOHbND3h+UbckfSaUuaV\n" +
                "sVJNTD1D6GElfRj4Nmz1BGPfSYvKorwNZEU3gXwFgtDoAcGx7tcyClLpDHfqRfw/\n" +
                "7yiqLyeiP7D4hl5lMNouJWDlAdMFp0FMgS3s9VDFinIcr6VtBWMTG7+4+czHAB+3\n" +
                "fvrwlqNzhBn3uFHrekN/w8fNxwIhAJo7Sae1za7IMW0Q6hE5B4b+s2B/FaKPoA4E\n" +
                "jtZu13B9AoIBAQCOZqLMKfvqZWUgT0PQ3QjR7dAFdd06I9Y3+TOQzZk1+j+vw/6E\n" +
                "X4vFItX4gihb/u5Q9CdmpwhVGi7bvo+7+/IKeTgoQ6f5+PSug7SrWWUQ5sPwaZui\n" +
                "zXZJ5nTeZDucFc2yFx0wgnjbPwiUxZklOT7xGiOMtzOTa2koCz5KuIBL+/wPKKxm\n" +
                "ypo9VoY9xfbdU6LMXZv/lpD5XTM9rYHr/vUTNkukvV6Hpm0YMEWhVZKUJiqCqTqG\n" +
                "XHaleOxSw6uQWB/+TznifcC7gB48UOQjCqOKf5VuwQneJLhlhU/jhRV3xtr+hLZa\n" +
                "hW1wYhVi8cjLDrZFKlgEQqhB4crnJU0mJY+tA4IBBQACggEAID0ezl00/X8mv7eb\n" +
                "bzovum1+DEEP7FM57k6HZEG2N3ve4CW+0m9Cd+cWPz8wkZ+M0j/Eqa6F0IdbkXEc\n" +
                "Q7CuzvUyJ57xQ3L/WCgXsiS+Bh8O4Mz7GwW22CGmHqafbVv+hKBfr8MkskO6GJUt\n" +
                "SUF/CVLzB4gMIvZMH26tBP2xK+i7FeEK9kT+nGdzQSZBAhFYpEVCBplHZO24/OYq\n" +
                "1DNoU327nUuXIhmsfA8N0PjiWbIZIjTPwBGr9H0LpATI7DIDNcvRRvtROP+pBU9y\n" +
                "fuykPkptg9C0rCM9t06bukpOSaEz/2VIQdLE8fHYFA6pHZ6CIc2+5cfvMgTPhcjz\n" +
                "W2jCt6MjMCEwHwYDVR0jBBgwFoAUdmae9zvdReU72XI8P/BUOYYxJlMwCwYJYIZI\n" +
                "AWUDBAMCA0gAMEUCIQCeI5fN08b9BpOaHdc3zQNGjp24FOL/RxlBLeBAorswJgIg\n" +
                "JEZ8DhYxQy1O7mmZ2UIT7op6epWMB4dENjs0qWPmcKo=\n" +
                "-----END CERTIFICATE-----",
                "MIICZQIBADCCAjoGByqGSM44BAEwggItAoIBAQCaVq+CgmsxyOpZFVwMTZ2ZYA9E\n" +
                "SCfNC+d4QN5/rxymsPONLC86zlB4XLHvmJtp2jV7qM7+tbWmsIZYcMPWgD9Ofg+T\n" +
                "e+7SdTHO+XWoBNz5olulRTk6nRu+notsKxBCyVX0V5GHsRvts/qClz+QcIHMSALV\n" +
                "UIrumtxxdbL63pIw4ds0PeH5RtyR9JpS5pWxUk1MPUPoYSV9GPg2bPUEY99Ji8qi\n" +
                "vA1kRTeBfAWC0OgBwbHu1zIKUukMd+pF/D/vKKovJ6I/sPiGXmUw2i4lYOUB0wWn\n" +
                "QUyBLez1UMWKchyvpW0FYxMbv7j5zMcAH7d++vCWo3OEGfe4Uet6Q3/Dx83HAiEA\n" +
                "mjtJp7XNrsgxbRDqETkHhv6zYH8Voo+gDgSO1m7XcH0CggEBAI5moswp++plZSBP\n" +
                "Q9DdCNHt0AV13Toj1jf5M5DNmTX6P6/D/oRfi8Ui1fiCKFv+7lD0J2anCFUaLtu+\n" +
                "j7v78gp5OChDp/n49K6DtKtZZRDmw/Bpm6LNdknmdN5kO5wVzbIXHTCCeNs/CJTF\n" +
                "mSU5PvEaI4y3M5NraSgLPkq4gEv7/A8orGbKmj1Whj3F9t1Tosxdm/+WkPldMz2t\n" +
                "gev+9RM2S6S9XoembRgwRaFVkpQmKoKpOoZcdqV47FLDq5BYH/5POeJ9wLuAHjxQ\n" +
                "5CMKo4p/lW7BCd4kuGWFT+OFFXfG2v6EtlqFbXBiFWLxyMsOtkUqWARCqEHhyucl\n" +
                "TSYlj60EIgIgLfA75+8KcKxdN8mr6gzGjQe7jPFGG42Ejhd7Q2F4wuw="),

        EE_DSA_1024(
                "DSA",
                // dsaWithSHA1, 1024 bits
                // Validity
                //     Not Before: Apr 24 12:25:43 2020 GMT
                //     Not After : Apr 22 12:25:43 2030 GMT
                // Authority Key Identifier:
                //     E1:3C:01:52:EB:D1:38:F7:CF:F1:E3:5E:DB:54:75:7F:5E:AB:2D:36
                "-----BEGIN CERTIFICATE-----\n" +
                "MIIDADCCAr+gAwIBAgIUd2XJ5F2VTbk9a92w/NzLXR5zjUQwCQYHKoZIzjgEAzAu\n" +
                "MQswCQYDVQQGEwJVUzENMAsGA1UECgwESmF2YTEQMA4GA1UECwwHU3VuSlNTRTAe\n" +
                "Fw0yMDA0MjQxMjI1NDNaFw0zMDA0MjIxMjI1NDNaMEgxCzAJBgNVBAYTAlVTMQ0w\n" +
                "CwYDVQQKDARKYXZhMRAwDgYDVQQLDAdTdW5KU1NFMRgwFgYDVQQDDA9SZWdyZXNz\n" +
                "aW9uIFRlc3QwggG3MIIBLAYHKoZIzjgEATCCAR8CgYEA7fSkxYISlMJT+i8N5VOb\n" +
                "lHhjrPYAy3oR2/YXQW6T0hCMhm8jmxgk1bDId9ZKHrxsM05EkCtRYaqag4ZZeGde\n" +
                "ywv3IwwYqCQfGtkPwT9QAsdSABYwGOrlhEtZtBG1yQ44c+Rz/Vs+PtkAyZbf5VG1\n" +
                "iSxFb9bI5QFJWJ9a2VpZh58CFQCCGALQoK4MsQP8V72WlB7Bvt9erwKBgQDCxu0G\n" +
                "M2iZr0J8DaAo9/ChS4m7E7h6Jz9KOm2cFhzYGekkUXNzny7nyz6Qpgbuf8KNFKjt\n" +
                "qoUDC8tlcVQAUlTcESC0TZXR3h21hl9wzIBhE+kJ1j8v1KAxfOaJOxObk5QEvIaA\n" +
                "5j+jiHGwRS5tDqywOatz+emwMZv1wKnCNBElNgOBhAACgYBHjuQKucCuuvy/4DpG\n" +
                "rSIzdueK+HrzOW8h2pfvz3lzpsyV6XJPC6we9CjaQjU01VcjwN2PoYtbGyml0pbK\n" +
                "We4sdgn6LDL1aCM/WKRSxGHVTx+wkhKQ719YtiC0T6sA+eLirc6VT3/6+FbQWC+2\n" +
                "bG7N19sGpV/RAXMBpRXUnBJSQaNCMEAwHQYDVR0OBBYEFNNZxyxuQmKvWowofr/S\n" +
                "HdCIS+W8MB8GA1UdIwQYMBaAFOE8AVLr0Tj3z/HjXttUdX9eqy02MAkGByqGSM44\n" +
                "BAMDMAAwLQIUUzzMhZ9St/Vo/YdgNTHdTw4cm14CFQCE6tWG157Wl5YFyYsGHsLY\n" +
                "NN8uCA==\n" +
                "-----END CERTIFICATE-----",
                "MIIBSwIBADCCASwGByqGSM44BAEwggEfAoGBAO30pMWCEpTCU/ovDeVTm5R4Y6z2\n" +
                "AMt6Edv2F0Fuk9IQjIZvI5sYJNWwyHfWSh68bDNORJArUWGqmoOGWXhnXssL9yMM\n" +
                "GKgkHxrZD8E/UALHUgAWMBjq5YRLWbQRtckOOHPkc/1bPj7ZAMmW3+VRtYksRW/W\n" +
                "yOUBSVifWtlaWYefAhUAghgC0KCuDLED/Fe9lpQewb7fXq8CgYEAwsbtBjNoma9C\n" +
                "fA2gKPfwoUuJuxO4eic/SjptnBYc2BnpJFFzc58u58s+kKYG7n/CjRSo7aqFAwvL\n" +
                "ZXFUAFJU3BEgtE2V0d4dtYZfcMyAYRPpCdY/L9SgMXzmiTsTm5OUBLyGgOY/o4hx\n" +
                "sEUubQ6ssDmrc/npsDGb9cCpwjQRJTYEFgIUNRiLmNzfTYOuVsjkySPzP5gPImM="),

        EE_ED25519(
                "EdDSA",
                // ED25519
                // Validity
                //     Not Before: May 24 23:32:36 2020 GMT
                //     Not After : May 22 23:32:36 2030 GMT
                // X509v3 Authority Key Identifier:
                //     keyid:06:76:DB:88:EB:61:55:4C:C9:63:41:C2:A0:A8:57:3F:D7:F1:B8:EC
                "-----BEGIN CERTIFICATE-----\n" +
                "MIIBlDCCAUagAwIBAgIUFTt/jcgQ65nhTG8LkrWFJhhEGuwwBQYDK2VwMDsxCzAJ\n" +
                "BgNVBAYTAlVTMQ0wCwYDVQQKDARqYXZhMR0wGwYDVQQLDBRTdW5KU1NFIFRlc3Qg\n" +
                "U2VyaXZjZTAeFw0yMDA1MjQyMzMyMzZaFw0zMDA1MjIyMzMyMzZaMFUxCzAJBgNV\n" +
                "BAYTAlVTMQ0wCwYDVQQKDARqYXZhMR0wGwYDVQQLDBRTdW5KU1NFIFRlc3QgU2Vy\n" +
                "aXZjZTEYMBYGA1UEAwwPUmVncmVzc2lvbiBUZXN0MCowBQYDK2VwAyEAGAYQmKb7\n" +
                "WNYpVxIdsc49lI1emNjF06/Jl85zlG0wc9OjQjBAMB0GA1UdDgQWBBQkJ2E4/S8Z\n" +
                "EIM1v9uTc0eYtYNk3zAfBgNVHSMEGDAWgBQGdtuI62FVTMljQcKgqFc/1/G47DAF\n" +
                "BgMrZXADQQCVZnl/AyIEtZ8r45e/hcfxwuezgRX+7e9NHZFV1A/TMGcBRORDfDUi\n" +
                "bbh72K528fjT7P4/WoXvm1zJKOAzUOUL\n" +
                "-----END CERTIFICATE-----",
                "MC4CAQAwBQYDK2VwBCIEIGBmdh4tfc0lng/LWokhfFLlo0ZlmTn2lbI639qou2KP"),

        EE_ED448(
                "EdDSA",
                // ED448
                // Validity
                //     Not Before: May 24 23:23:43 2020 GMT
                //     Not After : May 22 23:23:43 2030 GMT
                // X509v3 Authority Key Identifier:
                //     keyid:F5:D5:9D:FB:6F:B7:50:29:DF:F0:B8:83:10:5F:9B:C4:A8:1C:E9:F4
                "-----BEGIN CERTIFICATE-----\n" +
                "MIIB3zCCAV+gAwIBAgIUNlWzFrH2+BILqM3SNYQjKoY98S8wBQYDK2VxMDsxCzAJ\n" +
                "BgNVBAYTAlVTMQ0wCwYDVQQKDARqYXZhMR0wGwYDVQQLDBRTdW5KU1NFIFRlc3Qg\n" +
                "U2VyaXZjZTAeFw0yMDA1MjQyMzIzNDNaFw0zMDA1MjIyMzIzNDNaMFUxCzAJBgNV\n" +
                "BAYTAlVTMQ0wCwYDVQQKDARqYXZhMR0wGwYDVQQLDBRTdW5KU1NFIFRlc3QgU2Vy\n" +
                "aXZjZTEYMBYGA1UEAwwPUmVncmVzc2lvbiBUZXN0MEMwBQYDK2VxAzoAoIubPNAg\n" +
                "F11u3MQ5d9wujg10+80I0xzYzTqzzXrfJNtw+eU8NbUk86xiCvlMzJRH0Oo3DbY8\n" +
                "NAKAo0IwQDAdBgNVHQ4EFgQUUiI1+qT1x+HsDgfZRIU6hUaAbmUwHwYDVR0jBBgw\n" +
                "FoAU9dWd+2+3UCnf8LiDEF+bxKgc6fQwBQYDK2VxA3MAx8P0mle08s5YDd/p58dt\n" +
                "yORqvDPwo5IYPasqN8Zeen1B9u1xF/kvDGFxCJ6D9Gi4ynnDx0FZFMkA83evZcxJ\n" +
                "+X+swt7FyHwXrdkZcvjRKEcsWhkj+0FlxYF/NZzLTGuGIPYJnRLEwf/zr+5NDxKs\n" +
                "fCoA\n" +
                "-----END CERTIFICATE-----",
                "MEcCAQAwBQYDK2VxBDsEOfbhmUSuKP9WCO7Nr6JxVq5rfJESk1MNMyYhC134SiAP\n" +
                "Suw0Cu7RZVadpfPR7Kiwb2b/JXjMdY1HAA=="),

        EE_RSASSA_PSS(
                "RSASSA-PSS",
                // Signature Algorithm: rsassaPss
                // Hash Algorithm: sha256
                // Mask Algorithm: mgf1 with sha256
                //
                // Validity
                //      Not Before: Jun  6 07:11:00 2018 GMT
                //      Not After : Jun  1 07:11:00 2038 GMT
                // X509v3 Authority Key Identifier:
                //      1F:16:2B:79:8A:55:89:99:98:02:5F:84:18:D0:7B:1A:23:D8:88:0C
                "-----BEGIN CERTIFICATE-----\n" +
                "MIIDZjCCAh2gAwIBAgIUHxwPs3eAgJ057nJwiLgWZWeNqdgwPgYJKoZIhvcNAQEK\n" +
                "MDGgDTALBglghkgBZQMEAgGhGjAYBgkqhkiG9w0BAQgwCwYJYIZIAWUDBAIBogQC\n" +
                "AgDeMBQxEjAQBgNVBAMMCWxvY2FsaG9zdDAeFw0xODA2MDYwNzExMDBaFw0zODA2\n" +
                "MDEwNzExMDBaMBQxEjAQBgNVBAMMCWxvY2FsaG9zdDCCASAwCwYJKoZIhvcNAQEK\n" +
                "A4IBDwAwggEKAoIBAQCl8r4Qrg27BYUO/1Va2Ix8QPGzN/lvzmKvP5Ff26ovNW4v\n" +
                "RUx68HzAhhiWtcl+PwLSbJqJreEkTlle7PnRAypby3fO7ZAK0Y3YiHquaBg7d+7Y\n" +
                "FhhHwv8gG0lZcyA0BkXFJHqdq76qar0xHC6DVezXm0K3mcceymGtFR9BzWmAj+7D\n" +
                "YsSwvtTQ7WNoQmf0cdDMSM71IwaTwIwvT2wzX1vv5hcdDyXdr64WFqWSA9sNJ2K6\n" +
                "arxaaU1klwKSgDokF6njafWQ4UxdR67d5W1MYoiioDs2Yy3utsMpO2OUzZVBZNdT\n" +
                "gkr1jsJhIurpz/5K51lwJIRQBezEFSb+60AFVoMJAgMBAAGjUDBOMB0GA1UdDgQW\n" +
                "BBQfFit5ilWJmZgCX4QY0HsaI9iIDDAfBgNVHSMEGDAWgBQfFit5ilWJmZgCX4QY\n" +
                "0HsaI9iIDDAMBgNVHRMEBTADAQH/MD4GCSqGSIb3DQEBCjAxoA0wCwYJYIZIAWUD\n" +
                "BAIBoRowGAYJKoZIhvcNAQEIMAsGCWCGSAFlAwQCAaIEAgIA3gOCAQEAa4yUQ3gh\n" +
                "d1YWPdEa1sv2hdkhtenw6m5yxbmaQl2+nIKSpk4RfpXC7K1EYwBF8TdfFbD8hGGh\n" +
                "5n81BT0/dn1R9SRGCv7KTxx4lfQt31frlsw/tVciwyXQtcUZ6DqfnLP0/aRVLNgx\n" +
                "zaP542JUHFYLTC3EGz2zUgv70ZUTlIsPG3/p8YO1iXdnYGQyzOuQPUBpI7nS7UtR\n" +
                "Ug8VE9ACpBxxI3qChMahFZGHlXCCSjSmxpQa6UO4SQl8q5tPNnqdzWwvAW8qkCy4\n" +
                "6barRQ4sMcGayhHh/uSTx7bcl0FMJpcI1ygbw7/Pc03zKtw0gMTBMns7q4yXjb/u\n" +
                "ef47nW0t+LRAAg==\n" +
                "-----END CERTIFICATE-----",
                "MIIEuwIBADALBgkqhkiG9w0BAQoEggSnMIIEowIBAAKCAQEApfK+EK4NuwWFDv9V\n" +
                "WtiMfEDxszf5b85irz+RX9uqLzVuL0VMevB8wIYYlrXJfj8C0myaia3hJE5ZXuz5\n" +
                "0QMqW8t3zu2QCtGN2Ih6rmgYO3fu2BYYR8L/IBtJWXMgNAZFxSR6nau+qmq9MRwu\n" +
                "g1Xs15tCt5nHHsphrRUfQc1pgI/uw2LEsL7U0O1jaEJn9HHQzEjO9SMGk8CML09s\n" +
                "M19b7+YXHQ8l3a+uFhalkgPbDSdiumq8WmlNZJcCkoA6JBep42n1kOFMXUeu3eVt\n" +
                "TGKIoqA7NmMt7rbDKTtjlM2VQWTXU4JK9Y7CYSLq6c/+SudZcCSEUAXsxBUm/utA\n" +
                "BVaDCQIDAQABAoIBAAc4vRS0vlw5LUUtz2UYr2Ro3xvRf8Vh0eGWfpkRUiKjzJu6\n" +
                "BE4FUSh/rWpBlvcrfs/xcfgz3OxbjIAZB/YUkS9Vd21F4VLXM7kMl2onlYZg/b/h\n" +
                "lkTpM3kONu7xl6Er9LVTlRJveuinpHwSoeONRbVMSGb9BjFM1VtW4/lVGxZBG05D\n" +
                "y9i/o4vCZqULn9cAumOwicKuCyTcS58XcMJ+puSPfRA71PYLxqFkASAoJsUwCXpo\n" +
                "gs39lLsIFgrfO8mBO1ux/SE+QaRc+9XqFSHHKD1XqF/9zSYBgWjE910EcpdYEdZx\n" +
                "GEkwea7Fn4brO5OpIrHY/45naqbUOBzv6gufMAECgYEAz7PHCdcrQvmOb8EiNbQH\n" +
                "uvSimwObWJFeN1ykp6mfRbSnkXw7p8+M4Tc8HFi8QLpoq63Ev2AwoaQCQvHbFC2Y\n" +
                "1Cz0EkC0aOp+tZP7U2AUBdkcDesZAJQTad0zV6KesyIUXdxZXDG8JJ1XSNWfTJV4\n" +
                "QD+BjLZ0jiAyCIfVYvWQqYkCgYEAzIln1nKTixLMPr5CldSmR7ZarEtPJU+hHwVg\n" +
                "dV/Lc6d2Yy9JgunOXRo4BXB1TEo8JFbK3HBQH6tS8li4qDr7WK5wyYfh8qb4WZyu\n" +
                "lc562f2WVYntcN8/Ojb+Vyrt7lk9sq/8KoVHxEAWd6mqL9VTPYuAu1Vw9fTGIZfB\n" +
                "lDeELYECgYAvdzU4UXzofGGJtohb332YwwlaBZP9xJLUcg6K5l+orWVSASMc8XiP\n" +
                "i3DoRXsYC8GZ4kdBOPlEJ1gA9oaLcPQpIPDSLwlLpLM6Scw4vI822uvnXl/DWxOo\n" +
                "sM1n7Jj59QLUhGPDhvYpI+/rjC4wcUQe4qR3hMbUKBVnD6u7RsU9iQKBgQCQ17VK\n" +
                "7bSCRfuRaxaoGADww7gOTv5rQ6qr1xjpxb7D1hFGR9Rc+smCsPB/GZZXQjK44SWj\n" +
                "WX3ED4Ubzaxmpe4cbNu+O5XMSmWQwB36RFBHUwdE5/nXdqDFzu/qNqJrqZLBmVKP\n" +
                "ofaiiWffsaytVvotmT6+atElvAMbAua42V+nAQKBgHtIn3mYMHLriYGhQzpkFEA2\n" +
                "8YcAMlKppueOMAKVy8nLu2r3MidmLAhMiKJQKG45I3Yg0/t/25tXLiOPJlwrOebh\n" +
                "xQqUBI/JUOIpGAEnr48jhOXnCS+i+z294G5U/RgjXrlR4bCPvrtCmwzWwe0h79w2\n" +
                "Q2hO5ZTW6UD9CVA85whf"),

        DSA_SHA1_1024_EXPIRED( // for NullCerts test
            "DSA",
                //        Signature Algorithm: dsaWithSHA1
                //        Issuer: C = US, ST = CA, L = Cupertino, O = Dummy, OU = Dummy, CN = Example
                //        Validity
                //            Not Before: Mar 11 06:33:43 2001 GMT
                //            Not After : Dec  6 06:33:43 2003 GMT
                "-----BEGIN CERTIFICATE-----\n" +
                "MIIC9TCCArMCBDqrHEcwCwYHKoZIzjgEAwUAMGAxCzAJBgNVBAYTAlVTMQswCQYD\n" +
                "VQQIEwJDQTESMBAGA1UEBxMJQ3VwZXJ0aW5vMQ4wDAYDVQQKEwVEdW1teTEOMAwG\n" +
                "A1UECxMFRHVtbXkxEDAOBgNVBAMTB0V4YW1wbGUwHhcNMDEwMzExMDYzMzQzWhcN\n" +
                "MDMxMjA2MDYzMzQzWjBgMQswCQYDVQQGEwJVUzELMAkGA1UECBMCQ0ExEjAQBgNV\n" +
                "BAcTCUN1cGVydGlubzEOMAwGA1UEChMFRHVtbXkxDjAMBgNVBAsTBUR1bW15MRAw\n" +
                "DgYDVQQDEwdFeGFtcGxlMIIBuDCCASwGByqGSM44BAEwggEfAoGBAP1/U4EddRIp\n" +
                "Ut9KnC7s5Of2EbdSPO9EAMMeP4C2USZpRV1AIlH7WT2NWPq/xfW6MPbLm1Vs14E7\n" +
                "gB00b/JmYLdrmVClpJ+f6AR7ECLCT7up1/63xhv4O1fnxqimFQ8E+4P208UewwI1\n" +
                "VBNaFpEy9nXzrith1yrv8iIDGZ3RSAHHAhUAl2BQjxUjC8yykrmCouuEC/BYHPUC\n" +
                "gYEA9+GghdabPd7LvKtcNrhXuXmUr7v6OuqC+VdMCz0HgmdRWVeOutRZT+ZxBxCB\n" +
                "gLRJFnEj6EwoFhO3zwkyjMim4TwWeotUfI0o4KOuHiuzpnWRbqN/C/ohNWLx+2J6\n" +
                "ASQ7zKTxvqhRkImog9/hWuWfBpKLZl6Ae1UlZAFMO/7PSSoDgYUAAoGBAPqO/boo\n" +
                "m+n+tAdqetoQ2ZRoS8BpYIEFOJt4OJ8flb52T3vGNNdapq9pbjN+HKrT62ggNhZs\n" +
                "hajxYwFCpaidKZuGQXvvpHkj0UHjhZFry6Dd41cfEG13dfgACf8uooeTzPGFvUPv\n" +
                "TCHcPRh820BZMeOqdS4PjWPyf3HEtiTtFWR7MAsGByqGSM44BAMFAAMvADAsAhRH\n" +
                "dZQef04MwUTlAALf2J6PIcgmQAIUB2H/RnW2tVg+mbCl5jQLfudsEhI=\n" +
                "-----END CERTIFICATE-----",
                "MIIBSwIBADCCASwGByqGSM44BAEwggEfAoGBAP1/U4EddRIpUt9KnC7s5Of2EbdS\n" +
                "PO9EAMMeP4C2USZpRV1AIlH7WT2NWPq/xfW6MPbLm1Vs14E7gB00b/JmYLdrmVCl\n" +
                "pJ+f6AR7ECLCT7up1/63xhv4O1fnxqimFQ8E+4P208UewwI1VBNaFpEy9nXzrith\n" +
                "1yrv8iIDGZ3RSAHHAhUAl2BQjxUjC8yykrmCouuEC/BYHPUCgYEA9+GghdabPd7L\n" +
                "vKtcNrhXuXmUr7v6OuqC+VdMCz0HgmdRWVeOutRZT+ZxBxCBgLRJFnEj6EwoFhO3\n" +
                "zwkyjMim4TwWeotUfI0o4KOuHiuzpnWRbqN/C/ohNWLx+2J6ASQ7zKTxvqhRkImo\n" +
                "g9/hWuWfBpKLZl6Ae1UlZAFMO/7PSSoEFgIUZC+jBuwAPm8ejkybfAm2gT49ApY="
        );

        final String keyAlgo;
        final String certStr;
        final String privKeyStr;

        Cert(String keyAlgo, String certStr, String privKeyStr) {
            this.keyAlgo = keyAlgo;
            this.certStr = certStr;
            this.privKeyStr = privKeyStr;
        }
    }
}
