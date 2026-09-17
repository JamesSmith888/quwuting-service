package org.quwuting.quwutingservice.storage;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

/**
 * 存储服务：签发前端直传对象存储的上传凭证（2026-09-17 起双 provider）。
 * <p>
 * 后端不接收文件流，职责仅为：
 * 1. 校验文件元信息（类型、大小——两套 provider 共用同一校验语义与默认上限）
 * 2. 生成唯一上传路径（分类前缀 + userId + UUID + 扩展名——两套 provider 路径格式一致，
 *    存量对象迁移可保持路径不变）
 * 3. 按 {@code storage.provider} 签发对应凭证：
 *    <ul>
 *      <li>supabase（默认）：projectUrl / anonKey / bucket / uploadPath / publicUrl；</li>
 *      <li>oss：PostObject 服务端签名直传四件套——policy JSON（过期 15min + $key 精确匹配
 *          + content-length-range）经 Base64 后以 AccessKeySecret 做 HmacSHA1 签名。
 *          Secret 永不出后端；policy 限定精确 key，凭证泄露也只能写那一个对象。</li>
 *    </ul>
 */
@Service
@RequiredArgsConstructor
public class StorageService {

    /** OSS PostObject policy 有效期（分钟）：覆盖弱网下「取凭证→开始上传」的间隔 */
    private static final int OSS_POLICY_TTL_MINUTES = 15;

    private final StorageProperties props;
    private final StorageProviderProperties providerProps;
    private final OssCredentialService ossCredentials;

    /**
     * 签发上传凭证。
     *
     * @param userId   当前登录用户 ID（路径隔离用）
     * @param category 文件分类（决定路径前缀）
     * @param fileName 原始文件名（提取扩展名用）
     * @param fileSize 文件大小（字节）
     * @return 前端直传所需的完整凭证（形态由 provider 决定，见 UploadTokenResponse）
     */
    public UploadTokenResponse generateUploadToken(Long userId, FileCategory category,
                                                   String fileName, long fileSize) {
        validateFile(category, fileName, fileSize);

        String ext = extractExtension(fileName);
        String uploadPath = category.getPathPrefix() + "/" + userId + "/" + UUID.randomUUID() + ext;

        if (providerProps.isOss()) {
            return generateOssTicket(uploadPath, fileSizeLimit(category));
        }
        String publicUrl = props.projectUrl() + "/storage/v1/object/public/" + props.bucket() + "/" + uploadPath;
        return new UploadTokenResponse(
                "supabase",
                props.projectUrl(),
                props.anonKey(),
                props.bucket(),
                uploadPath,
                publicUrl,
                null, null, null, null, null
        );
    }

    /**
     * 签发 OSS PostObject 服务端签名直传凭证。
     * <p>
     * 凭证来源按 credential-mode：instance-role（生产）= STS 临时凭证 + x-oss-security-token；
     * ak（本地兜底）= 长期子账号 AK。policy（JSON，UTF-8 → Base64）条件：
     * {@code {"expiration": <UTC ISO8601>, "conditions": [{"bucket":...}, ["eq","$key",...],
     * ["content-length-range",1,limit] [, STS 时加 ["eq","$x-oss-security-token",token]]]}}；
     * signature = Base64(HmacSHA1(accessKeySecret, policyB64))。
     * 前端 formData：key/policy/OSSAccessKeyId/signature/success_action_status=200
     * （STS 加 x-oss-security-token）+ file（须为最后字段）。
     */
    private UploadTokenResponse generateOssTicket(String uploadPath, long contentLimit) {
        if (!providerProps.ossUploadConfigured()) {
            throw new IllegalStateException(
                    "OSS 直传未配置：需补齐 storage.oss.endpoint / bucket 及凭证"
                            + "（credential-mode=instance-role 填 instance-role-name；ak 填 access-key-id/secret）");
        }
        StorageProviderProperties.Oss oss = providerProps.oss();
        OssCredentialService.OssCredentials creds = ossCredentials.resolve();
        String host = "https://" + oss.bucket() + "." + oss.endpoint();
        String publicUrl = host + "/" + uploadPath;
        String expiration = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(OSS_POLICY_TTL_MINUTES)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"));
        String tokenCondition = creds.securityToken() != null
                ? ",[\"eq\",\"$x-oss-security-token\",\"" + creds.securityToken() + "\"]"
                : "";
        String policyJson = "{\"expiration\":\"" + expiration + "\",\"conditions\":["
                + "{\"bucket\":\"" + oss.bucket() + "\"},"
                + "[\"eq\",\"$key\",\"" + uploadPath + "\"],"
                + "[\"content-length-range\",1," + contentLimit + "]"
                + tokenCondition + "]}";
        String policyB64 = Base64.getEncoder()
                .encodeToString(policyJson.getBytes(StandardCharsets.UTF_8));
        String signature = base64HmacSha1(creds.accessKeySecret(), policyB64);
        return new UploadTokenResponse(
                "oss",
                null, null,
                oss.bucket(),
                uploadPath,
                publicUrl,
                host,
                creds.accessKeyId(),
                policyB64,
                signature,
                creds.securityToken()
        );
    }

