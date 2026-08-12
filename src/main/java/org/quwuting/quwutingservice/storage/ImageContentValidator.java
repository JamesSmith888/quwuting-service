package org.quwuting.quwutingservice.storage;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;

/**
 * 图片内容校验器（2026-08-12 新增，恶意文件防线）。
 * <p>
 * 背景：上传采用「前端直传 Supabase」模式，后端不接触文件流，token 接口只能校验
 * 前端自报的元信息（扩展名/大小），可被任意伪造绕过。本组件在业务提交（图片 URL
 * 落库）时做内容级校验，堵住「伪造凭证上传任意内容」的入口。
 * <p>
 * 校验项：
 * <ol>
 *   <li>URL 必须匹配本应用公开桶前缀（{projectUrl}/storage/v1/object/public/{bucket}/），
 *       排除外部图床与 SSRF 面；</li>
 *   <li>下载内容大小 ≤ 配置上限（maxFileSize）；</li>
 *   <li>magic bytes 必须匹配 JPEG / PNG / WebP 之一（排除 exe / HTML / 脚本等改名伪造文件）；</li>
 *   <li>JPEG / PNG 解析宽高并限制尺寸上限（防解压炸弹 decompression bomb）。</li>
 * </ol>
 * 结果按 URL 缓存（Caffeine 10min），同一 URL 重复提交（编辑全量覆盖旧图）不重复下载。
 * WebP 仅验文件头（JDK ImageIO 无内置 WebP 解码器，保持零依赖）。
 */
@Slf4j
@Component
public class ImageContentValidator {

    /** 尺寸上限：长边像素（防解压炸弹；常规手机照片长边 ≤ 9000） */
    private static final int MAX_DIMENSION = 10000;
    /** 尺寸上限：总像素（约 100MP，覆盖 4K / 8K 全景，阻止超大位图解码 OOM） */
    private static final long MAX_PIXELS = 100_000_000L;
    /** 下载读缓冲 */
    private static final int READ_BUFFER = 8192;

    private final StorageProperties props;
    private final HttpClient httpClient;
    private final Cache<String, Boolean> resultCache;

    public ImageContentValidator(StorageProperties props) {
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.resultCache = Caffeine.newBuilder()
                .maximumSize(1024)
                .expireAfterWrite(Duration.ofMinutes(10))
                .build();
    }

    /** 校验单个图片 URL（null / 空白直接通过——字段可空性由调用方语义决定） */
    public void validate(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        validateInternal(url);
    }

    /** 校验图片 URL 列表（null / 空列表直接通过；任一失败抛 BusinessException 中断） */
    public void validateAll(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return;
        }
        for (String url : urls) {
            validateInternal(url);
        }
    }

    private void validateInternal(String url) {
        // 1. 域名白名单：仅接受本应用公开桶前缀，排除外部图床与 SSRF 面
        String prefix = props.projectUrl() + "/storage/v1/object/public/" + props.bucket() + "/";
        if (!url.startsWith(prefix)) {
            throw new BusinessException(1005, "图片地址不合法，请重新上传");
        }
        // 2. 内容校验（缓存命中不产生下载）
        Boolean ok = resultCache.get(url, this::downloadAndCheck);
        if (!Boolean.TRUE.equals(ok)) {
            throw new BusinessException(1005, "上传内容不是有效图片，请重新上传");
        }
    }

    /** 下载并校验（Caffeine 加载函数；返回 false 不抛异常，统一由调用方拒绝） */
    private Boolean downloadAndCheck(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<InputStream> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                log.warn("[image-validate] download failed status={} url={}", resp.statusCode(), url);
                return false;
            }
            try (InputStream in = resp.body()) {
                byte[] content = readLimited(in);
                if (content == null) {
                    return false; // 超过 maxFileSize，判超限
                }
                return isValidContent(content);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("[image-validate] download error url={}", url, e);
            return false;
        }
    }

    /** 限流读取：内容超过 maxFileSize 字节返回 null（判超限），避免大文件整读入内存 */
    private byte[] readLimited(InputStream in) throws IOException {
        int limit = Math.toIntExact(props.maxFileSize()) + 1;
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, 64 * 1024));
        byte[] buf = new byte[READ_BUFFER];
        int total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > limit) {
                return null;
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** 纯内容校验（可单测）：大小 ≤ 上限 + magic bytes 命中 + JPEG/PNG 尺寸合规 */
    boolean isValidContent(byte[] content) {
        if (content == null || content.length == 0 || content.length > props.maxFileSize()) {
            return false;
        }
        if (isJpeg(content) || isPng(content)) {
            return checkDimensions(content);
        }
        return isWebp(content);
    }

    private boolean isJpeg(byte[] c) {
        return c.length >= 3 && (c[0] & 0xFF) == 0xFF && (c[1] & 0xFF) == 0xD8 && (c[2] & 0xFF) == 0xFF;
    }

    private boolean isPng(byte[] c) {
        if (c.length < 8) return false;
        byte[] sig = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        for (int i = 0; i < 8; i++) {
            if (c[i] != sig[i]) return false;
        }
        return true;
    }

    private boolean isWebp(byte[] c) {
        return c.length >= 12
                && c[0] == 'R' && c[1] == 'I' && c[2] == 'F' && c[3] == 'F'
                && c[8] == 'W' && c[9] == 'E' && c[10] == 'B' && c[11] == 'P';
    }

    /** 解析 JPEG/PNG 宽高并校验尺寸上限（防解压炸弹；伪造头/截断数据解析失败返回 false） */
    private boolean checkDimensions(byte[] content) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return false;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || w > MAX_DIMENSION || h > MAX_DIMENSION) {
                    return false;
                }
                return (long) w * h <= MAX_PIXELS;
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            // 伪造数据可能触发各类解析异常（含 RuntimeException），一律视为非有效图片
            return false;
        }
    }
}
