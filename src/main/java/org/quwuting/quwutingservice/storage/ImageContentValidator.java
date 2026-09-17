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
 *   <li>URL 必须匹配本应用自有存储前缀——Supabase 形态
 *       （{projectUrl}/storage/v1/object/public/{bucket}/，含 legacy 项目）或 OSS 形态
 *       （https://{bucket}.{endpoint}/，oss 配置完整即生效），排除外部图床与 SSRF 面；</li>
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
    private final StorageProviderProperties providerProps;
    private final HttpClient httpClient;
    private final Cache<String, Boolean> resultCache;
    /**
     * 允许的存储桶前缀集合（2026-08-22 项目切换兼容）：projectUrl + legacyProjectUrls
     * （均为本应用自有 Supabase 项目，安全语义保持封闭——任意外部域名仍被拒）。
     */
    private final List<String> storagePrefixes;
    /**
     * OSS 公开读前缀（2026-09-17 切回国内新增）：https://{bucket}.{endpoint}/，
     * oss 配置完整即生效（与 provider 开关无关——过渡期 provider=supabase 而 DB 中
     * 已混有 OSS 形态 URL，白名单须两形态并存）。
     */
    private final String ossPublicPrefix;
    /** OSS 内网下载基址（配置 internal-endpoint 才有；校验下载走同地域内网免流量费） */
    private final String ossInternalBase;
    /** OSS 通道单文件上限（与 supabase.maxFileSize 默认一致，校验时按 URL 形态取用） */
    private final long ossMaxFileSize;

    public ImageContentValidator(StorageProperties props, StorageProviderProperties providerProps) {
        this.props = props;
        this.providerProps = providerProps;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.resultCache = Caffeine.newBuilder()
                .maximumSize(1024)
                .expireAfterWrite(Duration.ofMinutes(10))
                .build();
        java.util.ArrayList<String> prefixes = new java.util.ArrayList<>(4);
        prefixes.add(props.projectUrl() + "/storage/v1/object/public/" + props.bucket() + "/");
        if (props.legacyProjectUrls() != null) {
            for (String legacy : props.legacyProjectUrls()) {
                if (legacy != null && !legacy.isBlank()) {
                    prefixes.add(legacy.trim() + "/storage/v1/object/public/" + props.bucket() + "/");
                }
            }
        }
        this.storagePrefixes = List.copyOf(prefixes);
        if (providerProps != null && providerProps.ossPublicConfigured()) {
            StorageProviderProperties.Oss oss = providerProps.oss();
            this.ossPublicPrefix = "https://" + oss.bucket() + "." + oss.endpoint() + "/";
            this.ossInternalBase = isNotBlank(oss.internalEndpoint())
                    ? "https://" + oss.bucket() + "." + oss.internalEndpoint()
                    : null;
            this.ossMaxFileSize = oss.maxFileSize();
        } else {
            this.ossPublicPrefix = null;
            this.ossInternalBase = null;
            this.ossMaxFileSize = 0;
        }
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** URL 是否属于本应用自有存储前缀（当前项目 + 历史遗留项目 + OSS 公开桶） */
    private boolean matchesStoragePrefix(String url) {
        if (ossPublicPrefix != null && url.startsWith(ossPublicPrefix)) {
            return true;
        }
        for (String prefix : storagePrefixes) {
            if (url.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** URL 形态对应的单文件上限（OSS URL 用 storage.oss.max-file-size，其余用 Supabase 配置） */
    private long maxBytesFor(String url) {
        return (ossPublicPrefix != null && url.startsWith(ossPublicPrefix))
                ? ossMaxFileSize : props.maxFileSize();
    }

    /**
     * 校验下载地址：OSS URL 且配置内网 endpoint 时改走内网（同地域 ECS 免流量费 + 毫秒级），
     * 其余原样返回。内网不可达时由 downloadAndCheck 兜底公网重试。
     */
    private String toDownloadUrl(String url) {
        if (ossInternalBase != null && ossPublicPrefix != null && url.startsWith(ossPublicPrefix)) {
            return ossInternalBase + "/" + url.substring(ossPublicPrefix.length());
        }
        return url;
    }

    /** 校验单个图片 URL（null / 空白直接通过——字段可空性由调用方语义决定） */
    public void validate(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        validateInternal(url);
    }

    /**
     * 校验视频 URL（2026-08-22 舞伴短视频落库）：仅做<b>域名白名单 + 扩展名</b>校验，
     * 不下载内容（视频可达 50MB，下载校验成本不可接受；恶意内容防线由「凭证签发时
     * 扩展名/大小校验」+「管理员直发 + 逐条 PENDING 审核」双闸门承接）。
     * 接受 mp4 / mov（微信 chooseMedia 输出格式）。
     */
    public void validateVideoUrl(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        if (!matchesStoragePrefix(url)) {
            throw new BusinessException(1005, "视频地址不合法，请重新上传");
        }
        String lower = url.toLowerCase();
        if (!lower.endsWith(".mp4") && !lower.endsWith(".mov")) {
            throw new BusinessException(1005, "视频地址不合法，请重新上传");
        }
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
        // 1. 域名白名单：仅接受本应用自有存储前缀（当前项目 + 历史遗留项目），
        //    排除外部图床与 SSRF 面
        if (!matchesStoragePrefix(url)) {
            throw new BusinessException(1005, "图片地址不合法，请重新上传");
        }
        // 2. 内容校验（缓存命中不产生下载）
        Boolean ok = resultCache.get(url, this::downloadAndCheck);
        if (!Boolean.TRUE.equals(ok)) {
            throw new BusinessException(1005, "上传内容不是有效图片，请重新上传");
        }
    }

    /**
     * 下载并校验（Caffeine 加载函数）：OSS URL 且配置内网 endpoint 时优先走内网，
     * 内网下载失败兜底公网重试一次（本地开发环境 internal 不通不致校验失败）。
     */
    private Boolean downloadAndCheck(String url) {
        String fetchUrl = toDownloadUrl(url);
        Boolean result = attemptDownload(fetchUrl, maxBytesFor(url));
        if (result == null && !fetchUrl.equals(url)) {
            result = attemptDownload(url, maxBytesFor(url));
        }
        return result;
    }

    /**
     * 单次下载并校验。
     * 返回 true=有效图片（缓存）；false=内容无效（缓存，同 URL 重复提交不再下载）；
     * null=下载失败（不缓存——内网误配/瞬时网络故障可于下次提交重试并触发公网兜底，
     * 避免旧逻辑「下载失败也缓存 false」把瞬时故障固化 10 分钟）。
     */
    private Boolean attemptDownload(String fetchUrl, long maxBytes) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(fetchUrl))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<InputStream> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                log.warn("[image-validate] download failed status={} url={}", resp.statusCode(), fetchUrl);
                return null;
            }
            try (InputStream in = resp.body()) {
                byte[] content = readLimited(in, maxBytes);
                if (content == null) {
                    return false; // 超过 maxFileSize，判超限
                }
                return isValidContent(content, maxBytes);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("[image-validate] download error url={}", fetchUrl, e);
            return null;
        }
    }

    /** 限流读取：内容超过 maxBytes 字节返回 null（判超限），避免大文件整读入内存 */
    private byte[] readLimited(InputStream in, long maxBytes) throws IOException {
        int limit = Math.toIntExact(maxBytes) + 1;
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

    /** 纯内容校验（可单测，默认上限）：大小 ≤ 上限 + magic bytes 命中 + JPEG/PNG 尺寸合规 */
    boolean isValidContent(byte[] content) {
        return isValidContent(content, props.maxFileSize());
    }

    /** 纯内容校验（可单测，按 URL 形态的上限）：大小 ≤ 上限 + magic bytes 命中 + JPEG/PNG 尺寸合规 */
    boolean isValidContent(byte[] content, long maxBytes) {
        if (content == null || content.length == 0 || content.length > maxBytes) {
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
