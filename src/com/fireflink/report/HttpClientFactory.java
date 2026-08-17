package com.fireflink.report;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;

/**
 * Builds the shared HttpClient used by all API calls.
 *
 * If you're hitting SSLHandshakeException: certificate_unknown when calling
 * the FireFlink APIs from this machine (corporate proxy / self-signed cert in
 * the chain is the usual cause), set FF_INSECURE_SSL=true to bypass
 * certificate validation as a diagnostic step. This is NOT safe for
 * production use - it disables protection against man-in-the-middle
 * attacks. The proper fix is importing the actual proxy/CA certificate into
 * the JDK's trust store (cacerts) via keytool, and this flag should only be
 * used temporarily to confirm that's really the cause of the failure.
 */
public class HttpClientFactory {

    public static HttpClient build() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .version(HttpClient.Version.HTTP_1_1);

        if ("true".equalsIgnoreCase(System.getenv("FF_INSECURE_SSL"))) {
            System.err.println("[WARN] FF_INSECURE_SSL=true - certificate validation is DISABLED. " +
                    "Use only to diagnose the SSLHandshakeException, not for normal runs.");
            builder.sslContext(trustAllSslContext());
        }

        return builder.build();
    }

    private static SSLContext trustAllSslContext() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build insecure SSLContext", e);
        }
    }
}