    /** HmacSHA1 → Base64（PostObject 表单签名；JDK 内置，零依赖） */
    private static String base64HmacSha1(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("OSS 上传签名失败", e);
        }
    }

    /** 视频分类（2026-08-22 舞伴短视频）——校验走视频扩展名 + 独立大小上限通道 */
    private static final java.util.Set<String> VIDEO_EXTENSIONS =
            java.util.Set.of(".mp4", ".mov");

    private static boolean isVideoCategory(FileCategory category) {
        return category == FileCategory.DANCER_VIDEO;
    }

    /** 分类对应的大小上限（图片通道按 provider 取各自配置，默认同为 5MB；视频通道沿用 Supabase 配置） */
    private long fileSizeLimit(FileCategory category) {
        if (isVideoCategory(category)) {
            return props.videoMaxFileSize();
        }
        return providerProps.isOss() ? providerProps.oss().maxFileSize() : props.maxFileSize();
    }

    /** 图片扩展名白名单（按 provider 取各自配置，默认一致） */
    private String[] imageExtensions() {
        return providerProps.isOss() ? providerProps.oss().allowedExtensions() : props.allowedExtensions();
    }

    private void validateFile(FileCategory category, String fileName, long fileSize) {
        if (fileName == null || fileName.isBlank()) {
            throw new BusinessException(1005, "文件名不能为空");
        }
        if (fileSize <= 0) {
            throw new BusinessException(1005, "文件大小无效");
        }
        String ext = extractExtension(fileName);
        if (isVideoCategory(category)) {
            // 视频分类（2026-08-22 舞伴短视频）：视频扩展名 + 独立大小上限
            if (fileSize > props.videoMaxFileSize()) {
                long maxMb = props.videoMaxFileSize() / (1024 * 1024);
                throw new BusinessException(1005, "视频大小不能超过 " + maxMb + "MB");
            }
            if (!VIDEO_EXTENSIONS.contains(ext)) {
                throw new BusinessException(1005, "不支持的视频格式，仅允许: mp4, mov");
            }
            return;
        }
        long maxFileSize = fileSizeLimit(category);
        if (fileSize > maxFileSize) {
            long maxMb = maxFileSize / (1024 * 1024);
            throw new BusinessException(1005, "文件大小不能超过 " + maxMb + "MB");
        }
        String[] extensions = imageExtensions();
        boolean allowed = Arrays.stream(extensions)
                .anyMatch(e -> e.equalsIgnoreCase(ext));
        if (!allowed) {
            throw new BusinessException(1005,
                    "不支持的文件类型，仅允许: " + String.join(", ", extensions));
        }
    }

    private String extractExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            throw new BusinessException(1005, "文件缺少扩展名");
        }
        return fileName.substring(dotIndex).toLowerCase();
    }
}
