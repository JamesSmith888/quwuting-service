package org.quwuting.quwutingservice.dancer.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.web.ClientIpResolver;
import org.quwuting.quwutingservice.dancer.repository.DancerViewRepository;
import org.quwuting.quwutingservice.venue.enums.ViewSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 舞伴浏览记录服务（2026-08-14 舞伴统计图第一期，V29）。
 * <p>
 * 完全镜像门店浏览服务（{@code VenueViewService}）的模式（能力平权：
 * 浏览埋点是平台级能力，舞伴域独立表 + 独立服务，同模式实现）：
 * <ul>
 *   <li>已登录用户按 (dancerId, userId, viewDate, source) 去重（同一天同一来源仅记
 *       一条；多渠道独立计数——搜索/列表/分享是不同流量）；</li>
 *   <li>匿名用户 userId 为 null，无法按身份去重——60s 简单频控（同舞伴同客户端 IP
 *       60 秒内只记一条），压制脚本连点/自动刷新放大 PV 的漏洞；</li>
 *   <li>写入采用无条件 upsert（{@code INSERT ... ON CONFLICT DO NOTHING}）：恒为 1 次
 *       DB 往返，去重与并发竞态由联合唯一约束在库内兜底；</li>
 *   <li>真实写入（affected &gt; 0）后经事务 afterCommit 失效舞伴统计缓存——否则
 *       refresh-ahead 缓存（60s）会让统计页在窗口内看不到刚记录的浏览与来源分列
 *       （门店 2026-08-13「搜索结果折线恒 0」同根因，勿重蹈）；冲突 DO NOTHING
 *       不改变统计，不失效。</li>
 * </ul>
 * 不做舞伴存在性校验：此端点为 fire-and-forget，由详情页 GET /dancers/{id} 发起，
 * 舞伴不存在时详情页已返回 404。冗余的舞伴查询对 fire-and-forget 端点是不合理的
 * 延迟负担——即使舞伴不存在，写入的 view 记录也无害（统计从 qwt_dancer_views 表驱动，
 * 不存在于 qwt_dancers 的行不会被任何公开入口引用）。
 */
@Service
@RequiredArgsConstructor
public class DancerViewService {

    /** 匿名浏览频控窗口：同一舞伴同一 IP 在窗口内只计 1 条 */
    private static final long ANON_RATE_LIMIT_SECONDS = 60;

    /** 匿名频控缓存（key = dancerId:ip；未命中时 putIfAbsent 竞争窗口内可能双写一条，无害） */
    private final Cache<String, Boolean> anonymousViewLimiter = Caffeine.newBuilder()
            .expireAfterWrite(ANON_RATE_LIMIT_SECONDS, TimeUnit.SECONDS)
            .maximumSize(50_000)
            .build();

    private final DancerViewRepository dancerViewRepository;

    /**
     * 统计缓存失效入口。浏览数是统计响应（viewTrend / viewSourceTrend）的组成部分——
     * 真实写入后必须失效，否则 refresh-ahead 缓存（60s）会让统计页在窗口内看不到
     * 刚记录的浏览与来源分列（门店「浏览来源折线恒 0」根因教训，见 {@link #recordView}）。
     */
    private final DancerStatsService dancerStatsService;

    /**
     * 记录一次浏览（匿名用户最多 60s 一条，已登录用户由 upsert 按天按来源去重，恒 1 次 DB 往返）。
     * <p>
     * 来源防御：source 为 null / 非枚举值（旧版本客户端未上报 / 脏数据）一律兜底为 OTHER，
     * 枚举类列不加 CHECK 约束（项目约定），非法值在本层收敛。
     * <p>
     * 统计缓存失效（对齐门店 VenueViewService 约定）：
     * <ul>
     *   <li>仅真实插入（upsert 受影响行数 &gt; 0）时失效——冲突（同一用户同一天同一来源
     *       已存在，DO NOTHING）不改变任何浏览统计，不触发无谓缓存逐出；</li>
     *   <li>失效必须延后到事务提交后（项目「失效时机约束」）：提交前失效存在竞态窗口——
     *       另一线程读到 cache miss → 回源重算 → 读不到本事务未提交数据 → 缓存陈旧值。
     *       afterCommit 注册的回调在提交完成后执行，回源必读到已提交数据。</li>
     * </ul>
     *
     * @param dancerId 舞伴 ID
     * @param userId   用户 ID，匿名时为 null（匿名记录参与 IP 频控，不参与按来源去重）
     * @param source   浏览来源（LIST/SHARE/SEARCH/OTHER），null 或未知值兜底 OTHER
     */
    @Transactional
    public void recordView(Long dancerId, Long userId, ViewSource source) {
        if (userId == null && isRateLimited(dancerId)) {
            return; // 匿名频控命中：跳过写入（尽力而为，防止脚本连点放大 PV）
        }
        LocalDate today = LocalDate.now();
        int inserted = dancerViewRepository.upsertView(
                dancerId, userId, today, normalizeSource(source).name(), LocalDateTime.now());
        if (inserted > 0) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dancerStatsService.invalidate(dancerId);
                }
            });
        }
    }

    /** 来源规范化：null / 非法值（含旧客户端未上报）→ OTHER，防御脏数据入库 */
    private ViewSource normalizeSource(ViewSource source) {
        return source != null ? source : ViewSource.OTHER;
    }

    /**
     * 匿名频控判定：同舞伴同 IP 在 ANON_RATE_LIMIT_SECONDS 内已记录过则返回 true。
     * putIfAbsent 原子占位——并发首写时可能都通过（最多双写一条匿名记录，对统计无实质影响）。
     */
    private boolean isRateLimited(Long dancerId) {
        String ip = ClientIpResolver.resolve();
        String key = dancerId + ":" + (ip != null ? ip : "anon");
        return anonymousViewLimiter.asMap().putIfAbsent(key, Boolean.TRUE) != null;
    }
}
