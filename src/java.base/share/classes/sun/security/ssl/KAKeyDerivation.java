/*
 * Copyright (c) 2019, 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package sun.security.ssl;

import sun.security.util.RawKeySpec;

import javax.crypto.DecapsulateException;
import javax.crypto.KEM;
import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.spec.AlgorithmParameterSpec;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * A common class for creating various KeyDerivation types.
 */
public class KAKeyDerivation implements SSLKeyDerivation {

    private final String algorithmName;
    private final HandshakeContext context;
    private final PrivateKey localPrivateKey;
    private final PublicKey peerPublicKey;
    private final byte[] keyshare;
    private final Provider provider;

    // Constructor called by Key Agreement
    KAKeyDerivation(String algorithmName,
            HandshakeContext context,
            PrivateKey localPrivateKey,
            PublicKey peerPublicKey) {
        this(algorithmName, null, context, localPrivateKey,
                peerPublicKey, null);
    }

    // When the constructor called by KEM: store the client's public key or the
    // encapsulated message in keyshare.
    KAKeyDerivation(String algorithmName,
                    NamedGroup namedGroup,
                    HandshakeContext context,
                    PrivateKey localPrivateKey,
                    PublicKey peerPublicKey,
                    byte[] keyshare) {
        this.algorithmName = algorithmName;
        this.context = context;
        this.localPrivateKey = localPrivateKey;
        this.peerPublicKey = peerPublicKey;
        this.keyshare = keyshare;
        this.provider = (namedGroup != null) ? namedGroup.getProvider() : null;
    }

    @Override
    public SecretKey deriveKey(String algorithm,
            AlgorithmParameterSpec params) throws IOException {
        if (!context.negotiatedProtocol.useTLS13PlusSpec()) {
            return t12DeriveKey(algorithm, params);
        } else {
            return t13DeriveKey(algorithm, params);
        }
    }

    /**
     * Handle the TLSv1-1.2 objects, which don't use the HKDF algorithms.
     */
    private SecretKey t12DeriveKey(String algorithm,
            AlgorithmParameterSpec params) throws IOException {
        try {
            KeyAgreement ka = KeyAgreement.getInstance(algorithmName);
            ka.init(localPrivateKey);
            ka.doPhase(peerPublicKey, true);
            SecretKey preMasterSecret
                    = ka.generateSecret("TlsPremasterSecret");
            SSLMasterKeyDerivation mskd
                    = SSLMasterKeyDerivation.valueOf(
                            context.negotiatedProtocol);
            if (mskd == null) {
                // unlikely
                throw new SSLHandshakeException(
                        "No expected master key derivation for protocol: "
                        + context.negotiatedProtocol.name);
            }
            SSLKeyDerivation kd = mskd.createKeyDerivation(
                    context, preMasterSecret);
            return kd.deriveKey("MasterSecret", params);
        } catch (GeneralSecurityException gse) {
            throw new SSLHandshakeException("Could not generate secret", gse);
        }
    }

