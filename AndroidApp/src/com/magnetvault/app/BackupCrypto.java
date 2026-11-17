package com.magnetvault.app;

import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 客户端加密：PBKDF2-HMAC-SHA256 → AES-256-GCM。
 * 容器格式（.mjbf）：
 *   "MJBF"(4B) | containerVersion(1B)=1 | headerLen(1B) | headerJson(UTF-8) | ciphertext‖tag
 * AAD = headerJson 原始字节（防篡改迭代次数/盐/版本）。
 */
public final class BackupCrypto {

    public static final byte[] MAGIC = {0x4D, 0x4A, 0x42, 0x46}; // "MJBF"
    public static final int CONTAINER_VERSION = 1;
    public static final int PBKDF2_ITERATIONS = 600000;
    public static final int SUPPORTED_PAYLOAD_VERSION = 1;
    private static final int SALT_LEN = 16;
    private static final int NONCE_LEN = 12;
    private static final int TAG_BITS = 128;

    public enum Error {
        NONE, CORRUPT_FILE, WRONG_PASSWORD, UNSUPPORTED_VERSION, CRYPTO_UNAVAILABLE
    }

    public static class Result {
        public Error error = Error.NONE;
        public String message = "";
        public byte[] payload;
        public int payloadVersion;
        public long createdAt;
        public String appVersion = "";
        public int iterations;
    }

    private BackupCrypto() {
    }

