package org.quwuting.quwutingservice.favorite.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.favorite.entity.Favorite;
import org.quwuting.quwutingservice.favorite.repository.FavoriteRepository;
import org.quwuting.quwutingservice.venue.dto.response.VenueResponse;
import org.quwuting.quwutingservice.venue.mapper.VenueResponseMapper;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class FavoriteService {

    private final FavoriteRepository favoriteRepository;
    private final VenueRepository venueRepository;
    private final VenueResponseMapper venueResponseMapper;

    /** 获取用户收藏的场所列表（按收藏时间倒序） */
    @Transactional(readOnly = true)
    public List<VenueResponse> getFavoriteVenues(Long userId) {
        List<Favorite> favorites = favoriteRepository.findByUserIdAndDeletedFalseOrderByCreatedAtDesc(userId);
        return favorites.stream()
                .map(fav -> venueRepository.findByIdAndDeletedFalse(fav.getVenueId()).orElse(null))
                .filter(Objects::nonNull)
                .map(venueResponseMapper::toResponse)
                .toList();
    }

    /** 收藏场所（幂等：已收藏则忽略） */
    @Transactional
    public void addFavorite(Long userId, Long venueId) {
        assertVenueExists(venueId);

        var existing = favoriteRepository.findByUserIdAndVenueId(userId, venueId);
        if (existing.isPresent()) {
            Favorite fav = existing.get();
            if (!fav.isDeleted()) {
                return; // 已收藏，幂等
            }
            fav.setDeleted(false); // 重新收藏（恢复逻辑删除的记录）
            favoriteRepository.save(fav);
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
    }

    /** 取消收藏（幂等：未收藏则忽略） */
    @Transactional
    public void removeFavorite(Long userId, Long venueId) {
        favoriteRepository.findByUserIdAndVenueId(userId, venueId)
                .filter(fav -> !fav.isDeleted())
                .ifPresent(fav -> {
                    fav.setDeleted(true);
                    favoriteRepository.save(fav);
                });
    }

    private void assertVenueExists(Long venueId) {
        if (venueRepository.findByIdAndDeletedFalse(venueId).isEmpty()) {
            throw new BusinessException(1001, "场所不存在");
        }
    }
}
