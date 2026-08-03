package org.quwuting.quwutingservice.favorite.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.favorite.entity.Favorite;
import org.quwuting.quwutingservice.favorite.repository.FavoriteRepository;
import org.quwuting.quwutingservice.taginteraction.service.TagInteractionService;
import org.quwuting.quwutingservice.venue.dto.response.VenueResponse;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.mapper.VenueResponseMapper;
import org.quwuting.quwutingservice.venue.service.VenueHeatService;
import org.quwuting.quwutingservice.venue.service.VenueLookupService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class FavoriteService {

    private final FavoriteRepository favoriteRepository;
    private final VenueResponseMapper venueResponseMapper;
    private final TagInteractionService tagInteractionService;
    private final VenueLookupService venueLookupService;
    private final VenueHeatService venueHeatService;

    /**
     * 获取用户收藏的场所列表（按收藏时间倒序）。
     * <p>
     * DB 往返压缩：收藏与场所经 {@link FavoriteRepository#findFavoriteVenuesByUserId}
     * 联查一次取回（原"查收藏列表 + 批量查场所"两步各占一次跨洲往返），
     * 加上整页标签点赞数的批量查询（IN 一次覆盖，避免 N+1），共 2 次往返。
     */
    @Transactional(readOnly = true)
    public List<VenueResponse> getFavoriteVenues(Long userId) {
        List<Venue> venues = favoriteRepository.findFavoriteVenuesByUserId(userId);
        if (venues.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, Map<String, Long>> tagLikeCountsByVenue =
                tagInteractionService.batchGetTagLikeCounts(venues.stream().map(Venue::getId).toList());
        return venues.stream()
                .map(v -> venueResponseMapper.toResponse(
                        v, tagLikeCountsByVenue.getOrDefault(v.getId(), Collections.emptyMap())))
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
