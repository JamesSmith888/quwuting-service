package org.quwuting.quwutingservice.venue.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.venue.entity.VenueView;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venue.repository.VenueViewRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 场所浏览记录服务。
 * <p>
 * 已登录用户按 (venueId, userId, viewDate) 去重（同一天仅记一条）；
 * 匿名用户 userId 为 null，每次访问均记录（无法去重，数据仅供参考）。
 * 写入采用幂等策略：唯一约束冲突时静默忽略，不抛异常。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VenueViewService {

    private final VenueRepository venueRepository;
    private final VenueViewRepository venueViewRepository;

    /**
     * 记录一次浏览。
     *
     * @param venueId 场所 ID
     * @param userId  用户 ID，匿名时为 null
     */
    @Transactional
    public void recordView(Long venueId, Long userId) {
        if (venueRepository.findByIdAndDeletedFalse(venueId).isEmpty()) {
            throw new BusinessException(1001, "场所不存在");
        }

        // 已登录用户：先查询是否已存在（避免无谓的 INSERT + 异常开销）
        if (userId != null) {
            LocalDate today = LocalDate.now();
            if (venueViewRepository.findByVenueIdAndUserIdAndViewDate(venueId, userId, today).isPresent()) {
                return;
            }
        }

        VenueView view = new VenueView();
        view.setVenueId(venueId);
        view.setUserId(userId);
        view.setViewDate(LocalDate.now());

        try {
            venueViewRepository.save(view);
        } catch (DataIntegrityViolationException e) {
            // 唯一约束冲突（并发场景下同一用户同一天重复写入），静默忽略
            log.debug("Duplicate view record ignored: venueId={}, userId={}", venueId, userId);
        }
    }
}
