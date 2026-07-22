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
        String[] allowedExtensions
) {
    public StorageProperties {
        if (maxFileSize <= 0) maxFileSize = 5 * 1024 * 1024; // 默认 5MB
        if (allowedExtensions == null || allowedExtensions.length == 0) {
            allowedExtensions = new String[]{".jpg", ".jpeg", ".png", ".webp"};
        }
    }
}
