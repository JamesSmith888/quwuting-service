package org.quwuting.quwutingservice.venue.change;

import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 门店事实已变更（2026-10-01）。由 {@link VenueChangePublisher} 在写事务内发布，
 * 各缓存属主以 {@code @TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)}
 * 订阅——<b>提交后才失效</b>：提交前失效存在「并发读 miss → 回源读到未提交前的旧值 →
 * 旧值被重新缓存」的竞态窗口；事务回滚则不发生任何失效（本来就什么都没变）。
 *
 * @param venueIds 发生变化的门店（非空；逐店缓存按它逐出，全局缓存一律整体失效）
 * @param changes  变更类别（仅用于日志/审计，不参与失效决策，见 {@link VenueFactChange}）
 */
public record VenueFactsChangedEvent(Set<Long> venueIds, Set<VenueFactChange> changes) {

    public VenueFactsChangedEvent {
        venueIds = Set.copyOf(venueIds);
        changes = changes.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(changes));
    }

    static VenueFactsChangedEvent of(VenueFactChange change, Collection<Long> venueIds) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Long id : venueIds) {
            if (id != null) {
                ids.add(id);
            }
        }
        return new VenueFactsChangedEvent(ids, EnumSet.of(change));
    }
}