    public static byte[] encrypt(char[] password, byte[] payload, String appVersion,
                                 int payloadVersion) throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_LEN];
        byte[] nonce = new byte[NONCE_LEN];
        random.nextBytes(salt);
        random.nextBytes(nonce);

        JSONObject header = new JSONObject();
        header.put("alg", "AES-256-GCM");
        header.put("kdf", "PBKDF2-HMAC-SHA256");
        header.put("kdfIterations", PBKDF2_ITERATIONS);
        header.put("salt", Base64.encodeToString(salt, Base64.NO_WRAP));
        header.put("nonce", Base64.encodeToString(nonce, Base64.NO_WRAP));
        header.put("payloadFormat", "magnetvault-backup-json");
        header.put("payloadVersion", payloadVersion);
        header.put("createdAt", System.currentTimeMillis());
        header.put("appVersion", appVersion);

        byte[] headerBytes = header.toString().getBytes(StandardCharsets.UTF_8);
        if (headerBytes.length > 255) {
            throw new IllegalStateException("header too long");
        }

        byte[] key = deriveKey(password, salt, PBKDF2_ITERATIONS);
        byte[] cipherText;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(headerBytes);
            cipherText = cipher.doFinal(payload);
        } finally {
            Arrays.fill(key, (byte) 0);
        }

        byte[] out = new byte[6 + headerBytes.length + cipherText.length];
        System.arraycopy(MAGIC, 0, out, 0, 4);
        out[4] = (byte) CONTAINER_VERSION;
        out[5] = (byte) headerBytes.length;
        System.arraycopy(headerBytes, 0, out, 6, headerBytes.length);
        System.arraycopy(cipherText, 0, out, 6 + headerBytes.length, cipherText.length);
        return out;
    }

    /** 解密。前 6 字节 + header 可单独用于展示元信息，不需要密码。 */
    public static Result decrypt(char[] password, byte[] container) {
        Result r = new Result();
        if (container == null || container.length < 6 + 32) {
            r.error = Error.CORRUPT_FILE;
            r.message = "文件太小，不是有效的备份文件";
            return r;
        }
        for (int i = 0; i < 4; i++) {
            if (container[i] != MAGIC[i]) {
                r.error = Error.CORRUPT_FILE;
                r.message = "文件头不匹配，不是本应用导出的备份";
                return r;
            }
        }
        int version = container[4] & 0xFF;
        if (version != CONTAINER_VERSION) {
            r.error = Error.UNSUPPORTED_VERSION;
            r.message = "备份容器版本 " + version + " 不受支持";
            return r;
        }
        int headerLen = container[5] & 0xFF;
        if (container.length < 6 + headerLen + 16) {
            r.error = Error.CORRUPT_FILE;
            r.message = "文件被截断，长度不足";
            return r;
        }
        byte[] headerBytes = new byte[headerLen];
        System.arraycopy(container, 6, headerBytes, 0, headerLen);
        byte[] cipherText = new byte[container.length - 6 - headerLen];
        System.arraycopy(container, 6 + headerLen, cipherText, 0, cipherText.length);

        JSONObject header;
        try {
            header = new JSONObject(new String(headerBytes, StandardCharsets.UTF_8));
            r.payloadVersion = header.optInt("payloadVersion", 0);
            r.createdAt = header.optLong("createdAt", 0);
            r.appVersion = header.optString("appVersion", "");
            r.iterations = header.optInt("kdfIterations", 0);
        } catch (JSONException e) {
            r.error = Error.CORRUPT_FILE;
            r.message = "备份头信息损坏";
            return r;
        }
        if (r.payloadVersion > SUPPORTED_PAYLOAD_VERSION) {
            r.error = Error.UNSUPPORTED_VERSION;
            r.message = "备份由更新版本创建（payloadVersion=" + r.payloadVersion + "），请先升级应用";
            return r;
        }
        if (r.iterations < 1000 || r.iterations > 5_000_000) {
            r.error = Error.CORRUPT_FILE;
            r.message = "备份参数异常（迭代次数 " + r.iterations + "）";
            return r;
        }

        byte[] salt;
        byte[] nonce;
        try {
            salt = Base64.decode(header.getString("salt"), Base64.NO_WRAP);
            nonce = Base64.decode(header.getString("nonce"), Base64.NO_WRAP);
        } catch (Exception e) {
            r.error = Error.CORRUPT_FILE;
            r.message = "备份参数无法解析";
            return r;
        }
        if (salt.length != SALT_LEN || nonce.length != NONCE_LEN) {
            r.error = Error.CORRUPT_FILE;
            r.message = "备份参数长度异常";
            return r;
        }

        byte[] key = null;
        try {
            key = deriveKey(password, salt, r.iterations);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(headerBytes);
            r.payload = cipher.doFinal(cipherText);
            r.error = Error.NONE;
            return r;
        } catch (Exception e) {
            String name = e.getClass().getSimpleName();
            if (name.contains("AEADBadTag") || name.contains("BadPadding")) {
                r.error = Error.WRONG_PASSWORD;
                r.message = "密码不正确，或文件内容被修改过";
            } else if (name.contains("NoSuchAlgorithm") || name.contains("InvalidKey")) {
                r.error = Error.CRYPTO_UNAVAILABLE;
                r.message = "本机加密算法不可用：" + name;
            } else {
                r.error = Error.CORRUPT_FILE;
                r.message = "解密失败（" + name + "）";
            }
            return r;
        } finally {
            if (key != null) {
                Arrays.fill(key, (byte) 0);
            }
        }
    }

    /** 只读 header，用于在输入密码前显示备份时间等信息。 */
    public static Result readHeader(byte[] container) {
        Result r = new Result();
        if (container == null || container.length < 6) {
            r.error = Error.CORRUPT_FILE;
            r.message = "文件太小";
            return r;
        }
        for (int i = 0; i < 4; i++) {
            if (container[i] != MAGIC[i]) {
                r.error = Error.CORRUPT_FILE;
                r.message = "不是本应用导出的备份文件";
                return r;
            }
        }
        int headerLen = container[5] & 0xFF;
        if (container.length < 6 + headerLen) {
            r.error = Error.CORRUPT_FILE;
            r.message = "文件被截断";
            return r;
        }
        try {
            JSONObject header = new JSONObject(new String(container, 6, headerLen,
                    StandardCharsets.UTF_8));
            r.payloadVersion = header.optInt("payloadVersion", 0);
            r.createdAt = header.optLong("createdAt", 0);
            r.appVersion = header.optString("appVersion", "");
            r.iterations = header.optInt("kdfIterations", 0);
            return r;
        } catch (Exception e) {
            r.error = Error.CORRUPT_FILE;
            r.message = "备份头信息损坏";
            return r;
        }
    }

    private static byte[] deriveKey(char[] password, byte[] salt, int iterations) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try {
            return factory.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }
}