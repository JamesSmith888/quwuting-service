package org.quwuting.quwutingservice.security;

import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 轻量 JWT 工具（HS256），无外部依赖。
 * Token 结构：base64url(header).base64url(payload).base64url(signature)
 */
@Component
public class JwtUtil {

    private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
    private static final String HMAC_ALGO = "HmacSHA256";

    private final byte[] secretBytes;
    private final long expiryMs;

    public JwtUtil(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiry-days:7}") int expiryDays
    ) {
        this.secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        this.expiryMs = expiryDays * 24L * 3600 * 1000;
    }

    /** 生成 token，payload 包含 sub（userId）、role、exp（毫秒时间戳） */
    public String generateToken(Long userId, UserRole role) {
        String header = base64Url(HEADER_JSON.getBytes(StandardCharsets.UTF_8));
        long exp = System.currentTimeMillis() + expiryMs;
        String payloadJson = "{\"sub\":" + userId + ",\"role\":\"" + role.name() + "\",\"exp\":" + exp + "}";
        String payload = base64Url(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signature = sign(header + "." + payload);
        return header + "." + payload + "." + signature;
    }

    /** 校验 token 并返回 userId；签名无效或过期时抛出 BusinessException(1002) */
    public Long validateToken(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new BusinessException(1002, "无效的token格式");
        }

        String expectedSig = sign(parts[0] + "." + parts[1]);
        if (!expectedSig.equals(parts[2])) {
            throw new BusinessException(1002, "token签名无效");
        }

        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);

        // 解析 exp
        long exp = extractLong(payloadJson, "exp");
        if (System.currentTimeMillis() > exp) {
            throw new BusinessException(1002, "token已过期");
        }

        // 解析 sub（userId）
        return extractLong(payloadJson, "sub");
    }

    // ===== private =====

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secretBytes, HMAC_ALGO));
            byte[] sig = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return base64Url(sig);
        } catch (Exception e) {
            throw new RuntimeException("JWT签名失败", e);
        }
    }

    private static String base64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    /** 从简单 JSON 中提取 long 值（避免引入额外 JSON 解析依赖） */
    private static long extractLong(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start < 0) {
            throw new BusinessException(1002, "token缺少必要字段: " + key);
        }
        start += search.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        return Long.parseLong(json.substring(start, end));
    }
}
