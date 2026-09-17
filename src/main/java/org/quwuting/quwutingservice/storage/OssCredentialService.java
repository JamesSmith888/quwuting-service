package org.quwuting.quwutingservice.storage;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * OSS 凭证解析（2026-09-17，按阿里云最佳实践双模式）。
 * <p>
 * <b>instance-role 模式（生产推荐）</b>：ECS 绑定实例角色后，后端从实例元数据端点
 * （http://100.100.100.200/latest/meta-data/ram/security-credentials/{role}）取
 * STS 临时凭证（AccessKeyId/AccessKeySecret/SecurityToken + Expiration）——
 * 无长期 AK/SK 存在，凭证由阿里云自动轮转，泄漏面为零。临时凭证临近过期
 * （&lt;5min）时同步刷新（低 QPS 场景无需后台线程）。
 * <p>
 * <b>ak 模式（本地开发兜底）</b>：直接使用配置的 RAM 子账号 AK/SK（无 SecurityToken）。
 * <p>
 * PostObject/PUT 使用 STS 凭证时须携带 x-oss-security-token（表单字段或头），
 * 由调用方按 {@link OssCredentials#securityToken()} 是否为空决定。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OssCredentialService {

    /** ECS 实例元数据端点（link-local，仅实例内可达） */
    private static final String METADATA_BASE = "http://100.100.100.200/latest/meta-data/ram/security-credentials/";
    /** 临时凭证提前刷新窗口（秒）：剩余 &lt;5min 即刷新，容忍元数据端点瞬时抖动 */
    private static final long REFRESH_AHEAD_SECONDS = 300;

    private final StorageProviderProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    /** resolved 凭证（securityToken 仅实例角色模式非空；expiration 仅供刷新判据） */
    public record OssCredentials(String accessKeyId, String accessKeySecret,
                                 String securityToken, Instant expiration) {
    }

    private volatile OssCredentials cached;

    /** 解析当前可用凭证（ak 模式直返配置；实例角色模式带缓存与提前刷新） */
    public OssCredentials resolve() {
        StorageProviderProperties.Oss oss = props.oss();
        if (oss == null || !oss.isInstanceRole()) {
            return new OssCredentials(oss != null ? oss.accessKeyId() : null,
                    oss != null ? oss.accessKeySecret() : null, null, null);
        }
        OssCredentials c = cached;
        if (isFresh(c)) {
            return c;
        }
        synchronized (this) {
            c = cached;
            if (isFresh(c)) {
                return c;
            }
            c = fetchFromMetadata(oss.instanceRoleName());
            cached = c;
            return c;
        }
    }

    private boolean isFresh(OssCredentials c) {
        return c != null && c.expiration() != null
                && c.expiration().isAfter(Instant.now().plusSeconds(REFRESH_AHEAD_SECONDS));
    }

    /** 拉取实例角色临时凭证（Code!=Success / 网络失败均视为配置性错误抛出） */
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
                throw new IllegalStateException("实例角色凭证响应异常 Code="
                        + node.path("Code").asText());
            }
            String ak = node.path("AccessKeyId").asText();
            String sk = node.path("AccessKeySecret").asText();
            String token = node.path("SecurityToken").asText();
            Instant expiration = Instant.parse(node.path("Expiration").asText());
            if (ak.isBlank() || sk.isBlank() || token.isBlank()) {
                throw new IllegalStateException("实例角色凭证字段缺失");
            }
            log.info("[oss-credential] refreshed instance-role credentials, expiresAt={}", expiration);
            return new OssCredentials(ak, sk, token, expiration);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("实例角色凭证拉取异常（实例外环境请用 credential-mode=ak）", e);
        }
    }
}