    private SecretKey deriveHandshakeSecret(String label,
            SecretKey sharedSecret)
            throws GeneralSecurityException, IOException {
        SecretKey earlySecret = null;
        SecretKey saltSecret = null;
        CipherSuite.HashAlg hashAlg = context.negotiatedCipherSuite.hashAlg;
        SSLKeyDerivation kd = context.handshakeKeyDerivation;
        try {
            HKDF hkdf = new HKDF(hashAlg.name);
            if (kd == null) {   // No PSK is in use.
                // If PSK is not in use Early Secret will still be
                // HKDF-Extract(0, 0).
                byte[] zeros = new byte[hashAlg.hashLength];
                SecretKeySpec ikm
                        = new SecretKeySpec(zeros, "TlsPreSharedSecret");
                earlySecret
                        = hkdf.extract(zeros, ikm, "TlsEarlySecret");
                kd = new SSLSecretDerivation(context, earlySecret);
            }

            // derive salt secret
            saltSecret = kd.deriveKey("TlsSaltSecret", null);

            // derive handshake secret
            if (sharedSecret instanceof Hybrid.SecretKeyImpl hsk) {
                // No javax.crypto.KDF. Concatenating the two hybrid
                // shared secrets for single IKM in HKDF.extract(),
                // then clearing the temporary copies.
                byte[] k1 = hsk.k1().getEncoded();
                byte[] k2 = hsk.k2().getEncoded();
                byte[] ikm = new byte[k1.length + k2.length];
                System.arraycopy(k1, 0, ikm, 0, k1.length);
                System.arraycopy(k2, 0, ikm, k1.length, k2.length);
                try {
                    return hkdf.extract(saltSecret,
                            new SecretKeySpec(ikm, "TlsPremasterSecret"),
                            label);
                } finally {
                    Arrays.fill(k1, (byte)0);
                    Arrays.fill(k2, (byte)0);
                    Arrays.fill(ikm, (byte)0);
                }
            } else {
                return hkdf.extract(saltSecret, sharedSecret, label);
            }
        } catch (GeneralSecurityException gse) {
            throw new SSLHandshakeException("Could not generate secret", gse);
        }
    }
    /**
     * This method is called by the server to perform KEM encapsulation.
     * It uses the client's public key (sent by the client as a keyshare)
     * to encapsulate a shared secret and returns the encapsulated message.
     *
     * Package-private, used from KeyShareExtension.SHKeyShareProducer::
     * produce().
     */
    KEM.Encapsulated encapsulate(String algorithm, SecureRandom random)
            throws IOException {
        SecretKey sharedSecret = null;

        if (keyshare == null) {
            throw new IOException("No keyshare available for KEM " +
                    "encapsulation");
        }

        // All exceptions thrown during KEM encapsulation are mapped
        // to TLS fatal alerts:
        // illegal_parameter alert or internal_error alert.
        try {
            KeyFactory kf = (provider != null) ?
                    KeyFactory.getInstance(algorithmName, provider) :
                    KeyFactory.getInstance(algorithmName);
            var pk = kf.generatePublic(new RawKeySpec(keyshare));

            KEM kem = (provider != null) ?
                    KEM.getInstance(algorithmName, provider) :
                    KEM.getInstance(algorithmName);
            KEM.Encapsulator e = kem.newEncapsulator(pk, random);
            KEM.Encapsulated enc = e.encapsulate();
            sharedSecret = enc.key();

            SecretKey derived = deriveHandshakeSecret(algorithm, sharedSecret);

            return new KEM.Encapsulated(derived, enc.encapsulation(), null);
        } catch (IllegalArgumentException | InvalidKeyException e) {
            // Peer validation failure
            // ECDH all-zero shared secret (RFC 8446 section 7.4.2),
            // ML-KEM encapsulation key check failure (FIPS-203 section 7.2)
            throw context.conContext.fatal(Alert.ILLEGAL_PARAMETER, e);
        } catch (GeneralSecurityException e) {
            // Cryptographic failure,
            // deriveHandshakeSecret failure.
            throw context.conContext.fatal(Alert.INTERNAL_ERROR, e);
        } catch (RuntimeException e) {
            // unexpected provider/runtime failure
            throw context.conContext.fatal(Alert.INTERNAL_ERROR, e);
        }
    }

    /**
     * Handle the TLSv1.3 objects, which use the HKDF algorithms.
     */
    private SecretKey t13DeriveKey(String type,
            AlgorithmParameterSpec params) throws IOException {
        SecretKey sharedSecret = null;

        try {
            if (keyshare != null) {
                // Using KEM: called by the client after receiving the KEM
                // ciphertext (keyshare) from the server in ServerHello.
                // The client decapsulates it using its private key.

                // All exceptions thrown during KEM decapsulation are mapped
                // to TLS fatal alerts:
                // illegal_parameter alert or internal_error alert.
                try {
                    KEM kem = (provider != null)
                            ? KEM.getInstance(algorithmName, provider)
                            : KEM.getInstance(algorithmName);
                    var decapsulator = kem.newDecapsulator(localPrivateKey);
                    sharedSecret = decapsulator.decapsulate(
                            keyshare, 0, decapsulator.secretSize(),
                            "TlsPremasterSecret");
                } catch (IllegalArgumentException | InvalidKeyException |
                        DecapsulateException e) {
                    // Peer validation failure
                    // ECDH all-zero shared secret (RFC 8446 section 7.4.2)
                    throw context.conContext.fatal(Alert.ILLEGAL_PARAMETER, e);
                } catch (GeneralSecurityException e) {
                    // cryptographic failure
                    throw context.conContext.fatal(Alert.INTERNAL_ERROR, e);
                } catch (RuntimeException e) {
                    // unexpected provider/runtime failure
                    throw context.conContext.fatal(Alert.INTERNAL_ERROR, e);
                }
            } else {
                // Using traditional DH-style Key Agreement
                KeyAgreement ka = KeyAgreement.getInstance(algorithmName);
                ka.init(localPrivateKey);
                ka.doPhase(peerPublicKey, true);
                sharedSecret = ka.generateSecret("TlsPremasterSecret");
            }

            return deriveHandshakeSecret(type, sharedSecret);
        } catch (GeneralSecurityException gse) {
            // deriveHandshakeSecret() failure
            throw new SSLHandshakeException("Could not generate secret", gse);
        }
    }
}
