package com.matheusmelo.kybr.service.scan;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * The mutual TLS link to the isolated scanner organisation (the malware scanner and the photo
 * sanitizer). TLS 1.3 only; this API presents its client certificate; the server must chain to the
 * link's private CA (and nothing else), match the expected host name or IP address, and carry the
 * pinned public key. A mismatch fails the handshake, before any file byte is sent.
 */
public final class ScanLinkTls {

    static final String PROTOCOL = "TLSv1.3";

    private final SSLContext context;

    private ScanLinkTls(SSLContext context) {
        this.context = context;
    }

    /**
     * @param clientCert this API's certificate, PEM
     * @param clientKey its PKCS#8 private key, PEM (EC or RSA)
     * @param ca the link's CA certificate, PEM: the only trust anchor
     * @param spkiSha256 base64 SHA-256 of the server certificate's public key
     */
    public static ScanLinkTls fromPem(String clientCert, String clientKey, String ca, String spkiSha256) {
        try {
            List<? extends Certificate> chain = certificates(clientCert);
            KeyStore keys = KeyStore.getInstance("PKCS12");
            keys.load(null, null);
            char[] password = new char[0];
            keys.setKeyEntry("client", privateKey(clientKey), password, chain.toArray(Certificate[]::new));
            KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keys, password);

            KeyStore anchors = KeyStore.getInstance("PKCS12");
            anchors.load(null, null);
            int i = 0;
            for (Certificate anchor : certificates(ca)) {
                anchors.setCertificateEntry("ca" + i++, anchor);
            }
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(anchors);
            X509ExtendedTrustManager pkix = null;
            for (TrustManager manager : trust.getTrustManagers()) {
                if (manager instanceof X509ExtendedTrustManager extended) {
                    pkix = extended;
                }
            }
            if (pkix == null) {
                throw new IllegalStateException("no X.509 trust manager");
            }
            SSLContext context = SSLContext.getInstance(PROTOCOL);
            context.init(keyManagers.getKeyManagers(),
                    new TrustManager[] {new PinningTrustManager(pkix, Base64.getDecoder().decode(spkiSha256))}, null);
            return new ScanLinkTls(context);
        } catch (GeneralSecurityException | IOException e) {
            // never echo the material: only which kind of failure
            throw new IllegalStateException("the scan link's TLS material is unusable (" + e.getClass().getSimpleName()
                    + ")");
        }
    }

    /** The context, for clients that open their own connections (TLS 1.3, pinned, CA-only). */
    public SSLContext context() {
        return context;
    }

    /** Parameters every connection on the link uses: TLS 1.3 and host name verification. */
    public SSLParameters parameters(String host) {
        SSLParameters parameters = context.getDefaultSSLParameters();
        parameters.setProtocols(new String[] {PROTOCOL});
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        if (!isIpLiteral(host)) {
            parameters.setServerNames(List.of(new SNIHostName(host)));
        }
        return parameters;
    }

    /**
     * Opens a verified connection.
     *
     * @throws IOException on a connection, handshake, name or pin failure
     */
    public SSLSocket connect(String host, int port, Duration connectTimeout, Duration readTimeout) throws IOException {
        Socket plain = new Socket();
        try {
            plain.connect(new InetSocketAddress(host, port), (int) connectTimeout.toMillis());
            plain.setSoTimeout((int) readTimeout.toMillis());
            SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(plain, host, port, true);
            socket.setSSLParameters(parameters(host));
            socket.startHandshake();
            return socket;
        } catch (IOException | RuntimeException e) {
            plain.close();
            throw e;
        }
    }

    private static boolean isIpLiteral(String host) {
        return host.indexOf(':') >= 0 || host.matches("[0-9.]+");
    }

    private static List<? extends Certificate> certificates(String pem) throws CertificateException {
        return List.copyOf(CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(pem.strip().getBytes(StandardCharsets.US_ASCII))));
    }

    private static PrivateKey privateKey(String pem) throws GeneralSecurityException {
        String body = pem.strip().replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body));
        for (String algorithm : List.of("EC", "RSA", "Ed25519")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec);
            } catch (GeneralSecurityException e) {
                // try the next algorithm
            }
        }
        throw new GeneralSecurityException("unsupported private key");
    }

    /** CA-only PKIX validation (with host name checks), then the public-key pin. */
    private static final class PinningTrustManager extends X509ExtendedTrustManager {

        private final X509ExtendedTrustManager pkix;
        private final byte[] pin;

        PinningTrustManager(X509ExtendedTrustManager pkix, byte[] pin) {
            this.pkix = pkix;
            this.pin = pin.clone();
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            pkix.checkServerTrusted(chain, authType, socket);
            checkPin(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            pkix.checkServerTrusted(chain, authType, engine);
            checkPin(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            // no socket or engine: no host name to check against, so never trusted
            throw new CertificateException("the scan link needs a host name check");
        }

        private void checkPin(X509Certificate[] chain) throws CertificateException {
            byte[] actual;
            try {
                actual = MessageDigest.getInstance("SHA-256").digest(chain[0].getPublicKey().getEncoded());
            } catch (GeneralSecurityException e) {
                throw new CertificateException("SHA-256 is unavailable");
            }
            if (!MessageDigest.isEqual(actual, pin)) {
                throw new CertificateException("the server's public key doesn't match the pin");
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            throw new CertificateException("client role only");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            throw new CertificateException("client role only");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("client role only");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return ((X509TrustManager) pkix).getAcceptedIssuers();
        }
    }
}
