package org.quwuting.quwutingservice.storage;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 上传凭证响应（前端凭此直传对象存储；2026-09-17 起双 provider 形态）。
 * <p>
 * {@code provider} 判别：
 * <ul>
 *   <li>{@code supabase}（缺省兼容老客户端——无 provider 字段视为 supabase）：
 *       填 projectUrl / anonKey / bucket / uploadPath / publicUrl；
 *       wx.uploadFile 目标 = {projectUrl}/storage/v1/object/{bucket}/{uploadPath}，
 *       Authorization: Bearer {anonKey}。</li>
 *   <li>{@code oss}：填 host / ossAccessKeyId / policy / signature / bucket / uploadPath / publicUrl；
 *       wx.uploadFile 目标 = host（https://{bucket}.{endpoint}），formData 携带
 *       key=uploadPath + policy + OSSAccessKeyId + signature + success_action_status，
 *       无需 Authorization header（PostObject 表单签名模式）。</li>
 * </ul>
 * 字段为超集 + NON_NULL 序列化：新增字段对老客户端透明（多余 JSON 字段被忽略），
 * 新客户端按 provider 分支读取——两端任意先后发布均兼容。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UploadTokenResponse(
        /** 凭证类型：supabase | oss */
        String provider,
        // ---------- supabase 专用字段 ----------
        /** Supabase 项目 URL */
        String projectUrl,
        /** Supabase anon key（公开密钥，RLS 策略控制访问） */
        String anonKey,
        // ---------- 共用字段 ----------
        /** 目标 bucket 名称（oss 模式下为信息性，上传目标由 host 决定） */
        String bucket,
        /** 服务端生成的唯一上传路径（{分类前缀}/{userId}/{uuid}.{ext}；oss 模式即 PostObject 的 key） */
        String uploadPath,
        /** 上传成功后的公开访问 URL（前端直接存入业务字段） */
        String publicUrl,
        // ---------- oss 专用字段（PostObject 服务端签名直传） ----------
        /** 上传目标 host（https://{bucket}.{endpoint}） */
        String host,
        /** OSS AccessKeyId（实例角色模式 = STS 临时 AK，自动轮转） */
        String ossAccessKeyId,
        /** Base64(policy JSON)：过期时间 + key 精确匹配 + content-length-range（+STS token 条件） */
        String policy,
        /** Base64(HmacSHA1(AccessKeySecret, policy))——服务端签名，Secret 不出后端 */
        String signature,
        /** STS SecurityToken（实例角色模式非空；前端以 x-oss-security-token 表单字段携带） */
        String securityToken
) {}
