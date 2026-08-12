package org.quwuting.quwutingservice.storage;

import org.junit.jupiter.api.Test;
import org.quwuting.quwutingservice.exception.BusinessException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ImageContentValidator 纯内容校验单测。
 * <p>
 * 覆盖：真实 JPEG/PNG 通过、WebP 头通过、伪造文件（MZ exe / HTML）拒绝、
 * 超大小拒绝、超尺寸拒绝（解压炸弹）、截断数据拒绝、非本桶 URL 快速失败。
 * 下载路径（downloadAndCheck）不做集成测试，避免单测触发真实网络。
 */
class ImageContentValidatorTest {

    private static final StorageProperties PROPS = new StorageProperties(
            "https://tkyreautvukkwpwmisbg.supabase.co", "anon", "qwt-public",
            5 * 1024 * 1024, new String[]{".jpg", ".jpeg", ".png", ".webp"});

    private final ImageContentValidator validator = new ImageContentValidator(PROPS);

    private static byte[] generateImage(String format, int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    @Test
    void realJpegPasses() throws Exception {
        assertTrue(validator.isValidContent(generateImage("jpg", 64, 64)));
    }

    @Test
    void realPngPasses() throws Exception {
        assertTrue(validator.isValidContent(generateImage("png", 64, 64)));
    }

    @Test
    void webpHeaderPasses() {
        byte[] webp = "RIFF\u0000\u0000\u0000\u0000WEBPVP8X".getBytes(StandardCharsets.ISO_8859_1);
        assertTrue(validator.isValidContent(webp));
    }

    @Test
    void exeMagicRejected() {
        byte[] exe = new byte[]{0x4D, 0x5A, 0x10, 0x00, 0x03, 0x00, 0x00, 0x00, 0x04, 0x00};
        assertFalse(validator.isValidContent(exe));
    }

    @Test
    void htmlRejected() {
        byte[] html = "<html><body>phishing</body></html>".getBytes(StandardCharsets.UTF_8);
        assertFalse(validator.isValidContent(html));
    }

    @Test
    void oversizedFileRejected() {
        assertFalse(validator.isValidContent(new byte[5 * 1024 * 1024 + 1]));
    }

    @Test
    void emptyRejected() {
        assertFalse(validator.isValidContent(new byte[0]));
    }

    @Test
    void oversizedDimensionRejected() throws Exception {
        // 长边超 MAX_DIMENSION（10000）的合法 PNG：解压炸弹防护
        assertFalse(validator.isValidContent(generateImage("png", 10001, 1)));
    }

    @Test
    void truncatedJpegRejected() throws Exception {
        byte[] jpeg = generateImage("jpg", 64, 64);
        byte[] truncated = java.util.Arrays.copyOf(jpeg, 3); // 仅 magic bytes，无完整头
        assertFalse(validator.isValidContent(truncated));
    }

    @Test
    void foreignUrlRejectedFast() {
        // 非本桶 URL 在前缀白名单处快速失败，不触发网络
        BusinessException e = assertThrows(BusinessException.class,
                () -> validator.validate("https://evil.example.com/x.jpg"));
        assertTrue(e.getMessage().contains("不合法"));
    }

    @Test
    void blankUrlPassesThrough() {
        // 可空字段语义：null / 空白直接通过
        validator.validate(null);
        validator.validate("");
        validator.validate("  ");
        validator.validateAll(null);
        validator.validateAll(java.util.List.of());
    }
}
