package com.magnetvault.app;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * WebDAV 客户端（坚果云实测行为，2026-10-25）：
 * · PROPFIND 必须用自写 HTTP 客户端（Android 的 HttpURLConnection 拒绝该方法）；
 * · 服务器不返回 getetag，且 If-None-Match:* 被忽略（覆盖同样返回 204）→ 覆盖保护只能客户端先查存在性；
 * · MKCOL 目录已存在返回 405；不存在返回 404。
 */
public class WebDavClient {

    public static final String DEFAULT_BASE = "https://dav.jianguoyun.com/dav/";
    public static final String DEFAULT_DIR = "磁力收藏夹";

    private static final String PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<d:propfind xmlns:d=\"DAV:\"><d:prop>"
                    + "<d:resourcetype/><d:getcontentlength/><d:getlastmodified/>"
                    + "</d:prop></d:propfind>";

    public static class DavException extends Exception {
        public final int status;      // HTTP 状态码；-1 表示网络/配置层错误
        public final String userMessage;

        public DavException(int status, String userMessage) {
            super(userMessage);
            this.status = status;
            this.userMessage = userMessage;
        }
    }

    public static class RemoteFile {
        public String name = "";
        public String href = "";
        public long size;
        public long lastModified;
    }

    private final String baseUrl;
    private final SimpleHttpClient http;
    private final String initError;

    public WebDavClient(String baseUrl, String user, String password, int timeoutMs) {
        this.baseUrl = normalizeBase(baseUrl);
        SimpleHttpClient c = null;
        String err = null;
        try {
            c = new SimpleHttpClient(this.baseUrl, user, password, timeoutMs);
        } catch (SimpleHttpClient.HttpFailure e) {
            err = e.getMessage();
        }
        this.http = c;
        this.initError = err;
    }

