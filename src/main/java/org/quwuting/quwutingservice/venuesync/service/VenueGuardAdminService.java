package org.quwuting.quwutingservice.venuesync.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venue.service.VenueStatusGuardService;
import org.quwuting.quwutingservice.venuesync.dto.response.VenueGuardStateItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 门店状态权威层级 · 管理端操作服务（2026-09-14，V25；方案见 docs/agents/48）。
 * <p>
 * 门禁的判定与打锁实现在 {@link VenueStatusGuardService} 与
 * {@code DailyOpeningService}；本服务只承载**管理员显式操作**：
 * <ul>
 *   <li>{@link #query} —— 读当前权威层级状态（后台门店编辑页 / 列表徽标）；</li>
 *   <li>{@link #unlock} —— 恢复自动同步（提前解除人工锁，下一轮舞讯即可覆盖）；</li>
 *   <li>{@link #setExempt} —— 设置 / 撤销「不参与舞讯推断」的永久豁免。</li>
 * </ul>
 * 三者都不改 {@code status} 本身，故不写状态变迁日志、不发关注者通知；
 * 也不触碰列表/详情缓存（这两个缓存都不含权威层级字段）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VenueGuardAdminService {

    private final VenueRepository venueRepository;
    private final VenueStatusGuardService venueStatusGuardService;

    /** 查询门禁状态（顺序按 venueId 升序，便于前端逐条匹配） */
    @Transactional(readOnly = true)
    public List<VenueGuardStateItem> query(List<Long> venueIds) {
        LocalDateTime now = LocalDateTime.now();
        return venueRepository.findByIdInAndDeletedFalse(venueIds).stream()
                .sorted(Comparator.comparing(Venue::getId))
                .map(v -> new VenueGuardStateItem(
                        v.getId(), v.getName(), v.getStatus().name(),
                        v.getStatusSource() == null ? null : v.getStatusSource().name(),
                        v.getStatusLockedUntil(),
                        venueStatusGuardService.isLockActive(v, now),
                        v.isDailySyncExempt(), v.getSyncNote()))
                .toList();
    }

    /**
     * 恢复自动同步：清除人工锁（幂等，无锁也成功）。
     *
     * @return 实际发生解锁的门店数（原本无锁的不计入，让汇报口径干净）
     */
    @Transactional
    public int unlock(List<Long> venueIds, Long adminId) {
        LocalDateTime now = LocalDateTime.now();
        int unlocked = 0;
        for (Venue venue : venueRepository.findByIdInAndDeletedFalse(venueIds)) {
            if (!venueStatusGuardService.isLockActive(venue, now)) {
                continue;
            }
            venueStatusGuardService.unlockByHuman(venue);
            venueRepository.save(venue);
            unlocked++;
        }
        log.info("[venue-guard] admin {} 解锁 {} 家（提交 {} 家）", adminId, unlocked, venueIds.size());
        return unlocked;
    }

    /**
     * 设置 / 撤销舞讯推断永久豁免。
     *
     * @return 实际发生变更的门店数（幂等：已是目标值的不计入）
     */
    @Transactional
    public int setExempt(List<Long> venueIds, boolean exempt, String note, Long adminId) {
        boolean hasNote = note != null && !note.isBlank();
        int changed = 0;
        for (Venue venue : venueRepository.findByIdInAndDeletedFalse(venueIds)) {
            if (venue.isDailySyncExempt() == exempt && !hasNote) {
                continue; // 已是目标值且无需更新备注：幂等跳过（汇报口径只计真实变更）
            }
            venueStatusGuardService.setExempt(venue, exempt, note);
            venueRepository.save(venue);
            changed++;
        }
        log.info("[venue-guard] admin {} 设置豁免={} 生效 {} 家（提交 {} 家）",
                adminId, exempt, changed, venueIds.size());
        return changed;
    }
}
