package org.quwuting.quwutingservice.favorite.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.favorite.entity.Favorite;
import org.quwuting.quwutingservice.favorite.repository.FavoriteRepository;
import org.quwuting.quwutingservice.venue.dto.response.VenueResponse;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.mapper.VenueResponseMapper;
import org.quwuting.quwutingservice.venue.repository.VenueViewRepository;
import org.quwuting.quwutingservice.venue.service.VenueHeatService;
import org.quwuting.quwutingservice.venue.service.VenueLookupService;
import org.quwuting.quwutingservice.venuereaction.ReactionWindow;
import org.quwuting.quwutingservice.venuereaction.dto.response.ReactionBadge;
import org.quwuting.quwutingservice.venuereaction.service.VenueReactionService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        return venues.stream()
                .map(v -> venueResponseMapper.toResponse(
                        v, reactionsByVenue.getOrDefault(v.getId(), Collections.emptyList()),
                        hotVenueIds.contains(v.getId()),
                        viewCounts.getOrDefault(v.getId(), 0L)))
                .toList();
    }

    /**
     * 收藏场所（幂等：已收藏则忽略）。
     * <p>
     * 写操作即时失效热度缓存：收藏总数与近30天新增收藏是热度公式输入
     * （权重 10 / 15）。热度缓存为 VenueHeatService 内嵌 LoadingCache，
     * 通过显式 invalidate 逐出（refresh 周期仅兜底）。
     */
    @Transactional
    public void addFavorite(Long userId, Long venueId) {
        venueLookupService.findById(venueId); // 存在性校验（缓存命中时 <1ms）

        var existing = favoriteRepository.findByUserIdAndVenueId(userId, venueId);
        if (existing.isPresent()) {
            Favorite fav = existing.get();
            if (!fav.isDeleted()) {
                return; // 已收藏，幂等（无写入，不需逐出缓存）
            }
            fav.setDeleted(false); // 重新收藏（恢复逻辑删除的记录）
            favoriteRepository.save(fav);
            venueHeatService.invalidate(venueId);
            return;
        }

        Favorite fav = new Favorite();
        fav.setUserId(userId);
        fav.setVenueId(venueId);
        try {
            favoriteRepository.save(fav);
        } catch (DataIntegrityViolationException e) {
            // 并发竞态：另一请求已插入，幂等忽略
            log.debug("addFavorite 并发冲突，幂等忽略: userId={}, venueId={}", userId, venueId);
        }
        venueHeatService.invalidate(venueId);
    }

    /** 取消收藏（幂等：未收藏则忽略），同步失效热度缓存（理由同 {@link #addFavorite}） */
    @Transactional
    public void removeFavorite(Long userId, Long venueId) {
        favoriteRepository.findByUserIdAndVenueId(userId, venueId)
                .filter(fav -> !fav.isDeleted())
                .ifPresent(fav -> {
                    fav.setDeleted(true);
                    favoriteRepository.save(fav);
                    venueHeatService.invalidate(venueId);
                });
    }
}
