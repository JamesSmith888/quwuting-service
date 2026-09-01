package org.quwuting.quwutingservice.favorite.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.favorite.entity.Favorite;
import org.quwuting.quwutingservice.favorite.repository.FavoriteRepository;
import org.quwuting.quwutingservice.message.service.MessageService;
import org.quwuting.quwutingservice.venue.dto.response.VenueResponse;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.mapper.VenueResponseMapper;
import org.quwuting.quwutingservice.venue.repository.VenueViewRepository;
import org.quwuting.quwutingservice.venue.service.VenueHeatService;
import org.quwuting.quwutingservice.venue.service.VenueLookupService;
import org.quwuting.quwutingservice.venue.service.VenueService;
import org.quwuting.quwutingservice.venuecrowd.service.CrowdReportService;
import org.quwuting.quwutingservice.venuereaction.ReactionWindow;
import org.quwuting.quwutingservice.venuereaction.dto.response.ReactionBadge;
import org.quwuting.quwutingservice.venuereaction.service.VenueReactionService;
import org.quwuting.quwutingservice.venuestatuswatcher.service.VenueStatusWatcherService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FavoriteService {

    private final FavoriteRepository favoriteRepository;
    private final VenueResponseMapper venueResponseMapper;
    private final VenueReactionService venueReactionService;
    private final VenueLookupService venueLookupService;
    private final VenueHeatService venueHeatService;
    /** 浏览记录（收藏列表响应组装累计浏览量 viewCount 用，2026-08-12 新增） */
    private final VenueViewRepository venueViewRepository;
    /** 门店公开照片批量加载（2026-08-20 门店照片域：收藏卡片轮播照片数据源；无循环依赖——VenueService 不依赖本模块） */
    private final VenueService venueService;
    /** 门店热度上报（2026-08-29：收藏列表与城市列表同为 venue-card 展示场景，角标
     * 「N人报过」+ 「最新上报」行须同口径下发——同 isHot 历史缺陷模式，见
     * {@link #getFavoriteVenues} javadoc） */
    private final CrowdReportService crowdReportService;
    /** 站内信服务（收藏门店「状态更新」角标数据源，2026-09-01，见 {@link #getFavoriteVenues}） */
    private final MessageService messageService;
    /** 营业状态关注服务（「收藏即关注」2026-09-01：收藏自动建立状态通知，取消收藏同步取消） */
    private final VenueStatusWatcherService venueStatusWatcherService;

    // ── 收藏/取消收藏写操作频控（2026-08-13 防刷） ──────────────────────────
    // 根因（用户反馈："频繁/恶意点击取消收藏、收藏，统计图怎么表现才合理"）：
    // 「新增收藏」只在首次收藏时计（restore 不新增行、created_at 不变），天然防膨胀；
    // 但「取消收藏」每次真实取消都写 unfavorited_at（新时刻）→ 恶意"收藏→取消"循环
    // 会把取消折线刷高（新增只 +1、取消却 +N，口径不对称）。
    // 方案：同 user+venue 的**真实状态切换写入**（新增/恢复/取消）做 60s 窗口阈值频控
    // ——窗口内放行 TOGGLE_RATE_LIMIT_PER_WINDOW 次（正常用户 1 分钟内 toggle 极少
    // 超过 3 次，覆盖"收藏→取消→再收藏"；脚本连点被压制成窗口内最多 3 次真实写入，
    // 取消折线最多每分钟 +2，与真实操作语义一致）。
    // 与既有频控同族（VenueViewService 匿名浏览 60s、feedback 60s），内存 Caffeine
    // 单机近似（多实例下窗口放大，仍能压制脚本连点）；幂等路径（已收藏再收藏等
    // 无写入）不计数不频控——正常用户误点无害。
    private static final int TOGGLE_RATE_LIMIT_PER_WINDOW = 3;
    private static final long TOGGLE_RATE_LIMIT_SECONDS = 60;

    private final Cache<String, Integer> favoriteToggleLimiter = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(TOGGLE_RATE_LIMIT_SECONDS, TimeUnit.SECONDS)
            .build();

    /** 频控 key：user:venue（同一用户对同一场所的 toggle 行为） */
    private static String toggleKey(Long userId, Long venueId) {
        return userId + ":" + venueId;
    }

    /**
     * 写频控判定（**仅在确认会发生真实写入时调用**，调用即计数）：
     * 窗口内已达阈值 → 返回 true（本次写入幂等忽略）；否则计数 +1 并返回 false。
     */
    private boolean isToggleLimited(Long userId, Long venueId) {
        String key = toggleKey(userId, venueId);
        Integer count = favoriteToggleLimiter.getIfPresent(key);
        if (count != null && count >= TOGGLE_RATE_LIMIT_PER_WINDOW) {
            log.debug("favorite toggle 频控忽略: userId={}, venueId={}", userId, venueId);
            return true;
        }
        favoriteToggleLimiter.put(key, (count != null ? count : 0) + 1);
        return false;
    }

    /**
     * 获取用户收藏的场所列表（按收藏时间倒序）。
     * <p>
     * DB 往返压缩：收藏与场所经 {@link FavoriteRepository#findFavoriteVenuesByUserId}
     * 联查一次取回（原"查收藏列表 + 批量查场所"两步各占一次跨洲往返），
     * 加上整页 Top Reaction 徽标的批量查询（IN 一次覆盖，避免 N+1），共 2 次往返。
     * <p>
     * <b>热门标记（2026-08-08 修复）</b>：收藏列表与城市列表同为 venue-card 卡片
     * 展示场景，isHot 必须与 {@code VenueService.listVenues} 同口径下发——经
     * {@link VenueLookupService#getHotVenueIds()}（5min 缓存）取热门 ID 集合后
     * 传入 {@link VenueResponseMapper} 三参重载。历史缺陷：本方法误用双参重载
     * （默认 isHot=false），导致"全部城市列表正常展示热门标签、收藏列表却不展示"。
     */
    @Transactional(readOnly = true)
    public List<VenueResponse> getFavoriteVenues(Long userId) {
        List<Venue> venues = favoriteRepository.findFavoriteVenuesByUserId(userId);
        if (venues.isEmpty()) {
            return Collections.emptyList();
        }
        // 收藏 Tab 无窗口切换入口，徽标固定取默认窗口（近7天），与列表页默认一致
        Map<Long, List<ReactionBadge>> reactionsByVenue =
                venueReactionService.batchGetBadges(venues.stream().map(Venue::getId).toList(),
                        userId, ReactionWindow.DAYS_7);
        // 热门 ID 集合为全局缓存（5min TTL），收藏列表跨城市展示同样按"城市内
        // top 20% + 绝对门槛"标记——与城市列表同口径（见 VenueLookupService#getHotVenueIds）
        Set<Long> hotVenueIds = venueLookupService.getHotVenueIds();
        // 批量累计浏览量（2026-08-12 收藏卡片「👁 浏览数」数据源，同列表页口径：
        // qwt_venue_views 全量行数，一次 IN + GROUP BY 避免 N+1，见 VenueViewRepository#countByVenueIds）
        List<Long> venueIds = venues.stream().map(Venue::getId).toList();
        Map<Long, Long> viewCounts = venueIds.isEmpty() ? Collections.emptyMap()
                : venueViewRepository.countByVenueIds(venueIds).stream()
                        .collect(Collectors.toMap(
                                row -> (Long) row[0],
                                row -> ((Number) row[1]).longValue()));
        // 批量公开照片（2026-08-20 门店照片域：一次 IN 覆盖整页，同列表页批量模式）
        Map<Long, List<String>> photosByVenue = venueService.loadPublicPhotosByVenueIds(venueIds);
        // 批量今晚热度角标 + 「最新上报」行（2026-08-29：收藏列表与城市列表同为
        // venue-card 展示场景，须同口径——角标 ≥3 人「N人报过」，最新上报行克制版
        // 「{时间} · {标识}舞友上报」，见 CrowdReportService#badgeTextsByVenue /
        // #latestTextsByVenue；历史缺陷同 isHot：漏注入导致"全部城市正常、收藏不显示"）
        Map<Long, String> crowdBadges = crowdReportService.badgeTextsByVenue(venueIds);
        Map<Long, String> crowdLatestTexts = crowdReportService.latestTextsByVenue(venueIds);
        // 批量未读状态变更门店 ID（2026-09-01「收藏即关注」）：一次 IN 覆盖整页收藏，
        // 无未读的门店不在集合中（与整页批量模式一致，避免 N+1）。数据源 = 未读
        // VENUE_STATUS_CHANGED 站内信（收藏自动建立关注 → 状态变更即有提醒 → 角标）。
        // 打开门店详情（venue-detail onLoad 调 status-alerts/read-by-venue）后已读收敛。
        Set<Long> statusChangedVenueIds = messageService.findUnreadStatusChangedVenueIds(userId, venueIds);
        return venues.stream()
                .map(v -> venueResponseMapper.toResponse(
                        v, reactionsByVenue.getOrDefault(v.getId(), Collections.emptyList()),
                        hotVenueIds.contains(v.getId()),
                        viewCounts.getOrDefault(v.getId(), 0L),
                        photosByVenue.getOrDefault(v.getId(), List.of()),
                        crowdBadges.get(v.getId()),
                        crowdLatestTexts.get(v.getId()),
                        statusChangedVenueIds.contains(v.getId())))
                .toList();
    }

    /**
     * 收藏场所（幂等：已收藏则忽略）。
     * <p>
     * <b>「收藏即关注」联动（2026-09-01）</b>：收藏成功的同时自动建立营业状态关注
     * （{@link VenueStatusWatcherService#ensureWatching}，同事务原子提交）——用户心智
     * 「收藏 = 在意的店」，该店营业状态后续每次实际变更都收到站内信提醒（收藏列表
     * 「状态更新」角标 + 首页提醒卡）。取消收藏（{@link #removeFavorite}）同步取消
     * 关注；详情页「营业状态通知」开关仍可单独关闭（显式退订）。
     * <p>
     * 写操作即时失效热度缓存：收藏写入改变近30天新增收藏（热度公式收藏项唯一输入，
     * 权重 15，取消软删自动抵消）与收藏总数（页面展示字段，2026-09-01 起不计入公式）。
     * 热度缓存为 VenueHeatService 内嵌 LoadingCache，
     * 通过显式 invalidate 逐出（refresh 周期仅兜底）。
     */
    @Transactional
    public void addFavorite(Long userId, Long venueId) {
        venueLookupService.findById(venueId); // 存在性校验（缓存命中时 <1ms）

        var existing = favoriteRepository.findByUserIdAndVenueId(userId, venueId);
        if (existing.isPresent()) {
            Favorite fav = existing.get();
            if (!fav.isDeleted()) {
                return; // 已收藏，幂等（无写入，不需逐出缓存、不计数频控）
            }
            // 重新收藏（恢复逻辑删除的记录）：真实写入，先过频控（窗口内超阈值则幂等忽略）
            if (isToggleLimited(userId, venueId)) {
                return;
            }
            fav.setDeleted(false);
            // 清空取消时刻：该行恢复为收藏态，unfavorited_at 不能再被计为一次取消
            // （取消趋势按 unfavorited_at 分组——残留旧值会让"已恢复的收藏"误计取消）
            fav.setUnfavoritedAt(null);
            favoriteRepository.save(fav);
            venueHeatService.invalidate(venueId);
            // 收藏恢复 = 重新建立状态通知（取消收藏时已同步取消关注）
            venueStatusWatcherService.ensureWatching(userId, venueId);
            return;
        }

        // 首次收藏：真实写入，先过频控
        if (isToggleLimited(userId, venueId)) {
            return;
        }
        // 2026-08-19 根因修复：原子 upsert（ON CONFLICT DO UPDATE）替代「save + 23505
        // 异常吞掉」——Hibernate flush 失败后事务可能已被标记 rollback-only，并发重复
        // 收藏的幂等返回实际变为 HTTP 500（与 DancerService.addFavorite 同根因同修复）
        favoriteRepository.upsertFavorite(userId, venueId, java.time.LocalDateTime.now());
        venueHeatService.invalidate(venueId);
        // 收藏即关注：同一事务内建立营业状态通知（幂等，见 VenueStatusWatcherService）
        venueStatusWatcherService.ensureWatching(userId, venueId);
    }

    /**
     * 取消收藏（幂等：未收藏则忽略），同步失效热度缓存（理由同 {@link #addFavorite}）。
     * 取消时刻写入 unfavoritedAt（V19）——「收藏趋势 · 取消收藏」折线按此列按日分组，
     * 使"新增 − 取消"的净变化可被趋势图验证（见 V19 迁移注释与后端 AGENTS.md「趋势」）。
     * 真实取消写入前过频控（防频繁/恶意 toggle 刷取消折线，见类注释）。
     * <p>
     * 「收藏即关注」联动（2026-09-01）：取消收藏同步取消营业状态关注
     * （{@link VenueStatusWatcherService#unwatch}，幂等）——不再在意的店不再推送
     * 状态变更通知；未读的状态提醒站内信保留在消息中心（历史留档，不删）。
     */
    @Transactional
    public void removeFavorite(Long userId, Long venueId) {
        favoriteRepository.findByUserIdAndVenueId(userId, venueId)
                .filter(fav -> !fav.isDeleted())
                .ifPresent(fav -> {
                    // 真实取消：先过频控（窗口内超阈值则幂等忽略，不写取消时刻）
                    if (isToggleLimited(userId, venueId)) {
                        return;
                    }
                    fav.setDeleted(true);
                    fav.setUnfavoritedAt(java.time.LocalDateTime.now());
                    favoriteRepository.save(fav);
                    venueHeatService.invalidate(venueId);
                    // 取消收藏 = 取消状态通知（详情页开关仍可单独再开）
                    venueStatusWatcherService.unwatch(userId, venueId);
                });
    }
}
