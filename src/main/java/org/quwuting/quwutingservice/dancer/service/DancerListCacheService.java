package org.quwuting.quwutingservice.dancer.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.dancer.DancerTagCode;
import org.quwuting.quwutingservice.dancer.dto.response.DancerTagStat;
import org.quwuting.quwutingservice.dancer.enums.DancerPhotoKind;
import org.quwuting.quwutingservice.dancer.enums.DancerVenueRelation;
import org.quwuting.quwutingservice.dancer.repository.DancerPhotoRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerRecognitionTagRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerVenueRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerViewRepository;
import org.quwuting.quwutingservice.tagdict.dto.response.TagItemResponse;
import org.quwuting.quwutingservice.tagdict.service.TagDictService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 舞伴公开列表「用户无关部分」聚合缓存（2026-08-22 性能根因修复，性能核心之二）。
 * <p>
 * <b>根因</b>：DancerService#listPublic 原本对每次列表请求顺序执行约 7 次 DB 往返——
 * 主查询（findPublicPage：认可全表聚合子查询 + 积分聚合子查询 + 排序）一次 +
 * buildSummaries 六次批量查询（Top 标签 / 常驻舞厅名 / 封面 / 个人今日认可 ×2 / 累计浏览量）。
 * Supabase 为跨洲远程库、单次往返 300~500ms，列表接口实测 2~3.5s 慢加载；
 * 其中绝大多数查询与「当前请求用户」无关（纯舞伴级聚合），却在每个请求重复执行。
 * <p>
 * <b>方案</b>（对齐 {@link DancerDetailCacheService} 的 refresh-ahead 缓存范式，
 * 与门店 {@code hotVenueIds} 的 allEntries 失效先例）：把与用户无关的列表部分
 * （主查询行 + Top 标签 + 常驻舞厅名 + 封面 + 累计浏览量）整体打包为一个
 * Caffeine LoadingCache 条目——key = city|page|size：
 * <ul>
 *   <li>60s refresh-ahead + 30min 绝对过期 + 500 条上限（同 DancerDetailCacheService）；</li>
 *   <li>新鲜度主保障是写路径显式 {@link #invalidateAll()}（唯一失效入口）——
 *       认可 toggle / 资料编辑 / 照片增删审 / 状态流转 / 认证流转 / 新建舞伴等
 *       <b>改变公开列表行内容或排序</b>的写操作后全清（列表条目数 = 城市×页数，
 *       数据量小，全清成本 = 下次请求回源一次，可接受；对齐门店 hotVenueIds
 *       @CacheEvict(allEntries=true) 先例）；收藏 add·remove 不改公开列表内容
 *       （DancerSummaryResponse 无收藏态字段，收藏 Tab 走个性化接口不进本缓存），
 *       豁免失效（详情缓存已覆盖）；</li>
 *   <li><b>用户相关状态不进缓存</b>：个人「今日已认可」ID 集合 / 今日投票标签
 *       （myTodayIds / myTagsById）恒实时查询——列表卡片 chips 活跃态是个人态，
 *       严禁进用户无关缓存（对齐 DancerDetailCacheService 的用户相关态边界）。</li>
 * </ul>
 * 效果：60s 窗口内重复进列表，DB 往返从 ~7 次降到 ~2 次（个人态两个实时查询），
 * Supabase 抖动影响面同步收窄。
 * <p>
 * <b>失效豁免（不入失效矩阵，靠 refresh 兜底）</b>：浏览量（recordView）/ 分享 /
 * 广告浏览——浏览量是卡片右下角弱信息（60s 陈旧可接受），且浏览量写入高频
 * （每次详情浏览都触发），显式失效会让列表缓存被频繁全清、失去缓存意义；
 * 认可/收藏/编辑等「改变列表排序或行内容」的写路径才显式失效。
 */
@Service
@RequiredArgsConstructor
public class DancerListCacheService {

    /** 缓存刷新间隔：写入 60s 后下一次访问触发异步回源（refresh-ahead，同 DancerDetailCacheService） */
    private static final long CACHE_REFRESH_SECONDS = 60;

    /** 缓存绝对过期：30 分钟（防长期无访问的陈旧条目常驻） */
    private static final long CACHE_EXPIRE_MINUTES = 30;

    /** 缓存容量上限（城市 × 分页组合，舞伴数据量小，500 条足够覆盖活跃入口） */
    private static final int MAX_CACHE_SIZE = 500;

    /** 每舞伴媒体预览条数上限（2026-08-24 晚：列表卡片多图预览，消息预览式——
     *  照片+视频混合取前 N 个 PUBLIC 媒体；N 恒为展示序最小的 4 个，防列表卡片过高） */
    public static final int LIST_MEDIA_PREVIEW_LIMIT = 4;

    private final DancerRepository dancerRepository;
    private final DancerRecognitionTagRepository recognitionTagRepository;
    private final DancerVenueRepository dancerVenueRepository;
    private final DancerPhotoRepository photoRepository;
    private final DancerViewRepository dancerViewRepository;
    /** 通用标签字典（2026-08-24：资料标签反序列化 + 字典解析） */
    private final TagDictService tagDictService;

    /**
     * 媒体预览简报（2026-08-24 晚，缓存内用户无关部分）：dancerId → 前 N 个 PUBLIC
     * 媒体的基础信息。url/coverUrl 为清晰版——仅服务端内存持有，组装响应时按当前
     * 用户解锁态选择下发（未解锁只下 blurUrl，见 DancerMediaPreviewResponse）。
     */
    public record DancerMediaBrief(
            Long id,
            DancerPhotoKind kind,
            String url,
            String blurUrl,
            String coverUrl,
            int cost,
            int durationSeconds
    ) {}

    /**
     * 列表用户无关的批量 enrichments（一次快照；消费方只读，不共享可变状态）。
     * 与 {@link DancerService#listPublic} 的 buildSummaries 消费侧一一对应——
     * 缓存命中时个人态（myTodayIds / myTagsById / unlockedMediaIds）仍在调用方
     * 实时查询后合并。
     */
    public record ListEnrichments(
            Map<Long, List<DancerTagStat>> tagsById,
            Map<Long, String> homeVenueNameById,
            Map<Long, String> coverPhotoUrlById,
            Map<Long, Long> viewCounts,
            /** 资料标签（2026-08-24：dancerId → TagItemResponse 列表，卡片长按弹说明文案） */
            Map<Long, List<TagItemResponse>> profileTagsById,
            /** 媒体预览简报（2026-08-24 晚：dancerId → 前 N 个 PUBLIC 媒体，列表卡片多图预览） */
            Map<Long, List<DancerMediaBrief>> mediaPreviewsById
    ) {}

    /** 列表公共部分（用户无关的主查询行 + enrichments；totalElements 随行缓存避免重复计数） */
    public record ListPublicPart(
            List<Object[]> rows,
            long totalElements,
            ListEnrichments enrichments
    ) {}

    private final LoadingCache<String, ListPublicPart> cache = Caffeine.newBuilder()
            .maximumSize(MAX_CACHE_SIZE)
            .refreshAfterWrite(CACHE_REFRESH_SECONDS, TimeUnit.SECONDS)
            .expireAfterWrite(CACHE_EXPIRE_MINUTES, TimeUnit.MINUTES)
            .build(this::compute);

    /**
     * 获取列表公共部分（缓存：单飞 + refresh-ahead；首次 miss 全量聚合一次）。
     *
     * @param city     城市筛选（null = 全部，与 DancerService.listPublic 同参数语义）
     * @param pageable 分页（page/size 参与缓存 key；compute 内重新构造 Pageable 查询）
     */
    public ListPublicPart get(String city, Pageable pageable) {
        return cache.get(key(city, pageable.getPageNumber(), pageable.getPageSize()));
    }

    /**
     * 写路径显式失效（唯一失效入口）：改变列表行内容/排序的写操作后调用。
     * 全清而非按 dancerId 精失效——列表条目数 = 城市×页数（小），且写频率低，
     * 全清成本 = 下次请求回源一次；按 dancerId 需维护反向索引，复杂度收益不成比例。
     */
    public void invalidateAll() {
        cache.invalidateAll();
    }

    /** 缓存 key（城市名来自标准行政区划词表，不含 '|'，安全分隔） */
    private static String key(String city, int page, int size) {
        return (city == null ? "" : city) + "|" + page + "|" + size;
    }

    /** 聚合计算（缓存 loader，勿直接调用——经 {@link #get} 走缓存）。 */
    private ListPublicPart compute(String key) {
        String[] parts = key.split("\\|", -1);
        String city = parts[0].isEmpty() ? null : parts[0];
        int page = Integer.parseInt(parts[1]);
        int size = Integer.parseInt(parts[2]);
        LocalDateTime now = LocalDateTime.now();
        Page<Object[]> rows = dancerRepository.findPublicPage(
                city, LocalDate.now().atStartOfDay() /* 今日锚点 = 今日0点 */,
                now.minusDays(7), now.minusDays(30),
                org.springframework.data.domain.PageRequest.of(page, size));
        List<Long> ids = rows.getContent().stream().map(r -> (Long) r[0]).toList();
        return new ListPublicPart(rows.getContent(), rows.getTotalElements(), computeEnrichments(ids));
    }

    /**
     * 批量用户无关 enrichments（单一权威，公开供 DancerService 的收藏列表/我的舞伴主页
     * 复用——它们是个性化列表不进本缓存，但 enrichments 计算口径必须与公开列表一致）：
     * Top 标签 / 常驻舞厅名 / 封面 / 累计浏览量 各一次 IN 批量查询，规避 N+1。
     */
    public ListEnrichments computeEnrichments(List<Long> dancerIds) {
        return new ListEnrichments(
                fetchTopTags(dancerIds),
                fetchHomeVenueNames(dancerIds),
                fetchCoverPhotoUrls(dancerIds),
                fetchViewCounts(dancerIds),
                fetchProfileTags(dancerIds),
                fetchMediaPreviews(dancerIds));
    }

    /** 批量标签聚合（全量，同 DancerService.fetchTopTags 口径——列表卡片 chips 数据源） */
    private Map<Long, List<DancerTagStat>> fetchTopTags(List<Long> dancerIds) {
        if (dancerIds.isEmpty()) {
            return Collections.emptyMap();
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime sinceToday = LocalDate.now().atStartOfDay();
        LocalDateTime since7d = now.minusDays(7);
        LocalDateTime since30d = now.minusDays(30);
        Map<Long, List<DancerTagStat>> result = new HashMap<>();
        for (Object[] row : recognitionTagRepository.aggregateByDancerIds(dancerIds, sinceToday, since7d, since30d)) {
            Long dancerId = (Long) row[0];
            String tag = (String) row[1];
            long countAll = ((Number) row[2]).longValue();
            long countToday = ((Number) row[3]).longValue();
            long count7d = ((Number) row[4]).longValue();
            long count30d = ((Number) row[5]).longValue();
            // 2026-08-24 全放开：tag 可为 legacy 或 EmojiCatalog 目录 code（valueOf 会抛异常），
            // 统一经 DancerTagCode 适配器查 emoji/label（非法 code 防御性跳过）
            if (!DancerTagCode.isValid(tag)) continue;
            result.computeIfAbsent(dancerId, k -> new ArrayList<>())
                    .add(new DancerTagStat(tag, DancerTagCode.emojiOf(tag), DancerTagCode.labelOf(tag),
                            countAll, countToday, count7d, count30d));
        }
        return result;
    }

    /** 批量"常去"舞厅名（同 DancerService.fetchHomeVenueNames 口径：每舞伴最早一条 HOME） */
    private Map<Long, String> fetchHomeVenueNames(List<Long> dancerIds) {
        if (dancerIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> result = new HashMap<>();
        for (Object[] row : dancerVenueRepository.findVenueBriefsByDancerIds(dancerIds)) {
            Long dancerId = (Long) row[0];
            if (DancerVenueRelation.HOME.name().equals(row[5]) && !result.containsKey(dancerId)) {
                result.put(dancerId, (String) row[2]);
            }
        }
        return result;
    }

    /** 批量封面照片（同 DancerService.fetchCoverPhotoUrls 口径：每舞伴展示序最小一张 PUBLIC） */
    private Map<Long, String> fetchCoverPhotoUrls(List<Long> dancerIds) {
        if (dancerIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> result = new HashMap<>();
        for (Object[] row : photoRepository.findCoverUrlsByDancerIds(dancerIds)) {
            result.put((Long) row[0], (String) row[1]);
        }
        return result;
    }

    /** 批量累计浏览量（同 DancerService 口径：qwt_dancer_views 全量历史行数，含匿名） */
    private Map<Long, Long> fetchViewCounts(List<Long> dancerIds) {
        if (dancerIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return dancerViewRepository.countByDancerIds(dancerIds).stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> ((Number) row[1]).longValue()));
    }

    /**
     * 批量资料标签（2026-08-24）：profile_tags JSON id 数组 → 字典解析为
     * TagItemResponse 列表（text + description，卡片长按弹说明的权威文案）。
     * 两次 IN 查询（profile_tags 列 + 字典），空标签舞伴无条目（消费方
     * getOrDefault 空列表，卡片不渲染标签行）。
     */
    private Map<Long, List<TagItemResponse>> fetchProfileTags(List<Long> dancerIds) {
        if (dancerIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, List<Long>> tagIdsByDancer = new HashMap<>();
        Set<Long> allTagIds = new LinkedHashSet<>();
        for (Object[] row : dancerRepository.findProfileTagsByDancerIds(dancerIds)) {
            List<Long> tagIds = tagDictService.deserializeIds((String) row[1]);
            tagIdsByDancer.put((Long) row[0], tagIds);
            allTagIds.addAll(tagIds);
        }
        Map<Long, TagItemResponse> byId = tagDictService.resolveByIds(allTagIds);
        Map<Long, List<TagItemResponse>> result = new HashMap<>();
        for (Map.Entry<Long, List<Long>> e : tagIdsByDancer.entrySet()) {
            List<TagItemResponse> items = new ArrayList<>(e.getValue().size());
            for (Long id : e.getValue()) {
                TagItemResponse item = byId.get(id);
                if (item != null) {
                    items.add(item);
                }
            }
            result.put(e.getKey(), items);
        }
        return result;
    }

    /**
     * 批量媒体预览简报（2026-08-24 晚）：每舞伴前 {@link #LIST_MEDIA_PREVIEW_LIMIT} 个
     * PUBLIC 媒体（照片+视频混合，展示序）。gate cost 随查询 LEFT JOIN 带回（用户无关，
     * 可缓存）；清晰 url/coverUrl 仅服务端内存持有，响应组装时按解锁态选择下发。
     */
    private Map<Long, List<DancerMediaBrief>> fetchMediaPreviews(List<Long> dancerIds) {
        if (dancerIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, List<DancerMediaBrief>> result = new HashMap<>();
        for (Object[] row : photoRepository.findMediaPreviewsByDancerIds(dancerIds, LIST_MEDIA_PREVIEW_LIMIT)) {
            result.computeIfAbsent((Long) row[0], k -> new ArrayList<>())
                    .add(new DancerMediaBrief(
                            (Long) row[1],
                            DancerPhotoKind.valueOf((String) row[2]),
                            (String) row[3],
                            (String) row[4],
                            (String) row[5],
                            ((Number) row[6]).intValue(),
                            ((Number) row[7]).intValue()));
        }
        return result;
    }
}
