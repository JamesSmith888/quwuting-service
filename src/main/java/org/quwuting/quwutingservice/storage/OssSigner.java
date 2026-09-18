package org.quwuting.quwutingservice.storage;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * 阿里云签名原语（JDK 内置，零第三方依赖；OSS storage 包内共用）。
 * <ul>
 *   <li>{@link #base64HmacSha1}：OSS PostObject policy 签名、STS RPC 签名共用；</li>
 *   <li>{@link #percentEncode}：阿里云 RPC 风格 API（STS）参数编码规则
 *       （RFC3986：除 A-Za-z0-9-_.~ 外一律百分号编码，空格 → %20）。</li>
 * </ul>
 */
final class OssSigner {

    private OssSigner() {
    }

    /** HmacSHA1 → Base64（阿里云 AK 签名通用；STS 场景 secret 需带尾随 "&"） */
    static String base64HmacSha1(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("阿里云签名失败", e);
        }
    }

    /** 阿里云 RPC API 参数百分号编码（URLEncoder 后修正 + / * / ~ 三处差异） */
    static String percentEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
                    .replace("+", "%20")
                    .replace("*", "%2A")
                    .replace("%7E", "~");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("编码失败", e);
        }
    }
}
