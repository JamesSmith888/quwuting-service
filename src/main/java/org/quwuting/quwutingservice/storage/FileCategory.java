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
    VENUE_QR("venue-qr");

    private final String pathPrefix;

    FileCategory(String pathPrefix) {
        this.pathPrefix = pathPrefix;
    }

    public String getPathPrefix() {
        return pathPrefix;
    }
}
