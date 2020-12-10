/*
 * Copyright (c) 2020, Red Hat, Inc.
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
 * @bug 8257545
 * @summary Test that SunJSSE uses FIPS during the key exchange (TLSv1.2)
 * @modules java.base/com.sun.net.ssl.internal.ssl
 * @library /test/lib ..
 * @run main/othervm/timeout=120 SunJSSEKeyExchangeFIPS
 */

import java.nio.ByteBuffer;

import java.security.spec.AlgorithmParameterSpec;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.security.Provider.Service;

import javax.crypto.KeyAgreementSpi;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.ShortBufferException;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManagerFactory;

public final class SunJSSEKeyExchangeFIPS extends SecmodTest {

    private static Provider sunPKCS11NSSProvider;
    private static com.sun.net.ssl.internal.ssl.Provider jsseProvider;
    private static KeyStore ks;
    private static KeyStore ts;
    private static char[] passphrase = "JAHshj131@@".toCharArray();

    public static final class NonFIPSDH extends KeyAgreementSpi {
        public NonFIPSDH() {
        }
        protected void engineInit(Key key, SecureRandom random)
                throws InvalidKeyException {
        }
        protected void engineInit(Key key, AlgorithmParameterSpec params,
                SecureRandom random) throws InvalidKeyException,
                InvalidAlgorithmParameterException {
        }
        protected Key engineDoPhase(Key key, boolean lastPhase)
                throws InvalidKeyException, IllegalStateException {
            return null;
        }
        protected byte[] engineGenerateSecret() throws IllegalStateException {
            return null;
        }
        protected int engineGenerateSecret(byte[] sharedSecret, int
                offset) throws IllegalStateException, ShortBufferException {
            return -1;
        }
        protected SecretKey engineGenerateSecret(String algorithm)
                throws IllegalStateException, NoSuchAlgorithmException,
                InvalidKeyException {
            return null;
        }
    }

    static final class NonFIPSService extends Service {
        public NonFIPSService(Provider p) {
            super(p, "KeyAgreement", "DiffieHellman",
                    "SunJSSEKeyExchangeFIPS$NonFIPSDH", null, null);
        }
    }

    static final class NonFIPSProvider extends Provider {
        public NonFIPSProvider() {
            super("NonFIPSProvider",
                    System.getProperty("java.specification.version"),
                    "NonFIPSProvider");
            putService(new SunJSSEKeyExchangeFIPS.NonFIPSService(this));
        }
    }

    public static void main(String[] args) throws Exception {
        try {
            initialize();
        } catch (Exception e) {
            System.out.println("Test skipped: failure during" +
                    " initialization");
            return;
        }

        if (shouldRun()) {
            // Self-integrity test (complete TLS 1.2 communication)
            new testTLS12SunPKCS11Communication().run();

            System.out.println("Test PASS - OK");
        } else {
            System.out.println("Test skipped: TLS 1.2 mechanisms" +
                    " not supported by current SunPKCS11 back-end");
        }
    }

    private static boolean shouldRun() {
        if (sunPKCS11NSSProvider == null) {
            return false;
        }
        try {
            KeyGenerator.getInstance("SunTls12MasterSecret",
                    sunPKCS11NSSProvider);
            KeyGenerator.getInstance(
                    "SunTls12RsaPremasterSecret", sunPKCS11NSSProvider);
            KeyGenerator.getInstance("SunTls12Prf", sunPKCS11NSSProvider);
        } catch (NoSuchAlgorithmException e) {
            return false;
        }
        return true;
    }

    private static class testTLS12SunPKCS11Communication {
        public static void run() throws Exception {
            SSLEngine[][] enginesToTest = getSSLEnginesToTest();

            for (SSLEngine[] engineToTest : enginesToTest) {

                SSLEngine clientSSLEngine = engineToTest[0];
                SSLEngine serverSSLEngine = engineToTest[1];

                // SSLEngine code based on RedhandshakeFinished.java

                boolean dataDone = false;

                ByteBuffer clientOut = null;
                ByteBuffer clientIn = null;
                ByteBuffer serverOut = null;
                ByteBuffer serverIn = null;
                ByteBuffer cTOs;
                ByteBuffer sTOc;

                SSLSession session = clientSSLEngine.getSession();
                int appBufferMax = session.getApplicationBufferSize();
                int netBufferMax = session.getPacketBufferSize();

                clientIn = ByteBuffer.allocate(appBufferMax + 50);
                serverIn = ByteBuffer.allocate(appBufferMax + 50);

                cTOs = ByteBuffer.allocateDirect(netBufferMax);
                sTOc = ByteBuffer.allocateDirect(netBufferMax);

                clientOut = ByteBuffer.wrap(
                        "Hi Server, I'm Client".getBytes());
                serverOut = ByteBuffer.wrap(
                        "Hello Client, I'm Server".getBytes());

                SSLEngineResult clientResult;
                SSLEngineResult serverResult;

                while (!dataDone) {
                    clientResult = clientSSLEngine.wrap(clientOut, cTOs);
                    runDelegatedTasks(clientResult, clientSSLEngine);
                    serverResult = serverSSLEngine.wrap(serverOut, sTOc);
                    runDelegatedTasks(serverResult, serverSSLEngine);
                    cTOs.flip();
                    sTOc.flip();

                    System.out.println("Client -> Network");
                    printTlsNetworkPacket("", cTOs);
                    System.out.println("");
                    System.out.println("Server -> Network");
                    printTlsNetworkPacket("", sTOc);
                    System.out.println("");

                    clientResult = clientSSLEngine.unwrap(sTOc, clientIn);
                    runDelegatedTasks(clientResult, clientSSLEngine);
                    serverResult = serverSSLEngine.unwrap(cTOs, serverIn);
                    runDelegatedTasks(serverResult, serverSSLEngine);

                    cTOs.compact();
                    sTOc.compact();

                    if (!dataDone &&
                            (clientOut.limit() == serverIn.position()) &&
                            (serverOut.limit() == clientIn.position())) {
                        checkTransfer(serverOut, clientIn);
                        checkTransfer(clientOut, serverIn);
                        dataDone = true;
                    }
                }
            }
        }

