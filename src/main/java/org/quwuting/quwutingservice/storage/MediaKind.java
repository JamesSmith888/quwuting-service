package org.quwuting.quwutingservice.storage;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 媒体类型（<b>文件校验通道的唯一判据</b>，2026-09-28 mediaKind 解耦）。
 * <p>
 * <b>为什么要有这个枚举（根因说明）</b>：旧模型里"这是不是视频"由
 * {@code StorageService.isVideoCategory()} 对 {@link FileCategory} 枚举值硬编码
 * （{@code == DANCER_VIDEO}）判断——分类枚举同时承担了"路径前缀"与"媒体能力"
 * 两个正交维度，每新增一种视频用途都要改 StorageService 的 if 分支，漏改即静默
 * 走错校验通道（视频被按 5MB 图片上限拒绝）。修复：媒体类型提升为
 * {@link FileCategory} 的一等属性（{@code allowedKinds}），校验通道由
 * 扩展名 → {@code MediaKind} → 分类是否允许 三步驱动，<b>新增分类零改动
 * StorageService</b>。
 * <p>
 * 内容域（媒体附件 {@code org.quwuting.quwutingservice.media}）复用本枚举，
 * 保证"存储校验的媒体类型"与"附件声明的媒体类型"是同一个定义，杜绝双枚举漂移。
 */
public enum MediaKind {

    /** 图片（扩展名白名单是部署事实，取自 storage 配置 allowed-extensions） */
    IMAGE,

    /** 视频（扩展名白名单是标准事实：容器格式由规范决定，不随部署变化） */
    VIDEO;

    /** 视频扩展名白名单（小写、含点号；保序仅供展示） */
    public static final List<String> VIDEO_EXTENSIONS = List.of(".mp4", ".mov");

    /**
     * 按扩展名判定媒体类型：视频扩展名优先（标准事实），其次按部署配置的图片
     * 扩展名白名单（部署事实）；两者都不命中 = 不支持（{@link Optional#empty()}）。
     *
     * @param extension       文件扩展名（大小写不敏感，如 ".MP4"）
     * @param imageExtensions 图片扩展名白名单（来自 storage 配置，小写含点）
     */
    public static Optional<MediaKind> fromExtension(String extension, String[] imageExtensions) {
        if (extension == null || extension.isBlank()) {
            return Optional.empty();
        }
        String ext = extension.toLowerCase(Locale.ROOT);
        if (VIDEO_EXTENSIONS.contains(ext)) {
            return Optional.of(VIDEO);
        }
        if (imageExtensions != null) {
            for (String allowed : imageExtensions) {
                if (allowed != null && allowed.equalsIgnoreCase(ext)) {
                    return Optional.of(IMAGE);
                }
            }
        }
        return Optional.empty();
    }

    /** 视频扩展名展示串（错误消息用；与 {@link #VIDEO_EXTENSIONS} 同源，禁手写文案防漂移） */
    public static String videoExtensionsLabel() {
        StringBuilder sb = new StringBuilder();
        for (String ext : VIDEO_EXTENSIONS) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(ext.substring(1));
        }
        return sb.toString();
    }
}
