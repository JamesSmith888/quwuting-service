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
 *   <li>{@code provider=oss}：签发阿里云 OSS PostObject 服务端签名直传凭证。</li>
 * </ul>
 * 切换通过外部配置（服务器 config/application-prod.yaml 的 STORAGE_PROVIDER）翻转，
 * 代码不需要重新发布；ImageContentValidator 的 URL 白名单与 provider 无关
 * （oss 路由配置非空即接受 OSS 形态 URL），过渡期两代 URL 并存。
 * <p>
 * <b>凭证 = 永远是 STS 临时凭证</b>（2026-09-18 统一：本地/生产同一套——
 * 单一 {@link OssCredentialService} 解析器，优先 ECS 实例角色元数据端点，
 * 取不到自动兜底 AssumeRole）。不存在"长期 AK 直签"分支，因此本地与生产
 * <b>行为完全同构</b>（都带 securityToken，前端同一条直传代码路径）。
 */
@ConfigurationProperties(prefix = "storage")
public record StorageProviderProperties(String provider, Oss oss) {

    public StorageProviderProperties {
        if (provider == null || provider.isBlank()) {
            provider = "supabase";
        }
        provider = provider.trim().toLowerCase();
        if (oss == null) {
            oss = new Oss(null, null, null, null, null, null, null, null, null, 0, null);
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

    /** oss 上传签发配置是否完整（路由配置 + 至少一条 STS 凭证来源） */
    public boolean ossUploadConfigured() {
        return ossPublicConfigured() && oss != null && (oss.hasInstanceRole() || oss.hasAssumeRole());
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
     * 凭证来源（两条，均为 STS 临时凭证，同等安全语义；本地与生产各填其一或都填）：
     * <ol>
     *   <li><b>instanceRoleName</b>（生产主路径）：ECS 实例角色 —— 凭证取自 ECS 元数据端点
     *       （100.100.100.200），无需任何 AccessKey，由阿里云自动轮转；</li>
     *   <li><b>assumeRoleArn + assumeRoleAccessKeyId/Secret</b>（本地开发等非 ECS 环境）：
     *       该 RAM 用户<b>仅授予 sts:AssumeRole、无任何 OSS 权限</b>，换取的临时凭证
     *       受角色策略约束（本 bucket 读写）。</li>
     * </ol>
     */
    public record Oss(
            /** Bucket 所在地域 endpoint（不含 bucket 前缀，如 oss-cn-hangzhou.aliyuncs.com） */
            String endpoint,
            /** Bucket 名称（公共读） */
            String bucket,
            /** ECS 实例角色名（生产主路径：实例元数据端点取 STS 临时凭证） */
            String instanceRoleName,
            /** STS 接口地址（AssumeRole 兜底用；默认 https://sts.aliyuncs.com/） */
            String stsEndpoint,
            /** AssumeRole 目标角色 ARN：acs:ram::<UID>:role/<role>（角色附 OSS 最小权限策略） */
            String assumeRoleArn,
            /** RAM 子账号 AccessKeyId：仅 sts:AssumeRole 权限 */
            String assumeRoleAccessKeyId,
            /** RAM 子账号 AccessKeySecret：仅 sts:AssumeRole 权限 */
            String assumeRoleAccessKeySecret,
            /** AssumeRole 会话名（留空自动生成 qwt-oss-<随机>） */
            String assumeRoleSessionName,
            /** 内网 endpoint（可选；留空 = 校验下载走公网） */
            String internalEndpoint,
            /** 单文件大小上限（字节，默认 5MB 与 Supabase 通道一致） */
            long maxFileSize,
            /** 允许的文件扩展名（默认与 Supabase 通道一致） */
            String[] allowedExtensions
    ) {
        public Oss {
            if (stsEndpoint == null || stsEndpoint.isBlank()) {
                stsEndpoint = "https://sts.aliyuncs.com/";
            }
            if (maxFileSize <= 0) maxFileSize = 5 * 1024 * 1024; // 默认 5MB
            if (allowedExtensions == null || allowedExtensions.length == 0) {
                allowedExtensions = new String[]{".jpg", ".jpeg", ".png", ".webp"};
            }
        }

        /** 是否配置了 ECS 实例角色 */
        public boolean hasInstanceRole() {
            return instanceRoleName != null && !instanceRoleName.isBlank();
        }

        /** 是否配置了 AssumeRole 兜底（ARN + AK/SK 三项齐全） */
        public boolean hasAssumeRole() {
            return assumeRoleArn != null && !assumeRoleArn.isBlank()
                    && assumeRoleAccessKeyId != null && !assumeRoleAccessKeyId.isBlank()
                    && assumeRoleAccessKeySecret != null && !assumeRoleAccessKeySecret.isBlank();
        }
    }
}
