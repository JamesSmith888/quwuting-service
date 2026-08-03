package org.quwuting.quwutingservice.venue.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.venue.repository.VenueViewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 场所浏览记录服务。
 * <p>
 * 已登录用户按 (venueId, userId, viewDate) 去重（同一天仅记一条）；
 * 匿名用户 userId 为 null，每次访问均记录（无法去重，数据仅供参考）。
 * <p>
 * 写入采用无条件 upsert（{@code INSERT ... ON CONFLICT DO NOTHING}，见
 * {@link VenueViewRepository#upsertView}）：恒为 1 次 DB 往返，去重与并发竞态
 * 由联合唯一约束在库内兜底。早期实现为 check-then-act（先 SELECT 存在性再 INSERT），
 * 当天首次浏览需 2 次跨洲往返（约 800ms），且 SELECT 与 INSERT 之间存在并发窗口
 * 需 catch 唯一约束异常——upsert 同时消除了这两项开销。
 * <p>
 * 不做场所存在性校验：此端点为 fire-and-forget，由详情页 GET /venues/{id} 发起，
 * 场所不存在时详情页已返回 404。冗余的场所查询（跨洲 DB 往返 300ms）对 fire-and-forget
 * 端点是不合理的延迟负担——即使场所不存在，写入的 view 记录也无害（不会被热度统计引用，
 * 因为热度统计从 qwt_venues 表驱动）。
 */
@Service
@RequiredArgsConstructor
public class VenueViewService {

    private final VenueViewRepository venueViewRepository;

    /**
     * 记录一次浏览（单次 DB 往返，幂等）。
     *
     * @param venueId 场所 ID
     * @param userId  用户 ID，匿名时为 null（匿名记录不参与去重）
     */
    @Transactional
    public void recordView(Long venueId, Long userId) {
        LocalDate today = LocalDate.now();
        venueViewRepository.upsertView(venueId, userId, today, LocalDateTime.now());
    }
}
