package org.quwuting.quwutingservice.storage;

/**
 * 文件分类，决定上传路径前缀。
 * <p>
 * 路径格式：{prefix}/{userId}/{uuid}.{ext}
 * 按用户隔离避免冲突，UUID 保证唯一性。
 */
public enum FileCategory {

    /** 场所封面图 */
    VENUE_COVER("venue-covers"),
    /** 场所相册图片 */
    VENUE_PHOTO("venue-photos"),
    /** 场所微信二维码 */
    VENUE_QR("venue-qr"),
    /** 用户头像 */
    USER_AVATAR("user-avatars"),
    /** 舞伴相册照片（本人上传，PENDING 审核后公开） */
    DANCER_PHOTO("dancer-photos"),
    /** 舞伴头像（本人编辑资料时上传） */
    DANCER_AVATAR("dancer-avatars"),
    /** 舞伴联系方式图片（2026-08-14 新增，二维码等；与 contact 同一门槛/遮挡语义） */
    DANCER_CONTACT_QR("dancer-contact-qr"),
    /** 门店认领营业执照（2026-08-11 新增，认领申请材料，仅管理端审核可见） */
    VENUE_CLAIM_LICENSE("claim-licenses"),
    /** 舞友群群二维码（2026-08-17 新增，运营管理端上传；用户端长按识别加入群聊） */
    GROUP_QR("group-qr"),
    /** 舞伴短视频（2026-08-22 新增，管理员直发 + PENDING 审核后公开；走视频扩展名/大小校验通道） */
    DANCER_VIDEO("dancer-videos"),
    /** 意见反馈截图（2026-08-28 新增，平台级意见反馈选填 1 张；仅管理端处理时可见） */
    APP_FEEDBACK("app-feedbacks");

    private final String pathPrefix;

    FileCategory(String pathPrefix) {
        this.pathPrefix = pathPrefix;
    }

    public String getPathPrefix() {
        return pathPrefix;
    }
}
