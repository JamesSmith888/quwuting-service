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
    /** 门店认领营业执照（2026-08-11 新增，认领申请材料，仅管理端审核可见） */
    VENUE_CLAIM_LICENSE("claim-licenses");

    private final String pathPrefix;

    FileCategory(String pathPrefix) {
        this.pathPrefix = pathPrefix;
    }

    public String getPathPrefix() {
        return pathPrefix;
    }
}
