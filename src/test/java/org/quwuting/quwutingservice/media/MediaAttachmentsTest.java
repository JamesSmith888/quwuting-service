package org.quwuting.quwutingservice.media;

import org.junit.jupiter.api.Test;
import org.quwuting.quwutingservice.storage.MediaKind;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 媒体附件结构单点测试（2026-09-28）。
 * <p>
 * 零依赖：不连库、不起 Spring（{@code BulletinReactionCodeTest} 同款范式）——
 * {@link MediaAttachments} 为纯静态实现，本测试直接驱动。
 * <p>
 * 锁定的不变量（防回归）：
 * <ul>
 *   <li>读写往返无损；</li>
 *   <li>读侧容错（坏 JSON → 空列表，绝不抛）；</li>
 *   <li>写侧严格（条数 / 必填 / https / 视频上限 / JSON 长度）；</li>
 *   <li>空语义：null / 空列表 → 序列化为 {@code null}（列保持 NULL = 幂等清空）。</li>
 * </ul>
 */
class MediaAttachmentsTest {

    private static MediaAttachment image(String url) {
        return new MediaAttachment(MediaKind.IMAGE, url, null);
    }

    // ── 序列化 / 解析往返 ─────────────────────────────────────

    @Test
    void roundTripPreservesEntries() {
        List<MediaAttachment> media = List.of(
                new MediaAttachment(MediaKind.VIDEO, "https://bucket.example.com/m/v.mp4",
                        "https://bucket.example.com/m/p.jpg"),
                image("https://bucket.example.com/m/a.webp"));
        String json = MediaAttachments.serialize(media);
        assertEquals(media, MediaAttachments.parseOrEmpty(json),
                "序列化再解析必须无损往返——附件契约变更时本断言即契约兼容性哨兵");
    }

    @Test
    void serializeOmitsNullPoster() {
        String json = MediaAttachments.serialize(List.of(image("https://bucket.example.com/m/a.jpg")));
        assertFalse(json.contains("poster"),
                "poster 为 null 的条目不应出现在 JSON 里（省字节，且读侧契约保持干净）: " + json);
    }

    @Test
    void emptySerializesToNull() {
        assertNull(MediaAttachments.serialize(null), "null 列表 → null（列保持 NULL = 无附件）");
        assertNull(MediaAttachments.serialize(List.of()), "空列表 → null（幂等清空语义）");
    }

    @Test
    void normalizeTrimsAndBlanksToNull() {
        MediaAttachment m = new MediaAttachment(MediaKind.IMAGE, " https://x.example.com/a.jpg ", "  ");
        assertEquals("https://x.example.com/a.jpg", m.url(), "url 应 trim");
        assertNull(m.poster(), "空白 poster 应归 null");
    }

    // ── 读侧容错 ─────────────────────────────────────────────

    @Test
    void parseNullOrBlankReturnsEmpty() {
        assertTrue(MediaAttachments.parseOrEmpty(null).isEmpty());
        assertTrue(MediaAttachments.parseOrEmpty("").isEmpty());
        assertTrue(MediaAttachments.parseOrEmpty("   ").isEmpty());
    }

    @Test
    void parseCorruptJsonReturnsEmptyNeverThrows() {
        // 一条坏数据不能放大成整页 500（读侧容错是渲染路径的正确行为）
        assertTrue(MediaAttachments.parseOrEmpty("{not-json").isEmpty());
        assertTrue(MediaAttachments.parseOrEmpty("{\"type\":\"IMAGE\"}").isEmpty(),
                "形状不符（对象而非数组）→ 空列表");
        assertTrue(MediaAttachments.parseOrEmpty("[{\"type\":\"NOPE\",\"url\":\"https://x/a.jpg\"}]").isEmpty(),
                "未知枚举值等反序列化失败 → 空列表");
    }

    // ── 写侧结构校验 ─────────────────────────────────────────

    private static void assertRejected(List<MediaAttachment> media, String keyword) {
        try {
            MediaAttachments.validateStructure(media);
        } catch (Exception e) {
            assertTrue(e.getMessage().contains(keyword),
                    "错误消息应含操作指引关键词 '" + keyword + "'，实际: " + e.getMessage());
            return;
        }
        throw new AssertionError("应拒绝：" + media);
    }

    @Test
    void structureAcceptsValidMedia() {
        MediaAttachments.validateStructure(List.of(
                new MediaAttachment(MediaKind.VIDEO, "https://b.example.com/v.mp4", "https://b.example.com/p.jpg"),
                image("https://b.example.com/a.jpg")));
        MediaAttachments.validateStructure(null);
        MediaAttachments.validateStructure(List.of());
    }

    @Test
    void structureRejectsOverLimits() {
        assertRejected(java.util.stream.IntStream.range(0, 10)
                        .mapToObj(i -> image("https://b.example.com/" + i + ".jpg")).toList(),
                "最多 " + MediaAttachments.MAX_TOTAL);
        assertRejected(java.util.stream.IntStream.range(0, 4)
                        .mapToObj(i -> new MediaAttachment(MediaKind.VIDEO, "https://b.example.com/" + i + ".mp4", null))
                        .toList(),
                "视频附件最多 " + MediaAttachments.MAX_VIDEOS);
    }

    @Test
    void structureRejectsMissingTypeOrUrl() {
        assertRejected(List.of(new MediaAttachment(null, "https://b.example.com/a.jpg", null)), "类型不能为空");
        assertRejected(List.of(new MediaAttachment(MediaKind.IMAGE, null, null)), "地址不能为空");
        assertRejected(java.util.Arrays.asList((MediaAttachment) null), "空条目");
    }

    @Test
    void structureRejectsNonHttps() {
        assertRejected(List.of(image("http://b.example.com/a.jpg")), "https");
        assertRejected(List.of(new MediaAttachment(MediaKind.VIDEO, "https://b.example.com/v.mp4",
                        "http://b.example.com/p.jpg")), "https");
    }

    @Test
    void serializeRejectsOversizedJson() {
        // 结构校验拦不住超长 url（长度上限在序列化层兜底，与 V32 列宽 varchar(4000) 对齐）
        String huge = "https://b.example.com/" + "a".repeat(5000) + ".jpg";
        try {
            MediaAttachments.serialize(java.util.List.of(new MediaAttachment(MediaKind.IMAGE, huge, null)));
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("存储上限"),
                    "超长 JSON 必须在写入前拒绝，实际: " + e.getMessage());
            return;
        }
        throw new AssertionError("超长附件 JSON 应拒绝序列化");
    }
}
