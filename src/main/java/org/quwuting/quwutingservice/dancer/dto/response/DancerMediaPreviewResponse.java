package org.quwuting.quwutingservice.dancer.dto.response;

import org.quwuting.quwutingservice.dancer.enums.DancerPhotoKind;

/**
 * 舞伴列表卡片媒体预览条目（2026-08-24 晚：列表卡片多图预览，消息预览式）。
 * <p>
 * 列表页卡片缩略图行由单张封面（coverPhotoUrl）升级为<b>多张媒体预览</b>（照片 + 视频
 * 混合，按展示顺序取前 {@code LIST_MEDIA_PREVIEW_LIMIT} 个 PUBLIC 媒体）——用户一瞥
 * 即见该舞伴的更多内容（含需解锁的付费媒体，薄码呈现），同聊天软件对话列表预览语义。
 * <p>
 * 可见性/解锁语义对齐详情页 {@link DancerPhotoResponse}（同一套门槛口径）：
 * <ul>
 *   <li>免费（cost=0）→ unlocked 恒 true，url 恒下发（照片原图 / 视频封面帧），无薄码；</li>
 *   <li>付费且当前用户已解锁 → url 下发（清晰），unlocked=true；</li>
 *   <li><b>付费且未解锁 → url 置 null，仅下发 blurUrl 薄码</b>（模糊图，防内容绕过——
 *       与详情页「未解锁照片模糊版背景」同一张 blurUrl，前端渲染薄码 + 锁角标）；</li>
 *   <li>本人/管理员视角（listMyDancers）→ 全解锁（unlocked 恒 true）。</li>
 * </ul>
 * 列表接口为软鉴权：未登录/匿名 → 付费媒体恒未解锁（薄码）；登录用户按实时解锁态组装
 * （用户相关态不进列表缓存，见 DancerListCacheService）。
 */
public record DancerMediaPreviewResponse(
        /** 媒体 ID（DancerPhoto id，前端 wx:key 用） */
        Long id,
        /** 媒体类型（PHOTO 照片 / VIDEO 短视频；前端据此渲染播放角标） */
        DancerPhotoKind kind,
        /** 展示图清晰版：照片 = 原图 URL；视频 = 封面帧 URL。未解锁（付费且未解锁）→ null */
        String url,
        /** 薄码图（上传时前端降采样的模糊版本）：未解锁付费媒体 → 模糊图；免费/已解锁 → null */
        String blurUrl,
        /** 解锁积分门槛（0 = 免费，恒清晰展示） */
        int cost,
        /** 当前用户视角是否已解锁（免费恒 true；登录用户已解锁 true；匿名恒 false；本人/管理员恒 true） */
        boolean unlocked,
        /** 视频时长（秒；照片恒 0，前端不渲染） */
        int durationSeconds
) {}
