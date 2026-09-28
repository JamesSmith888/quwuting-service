package org.quwuting.quwutingservice.storage;

import java.util.Set;

/**
 * 文件分类，决定上传路径前缀与<b>允许的媒体类型</b>（2026-09-28 mediaKind 解耦）。
 * <p>
 * 路径格式：{prefix}/{userId}/{uuid}.{ext}
 * 按用户隔离避免冲突，UUID 保证唯一性。
 * <p>
 * <b>媒体能力是分类的一等属性</b>（根因修复）：每个分类显式声明允许的
 * {@link MediaKind} 集合，校验通道由"扩展名 → MediaKind → 分类是否允许"驱动。
 * 旧模型把"是否视频"硬编码在 StorageService（{@code == DANCER_VIDEO}），
 * 新增视频分类必须改校验分支——漏改即静默走错通道。现在新增分类只需加一个
 * 枚举常量并声明 kind，StorageService 零改动。
 */
public enum FileCategory {

    /** 场所封面图 */
    VENUE_COVER("venue-covers", MediaKind.IMAGE),
    /** 场所相册图片 */
    VENUE_PHOTO("venue-photos", MediaKind.IMAGE),
    /** 场所微信二维码 */
    VENUE_QR("venue-qr", MediaKind.IMAGE),
    /** 用户头像 */
    USER_AVATAR("user-avatars", MediaKind.IMAGE),
    /** 舞伴相册照片（本人上传，PENDING 审核后公开） */
    DANCER_PHOTO("dancer-photos", MediaKind.IMAGE),
    /** 舞伴头像（本人编辑资料时上传） */
    DANCER_AVATAR("dancer-avatars", MediaKind.IMAGE),
    /** 舞伴联系方式图片（2026-08-14 新增，二维码等；与 contact 同一门槛/遮挡语义） */
    DANCER_CONTACT_QR("dancer-contact-qr", MediaKind.IMAGE),
    /** 门店认领营业执照（2026-08-11 新增，认领申请材料，仅管理端审核可见） */
    VENUE_CLAIM_LICENSE("claim-licenses", MediaKind.IMAGE),
    /** 舞友群群二维码（2026-08-17 新增，运营管理端上传；用户端长按识别加入群聊） */
    GROUP_QR("group-qr", MediaKind.IMAGE),
    /** 舞伴短视频（2026-08-22 新增，管理员直发 + PENDING 审核后公开；走视频扩展名/大小校验通道） */
    DANCER_VIDEO("dancer-videos", MediaKind.VIDEO),
    /** 意见反馈截图（2026-08-28 新增，平台级意见反馈选填 1 张；仅管理端处理时可见） */
    APP_FEEDBACK("app-feedbacks", MediaKind.IMAGE),
    /**
     * 运营内容媒体（2026-09-28 新增）：公告/快讯（{@code qwt_announcements.media_json}）
     * 等平台运营内容的配图与短视频。<b>图片 + 视频双通道</b>——同一个分类下，图片走
     * 图片扩展名/大小上限，视频走视频扩展名/大小上限，按扩展名自动分流。
     * 仅 ADMIN 管理端使用（运营自产内容，非 UGC）。
     */
    OPERATION_MEDIA("operation-media", MediaKind.IMAGE, MediaKind.VIDEO);

    private final String pathPrefix;

    /** 允许的媒体类型（不可变集合；校验通道归属的唯一判据） */
    private final Set<MediaKind> allowedKinds;

    FileCategory(String pathPrefix, MediaKind... allowedKinds) {
        this.pathPrefix = pathPrefix;
        this.allowedKinds = Set.of(allowedKinds);
    }

    public String getPathPrefix() {
        return pathPrefix;
    }

    /** 该分类是否接受此媒体类型（校验通道归属的唯一判据，StorageService 消费） */
    public boolean allows(MediaKind kind) {
        return allowedKinds.contains(kind);
    }

    public Set<MediaKind> getAllowedKinds() {
        return allowedKinds;
    }
}
