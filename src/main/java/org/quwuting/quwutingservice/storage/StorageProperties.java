package org.quwuting.quwutingservice.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Supabase Storage 配置（前端直传模式）。
 * <p>
 * 后端不接收文件流，仅签发上传凭证（projectUrl + anonKey + bucket + uploadPath），
 * 前端凭此直接上传至 Supabase Storage REST API。
 * <p>
 * anonKey 是 Supabase 的公开密钥（安全性由 Storage RLS 策略保障），可安全下发给前端。
 * serviceRoleKey 绝不下发。
 */
@ConfigurationProperties(prefix = "supabase.storage")
public record StorageProperties(
        /** Supabase 项目 URL，如 https://xxxx.supabase.co */
        String projectUrl,
        /** Supabase anon public key（公开，RLS 策略控制访问） */
        String anonKey,
        /** 公开读 bucket 名称（存放场所图片等） */
        String bucket,
        /** 单文件大小上限（字节） */
        long maxFileSize,
        /** 允许的文件扩展名（小写，含点号） */
        String[] allowedExtensions,
        /**
         * 视频单文件大小上限（字节，2026-08-22 新增——短视频经客户端压缩后通常 2~6MB，
         * 5MB 图片上限不适用；仅 DANCER_VIDEO 分类生效，图片分类恒用 maxFileSize）。
         */
        long videoMaxFileSize,
        /**
         * 历史遗留项目 URL 列表（2026-08-22 新增：Supabase 项目切换后，历史图片 URL 仍指向
         * 旧项目且未迁移——编辑回显提交时被域名白名单拒绝「图片地址不合法」）。
         * 校验白名单 = projectUrl + 本列表（均为本应用自有项目，安全语义保持封闭）；
         * 新上传恒走 projectUrl。可空/空数组 = 仅当前项目。
         */
        String[] legacyProjectUrls
) {
    public StorageProperties {
        if (maxFileSize <= 0) maxFileSize = 5 * 1024 * 1024; // 默认 5MB
        if (videoMaxFileSize <= 0) videoMaxFileSize = 50 * 1024 * 1024; // 默认 50MB
        if (allowedExtensions == null || allowedExtensions.length == 0) {
            allowedExtensions = new String[]{".jpg", ".jpeg", ".png", ".webp"};
        }
        if (legacyProjectUrls == null) {
            legacyProjectUrls = new String[0];
        }
    }
}
