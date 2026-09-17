package org.quwuting.quwutingservice.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对象存储 Provider 切换配置（2026-09-17：Supabase(东京) → 阿里云 OSS 切回国内）。
 * <p>
 * 背景：DB 已迁阿里云 RDS MySQL（08-31），对象存储为最后一项海外依赖——
 * supabase.co 无法 ICP 备案（微信合法域名硬伤）、详情页大图加载慢（跨洲回源）、
 * 内容校验跨洲下载。切 OSS 后后端校验走同地域内网（杭州，免费+毫秒级）。
 * <p>
 * 双 provider 并存设计（对齐 08-22 Supabase 项目切换先例）：
 * <ul>
 *   <li>{@code provider=supabase}（默认）：现行逻辑，签发 Supabase 直传凭证——
 *       部署新后端后零行为变化，老版本小程序/后台客户端继续可用；</li>
 *   <li>{@code provider=oss}：签发阿里云 OSS PostObject 服务端签名直传凭证
 *       （HMAC-SHA1 服务端签名，AccessKeySecret 永不下发前端）。</li>
 * </ul>
 * 切换通过外部配置（服务器 config/application-prod.yaml 的 STORAGE_PROVIDER）翻转，
 * 代码不需要重新发布；ImageContentValidator 的 URL 白名单与 provider 无关
 * （oss 配置非空即接受 OSS 形态 URL），过渡期两代 URL 并存。
 */
@ConfigurationProperties(prefix = "storage")
public record StorageProviderProperties(String provider, Oss oss) {

    public StorageProviderProperties {
        if (provider == null || provider.isBlank()) {
            provider = "supabase";
        }
        provider = provider.trim().toLowerCase();
        if (oss == null) {
            oss = new Oss(null, null, null, null, null, null, null, 0, null);
        }
    }

    /** 是否 OSS 直传模式（决定 upload-token 签发哪套凭证） */
    public boolean isOss() {
        return "oss".equals(provider);
    }

    /** oss 路由配置是否完整（endpoint + bucket）——URL 白名单/前缀构建用，与凭证无关 */
    public boolean ossPublicConfigured() {
        return oss != null
                && notBlank(oss.endpoint()) && notBlank(oss.bucket());
    }

    /** oss 上传签发配置是否完整（路由配置 + 按 credentialMode 的凭证来源） */
    public boolean ossUploadConfigured() {
        if (!ossPublicConfigured() || oss == null) {
            return false;
        }
        if (oss.isInstanceRole()) {
            return notBlank(oss.instanceRoleName());
        }
        return notBlank(oss.accessKeyId()) && notBlank(oss.accessKeySecret());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * 阿里云 OSS 直传配置。
     * <p>
     * endpoint 形如 oss-cn-hangzhou.aliyuncs.com（须与 ECS 同地域，内网流量才免费）；
     * internalEndpoint 形如 oss-cn-hangzhou-internal.aliyuncs.com——配置后
     * ImageContentValidator 对 OSS URL 的下载改走内网（ECS 免流量费 + 毫秒级），
     * 不可达时自动兜底公网重试（本地开发环境 internal 不通不致校验失败）。
     * <p>
     * 凭证来源（2026-09-17 按阿里云最佳实践定稿）：
     * <b>instance-role</b>（推荐，生产）= ECS 实例角色绑桶权限，后端从实例元数据端点
     * 取 STS 临时凭证（自动轮转，无长期 AK 可泄漏）；<b>ak</b>（本地开发兜底）=
     * 显式 RAM 子账号 AK/SK。
     */
    public record Oss(
            /** Bucket 所在地域 endpoint（不含 bucket 前缀，如 oss-cn-hangzhou.aliyuncs.com） */
            String endpoint,
            /** Bucket 名称（公共读） */
            String bucket,
            /** 凭证来源：ak（默认）| instance-role（ECS 实例角色，生产推荐） */
            String credentialMode,
            /** ECS 实例角色名（credential-mode=instance-role 时必填） */
            String instanceRoleName,
            /** RAM 子账号 AccessKeyId（credential-mode=ak 时使用） */
            String accessKeyId,
            /** RAM 子账号 AccessKeySecret（credential-mode=ak 时使用；实例角色模式留空） */
            String accessKeySecret,
            /** 内网 endpoint（可选；留空 = 校验下载走公网） */
            String internalEndpoint,
            /** 单文件大小上限（字节，默认 5MB 与 Supabase 通道一致） */
            long maxFileSize,
            /** 允许的文件扩展名（默认与 Supabase 通道一致） */
            String[] allowedExtensions
    ) {
        public Oss {
            if (credentialMode == null || credentialMode.isBlank()) credentialMode = "ak";
            credentialMode = credentialMode.trim().toLowerCase();
            if (maxFileSize <= 0) maxFileSize = 5 * 1024 * 1024; // 默认 5MB
            if (allowedExtensions == null || allowedExtensions.length == 0) {
                allowedExtensions = new String[]{".jpg", ".jpeg", ".png", ".webp"};
            }
        }

        /** 是否实例角色模式（STS 临时凭证，无长期 AK） */
        public boolean isInstanceRole() {
            return "instance-role".equals(credentialMode);
        }
    }
}