        static void printTlsNetworkPacket(String prefix, ByteBuffer bb) {
            ByteBuffer slice = bb.slice();
            byte[] buffer = new byte[slice.remaining()];
            slice.get(buffer);
            for (int i = 0; i < buffer.length; i++) {
                System.out.printf("%02X, ", (byte)(buffer[i] & (byte)0xFF));
                if (i % 8 == 0 && i % 16 != 0) {
                    System.out.print(" ");
                }
                if (i % 16 == 0) {
                    System.out.println("");
                }
            }
            System.out.flush();
        }

        private static void checkTransfer(ByteBuffer a, ByteBuffer b)
                throws Exception {
            a.flip();
            b.flip();
            if (!a.equals(b)) {
                throw new Exception("Data didn't transfer cleanly");
            }
            a.position(a.limit());
            b.position(b.limit());
            a.limit(a.capacity());
            b.limit(b.capacity());
        }

        private static void runDelegatedTasks(SSLEngineResult result,
                SSLEngine engine) throws Exception {

            if (result.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
                Runnable runnable;
                while ((runnable = engine.getDelegatedTask()) != null) {
                    runnable.run();
                }
                HandshakeStatus hsStatus = engine.getHandshakeStatus();
                if (hsStatus == HandshakeStatus.NEED_TASK) {
                    throw new Exception(
                        "handshake shouldn't need additional tasks");
                }
            }
        }

        private static SSLEngine[][] getSSLEnginesToTest() throws Exception {
            SSLEngine[][] enginesToTest = new SSLEngine[1][2];
            String[] preferredSuites = new String[] {
                    "TLS_DHE_RSA_WITH_AES_128_CBC_SHA256"
            };
            for (int i = 0; i < enginesToTest.length; i++) {
                enginesToTest[i][0] = createSSLEngine(true);
                enginesToTest[i][1] = createSSLEngine(false);
                enginesToTest[i][0].setEnabledCipherSuites(preferredSuites);
                enginesToTest[i][1].setEnabledCipherSuites(preferredSuites);
            }
            return enginesToTest;
        }

        static private SSLEngine createSSLEngine(boolean client)
                throws Exception {
            SSLEngine ssle;
            KeyManagerFactory kmf = KeyManagerFactory.getInstance("PKIX",
                    jsseProvider);
            kmf.init(ks, passphrase);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX",
                    jsseProvider);
            tmf.init(ts);

            SSLContext sslCtx = SSLContext.getInstance("TLSv1.2",
                    jsseProvider);
            sslCtx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            ssle = sslCtx.createSSLEngine("localhost", 443);
            ssle.setUseClientMode(client);
            SSLParameters sslParameters = ssle.getSSLParameters();
            ssle.setSSLParameters(sslParameters);

            return ssle;
        }
    }

    private static void initialize() throws Exception {
        if (initSecmod() == false) {
            return;
        }

        // A non-FIPS provider is added first in order of preference.
        // This provider must not be used by the SunJSSE TLS engine
        // during the key exchange.
        Security.addProvider(new SunJSSEKeyExchangeFIPS.NonFIPSProvider());

        String configName = BASE + SEP + "fips.cfg";
        sunPKCS11NSSProvider = getSunPKCS11(configName);
        System.out.println("SunPKCS11 provider: " + sunPKCS11NSSProvider);
        Security.addProvider(sunPKCS11NSSProvider);

        Security.removeProvider("SunJSSE");
        jsseProvider =new com.sun.net.ssl.internal.ssl.Provider(
                sunPKCS11NSSProvider);
        Security.addProvider(jsseProvider);
        System.out.println(jsseProvider.getInfo());

        ks = KeyStore.getInstance("PKCS11", sunPKCS11NSSProvider);
        ks.load(null, "test12".toCharArray());
        ts = ks;
    }
}