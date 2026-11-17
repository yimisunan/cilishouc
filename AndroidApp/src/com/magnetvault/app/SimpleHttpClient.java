package com.magnetvault.app;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 极简 HTTP/1.1 客户端（原因为何不用 HttpURLConnection：
 * Android 的实现只允许 OPTIONS/GET/HEAD/POST/PUT/DELETE/TRACE/PATCH，
 * 调用 PROPFIND 会直接抛 ProtocolException，而 WebDAV 离不开它）。
 *
 * 只支持我们需要的子集：TLS、Basic 认证、Content-Length、chunked、Connection: close。
 */
public class SimpleHttpClient {

    public static class Response {
        public int code;
        public final Map<String, String> headers = new LinkedHashMap<>();
        public byte[] body = new byte[0];

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.US));
        }
    }

    public static class HttpFailure extends Exception {
        public HttpFailure(String msg) {
            super(msg);
        }
    }

    private final String host;
    private final int port;
    private final String basePath;   // 以 / 开头、以 / 结尾
    private final String authHeader;
    private final int timeoutMs;

    public SimpleHttpClient(String baseUrl, String user, String password, int timeoutMs)
            throws HttpFailure {
        try {
            java.net.URL u = new java.net.URL(baseUrl);
            if (!"https".equalsIgnoreCase(u.getProtocol())) {
                throw new HttpFailure("只支持 https 地址");
            }
            this.host = u.getHost();
            this.port = u.getPort() > 0 ? u.getPort() : 443;
            String p = u.getPath();
            if (p == null || p.length() == 0) {
                p = "/";
            }
            if (!p.endsWith("/")) {
                p = p + "/";
            }
            this.basePath = p;
            this.authHeader = "Basic " + Base64.encodeToString(
                    (user + ":" + password).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            this.timeoutMs = timeoutMs;
        } catch (HttpFailure e) {
            throw e;
        } catch (Exception e) {
            throw new HttpFailure("服务器地址无法解析：" + e.getClass().getSimpleName());
        }
    }

    public String basePath() {
        return basePath;
    }

    /** path 必须是绝对路径（以 / 开头），可以包含已编码的 %XX。 */
    public Response execute(String method, String path, Map<String, String> extraHeaders, byte[] body)
            throws IOException, SSLException, HttpFailure {
        SSLSocket socket = null;
        try {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            socket = (SSLSocket) factory.createSocket();
            SSLParameters params = socket.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(params);
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.startHandshake();

            StringBuilder req = new StringBuilder();
            req.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(host).append("\r\n");
            req.append("Authorization: ").append(authHeader).append("\r\n");
            req.append("User-Agent: MagnetVault/1.1 (Android)\r\n");
            req.append("Accept: */*\r\n");
            req.append("Connection: close\r\n");
            if (extraHeaders != null) {
                for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                    req.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
                }
            }
            int len = body == null ? 0 : body.length;
            if (body != null) {
                req.append("Content-Length: ").append(len).append("\r\n");
            }
            req.append("\r\n");

            OutputStream os = socket.getOutputStream();
            os.write(req.toString().getBytes(StandardCharsets.UTF_8));
            if (body != null) {
                os.write(body);
            }
            os.flush();

            InputStream in = socket.getInputStream();
            return readResponse(in);
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private Response readResponse(InputStream in) throws IOException, HttpFailure {
        Response resp = new Response();
        String statusLine = readLine(in);
        if (statusLine == null) {
            throw new HttpFailure("服务器没有返回任何数据");
        }
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2) {
            throw new HttpFailure("无效的状态行：" + statusLine);
        }
        resp.code = Integer.parseInt(parts[1].trim());
        while (true) {
            String line = readLine(in);
            if (line == null || line.length() == 0) {
                break;
            }
            int idx = line.indexOf(':');
            if (idx > 0) {
                resp.headers.put(line.substring(0, idx).trim().toLowerCase(Locale.US),
                        line.substring(idx + 1).trim());
            }
        }

        String te = resp.header("transfer-encoding");
        String cl = resp.header("content-length");
        if (te != null && te.toLowerCase(Locale.US).contains("chunked")) {
            resp.body = readChunked(in);
        } else if (cl != null) {
            int n;
            try {
                n = Integer.parseInt(cl.trim());
            } catch (NumberFormatException e) {
                n = 0;
            }
            resp.body = readFixed(in, n);
        } else {
            resp.body = readToEof(in);
        }
        return resp;
    }

    private byte[] readFixed(InputStream in, int len) throws IOException {
        if (len <= 0) {
            return new byte[0];
        }
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) {
                break;
            }
            off += n;
        }
        if (off == len) {
            return buf;
        }
        byte[] out = new byte[off];
        System.arraycopy(buf, 0, out, 0, off);
        return out;
    }

    private byte[] readToEof(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private byte[] readChunked(InputStream in) throws IOException, HttpFailure {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) {
                break;
            }
            sizeLine = sizeLine.trim();
            int semi = sizeLine.indexOf(';');
            if (semi >= 0) {
                sizeLine = sizeLine.substring(0, semi);
            }
            if (sizeLine.length() == 0) {
                continue;
            }
            int size;
            try {
                size = Integer.parseInt(sizeLine, 16);
            } catch (NumberFormatException e) {
                throw new HttpFailure("chunked 长度无法解析：" + sizeLine);
            }
            if (size == 0) {
                readLine(in); // 末尾空行
                break;
            }
            bos.write(readFixed(in, size));
            readLine(in);    // chunk 结尾 CRLF
        }
        return bos.toByteArray();
    }

    /** 逐字节读一行（性能足够，响应头很小）。 */
    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                bos.write(c);
            }
        }
        if (c < 0 && bos.size() == 0) {
            return null;
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    // ==================== 错误归类 ====================

    public static String describeNetworkError(Exception e) {
        if (e instanceof UnknownHostException) {
            return "无法解析服务器域名：请检查网络或地址";
        }
        if (e instanceof SocketTimeoutException) {
            return "连接超时：网络较慢或被拦截";
        }
        if (e instanceof SSLException) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (m.contains("Trust anchor") || m.contains("certificate")
                    || m.contains("Certificate")) {
                return "证书校验失败：" + m + "\n（如开启了抓包/代理，请关闭）";
            }
            return "TLS 握手失败：" + m;
        }
        if (e instanceof java.net.ConnectException) {
            return "无法建立连接：网络不可用或被拦截";
        }
        return "网络错误：" + e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : "（" + e.getMessage() + "）");
    }

    public static Map<String, String> headers(String... kv) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }
}