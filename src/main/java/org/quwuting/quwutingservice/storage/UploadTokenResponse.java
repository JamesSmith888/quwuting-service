package org.quwuting.quwutingservice.storage;

/**
 * 上传凭证响应（前端凭此直传 Supabase Storage）。
 * <p>
 * 前端使用方式：
 * 1. 用 projectUrl + anonKey 构造上传请求的 Authorization header
 * 2. wx.uploadFile 目标 URL = {projectUrl}/storage/v1/object/{bucket}/{uploadPath}
 * 3. 上传成功后公开访问 URL = {projectUrl}/storage/v1/object/public/{bucket}/{uploadPath}
 */
public record UploadTokenResponse(
        /** Supabase 项目 URL */
        String projectUrl,
        /** Supabase anon key（公开密钥，RLS 策略控制访问） */
        String anonKey,
        /** 目标 bucket 名称 */
        String bucket,
        /** 服务端生成的唯一上传路径（含分类前缀 + userId + UUID） */
        String uploadPath,
        /** 上传成功后的公开访问 URL（前端直接存入业务字段） */
        String publicUrl
) {}
