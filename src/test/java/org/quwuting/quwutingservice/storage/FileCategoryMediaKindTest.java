package org.quwuting.quwutingservice.storage;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件分类 × 媒体类型声明完整性测试（2026-09-28 mediaKind 解耦）。
 * <p>
 * 零依赖：不连库、不起 Spring（{@code BulletinReactionCodeTest} 同款范式）。
 * <p>
 * 锁定的不变量（防回归）：
 * <ul>
 *   <li><b>每个分类必须至少声明一种媒体类型</b>——声明缺失 = 该分类上传必被拒，
 *       属于配置错误而非运行时行为；</li>
 *   <li>历史语义锁定：DANCER_VIDEO 仅视频（舞伴短视频通道），其余既有分类仅图片
 *       （旧模型的隐式契约，显式化后必须逐值锁定）；</li>
 *   <li>OPERATION_MEDIA 必须图片 + 视频双通道（运营内容媒体的存在意义）；</li>
 *   <li>扩展名判定与错误消息展示串同源（禁手写 "mp4, mov" 文案防漂移）。</li>
 * </ul>
 */
class FileCategoryMediaKindTest {

    @Test
    void everyCategoryDeclaresAtLeastOneKind() {
        for (FileCategory category : FileCategory.values()) {
            assertFalse(category.getAllowedKinds().isEmpty(),
                    category.name() + " 未声明允许的媒体类型——该分类上传会被全部拒绝；"
                            + "请在枚举常量上显式声明 MediaKind（图片 / 视频 / 双通道）");
        }
    }

    @Test
    void legacyCategoriesKeepTheirChannel() {
        // 旧模型隐式契约的显式锁定：改这些断言 = 改线上既有上传位的校验通道，须走迁移评估
        assertEquals(Optional.of(MediaKind.VIDEO), MediaKind.fromExtension(".mp4", new String[]{}),
                "视频扩展名判定是标准事实");
        assertTrue(FileCategory.DANCER_VIDEO.getAllowedKinds().contains(MediaKind.VIDEO),
                "DANCER_VIDEO 必须保留视频通道（舞伴短视频）");
        assertFalse(FileCategory.DANCER_VIDEO.getAllowedKinds().contains(MediaKind.IMAGE),
                "DANCER_VIDEO 不开放图片通道（历史语义）");
        for (FileCategory category : FileCategory.values()) {
            if (category == FileCategory.DANCER_VIDEO || category == FileCategory.OPERATION_MEDIA) {
                continue;
            }
            assertTrue(category.getAllowedKinds().contains(MediaKind.IMAGE)
                            && !category.getAllowedKinds().contains(MediaKind.VIDEO),
                    category.name() + " 应为仅图片通道（历史语义锁定）");
        }
    }

    @Test
    void operationMediaIsDualChannel() {
        assertTrue(FileCategory.OPERATION_MEDIA.getAllowedKinds().contains(MediaKind.IMAGE)
                        && FileCategory.OPERATION_MEDIA.getAllowedKinds().contains(MediaKind.VIDEO),
                "OPERATION_MEDIA 必须图片 + 视频双通道——运营内容媒体分类的存在意义");
    }

    @Test
    void extensionClassification() {
        String[] imageExtensions = {".jpg", ".jpeg", ".png", ".webp"};
        assertEquals(Optional.of(MediaKind.VIDEO), MediaKind.fromExtension(".mp4", imageExtensions));
        assertEquals(Optional.of(MediaKind.VIDEO), MediaKind.fromExtension(".MOV", imageExtensions),
                "扩展名判定大小写不敏感");
        assertEquals(Optional.of(MediaKind.IMAGE), MediaKind.fromExtension(".WEBP", imageExtensions));
        assertEquals(Optional.empty(), MediaKind.fromExtension(".exe", imageExtensions),
                "未知扩展名 = 不支持，不是默认图片");
        assertEquals(Optional.empty(), MediaKind.fromExtension(null, imageExtensions));
        assertEquals(Optional.empty(), MediaKind.fromExtension("", imageExtensions));
    }

    @Test
    void videoExtensionsLabelStaysInSync() {
        for (String ext : MediaKind.VIDEO_EXTENSIONS) {
            assertTrue(MediaKind.videoExtensionsLabel().contains(ext.substring(1)),
                    "展示串与白名单必须同源（错误消息禁手写文案防漂移）: " + ext);
        }
        assertFalse(MediaKind.videoExtensionsLabel().contains("."),
                "展示串是给人看的格式名（mp4, mov），不应带点号");
    }
}
