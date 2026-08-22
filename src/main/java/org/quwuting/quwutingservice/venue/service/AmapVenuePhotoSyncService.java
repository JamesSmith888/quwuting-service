package org.quwuting.quwutingservice.venue.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.config.AmapProperties;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 门店图片批量同步服务（2026-08-21 新增，管理端「一键同步门店图片」）。
 * <p>
 * 数据源：高德地图 Web 服务 place/text 关键词搜索 API（extensions=all 时返回
 * photos[]）。key 只放后端配置（app.amap.key），不落前端（与 GeocodeService 同策略）。
 * <p>
 * <b>图片策略（2026-08-21 定稿，2026-08-22 升级多图）</b>：image_url 直接存高德官方
 * 图床 URL（store.is.autonavi.com），<b>不下载到 Supabase Storage</b>——省存储成本 +
 * 高德 CDN 直出（用户决策）。候选排序：① 官方图床（store.is.autonavi.com）优先；
 * ② 无官方图时回退取 photos[] 第一条（可能为 aos-comment.amap.com 用户评论图，
 * 有图总比没图好，管理端可在失败明细中人工判断）；③ photos 为空 → 记失败
 * 「高德未收录照片」，可手动补地址后重试。
 * <p>
 * <b>多图落点（2026-08-22 修复）</b>：image_url 是<b>单值主图字段</b>（varchar 500），
 * 高德 photos[] 多图写入独立相册表 qwt_venue_photos（status=PUBLIC、createdBy=0
 * 存量导入、<b>重置式导入</b>：每店先清旧高德图再插入最新匹配结果）——V35 起
 * venue.photos JSON 列已废弃，详情/列表轮播读路径整体走 qwt_venue_photos
 * （VenueResponseMapper 五参重载注入），同步后详情页自动展示多图轮播。
 * 幂等口径 = 缺主图 OR 无公开相册（findMissingImages 同口径），已有主图的存量
 * 门店重跑本轮时若主图仍在匹配结果中则保留，否则重写（错配自愈）。
 * <p>
 * <b>名称匹配（2026-08-22 修复错配）</b>：全量同步按名称归一化打分锁定目标 POI
 * （阈值 {@link #NAME_MATCH_THRESHOLD}），只取该 POI 的照片——杜绝「梦幻酒馆」
 * 混入「梦幻网咖」等相似名店铺照片；低于阈值记失败（附最近候选名），管理员可
 * 补精确地址重试（重试 = 地址模式，直接取第一个 POI，意图明确）。
 * <p>
 * <b>实时进度</b>：同步在独立单线程执行器异步运行，进度写入内存
 * {@link SyncProgress}（单实例部署，systemd 单进程，内存态可靠）；前端轮询
 * GET /admin/venues/photo-sync/progress 获取。失败项可反复重试。
 * <p>
 * <b>手动补地址重试</b>：搜索失败（高德搜不到/名称不匹配）的门店可在前端填写
 * 更精确的地址，调 {@link #retrySync} 用该地址作为搜索关键词（高德对地址串检索
 * 优于店名，且跳过名称校验直接取第一个 POI）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AmapVenuePhotoSyncService {

    /** 高德 place/text 关键词搜索 WebService 端点 */
    private static final String PLACE_TEXT_URL = "https://restapi.amap.com/v3/place/text";

    /** 高德官方图床域名（优先采纳；评论图域名 aos-comment.amap.com 为回退） */
    private static final String OFFICIAL_IMAGE_HOST = "store.is.autonavi.com";

    /** 单店同步限速间隔（高德个人版 QPS≈3，放宽余量防限流） */
    private static final long RATE_LIMIT_MS = 400;

    /** 名称匹配阈值：相似度低于本值 = 不匹配（拒绝错配到相似名店铺） */
    private static final int NAME_MATCH_THRESHOLD = 60;

    private final VenueRepository venueRepository;
    private final VenueService venueService;
    private final AmapProperties amapProperties;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    /** 同步专用单线程执行器（串行任务，避免并发触发互相踩踏进度） */
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "amap-photo-sync");
        t.setDaemon(true);
        return t;
    });

    /** 同步运行中标记（防并发重复触发） */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 当前/最近一次同步进度（原子替换；并发可见） */
    private final AtomicReference<SyncProgress> progress = new AtomicReference<>(
            new SyncProgress(false, 0, 0, 0, 0, 0, "", List.of()));

    /**
     * 单条门店同步结果（Controller 直接下发前端展示 / 重试响应 / 纠错详情）。
     * <p>
     * 2026-08-22 扩展「匹配来源与置信度」——成功 ≠ 100% 正确（近似匹配置信
     * 60~99 需人工复核），matchedName/confidence/poiId 供管理端判断与纠错。
     *
     * @param status      SUCCESS / FAILED / SKIPPED
     * @param matchedName 匹配到的高德 POI 名称（失败 = 最近候选名；跳过 = null）
     * @param confidence  名称匹配置信度（100 = 精确；60~99 = 近似，需复核；
     *                    地址模式/失败 = null）
     * @param poiId       匹配到的高德 POI id（未来可跳高德核实）
     */
    public record SyncItem(long venueId, String name, String status, String imageUrl,
                           String message, String city, String matchedName,
                           Integer confidence, String poiId) {
        public static SyncItem of(Venue venue, String status, String imageUrl, String message,
                                  String matchedName, Integer confidence, String poiId) {
            return new SyncItem(venue.getId(), venue.getName(), status, imageUrl, message,
                    venue.getCity(), matchedName, confidence, poiId);
        }

        /** 失败/跳过等无匹配信息场景的简写。 */
        public static SyncItem of(Venue venue, String status, String imageUrl, String message) {
            return of(venue, status, imageUrl, message, null, null, null);
        }
    }

    /**
     * 门店图片状态列表项（2026-08-22 新增，工作台列表/纠错入口）。
     * 数据源 = DB 现状（qwt_venues + qwt_venue_photos 聚合）。
     */
    public record VenuePhotoStatusItem(long venueId, String name, String city, String address,
                                       String imageUrl, int photoCount) {
    }

    /** 同步进度快照（前端轮询展示：统计 + 逐条结果） */
    public record SyncProgress(boolean running, int total, int processed, int updated,
                               int failed, int skipped, String currentName,
                               List<SyncItem> items) {
        public SyncProgress {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /**
     * 高德搜索内部结果：目标 POI 的照片 + 匹配信息。
     *
     * @param matchedName 匹配到的 POI 名称（名称模式 = 最高分候选；地址模式 = 第一个
     *                    POI；无结果/名称不匹配 = null 或最近候选名）
     * @param poiId       匹配到的 POI id（地址模式/无结果 = null）
     * @param confidence  名称模式得分（100 = 精确；60~99 = 近似；地址模式/无结果 = null）
     * @param note        展示备注（名称近似匹配的置信提示，追加到 SyncItem.message）
     */
    private record SearchResult(List<String> photos, String matchedName, String poiId,
                                Integer confidence, String note) {
    }

    /** 缺图门店待同步数量（管理端按钮展示用）。 */
    public long countMissing() {
        return venueRepository.countMissingImages();
    }

    /**
     * 门店图片状态分页（2026-08-22 新增，工作台列表）：数据源 = DB 现状
     * （qwt_venues + qwt_venue_photos 聚合），非同步内存快照——服务重启不丢、
     * 与真实数据一致；同时是「成功项纠错」入口（成功 ≠ 100% 正确）。
     *
     * @param hasImage 主图有无筛选（null = 全部）
     * @param city     城市精确筛选（null/空 = 全部）
     * @param keyword  名称模糊筛选（null/空 = 全部）
     */
    public org.springframework.data.domain.Page<VenuePhotoStatusItem> listPhotoStatus(
            Boolean hasImage, String city, String keyword, int page, int size) {
        String kw = (keyword == null || keyword.isBlank())
                ? null : "%" + keyword.trim() + "%";
        String cityFilter = (city == null || city.isBlank()) ? null : city;
        org.springframework.data.domain.Pageable pageable =
                org.springframework.data.domain.PageRequest.of(page, size);
        return venueRepository.findPhotoStatusPage(hasImage, cityFilter, kw, pageable)
                .map(r -> new VenuePhotoStatusItem(
                        ((Number) r[0]).longValue(), (String) r[1], (String) r[2],
                        (String) r[3], (String) r[4], ((Number) r[5]).intValue()));
    }

    /** 当前同步进度（无任务时返回最近一次结果快照）。 */
    public SyncProgress getProgress() {
        return progress.get();
    }

    /**
     * 触发全量同步（异步执行）。已运行中返回 false（防并发重复触发）。
     * 幂等：只处理 image_url 为空的场所，可反复触发补扫失败项。
     */
    public boolean startSync() {
        String key = amapProperties.key();
        if (!amapProperties.isConfigured()) {
            throw new org.quwuting.quwutingservice.exception.BusinessException(
                    1004, "未配置高德 Web 服务 key（app.amap.key / AMAP_KEY）");
        }
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        // 重置进度（保留旧 items 无意义——新一轮覆盖）
        progress.set(new SyncProgress(true, 0, 0, 0, 0, 0, "", List.of()));
        CompletableFuture.runAsync(() -> {
            try {
                runAll(key);
            } catch (Exception e) {
                log.error("[amap-photo-sync] run failed", e);
                SyncProgress p = progress.get();
                progress.set(new SyncProgress(false, p.total(), p.processed(), p.updated(),
                        p.failed(), p.skipped(), "", p.items()));
            } finally {
                running.set(false);
            }
        }, syncExecutor);
        return true;
    }

    /**
     * 单店重匹配（同步执行，返回单条结果；2026-08-22 扩展为纠错通用入口）。
     * <p>
     * 双模式：<b>address 为空 = 名称模式</b>（按门店名走名称匹配校验，强制覆盖
     * 现有图——用于成功项人工复核后重取）；<b>address 非空 = 地址模式</b>
     * （用用户提供的精确地址作搜索关键词，地址串含省市区命中率远高于店名，
     * 覆盖"店名与高德不一致/搜不到"场景，跳过名称校验取第一个 POI）。
     */
    public SyncItem retrySync(long venueId, String address) {
        String key = amapProperties.key();
        if (!amapProperties.isConfigured()) {
            throw new org.quwuting.quwutingservice.exception.BusinessException(
                    1004, "未配置高德 Web 服务 key（app.amap.key / AMAP_KEY）");
        }
        Venue venue = venueRepository.findByIdAndDeletedFalse(venueId)
                .orElseThrow(() -> new org.quwuting.quwutingservice.exception.BusinessException(
                        1001, "门店不存在"));
        boolean nameMode = address == null || address.isBlank();
        String query = nameMode ? venue.getName() : address.trim();
        if (query == null || query.isBlank()) {
            throw new org.quwuting.quwutingservice.exception.BusinessException(
                    1001, "门店无名称且未填写地址，无法检索");
        }
        // 重匹配恒强制覆盖（overwrite=true）：人工触发的语义 = 以最新匹配结果为准
        return syncOne(venue, query, key, true, nameMode);
    }

    /**
     * 清除门店图片（2026-08-22 新增，纠错入口：人工判定当前图错配后回退）。
     * 主图 image_url 置空 + 物理删除高德导入相册（created_by=0）+ 缓存失效，
     * 门店回到「无图」状态可重新同步。幂等：门店本就无图时同样安全。
     */
    public void clearPhotos(long venueId) {
        Venue venue = venueRepository.findByIdAndDeletedFalse(venueId)
                .orElseThrow(() -> new org.quwuting.quwutingservice.exception.BusinessException(
                        1001, "门店不存在"));
        venueService.clearImportedPhotos(venueId);
        log.info("[amap-photo-sync] clear photos: venue={} ({})", venueId, venue.getName());
    }

    /** 全量同步主流程：遍历缺图门店逐条同步（单店失败不影响其他项）。 */
    private void runAll(String key) {
        List<Venue> missing = venueRepository.findMissingImages();
        int total = missing.size();
        int updated = 0, failed = 0, skipped = 0, processed = 0;
        List<SyncItem> items = new ArrayList<>();

        for (Venue venue : missing) {
            processed++;
            String query = buildSearchQuery(venue);
            progress.set(new SyncProgress(true, total, processed, updated, failed, skipped,
                    venue.getName(), new ArrayList<>(items)));
            if (query == null || query.isBlank()) {
                skipped++;
                items.add(SyncItem.of(venue, "SKIPPED", null, "无名称/城市，无法检索"));
                continue;
            }
            try {
                SyncItem item = syncOne(venue, query, key, false, true);
                items.add(item);
                if ("SUCCESS".equals(item.status())) {
                    updated++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                log.warn("[amap-photo-sync] venue {} ({}) failed: {}", venue.getId(),
                        venue.getName(), e.getMessage());
                failed++;
                items.add(SyncItem.of(venue, "FAILED", null, e.getMessage()));
            }
            try {
                Thread.sleep(RATE_LIMIT_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        progress.set(new SyncProgress(false, total, processed, updated, failed, skipped, "", items));
        log.info("[amap-photo-sync] done: total={} updated={} failed={} skipped={}",
                total, updated, failed, skipped);
    }

    /**
     * 单店同步：按查询串调高德 place/text，主图写 image_url、多图写相册表。
     * <p>
     * <b>名称匹配校验（2026-08-22 修复错配）</b>：名称模式（nameMode=true）不收集
     * 高德返回的全部 POI 照片——按名称归一化打分锁定目标 POI（阈值
     * {@link #NAME_MATCH_THRESHOLD}），杜绝「梦幻酒馆」混入「梦幻网咖」等相似名
     * 店铺照片；地址模式（nameMode=false）时用户已补精确地址，意图明确，直接取
     * 高德结果第一个 POI。
     *
     * @param query      搜索串（名称模式 = 门店名；地址模式 = 用户补的完整地址）
     * @param overwrite  是否强制覆盖已有主图（重匹配 = true；全量 = false——
     *                   已有主图且仍在匹配结果中时保留，错配则重写）
     * @param nameMode   搜索匹配模式（true = 名称匹配校验；false = 地址直取第一个 POI）
     */
    private SyncItem syncOne(Venue venue, String query, String key, boolean overwrite,
                             boolean nameMode) {
        try {
            SearchResult result = searchPoi(query, venue.getCity(), key, nameMode);
            if (result.photos().isEmpty()) {
                String hint = result.matchedName() == null
                        ? "高德未收录该店照片（可重新匹配或补充详细地址后重试）"
                        : "高德未收录照片（名称未匹配，最近候选："
                                + result.matchedName() + "；可补充详细地址后重试）";
                return SyncItem.of(venue, "FAILED", null, hint);
            }
            String imageUrl = pickPrimary(result.photos());
            String existingUrl = venue.getImageUrl();
            if (!overwrite && existingUrl != null && !existingUrl.isBlank()
                    && result.photos().contains(existingUrl)) {
                // 主图仍在本次匹配结果中（有效）：只补相册，不重写主图
                persistGalleryPhotos(venue.getId(), result.photos());
                return SyncItem.of(venue, "SUCCESS", existingUrl,
                        result.photos().size() + " 张照片，已更新相册（主图沿用）"
                                + result.note(),
                        result.matchedName(), result.confidence(), result.poiId());
            }
            // 主图缺失 / 不在匹配结果（疑似错配）→ 重写主图 + 重置相册
            venue.setImageUrl(imageUrl);
            venueRepository.save(venue);
            persistGalleryPhotos(venue.getId(), result.photos());
            return SyncItem.of(venue, "SUCCESS", imageUrl,
                    result.photos().size() + " 张照片，已取官方图床主图并入相册"
                            + result.note(),
                    result.matchedName(), result.confidence(), result.poiId());
        } catch (org.quwuting.quwutingservice.exception.BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("高德检索失败：" + e.getMessage(), e);
        }
    }

    /**
     * 高德多图写入相册表（qwt_venue_photos，PUBLIC 直发，2026-08-22 新增）：
     * V35 起 venue.photos JSON 列废弃，详情/列表轮播读 qwt_venue_photos（PUBLIC）。
     * 经 VenueService.syncGalleryPhotos 落库（平台侧导入通道：跳过
     * ImageContentValidator 存储桶白名单——高德官方图床 URL 非本应用桶前缀，
     * 走用户上传校验必然 1005 拒绝；URL 来自受信数据源，无 SSRF 面），
     * 重置式导入自带旧记录清理 + 详情/列表公共缓存失效，无需调用方去重。
     */
    private void persistGalleryPhotos(Long venueId, List<String> photos) {
        venueService.syncGalleryPhotos(venueId, photos);
    }

    /**
     * 构建全量搜索串：仅门店名（城市走 city 参数，地址不参与——地址与高德登记
     * 可能有出入，名称 + 城市已能命中绝大多数门店；人工地址只用于重试）。
     */
    private String buildSearchQuery(Venue venue) {
        String name = venue.getName() == null ? "" : venue.getName().trim();
        return name.isEmpty() ? null : name;
    }

    /**
     * 调高德 place/text 关键词搜索，返回目标 POI 的照片 + 匹配信息。
     * 必须 extensions=all 才带 photos 字段（base 不含，2026-08-21 实测）。
     *
     * @param nameMode  true = 名称模式（全量同步）：按名称打分锁定目标 POI，
     *                  拒绝相似名错配；false = 地址模式（用户补地址重试）：
     *                  意图明确，直接取结果第一个 POI。
     */
    private SearchResult searchPoi(String keywords, String city, String key, boolean nameMode)
            throws Exception {
        String url = PLACE_TEXT_URL + "?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "&keywords=" + URLEncoder.encode(keywords, StandardCharsets.UTF_8)
                + (city != null && !city.isBlank()
                        ? "&city=" + URLEncoder.encode(city, StandardCharsets.UTF_8) : "")
                + "&extensions=all&output=JSON";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "quwuting-service/1.0")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(response.body());
        int infocode = root.path("infocode").asInt(-1);
        if (infocode != 10000) {
            throw new IllegalStateException("amap infocode=" + infocode
                    + " info=" + root.path("info").asText(""));
        }
        List<JsonNode> pois = new ArrayList<>();
        root.path("pois").forEach(pois::add);
        if (pois.isEmpty()) {
            return new SearchResult(List.of(), null, null, null, "");
        }
        if (!nameMode) {
            // 地址模式：用户已核实地址，取第一个 POI（高德相关度最高）
            JsonNode poi = pois.get(0);
            return new SearchResult(photosOf(poi), poi.path("name").asText(""),
                    poi.path("id").asText(null), null,
                    "（按地址匹配：" + poi.path("name").asText("") + "）");
        }
        // 名称模式：遍历全部候选，取名称相似度最高者；低于阈值 = 不匹配（拒绝错配）
        String target = normalize(keywords);
        JsonNode best = null;
        String bestName = "";
        String bestPoiId = null;
        int bestScore = 0;
        for (JsonNode poi : pois) {
            String pname = poi.path("name").asText("");
            int score = nameScore(target, normalize(pname));
            if (score > bestScore) {
                bestScore = score;
                best = poi;
                bestName = pname;
                bestPoiId = poi.path("id").asText(null);
            }
        }
        if (best == null || bestScore < NAME_MATCH_THRESHOLD) {
            // 未找到名称匹配：返回空照片 + 最近候选名（供管理端判断/补地址重试）
            return new SearchResult(List.of(), bestName, bestPoiId, bestScore, "");
        }
        String note = bestScore >= 100
                ? "" : "（名称近似匹配：" + bestName + "，置信 " + bestScore + "）";
        return new SearchResult(photosOf(best), bestName, bestPoiId, bestScore, note);
    }

    /** 提取单个 POI 的照片 URL 列表（按高德返回顺序）。 */
    private List<String> photosOf(JsonNode poi) {
        List<String> photos = new ArrayList<>();
        for (JsonNode photo : poi.path("photos")) {
            String u = photo.path("url").asText("");
            if (!u.isBlank()) {
                photos.add(u);
            }
        }
        return photos;
    }

    /** 名称归一化：去首尾空白 + 全角转半角 + 去内部空白（「梦幻 酒馆」==「梦幻酒馆」）。 */
    private String normalize(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.trim().toCharArray()) {
            if (c >= '\uFF01' && c <= '\uFF5E') {
                c = (char) (c - 0xFEE0); // 全角 → 半角
            } else if (c == '\u3000') {
                c = ' ';
            }
            if (!Character.isWhitespace(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /**
     * 名称相似度打分（0~100）：归一化后完全相等 = 100；互为子串且短串 ≥ 2 字 =
     * 按短串长度占比（下限 60，容忍「梦幻酒馆」vs「梦幻酒馆(都市晴园店)」这类
     * 后缀/前缀差异）；其余 = 0（「梦幻酒馆」vs「梦幻网咖」零分，杜绝错配）。
     */
    private int nameScore(String target, String candidate) {
        if (target.isEmpty() || candidate.isEmpty()) {
            return 0;
        }
        if (target.equals(candidate)) {
            return 100;
        }
        String longer = target.length() >= candidate.length() ? target : candidate;
        String shorter = longer.equals(target) ? candidate : target;
        if (shorter.length() >= 2 && longer.contains(shorter)) {
            return Math.max(60, (int) (shorter.length() * 100.0 / longer.length()));
        }
        return 0;
    }

    /** 主图选择：官方图床优先，无官方图回退第一条。 */
    private String pickPrimary(List<String> photos) {
        for (String u : photos) {
            if (u.contains(OFFICIAL_IMAGE_HOST)) {
                return u;
            }
        }
        return photos.get(0);
    }
}
