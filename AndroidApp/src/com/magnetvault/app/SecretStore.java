package com.magnetvault.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.KeyStore;
import java.nio.charset.StandardCharsets;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

/**
 * 用 Android Keystore 里不可导出的 AES 密钥加密本地敏感字符串（如"记住的备份密码"）。
 * 注意：该密钥不可导出，换设备后必然解不开 —— 这是设计，不是缺陷。
 */
public final class SecretStore {

    private static final String KS = "AndroidKeyStore";
    private static final String ALIAS = "mv_local_secret_v1";
    private static final String PREFS = "mv_secret";
    private static final int IV_LEN = 12;

    /** 最近一次失败原因，供界面显示排查。 */
    public static volatile String lastError = "";

    private SecretStore() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean has(Context ctx, String key) {
        String blob = prefs(ctx).getString(key, null);
        return blob != null && blob.length() > 0;
    }

    public static void clear(Context ctx, String key) {
        prefs(ctx).edit().remove(key).apply();
    }

    public static boolean put(Context ctx, String key, String plain) {
        try {
            // 注意：AndroidKeyStore 在 setRandomizedEncryptionRequired(true) 下
            // 不允许外部指定 IV，必须用 Cipher 自己生成的 IV，再连同密文一起保存。
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[1 + iv.length + ct.length];
            out[0] = 1;
            System.arraycopy(iv, 0, out, 1, iv.length);
            System.arraycopy(ct, 0, out, 1 + iv.length, ct.length);
            prefs(ctx).edit().putString(key, Base64.encodeToString(out, Base64.NO_WRAP)).apply();
            return true;
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : "：" + e.getMessage());
            return false;
        }
    }

    /** 失败（密钥失效/数据损坏）返回 null，调用方应清除并提示重新输入。 */
    public static String get(Context ctx, String key) {
        String blob = prefs(ctx).getString(key, null);
        if (blob == null || blob.length() == 0) {
            return null;
        }
        try {
            byte[] data = Base64.decode(blob, Base64.NO_WRAP);
            if (data.length < 1 + IV_LEN + 16) {
                return null;
            }
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(data, 1, iv, 0, IV_LEN);
            byte[] ct = new byte[data.length - 1 - IV_LEN];
            System.arraycopy(data, 1 + IV_LEN, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(KS);
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            return (SecretKey) ks.getKey(ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}