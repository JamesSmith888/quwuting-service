package org.quwuting.quwutingservice.venuesync.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.config.CacheConfig;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.entity.VenueStatusLog;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venue.repository.VenueStatusLogRepository;
import org.quwuting.quwutingservice.venue.service.VenueService;
import org.quwuting.quwutingservice.venuesync.dto.request.CreateVenueItem;
import org.quwuting.quwutingservice.venuesync.dto.response.BatchCreateVenueResponse;
import org.quwuting.quwutingservice.venuesync.dto.response.CreateVenueItemResult;
import org.quwuting.quwutingservice.venuesync.dto.response.VenueExportItem;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 门店数据接口服务（2026-09-01，舞讯采集 Skill 专用）。
 * <p>
 * 定位：给「采集舞讯 → 比对 → 一键录入/更新状态」的 Skill/Agent 提供数据底座与
 * 写库入口，与 {@link VenueSyncReportService}（Web 后台报告流程）职责分离：
 * <ul>
 *   <li>exportVenues    — 候选门店按量加载（city/status 筛选 + 大页，轻量字段）；</li>
 *   <li>batchCreateVenues — 批量新增门店（同城同名归一化幂等，Agent 来源审计）。</li>
 * </ul>
 * 状态更新<b>不</b>在此重复实现——Skill 直接复用
 * {@code POST /admin/venue-daily-openings/batch}（{@link org.quwuting.quwutingservice.venue.dailyopening.service.DailyOpeningService}
 * 的反转语义 + 审计链 + 关注者通知 + 缓存失效是权威实现，避免再造一套）。
 * <p>
 * 审计约定：批量建档的 statusLog.changedBy = null（null = 系统/Agent 来源，人工编辑 =
 * userId），与管线反转（{@code DailyOpeningService}）同口径——Web 后台「更新记录」按
 * changedBy IS NULL 统一追踪系统/Agent 动作。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VenueSyncDataService {

    /** 导出单页上限（Skill 按城市拉取，一城几十家；500 留足余量） */
    private static final int MAX_EXPORT_SIZE = 500;
    /** 批量新增单批上限（与 BatchCreateVenueRequest @Size 对齐，Service 侧兜底防绕过） */
    private static final int MAX_BATCH_CREATE = 100;

    private final VenueRepository venueRepository;
    private final VenueStatusLogRepository venueStatusLogRepository;
    private final VenueService venueService;
    private final CacheManager cacheManager;
    private final org.quwuting.quwutingservice.announcement.service.AnnouncementService announcementService;

    // ===== 候选门店导出（GET /admin/venue-sync/venues/export） =====

    /**
     * 候选门店分页导出：city/status 精确筛选 + id 升序稳定翻页。
     * 返回精简字段（id/name/city/district/address/status），供 Skill 建内存索引后
     * 与舞讯条目做城市 + 名称（+地址）多维度比对。
     */
    @Transactional(readOnly = true)
    public Page<VenueExportItem> exportVenues(String city, VenueStatus status, int page, int size) {
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), MAX_EXPORT_SIZE);
        return venueRepository.findExportPage(blankToNull(city), status, PageRequest.of(p, s))
                .map(v -> new VenueExportItem(
                        v.getId(), v.getName(), v.getCity(), v.getDistrict(),
                        v.getAddress(), v.getStatus().name()));
    }

    // ===== 批量新增（POST /admin/venue-sync/venues/batch-create） =====

    /**
     * 批量新增门店（Skill「一键录入」）。
     * <p>
     * 逐条独立提交（<b>外层不挂 @Transactional</b>）：{@code venueRepository.save()}
     * 各自独立事务提交，单条失败不拖累同批其他门店（与 GeocodeService.backfillAll
     * 「禁跨批大事务」哲学一致——连接池仅 5 连接）。
     * <p>
     * 幂等：同城 + 名称归一化（去空白/小写/全角括号转半角）判重，命中返回 EXISTED
     * （不回滚、不重复建档）；批内重复条目同样被第二次判重拦截。
     * <p>
     * 字段约定：仅 name/city 必填（舞讯能提供的信息），district/address/status 选填
     * （status 缺省 OPEN）；营业时段/门票/照片等业务字段批量建档不提供，走默认值，
     * 后续人工在门店详情页补全（与「确认态 / 未经核实」哲学一致：批量建档只保证
     * 基础信息正确，精细化信息留人工）。
     */
    public BatchCreateVenueResponse batchCreateVenues(List<CreateVenueItem> items) {
        if (items == null || items.isEmpty()) {
            throw new BusinessException(1001, "新增门店列表不能为空");
        }
        if (items.size() > MAX_BATCH_CREATE) {
            throw new BusinessException(1001, "单次最多新增 " + MAX_BATCH_CREATE + " 家门店");
        }

        int created = 0, existed = 0, failed = 0;
        List<CreateVenueItemResult> results = new ArrayList<>(items.size());
        // 同城门店缓存：幂等判重按城市一次性加载 + 内存归一化比对，避免逐条查库
        Map<String, List<Venue>> venuesByCity = new HashMap<>();

        for (int i = 0; i < items.size(); i++) {
            CreateVenueItem item = items.get(i);
            String name = blankToNull(item.name());
            String city = blankToNull(item.city());
            String message = validateItem(name, city, item);
            if (message != null) {
                failed++;
                results.add(new CreateVenueItemResult(i, name, city, "FAILED", null, message));
                continue;
            }
            List<Venue> cityVenues = venuesByCity.computeIfAbsent(city, venueRepository::findByCityAndDeletedFalse);
            Venue existing = findByNameNormalized(cityVenues, name);
            if (existing != null) {
                existed++;
                results.add(new CreateVenueItemResult(
                        i, name, city, "EXISTED", existing.getId(), "同城同名已存在（门店 " + existing.getId() + "）"));
                continue;
            }
            try {
                Venue venue = new Venue();
                venue.setName(name);
                venue.setCity(city);
                venue.setDistrict(blankToNull(item.district()));
                venue.setAddress(blankToNull(item.address()));
                venue.setStatus(item.status() != null ? item.status() : VenueStatus.OPEN);
                Venue saved = venueRepository.save(venue);
                // 初始状态日志：建立审计链起点（fromStatus=null = 首次建档；changedBy=null = Agent 来源）
                VenueStatusLog statusLog = new VenueStatusLog();
                statusLog.setVenueId(saved.getId());
                statusLog.setFromStatus(null);
                statusLog.setToStatus(saved.getStatus());
                statusLog.setChangedBy(null);
                venueStatusLogRepository.save(statusLog);
                created++;
                results.add(new CreateVenueItemResult(i, name, city, "CREATED", saved.getId(), null));
            } catch (Exception e) {
                log.warn("[venue-sync] batch create failed: {} / {}: {}", city, name, e.getMessage());
                failed++;
                results.add(new CreateVenueItemResult(i, name, city, "FAILED", null, "创建失败：" + e.getMessage()));
            }
        }

        // 新店出现：列表/热门/城市统计缓存统一失效（与 createVenue 写路径同口径——
        // 列表 60s 缓存、hotIds 5min、cityStats 5min 都是全局维度，逐店失效无意义）
        if (created > 0) {
            venueService.invalidateVenueListCache();
            evictCacheAll(CacheConfig.CACHE_HOT_VENUE_IDS);
            evictCacheAll(CacheConfig.CACHE_CITY_STATS);
            // 数据更新公告（2026-09-01，docs/agents/34）：新店录入成功触发 SYSTEM 公告；
            // 开关关闭/同日已存在 → 内部幂等跳过，不干扰写库主流程
            announcementService.createDataUpdateAnnouncement(created, 0);
        }
        log.info("[venue-sync] batch create done: total={} created={} existed={} failed={}",
                items.size(), created, existed, failed);
        return new BatchCreateVenueResponse(items.size(), created, existed, failed, results);
    }

    // ===== 辅助 =====

    /** 逐条校验：null/空白/超长返回错误文案（null = 通过） */
    private static String validateItem(String name, String city, CreateVenueItem item) {
        if (name == null || city == null) {
            return "名称与城市必填";
        }
        if (name.length() > 100) {
            return "名称最长 100 字符";
        }
        if (item.district() != null && item.district().length() > 50) {
            return "区县最长 50 字符";
        }
        if (item.address() != null && item.address().length() > 200) {
            return "地址最长 200 字符";
        }
        return null;
    }

    /** 名称归一化（对齐管线 matcher._norm 口径：去空白 + 小写 + 全角括号转半角） */
    private static String normName(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().toLowerCase()
                .replace(" ", "")
                .replace("\u3000", "")
                .replace("（", "(")
                .replace("）", ")");
    }

    /** 同城门店集合中按归一化名称判重 */
    private static Venue findByNameNormalized(List<Venue> venues, String name) {
        String target = normName(name);
        for (Venue v : venues) {
            if (normName(v.getName()).equals(target)) {
                return v;
            }
        }
        return null;
    }

    /** 缓存整域逐出（hotIds/cityStats 用；key 无关调用方，全部失效最简） */
    private void evictCacheAll(String cacheName) {
        org.springframework.cache.Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.clear();
        }
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
