package org.quwuting.quwutingservice.dancer.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.dancer.DancerTagCode;
import org.quwuting.quwutingservice.dancer.dto.response.DancerTagStat;
import org.quwuting.quwutingservice.dancer.enums.DancerPhotoKind;
import org.quwuting.quwutingservice.dancer.enums.DancerServiceCategory;
import org.quwuting.quwutingservice.dancer.enums.DancerSortMode;
import org.quwuting.quwutingservice.dancer.enums.DancerVenueRelation;
import org.quwuting.quwutingservice.dancer.repository.DancerPhotoRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerRecognitionTagRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerServiceRepository;
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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 舞伴公开列表「用户无关部分」聚合缓存（2026-08-22 性能根因修复，性能核心之二）。
 * <p>
 * <b>根因</b>：DancerService#listPublic 原本对每次列表请求顺序执行约 7 次 DB 往返——
 * 主查询（findPublicPage：认可全表聚合子查询 + 收藏聚合子查询 + 排序）一次 +
 * buildSummaries 六次批量查询（Top 标签 / 常驻舞厅名 / 封面 / 个人今日认可 ×2 / 累计浏览量）。
 * Supabase 为跨洲远程库、单次往返 300~500ms，列表接口实测 2~3.5s 慢加载；
 * 其中绝大多数查询与「当前请求用户」无关（纯舞伴级聚合），却在每个请求重复执行。
 * <p>
 * <b>方案</b>（对齐 {@link DancerDetailCacheService} 的 refresh-ahead 缓存范式，
 * 与门店 {@code hotVenueIds} 的 allEntries 失效先例）：把与用户无关的列表部分
 * （主查询行 + Top 标签 + 常驻舞厅名 + 封面 + 累计浏览量）整体打包为一个
 * Caffeine LoadingCache 条目——key = city|serviceCategory|sort|page|size
 * （sort 为 2026-08-26 晚排序模式维度，HOT/LATEST 各自缓存）：
 * <ul>
 *   <li>60s refresh-ahead + 30min 绝对过期 + 500 条上限（同 DancerDetailCacheService）；</li>
 *   <li>新鲜度主保障是写路径显式失效——失效分两级（2026-08-30 精失效根因修复，
 *       见「失效分级」）：<b>精失效</b> {@link #invalidateByDancerId}（仅影响该舞伴
 *       排序分/行内容的写操作——认可 toggle / 收藏 add·remove / 积分解锁 /
 *       照片增删审，按反向索引只清该舞伴所在的列表条目）；<b>全清</b>
 *       {@link #invalidateAll}（成员资格可能变化的写操作——新建舞伴 / 编辑城市·
 *       昵称 / 服务类别增删 / 状态流转 HIDDEN↔NORMAL，条目数小、写频率低，
 *       全清成本 = 下次请求回源一次，对齐门店 hotVenueIds 先例）；</li>
 *   <li><b>用户相关状态不进缓存</b>：个人「今日已认可」ID 集合 / 今日投票标签
 *       （myTodayIds / myTagsById）恒实时查询——列表卡片 chips 活跃态是个人态，
 *       严禁进用户无关缓存（对齐 DancerDetailCacheService 的用户相关态边界）。</li>
 * </ul>
 * 效果：60s 窗口内重复进列表，DB 往返从 ~7 次降到 ~2 次（个人态两个实时查询），
 * Supabase 抖动影响面同步收窄。
 * <p>
 * <b>失效分级根因（2026-08-30）</b>：旧实现一律 {@code invalidateAll()} 全清，
 * 而认可 toggle（12h 内 34 次）等<b>高频排序信号写</b>每次都清空全部城市×分页×排序
 * 条目 → 下一个用户进入必然全量回源，列表缓存命中率实际很低（线上每次进列表都在
 * miss 区间）。修复 = 反向索引（dancerId → 所在缓存 key）精失效：排序信号写只清
 * 该舞伴所在条目，命中率大幅回升。边界说明：排序分变化可能令舞伴跨页移动（移入
 * 未含该舞伴的相邻页）——该页最迟由 refreshAfterWrite（60s）自愈，符合缓存既有
 * 新鲜度契约；全清仍保留给成员资格写（新建/城市/服务类别/状态流转，低频）。
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
    /** 舞伴服务范围（2026-08-24：线上服务标识批量判定） */
    private final DancerServiceRepository dancerServiceRepository;
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
            Map<Long, List<DancerMediaBrief>> mediaPreviewsById,
            /**
             * 提供线上服务的舞伴 id 集合（2026-08-24：存在 ≥1 个在用且类别为
             * ONLINE_CHAT（线上聊天）的服务——列表卡片「线上」胶囊数据源；
             * 用户无关可缓存）。
             */
            Set<Long> onlineServiceDancerIds
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
            // 2026-08-30 精失效配套：条目被清除（显式失效/过期/驱逐）时同步清理反向索引
            .removalListener((String key, ListPublicPart value, com.github.benmanes.caffeine.cache.RemovalCause cause) ->
                    unregisterIndex(key))
            .build(this::compute);

    /**
     * 反向索引（2026-08-30 精失效配套）：dancerId → 包含该舞伴行的缓存 key 集合。
     * 与 {@link #keyDancerIds} 双向维护；get 命中/装载成功后登记，removalListener
     * 清除时注销（驱逐/过期/显式失效都经 removalListener，索引不泄漏）。
     */
    private final ConcurrentMap<Long, Set<String>> dancerKeys = new ConcurrentHashMap<>();

    /** 反向索引（key → 该条目包含的 dancerId 集合），removalListener 注销用 */
    private final ConcurrentMap<String, Set<Long>> keyDancerIds = new ConcurrentHashMap<>();

    /**
     * 获取列表公共部分（缓存：单飞 + refresh-ahead；首次 miss 全量聚合一次）。
     * 成功后登记反向索引（幂等覆盖——刷新产生的同 key 新值以最新行集为准）。
     *
     * @param city            城市筛选（null = 全部，与 DancerService.listPublic 同参数语义）
     * @param serviceCategory 服务类别筛选（2026-08-24 需求优先匹配；null = 全部——
     *                        命中"存在 ≥1 个在用且类别匹配的服务"的舞伴）
     * @param sort            排序模式（DancerSortMode.name()：HOT/LATEST，2026-08-26 晚——
     *                        参与缓存 key，两种排序各自缓存互不干扰）
     * @param pageable        分页（page/size 参与缓存 key；compute 内重新构造 Pageable 查询）
     */
    public ListPublicPart get(String city, String serviceCategory, String sort, Pageable pageable) {
        String k = key(city, serviceCategory, sort, pageable.getPageNumber(), pageable.getPageSize());
        ListPublicPart part = cache.get(k);
        registerIndex(k, part);
        return part;
    }

    /**
     * 精失效（2026-08-30 新增，排序信号写路径统一入口）：按 dancerId 清掉该舞伴
     * 所在的所有列表条目（含全城市/多分页/双排序），下次请求回源重算。
     * 反向索引未命中（该舞伴不在任何缓存条目）→ 零操作（无回源浪费）。
     *
     * @see #invalidateAll() 成员资格写（新建/城市/服务类别/状态流转）仍走全清
     */
    public void invalidateByDancerId(Long dancerId) {
        if (dancerId == null) {
            return;
        }
        Set<String> keys = dancerKeys.get(dancerId);
        if (keys == null || keys.isEmpty()) {
            return;
        }
        // 快照遍历：cache.invalidate 同步触发 removalListener 修改 dancerKeys，防 ConcurrentModification
        for (String k : new ArrayList<>(keys)) {
            cache.invalidate(k);
        }
    }

    /** 批量精失效（多舞伴写路径——暂无调用方，预留对称 API；语义 = 逐个 {@link #invalidateByDancerId}） */
    public void invalidateByDancerIds(Collection<Long> dancerIds) {
        if (dancerIds == null || dancerIds.isEmpty()) {
            return;
        }
        for (Long id : dancerIds) {
            invalidateByDancerId(id);
        }
    }

    /**
     * 写路径显式全清（成员资格写专用）：新建舞伴 / 编辑（城市·昵称等行内容）/
     * 服务类别增删 / 状态流转（HIDDEN↔NORMAL）——这些操作改变「哪些舞伴出现在
     * 哪些列表条目」，按 dancerId 反向索引无法覆盖"未含该舞伴的条目"（新成员
     * 可能移入任意条目），必须全清。此类写低频（管理员/本人操作），全清成本 =
     * 下次请求回源一次，可接受。removalListener 顺带清理反向索引。
     */
    public void invalidateAll() {
        cache.invalidateAll();
    }

    /** 缓存 key（城市名/类别 code/排序来自标准词表与枚举，不含 '|'，安全分隔） */
    private static String key(String city, String serviceCategory, String sort, int page, int size) {
        return (city == null ? "" : city) + "|" + (serviceCategory == null ? "" : serviceCategory)
                + "|" + (sort == null ? "" : sort) + "|" + page + "|" + size;
    }

    /** 登记反向索引（幂等：refresh 同 key 新行集覆盖旧登记；条目行集为空不登记） */
    private void registerIndex(String key, ListPublicPart part) {
        if (part == null || part.rows().isEmpty()) {
            return;
        }
        Set<Long> ids = new HashSet<>(part.rows().size());
        for (Object[] row : part.rows()) {
            ids.add((Long) row[0]);
        }
        if (ids.isEmpty()) {
            return;
        }
        keyDancerIds.put(key, ids);
        for (Long id : ids) {
            dancerKeys.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(key);
        }
    }

    /** 注销反向索引（removalListener：显式失效/过期/驱逐统一入口，索引与缓存条目生命周期一致） */
    private void unregisterIndex(String key) {
        Set<Long> ids = keyDancerIds.remove(key);
        if (ids == null) {
            return;
        }
        for (Long id : ids) {
            dancerKeys.computeIfPresent(id, (k, keys) -> {
                keys.remove(key);
                return keys.isEmpty() ? null : keys; // 空集合 → 移除 dancerId 键（防空集合泄漏）
            });
        }
    }

    /** 聚合计算（缓存 loader，勿直接调用——经 {@link #get} 走缓存）。 */
    private ListPublicPart compute(String key) {
        String[] parts = key.split("\\|", -1);
        String city = parts[0].isEmpty() ? null : parts[0];
        String serviceCategory = parts[1].isEmpty() ? null : parts[1];
        // 2026-08-26 晚：排序模式参与缓存 key（HOT/LATEST 各自缓存）；空 = 旧条目兜底 HOT
        String sort = parts[2].isEmpty() ? DancerSortMode.HOT.name() : parts[2];
        int page = Integer.parseInt(parts[3]);
        int size = Integer.parseInt(parts[4]);
        LocalDateTime now = LocalDateTime.now();
        Page<Object[]> rows = dancerRepository.findPublicPage(
                sort,
                city, serviceCategory,
                LocalDate.now().atStartOfDay() /* 今日锚点 = 今日0点 */,
                now.minusDays(7), now.minusDays(30),
                now.minusDays(14) /* 新舞伴保护期窗口 */,
                now.minusDays(3) /* 新鲜度窗口（相册/联系方式 3 天内更新） */,
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
                fetchMediaPreviews(dancerIds),
                fetchOnlineServiceDancerIds(dancerIds));
    }

    /** 批量「提供线上服务」舞伴 id（2026-08-24：复用服务类别批量判定，一次 IN 查询） */
    private Set<Long> fetchOnlineServiceDancerIds(List<Long> dancerIds) {
        if (dancerIds.isEmpty()) {
            return Collections.emptySet();
        }
        return new HashSet<>(dancerServiceRepository.findDancerIdsByCategoryIn(
                dancerIds, DancerServiceCategory.ONLINE_CHAT));
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
