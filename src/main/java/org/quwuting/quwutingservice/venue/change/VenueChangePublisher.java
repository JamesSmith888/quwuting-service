package org.quwuting.quwutingservice.venue.change;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * 门店事实变更的<b>唯一</b>声明入口（2026-10-01，根因与契约见 docs/agents/29-performance.md
 * 「门店读模型失效：领域事件」）。
 * <p>
 * <b>写路径只声明「哪些门店的事实变了」，不决定「该失效哪些缓存」</b>。后者由各缓存属主
 * 订阅 {@link VenueFactsChangedEvent} 自行处理（{@code VenueService} 的详情/列表/分面、
 * {@code VenueHeatService} 的热度、{@link VenueSharedCacheInvalidator} 的 Spring 托管缓存）。
 * 新增一个门店读模型缓存 = 在它的属主里加一个监听器，所有写路径零改动。
 * <p>
 * 约束（由 {@code VenueFactWritersPublishChangeTest} 机器校验）：凡对门店事实表
 * （qwt_venues / qwt_venue_photos / qwt_venue_aliases / qwt_venue_activities）执行写入的类，
 * 必须经本类发布；缓存属主的失效方法一律 private，外部无从「只失效一部分」。
 * <p>
 * 时机：在事务内调用即可，监听器在<b>提交后</b>执行；无事务时（逐条独立提交的批量入口）
 * 立即执行。批量写库请在循环结束后<b>发布一次</b>（携带全部门店 id），不要逐条发布。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VenueChangePublisher {

    private final ApplicationEventPublisher eventPublisher;

    public void publish(VenueFactChange change, Long venueId) {
        if (venueId == null) {
            return;
        }
        publish(change, List.of(venueId));
    }

    public void publish(VenueFactChange change, Collection<Long> venueIds) {
        if (venueIds == null || venueIds.isEmpty()) {
            return;
        }
        VenueFactsChangedEvent event = VenueFactsChangedEvent.of(change, venueIds);
        if (event.venueIds().isEmpty()) {
            return;
        }
        log.debug("[venue-change] {} venues={}", change, event.venueIds());
        eventPublisher.publishEvent(event);
    }
}
