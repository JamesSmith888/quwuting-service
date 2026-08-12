package org.quwuting.quwutingservice.venuestatuswatcher.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.message.enums.MessageType;
import org.quwuting.quwutingservice.message.service.MessageService;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venuestatuswatcher.entity.VenueStatusWatcher;
import org.quwuting.quwutingservice.venuestatuswatcher.repository.VenueStatusWatcherRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 关注门店营业状态（2026-08-12 新增，见 AGENTS.md「关注门店营业状态通知」）。
 * <ul>
 *   <li><b>写（用户）</b>：{@link #watch} / {@link #unwatch}——详情页开关，
 *       与收藏解耦（关注=只盯营业状态变化）；</li>
 *   <li><b>读（用户）</b>：{@link #isWatching}——详情页开关态；</li>
 *   <li><b>写（状态变更挂点）</b>：{@link #notifyStatusChanged}——门店营业状态实际
 *       变更时向全部关注者发站内信（同事务、幂等：一次状态变更一条消息），
 *       调用方 = VenueService 三个状态变更入口（updateVenue / markSuspendedByReport /
 *       reopenByReport），见 AGENTS.md「关注门店营业状态通知 · 触发挂点」。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class VenueStatusWatcherService {

    /** 站内信标题（消息中心列表行主展示文案） */
    private static final String TITLE = "门店状态更新";

    private final VenueStatusWatcherRepository watcherRepository;
    private final VenueRepository venueRepository;
    private final MessageService messageService;

    /**
     * 开启关注（详情页开关 ON）。幂等：已关注时直接成功（唯一约束兜底防并发重入）。
     */
    @Transactional
    public void watch(Long userId, Long venueId) {
        venueRepository.findByIdAndDeletedFalse(venueId)
                .orElseThrow(() -> new BusinessException(1001, "场所不存在"));
        if (watcherRepository.existsByUserIdAndVenueIdAndDeletedFalse(userId, venueId)) {
            return; // 幂等：重复开启不重复插入
        }
        VenueStatusWatcher watcher = new VenueStatusWatcher();
        watcher.setUserId(userId);
        watcher.setVenueId(venueId);
        watcherRepository.save(watcher);
    }

    /** 关闭关注（详情页开关 OFF）。幂等：未关注时静默成功（物理删除） */
    @Transactional
    public void unwatch(Long userId, Long venueId) {
        watcherRepository.deleteByUserIdAndVenueId(userId, venueId);
    }

    /** 我是否关注了该门店（详情页开关态） */
    @Transactional(readOnly = true)
    public boolean isWatching(Long userId, Long venueId) {
        return watcherRepository.existsByUserIdAndVenueIdAndDeletedFalse(userId, venueId);
    }

    /**
     * 门店营业状态变更通知（状态变更挂点调用，REQUIRED 传播加入调用方事务——与
     * {@link MessageService#create} 同模式，通知不丢失）。
     * <p>
     * 幂等契约：一次状态变更 = 一次调用 = 每个关注者一条站内信；调用方保证仅在
     * 状态实际变更（from ≠ to）后调用（updateVenue 已按状态差异分支、采纳/恢复
     * 挂点幂等早退），本方法不再重复判断。
     * <p>
     * 门店已软删时无可通知对象（名称取不到）直接返回；无关注者时不发消息。
     */
    @Transactional
    public void notifyStatusChanged(Long venueId, VenueStatus from, VenueStatus to) {
        Venue venue = venueRepository.findByIdAndDeletedFalse(venueId).orElse(null);
        if (venue == null) {
            return; // 门店已删除：无可通知对象
        }
        List<VenueStatusWatcher> watchers = watcherRepository.findByVenueIdAndDeletedFalse(venueId);
        if (watchers.isEmpty()) {
            return;
        }
        String content = composeContent(venue.getName(), from, to);
        for (VenueStatusWatcher watcher : watchers) {
            messageService.create(watcher.getUserId(), MessageType.VENUE_STATUS_CHANGED,
                    TITLE, content, "VENUE", venueId);
        }
    }

    /**
     * 站内信正文：仅陈述事实（门店名 + 最新状态 + 原状态），不掺主观判断。
     * 例：「XX舞厅」已恢复营业（原暂停营业）
     */
    private String composeContent(String venueName, VenueStatus from, VenueStatus to) {
        String toText = to == VenueStatus.OPEN ? "已恢复营业" : to.getDisplayName();
        StringBuilder sb = new StringBuilder("「").append(venueName).append("」").append(toText);
        if (from != null) {
            sb.append("（原").append(from.getDisplayName()).append("）");
        }
        return sb.toString();
    }
}
