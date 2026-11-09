package com.matheusmelo.kybr.support.tls;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * A throwaway private CA and the certificates a test needs, made at run time with the JDK's
 * {@code keytool} (nothing is committed): EC P-256 keys, valid for two days. Each named identity is
 * made once per JVM and cached.
 */
public final class TestPki {

    private static final char[] PASSWORD = java.util.UUID.randomUUID().toString().toCharArray();
    private static final ConcurrentHashMap<String, TestPki> AUTHORITIES = new ConcurrentHashMap<>();

    private final Path dir;
    private final String caAlias;
    private final X509Certificate caCertificate;
    private final ConcurrentHashMap<String, Identity> identities = new ConcurrentHashMap<>();

    /** A certificate and its key, issued by this CA. */
    public record Identity(X509Certificate certificate, X509Certificate issuer, PrivateKey privateKey) {

        public String certificatePem() {
            return pem("CERTIFICATE", encoded(certificate));
        }

        public String keyPem() {
            return pem("PRIVATE KEY", privateKey.getEncoded());
        }

        /** Base64 SHA-256 of the certificate's public key: the pin. */
        public String spkiSha256() {
            return TestPki.spkiSha256(certificate);
        }

        /** A key manager presenting this identity. */
        public KeyManagerFactory keyManagers() {
            try {
                KeyStore store = KeyStore.getInstance("PKCS12");
                store.load(null, null);
                store.setKeyEntry("id", privateKey, PASSWORD, new Certificate[] {certificate, issuer});
                KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                factory.init(store, PASSWORD);
                return factory;
            } catch (GeneralSecurityException | IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private TestPki(String name) {
        try {
            dir = Files.createTempDirectory("kybr-test-pki-");
            dir.toFile().deleteOnExit();
            caAlias = "ca";
            keytool("-genkeypair", "-alias", caAlias, "-keyalg", "EC", "-groupname", "secp256r1",
                    "-dname", "CN=" + name, "-ext", "bc:c", "-validity", "2", "-keystore", store(caAlias));
            keytool("-exportcert", "-rfc", "-alias", caAlias, "-keystore", store(caAlias), "-file", file("ca.crt"));
            caCertificate = (X509Certificate) certificates(Files.readString(dir.resolve("ca.crt"))).getFirst();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The CA named {@code name}, made on first use. */
    public static TestPki authority(String name) {
        return AUTHORITIES.computeIfAbsent(name, TestPki::new);
    }

    public X509Certificate caCertificate() {
        return caCertificate;
    }

    public String caPem() {
        return pem("CERTIFICATE", encoded(caCertificate));
    }

    /**
     * An identity issued by this CA, made on first use.
     *
     * @param alias a cache key and the certificate's CN
     * @param san a keytool SAN value, e.g. {@code dns:scanner.test,ip:127.0.0.1}; null for none
     */
    public Identity issue(String alias, String san) {
        return identities.computeIfAbsent(alias + "|" + san, ignored -> make(alias, san));
    }

    /** A trust manager trusting only this CA. */
    public TrustManagerFactory trustManagers() {
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setCertificateEntry("ca", caCertificate);
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(store);
            return factory;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A TLS 1.3 context presenting {@code identity} and trusting only this CA. */
    public SSLContext serverContext(Identity identity) {
        try {
            SSLContext context = SSLContext.getInstance("TLSv1.3");
            context.init(identity.keyManagers().getKeyManagers(), trustManagers().getTrustManagers(), null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String spkiSha256(X509Certificate certificate) {
        try {
            return Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(certificate.getPublicKey().getEncoded()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private Identity make(String alias, String san) {
        try {
            String safe = alias.replaceAll("[^A-Za-z0-9]", "") + Integer.toHexString((alias + san).hashCode());
            keytool("-genkeypair", "-alias", safe, "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=" + alias,
                    "-validity", "2", "-keystore", store(safe));
            keytool("-certreq", "-alias", safe, "-keystore", store(safe), "-file", file(safe + ".csr"));
            List<String> gencert = new ArrayList<>(List.of("-gencert", "-rfc", "-alias", caAlias, "-keystore",
                    store(caAlias), "-infile", file(safe + ".csr"), "-outfile", file(safe + ".crt"), "-validity", "2"));
            if (san != null) {
                gencert.addAll(List.of("-ext", "san=" + san));
            }
            keytool(gencert.toArray(String[]::new));
            X509Certificate certificate = (X509Certificate) certificates(
                    Files.readString(dir.resolve(safe + ".crt"))).getFirst();
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(dir.resolve(safe + ".p12"))) {
                store.load(in, PASSWORD);
            }
            PrivateKey key = (PrivateKey) store.getKey(safe, PASSWORD);
            return new Identity(certificate, caCertificate, key);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private String store(String alias) {
        return file(alias + ".p12");
    }

    private String file(String name) {
        Path path = dir.resolve(name);
        path.toFile().deleteOnExit();
        return path.toString();
    }

    private static void keytool(String... args) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(args));
        command.addAll(List.of("-storetype", "PKCS12", "-storepass", new String(PASSWORD), "-noprompt"));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException("keytool failed: " + output);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static List<? extends Certificate> certificates(String pem) {
        try {
            return List.copyOf(CertificateFactory.getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII))));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] encoded(X509Certificate certificate) {
        try {
            return certificate.getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(der) + "\n-----END " + label + "-----\n";
    }
}
