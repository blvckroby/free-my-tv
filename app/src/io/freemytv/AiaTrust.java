package io.freemytv;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Some servers (Sky's guide) leave out their intermediate certificate. Browsers fetch it from the address the
 * certificate gives (its "CA Issuers" AIA entry); Android doesn't, and refuses the connection. This does what the
 * browsers do: the chain, with the fetched certificate, is then checked by Android's own trust manager as usual, so
 * the intermediate counts only if a trusted root signed it.
 */
final class AiaTrust implements X509TrustManager {
    private static SSLSocketFactory factory;
    private static final Map<String, X509Certificate> FETCHED = new ConcurrentHashMap<>();
    private final X509TrustManager system;

    private AiaTrust(X509TrustManager system) {
        this.system = system;
    }

    /** For this connection only; when anything goes wrong it keeps the default. */
    static void use(HttpURLConnection c) {
        if (!(c instanceof HttpsURLConnection)) return;
        SSLSocketFactory f = factory();
        if (f != null) ((HttpsURLConnection) c).setSSLSocketFactory(f);
    }

    private static synchronized SSLSocketFactory factory() {
        if (factory != null) return factory;
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    SSLContext ssl = SSLContext.getInstance("TLS");
                    ssl.init(null, new TrustManager[] {new AiaTrust((X509TrustManager) tm)}, null);
                    factory = ssl.getSocketFactory();
                    break;
                }
            }
        } catch (Exception e) {
            AppLog.w("AIA: " + e);
        }
        return factory;
    }

    @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        try {
            system.checkServerTrusted(chain, authType);
        } catch (CertificateException missing) {
            if (chain == null || chain.length == 0) throw missing;
            List<X509Certificate> full = new ArrayList<>(Arrays.asList(chain));
            X509Certificate last = chain[chain.length - 1];
            for (int up = 0; up < 3; up++) {        // at most three steps up toward a root
                X509Certificate issuer = issuerOf(last);
                if (issuer == null) break;
                full.add(issuer);
                try {
                    system.checkServerTrusted(full.toArray(new X509Certificate[0]), authType);
                    return;
                } catch (CertificateException stillMissing) {
                    last = issuer;
                }
            }
            throw missing;
        }
    }

    @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        system.checkClientTrusted(chain, authType);
    }

    @Override public X509Certificate[] getAcceptedIssuers() {
        return system.getAcceptedIssuers();
    }

    /** The certificate that signed `cert`, from its CA Issuers address (null if it gives none or it doesn't load). */
    private static X509Certificate issuerOf(X509Certificate cert) {
        String url = caIssuers(cert.getExtensionValue("1.3.6.1.5.5.7.1.1"));
        if (url == null) return null;
        X509Certificate known = FETCHED.get(url);
        if (known != null) return known;
        try {
            byte[] data = download(url);
            Collection<? extends Certificate> certs = CertificateFactory.getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(data));     // DER, PEM or PKCS#7
            for (Certificate c : certs) {
                if (c instanceof X509Certificate) {
                    FETCHED.put(url, (X509Certificate) c);
                    AppLog.w("AIA: intermediate certificate from " + url);
                    return (X509Certificate) c;
                }
            }
        } catch (Exception e) {
            AppLog.w("AIA: " + url + ": " + e);
        }
        return null;
    }

    /** The first CA Issuers URL (OID 1.3.6.1.5.5.7.48.2, then a [6] URI) in the extension's DER bytes. */
    static String caIssuers(byte[] ext) {
        if (ext == null) return null;
        byte[] oid = {0x06, 0x08, 0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x02};
        for (int i = 0; i + oid.length + 2 < ext.length; i++) {
            int j = 0;
            while (j < oid.length && ext[i + j] == oid[j]) j++;
            if (j < oid.length) continue;
            int p = i + oid.length;
            if ((ext[p] & 0xFF) != 0x86) continue;
            int len = ext[p + 1] & 0xFF;
            p += 2;
            if (len == 0x81 && p < ext.length) len = ext[p++] & 0xFF;
            else if (len > 0x7F) continue;
            if (p + len > ext.length) continue;
            String url = new String(ext, p, len, java.nio.charset.StandardCharsets.US_ASCII);
            if (url.startsWith("http")) return url;
        }
        return null;
    }

    /**
     * CA Issuers addresses are plain http (the certificate vouches for itself, signed by a root). The app doesn't allow
     * plain http through HttpURLConnection, so this asks over a socket; https goes the usual way.
     */
    private static byte[] download(String address) throws Exception {
        URL url = new URL(address);
        if ("https".equals(url.getProtocol())) {
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            try (InputStream in = c.getInputStream()) {
                return readAll(in);
            } finally {
                c.disconnect();
            }
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(url.getHost(), url.getPort() > 0 ? url.getPort() : 80), 8000);
            s.setSoTimeout(8000);
            String path = url.getFile().isEmpty() ? "/" : url.getFile();
            OutputStream out = s.getOutputStream();
            out.write(("GET " + path + " HTTP/1.0\r\nHost: " + url.getHost() + "\r\nUser-Agent: FreeMyTV\r\nConnection: close\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            out.flush();
            byte[] all = readAll(s.getInputStream());
            int body = -1;
            for (int i = 0; i + 3 < all.length; i++) {
                if (all[i] == '\r' && all[i + 1] == '\n' && all[i + 2] == '\r' && all[i + 3] == '\n') {
                    body = i + 4;
                    break;
                }
            }
            String head = new String(all, 0, body < 0 ? Math.min(all.length, 64) : body, java.nio.charset.StandardCharsets.US_ASCII);
            String status = head.split("\r?\n", 2)[0].trim();
            if (body < 0 || !status.matches("HTTP/1\\.[01] 200( .*)?")) throw new java.io.IOException(status);
            return Arrays.copyOfRange(all, body, all.length);
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }
}
