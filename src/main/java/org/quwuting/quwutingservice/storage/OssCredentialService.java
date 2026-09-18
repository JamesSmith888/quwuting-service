package org.quwuting.quwutingservice.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * OSS 凭证解析（2026-09-18 统一模型：<b>本地与生产同一套，永远 STS 临时凭证</b>）。
 * <p>
 * 单一解析路径，两条来源（自动择一，语义等价，都产出临时 AK + SecurityToken）：
 * <ol>
 *   <li><b>ECS 实例角色</b>（生产主路径）：从实例元数据端点
 *       （http://100.100.100.200/latest/meta-data/ram/security-credentials/{role}）取
 *       STS 临时凭证——无长期 AK 存在，由阿里云自动轮转；<br>
 *       元数据不可达（非 ECS 环境：本机开发）时置 5 分钟抑制窗口后走第 2 条，
 *       不重复拖慢每次凭证请求；</li>
 *   <li><b>AssumeRole 兜底</b>（非 ECS 环境）：用仅具备 {@code sts:AssumeRole}
 *       权限（无任何 OSS 权限）的 RAM 子账号 AK 调 STS AssumeRole（V1 RPC 签名，零依赖）
 *       换取同一角色下的临时凭证。</li>
 * </ol>
 * 凭证临近过期（&lt;5min）同步刷新——上传凭证签发是低 QPS 路径，无需后台刷新线程。
 * <p>
 * 临时凭证用于 PostObject 直传时须携带 x-oss-security-token（表单字段），
 * 由 {@link StorageService} 写入 policy 条件与响应字段。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OssCredentialService {

    /** ECS 实例元数据端点（link-local，仅实例内可达） */
    private static final String METADATA_BASE = "http://100.100.100.200/latest/meta-data/ram/security-credentials/";
    /** 元数据不可达后的抑制窗口（秒）：避免本机环境每次凭证请求都等元数据超时 */
    private static final long METADATA_SUPPRESS_SECONDS = 300;
    /** 临时凭证提前刷新窗口（秒） */
    private static final long REFRESH_AHEAD_SECONDS = 300;
    /** AssumeRole 临时凭证有效期（秒，1h） */
    private static final int ASSUME_ROLE_DURATION = 3600;

    private final StorageProviderProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    /** 解析结果（securityToken 恒非空——两条来源都是 STS） */
    public record OssCredentials(String accessKeyId, String accessKeySecret,
                                 String securityToken, Instant expiration) {
    }

    private volatile OssCredentials cached;
    private volatile Instant metadataSuppressedUntil = Instant.EPOCH;

    /** 解析当前可用临时凭证 */
    public OssCredentials resolve() {
        OssCredentials c = cached;
        if (isFresh(c)) {
            return c;
        }
        synchronized (this) {
            c = cached;
            if (isFresh(c)) {
                return c;
            }
            StorageProviderProperties.Oss oss = props.oss();
            if (oss != null && oss.hasInstanceRole() && Instant.now().isAfter(metadataSuppressedUntil)) {
                try {
                    c = fetchFromMetadata(oss.instanceRoleName());
                    cached = c;
                    return c;
                } catch (IllegalStateException e) {
                    metadataSuppressedUntil = Instant.now().plusSeconds(METADATA_SUPPRESS_SECONDS);
                    log.warn("[oss-credential] 实例元数据不可用（{}s 内改用 AssumeRole）：{}",
                            METADATA_SUPPRESS_SECONDS, e.getMessage());
                }
            }
            if (oss != null && oss.hasAssumeRole()) {
                c = assumeRole(oss);
                cached = c;
                return c;
            }
            throw new IllegalStateException(
                    "OSS 凭证不可用：需配置 storage.oss.instance-role-name（ECS 实例角色）或"
                            + " assume-role-arn + assume-role-access-key-id/secret（非 ECS 环境）");
        }
    }

    private boolean isFresh(OssCredentials c) {
        return c != null && c.expiration() != null
                && c.expiration().isAfter(Instant.now().plusSeconds(REFRESH_AHEAD_SECONDS));
    }

    // ------------------------------------------------------------------
    // 来源一：ECS 实例角色元数据端点
    // ------------------------------------------------------------------

    private OssCredentials fetchFromMetadata(String roleName) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(METADATA_BASE + roleName))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("实例角色凭证拉取失败 HTTP " + resp.statusCode()
                        + "（检查 ECS 是否已绑定角色 " + roleName + "）");
            }
            JsonNode node = objectMapper.readTree(resp.body());
            if (!"Success".equals(node.path("Code").asText())) {
                throw new IllegalStateException("实例角色凭证响应异常 Code=" + node.path("Code").asText());
            }
            OssCredentials creds = readCredentials(node);
            log.info("[oss-credential] 实例角色凭证已刷新，expiresAt={}", creds.expiration());
            return creds;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("实例角色凭证拉取异常（非 ECS 环境请用 AssumeRole）", e);
        }
    }

    // ------------------------------------------------------------------
    // 来源二：STS AssumeRole（非 ECS 环境；AK 仅具 sts:AssumeRole 权限）
    // ------------------------------------------------------------------

    private OssCredentials assumeRole(StorageProviderProperties.Oss oss) {
        try {
            Map<String, String> params = new TreeMap<>();
            params.put("Action", "AssumeRole");
            params.put("Version", "2015-04-01");
            params.put("Format", "JSON");
            params.put("AccessKeyId", oss.assumeRoleAccessKeyId());
            params.put("SignatureMethod", "HMAC-SHA1");
            params.put("SignatureNonce", UUID.randomUUID().toString());
            params.put("SignatureVersion", "1.0");
            params.put("Timestamp", OffsetDateTime.now(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")));
            params.put("RoleArn", oss.assumeRoleArn());
            params.put("RoleSessionName", sessionName(oss));
            params.put("DurationSeconds", String.valueOf(ASSUME_ROLE_DURATION));

            String canonical = params.entrySet().stream()
                    .map(e -> OssSigner.percentEncode(e.getKey()) + "=" + OssSigner.percentEncode(e.getValue()))
                    .collect(Collectors.joining("&"));
            String stringToSign = "GET" + "&" + OssSigner.percentEncode("/")
                    + "&" + OssSigner.percentEncode(canonical);
            // STS RPC 签名密钥带尾随 "&"
            String signature = OssSigner.base64HmacSha1(
                    oss.assumeRoleAccessKeySecret() + "&", stringToSign);
            URI uri = URI.create(oss.stsEndpoint() + "?" + canonical
                    + "&Signature=" + OssSigner.percentEncode(signature));

            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(resp.body());
            if (resp.statusCode() != 200 || !root.path("Credentials").isObject()) {
                throw new IllegalStateException("AssumeRole 失败 HTTP " + resp.statusCode()
                        + " " + root.path("Message").asText() + "（Code=" + root.path("Code").asText() + "）");
            }
            OssCredentials creds = readCredentials(root.path("Credentials"));
            log.info("[oss-credential] AssumeRole 凭证已换取，expiresAt={}", creds.expiration());
            return creds;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "AssumeRole 调用异常（检查 AK 是否仅授予 sts:AssumeRole、角色 ARN 是否正确）", e);
        }
    }

    private static OssCredentials readCredentials(JsonNode node) {
        String ak = node.path("AccessKeyId").asText();
        String sk = node.path("AccessKeySecret").asText();
        String token = node.path("SecurityToken").asText();
        if (ak.isBlank() || sk.isBlank() || token.isBlank()) {
            throw new IllegalStateException("临时凭证字段缺失");
        }
        Instant expiration = Instant.parse(node.path("Expiration").asText());
        return new OssCredentials(ak, sk, token, expiration);
    }

    private static String sessionName(StorageProviderProperties.Oss oss) {
        if (oss.assumeRoleSessionName() != null && !oss.assumeRoleSessionName().isBlank()) {
            return oss.assumeRoleSessionName();
        }
        return "qwt-oss-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