    public static String normalizeBase(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.length() == 0) {
            s = DEFAULT_BASE;
        }
        if (!s.endsWith("/")) {
            s = s + "/";
        }
        return s;
    }

    public String baseUrl() {
        return baseUrl;
    }

    // ==================== 内部请求 ====================

    private SimpleHttpClient.Response exec(String method, String relativePath,
                                           Map<String, String> headers, byte[] body)
            throws DavException {
        if (http == null) {
            throw new DavException(-1, initError == null ? "客户端初始化失败" : initError);
        }
        String path = http.basePath() + relativePath;
        try {
            return http.execute(method, path, headers, body);
        } catch (Exception e) {
            throw new DavException(-1, SimpleHttpClient.describeNetworkError(e));
        }
    }

    private DavException mapStatus(int code, String action) {
        String msg;
        if (code == 401) {
            msg = "认证失败（401）：账号或应用密码不正确。\n必须使用坚果云「安全选项 → 添加应用」生成的第三方应用密码，不是登录密码。";
        } else if (code == 403) {
            msg = "没有权限（403）：账号可能未开通 WebDAV，或被限制访问。";
        } else if (code == 404) {
            msg = "路径不存在（404）：检查服务器地址是否以 /dav/ 结尾。";
        } else if (code == 405) {
            msg = "方法不允许（405）：目录可能已存在，或服务器拒绝该操作。";
        } else if (code == 507) {
            msg = "空间不足（507）：坚果云额度已用满，请清理后重试。";
        } else if (code == 409) {
            msg = "父目录不存在（409）：请先创建目录。";
        } else if (code == 423) {
            msg = "资源被锁定（423）：稍后重试。";
        } else if (code >= 500) {
            msg = "服务器错误（" + code + "）：稍后重试。";
        } else {
            msg = action + "失败（HTTP " + code + "）";
        }
        return new DavException(code, msg);
    }

    private byte[] propfind(String relativePath, String depth) throws DavException {
        SimpleHttpClient.Response r = exec("PROPFIND", relativePath,
                SimpleHttpClient.headers("Depth", depth,
                        "Content-Type", "application/xml; charset=utf-8"),
                PROPFIND_BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (r.code == 207 || r.code == 200) {
            return r.body;
        }
        throw mapStatus(r.code, "读取目录");
    }

    // ==================== 对外操作 ====================

    public void testRoot() throws DavException {
        propfind("", "0");
    }

    public void ensureCollection(String dirName) throws DavException {
        SimpleHttpClient.Response r = exec("MKCOL", encodeSegment(dirName) + "/", null, null);
        if (r.code == 201 || r.code == 200 || r.code == 405 || r.code == 301) {
            return;
        }
        throw mapStatus(r.code, "创建目录");
    }

    public List<RemoteFile> list(String dirName) throws DavException {
        byte[] xml = propfind(encodeSegment(dirName) + "/", "1");
        List<RemoteFile> out = new ArrayList<>();
        try {
            parseMultiStatus(new ByteArrayInputStream(xml), out);
        } catch (Exception e) {
            throw new DavException(-1, "解析目录列表失败：" + e.getClass().getSimpleName());
        }
        return out;
    }

    public boolean exists(String dirName, String fileName) throws DavException {
        SimpleHttpClient.Response r = exec("PROPFIND",
                encodeSegment(dirName) + "/" + encodeSegment(fileName),
                SimpleHttpClient.headers("Depth", "0",
                        "Content-Type", "application/xml; charset=utf-8"),
                PROPFIND_BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (r.code == 404) {
            return false;
        }
        if (r.code == 207 || r.code == 200) {
            return true;
        }
        throw mapStatus(r.code, "检查文件是否存在");
    }

    public int put(String dirName, String fileName, byte[] data) throws DavException {
        SimpleHttpClient.Response r = exec("PUT",
                encodeSegment(dirName) + "/" + encodeSegment(fileName),
                SimpleHttpClient.headers("Content-Type", "application/octet-stream"),
                data);
        if (r.code == 201 || r.code == 204 || r.code == 200) {
            return r.code;
        }
        throw mapStatus(r.code, "上传");
    }

    public byte[] get(String dirName, String fileName) throws DavException {
        SimpleHttpClient.Response r = exec("GET",
                encodeSegment(dirName) + "/" + encodeSegment(fileName), null, null);
        if (r.code == 200) {
            return r.body;
        }
        throw mapStatus(r.code, "下载");
    }

    public void delete(String dirName, String fileName) throws DavException {
        SimpleHttpClient.Response r = exec("DELETE",
                encodeSegment(dirName) + "/" + encodeSegment(fileName), null, null);
        if (r.code == 204 || r.code == 200 || r.code == 404 || r.code == 202) {
            return;
        }
        throw mapStatus(r.code, "删除");
    }

    // ==================== 解析 ====================

    private void parseMultiStatus(InputStream in, List<RemoteFile> out) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setInput(in, "UTF-8");
        RemoteFile cur = null;
        String tag;
        int ev;
        while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                tag = local(p.getName());
                if ("response".equals(tag)) {
                    cur = new RemoteFile();
                } else if (cur != null) {
                    if ("href".equals(tag)) {
                        cur.href = p.nextText().trim();
                    } else if ("getcontentlength".equals(tag)) {
                        cur.size = parseLong(p.nextText());
                    } else if ("getlastmodified".equals(tag)) {
                        cur.lastModified = parseHttpDate(p.nextText());
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                tag = local(p.getName());
                if ("response".equals(tag) && cur != null) {
                    if (!cur.href.endsWith("/")) {
                        cur.name = lastSegmentDecoded(cur.href);
                        if (cur.name.length() > 0) {
                            out.add(cur);
                        }
                    }
                    cur = null;
                }
            }
        }
    }

    private static String local(String name) {
        if (name == null) {
            return "";
        }
        int i = name.indexOf(':');
        return i >= 0 ? name.substring(i + 1) : name;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static long parseHttpDate(String s) {
        try {
            return new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                    .parse(s.trim()).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String lastSegmentDecoded(String href) {
        String h = href;
        if (h.endsWith("/")) {
            h = h.substring(0, h.length() - 1);
        }
        int idx = h.lastIndexOf('/');
        String seg = idx >= 0 ? h.substring(idx + 1) : h;
        try {
            return java.net.URLDecoder.decode(seg, "UTF-8");
        } catch (Exception e) {
            return seg;
        }
    }

    public static String encodeSegment(String seg) {
        try {
            return java.net.URLEncoder.encode(seg, "UTF-8")
                    .replace("+", "%20")
                    .replace("%2F", "/");
        } catch (Exception e) {
            return seg;
        }
    }

    private static IOException unused() { // 保留 import 兼容
        return null;
    }
}