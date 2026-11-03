package com.deepseek.dafeiyu.chat;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Android 4.4 / 5.x / 6.x 的 HTTPS 兼容补丁。
 *
 * 两个真实问题：
 * 1) API 19 的 HttpsURLConnection 默认只启用 TLSv1.0，握手直接被只支持 TLS1.2 的服务器拒绝。
 * 2) 4.4 ~ 6.x 的系统 CA 库里没有 ISRG Root X1（2015 年才签发），
 *    所以任何 Let's Encrypt 证书都会 "Trust anchor for certification path not found"。
 *
 * 做法：只对 API < 24 生效。
 *  - 低版本强制 TLSv1.2（并补 SNI，4.4 用自定义 SocketFactory 时 SNI 会丢）
 *  - 信任校验先走系统 TrustManager，失败后用 PKIX 把链补到内置的根证书再验一次
 *    （仍然做完整链路 + 有效期校验，不是 trust-all）
 */
final class TlsCompat {
    private static final String TAG = "DafeiyuTls";

    /** 内置根证书（PEM，放在 assets/tls/），用于补 4.4 时代缺失的信任锚。 */
    private static final String[] EXTRA_ROOTS = new String[]{
            "tls/isrgrootx1.pem",
            "tls/isrg-root-x2.pem"
    };

    private static List<X509Certificate> extraRoots;
    private static SSLSocketFactory patchFactory;

    private TlsCompat() {
    }

    static boolean needsPatch() {
        return Build.VERSION.SDK_INT < 24;
    }

    static synchronized void prepare(Context context, HttpURLConnection conn) {
        if (!(conn instanceof HttpsURLConnection)) return;
        if (!needsPatch()) return;
        try {
            if (patchFactory == null) {
                SSLContext ssl = SSLContext.getInstance("TLS");
                ssl.init(null, new TrustManager[]{new ExtraTrustManager(context)}, null);
                SSLSocketFactory base = ssl.getSocketFactory();
                patchFactory = Build.VERSION.SDK_INT <= 20 ? new ForceTls12Factory(base) : base;
                Log.i(TAG, "TLS patch active, extra roots=" + roots(context).size());
            }
            ((HttpsURLConnection) conn).setSSLSocketFactory(patchFactory);
        } catch (Throwable t) {
            Log.w(TAG, "TLS patch failed: " + t);
        }
    }

    private static synchronized List<X509Certificate> roots(Context context) {
        if (extraRoots != null) return extraRoots;
        List<X509Certificate> out = new ArrayList<>();
        for (String name : EXTRA_ROOTS) {
            InputStream is = null;
            try {
                is = context.getAssets().open(name);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                out.add((X509Certificate) cf.generateCertificate(
                        new ByteArrayInputStream(bos.toByteArray())));
            } catch (Throwable t) {
                Log.w(TAG, "extra root " + name + " skipped: " + t);
            } finally {
                if (is != null) {
                    try {
                        is.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        extraRoots = out;
        return out;
    }

    /** 系统 TrustManager + PKIX 兜底校验。 */
    private static final class ExtraTrustManager implements X509TrustManager {
        private final Context context;
        private final X509TrustManager system;
        private final Set<TrustAnchor> anchors;

        ExtraTrustManager(Context context) throws Exception {
            this.context = context.getApplicationContext() == null
                    ? context : context.getApplicationContext();
            TrustManagerFactory tmf = TrustManagerFactory.getInstance("X509");
            tmf.init((KeyStore) null);
            X509TrustManager found = null;
            Set<TrustAnchor> pool = new HashSet<>();
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    found = (X509TrustManager) tm;
                    for (X509Certificate ca : found.getAcceptedIssuers()) {
                        pool.add(new TrustAnchor(ca, null));
                    }
                }
            }
            if (found == null) throw new CertificateException("no system trust manager");
            this.system = found;
            this.anchors = pool;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // 客户端不做校验
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            try {
                system.checkServerTrusted(chain, authType);
                return;
            } catch (CertificateException ignored) {
                // 系统库不认这张链，走下面的补锚校验
            }
            Set<TrustAnchor> pool = new HashSet<>(anchors);
            for (X509Certificate ca : roots(context)) {
                pool.add(new TrustAnchor(ca, null));
            }
            try {
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                CertPath path = cf.generateCertPath(Arrays.asList(chain));
                PKIXParameters params = new PKIXParameters(pool);
                params.setRevocationEnabled(false);
                CertPathValidator.getInstance("PKIX").validate(path, params);
            } catch (Exception e) {
                throw new CertificateException("证书链校验失败: " + e);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return system.getAcceptedIssuers();
        }
    }

    /** API 19 强制 TLSv1.2，并补回 SNI。 */
    private static final class ForceTls12Factory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        ForceTls12Factory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        private Socket tune(Socket socket, String host) {
            if (socket instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) socket;
                try {
                    ssl.setEnabledProtocols(new String[]{"TLSv1.2"});
                } catch (Throwable ignored) {
                }
                if (host != null) {
                    try {
                        ssl.getClass().getMethod("setHost", String.class).invoke(ssl, host);
                    } catch (Throwable ignored) {
                        // 隐藏 API，拿不到就算了；Android 4.2+ 多数情况会自己带 SNI
                    }
                }
            }
            return socket;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose)
                throws IOException {
            return tune(delegate.createSocket(s, host, port, autoClose), host);
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return tune(delegate.createSocket(host, port), host);
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
                throws IOException {
            return tune(delegate.createSocket(host, port, localHost, localPort), host);
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return tune(delegate.createSocket(host, port), host.getHostName());
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress,
                                   int localPort) throws IOException {
            return tune(delegate.createSocket(address, port, localAddress, localPort),
                    address.getHostName());
        }
    }
}
