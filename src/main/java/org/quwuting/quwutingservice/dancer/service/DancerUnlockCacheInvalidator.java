package org.quwuting.quwutingservice.dancer.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 解锁写路径缓存失效协调器（2026-08-31 根因收敛）：「解锁记录<b>真实写入</b>数据库」
 * 之后的舞伴域缓存失效矩阵<b>唯一入口</b>。
 * <p>
 * <b>为什么存在</b>：解锁记录的写路径有多条（直连解锁 {@code PointsService#unlock} /
 * 邀约中转获批 {@code DemandRelayService#approve} / 24h 自动发放 {@code autoRelease} /
 * 代找替代 {@code rescue}），而解锁改变两类缓存输入——
 * <ul>
 *   <li><b>统计输入</b>：unlockStats 累计人次/人数 + 统计页「排名热度」卡
 *       （近7天/30天联系解锁数）——经详情缓存级联失效（内含
 *       {@link DancerStatsService} 与 {@link DancerAggregateService}）；</li>
 *   <li><b>列表排序输入</b>：HOT 排序主导信号 = 近7天联系解锁数
 *       （{@code DancerHeatWeights#UNLOCK_CONTACT}，付费意向权重最高）——须对
 *       {@link DancerListCacheService} 按舞伴精失效。</li>
 * </ul>
 * 旧实现把「afterCommit 注册 + 两个缓存各失效一次」的样板在
 * {@code PointsService} 与 {@code DemandRelayService} 各手抄一份，2026-08-31
 * 排查「邀约解锁统计未记录」报告时确立<b>已发生一次静默漂移</b>：中转获批路径
 * 漏失效列表缓存（与直连解锁不对称，HOT 排序在 60s refresh 兜底前读到旧分）。
 * 根因 = 「多写路径 × 失效矩阵」靠手抄组合，每新增一条写路径都要重抄一遍，
 * 漏抄即漂移且无编译期/测试期护栏。收敛为本类后，新写路径只调用
 * {@link #afterUnlockWrite(Long)} 一个方法，矩阵内容改一处全局生效。
 * <p>
 * <b>调用契约</b>：
 * <ul>
 *   <li>仅在解锁记录<b>真实写入</b>后调用（insertIfAbsent 返回 1 / save 成功）；
 *       幂等跳过（记录已存在，无新数据）<b>不得</b>调用——无新输入不需失效，
 *       对齐 {@code PointsService#unlock} 幂等分支不失效的同款边界；</li>
 *   <li>事务语义：活跃事务内调用 = 注册 afterCommit（提交后执行，保证并发读者
 *       回源必读到已提交数据，对齐项目既有 afterCommit 失效范式）；无事务上下文
 *       （如测试/非事务路径）退化为立即失效；</li>
 *   <li>dancerId 为 null 时静默忽略（照片目标解析失败的防御边界，调用方无需判空）。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class DancerUnlockCacheInvalidator {

    private final DancerDetailCacheService dancerDetailCacheService;
    private final DancerListCacheService dancerListCacheService;

    /**
     * 解锁记录真实写入后的失效入口（见类注释调用契约）。
     *
     * @param dancerId 被解锁内容所属舞伴 ID（联系方式 = 舞伴 ID；照片/视频经
     *                 相册行解析出的舞伴 ID）
     */
    public void afterUnlockWrite(Long dancerId) {
        if (dancerId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidate(dancerId);
                }
            });
        } else {
            invalidate(dancerId);
        }
    }

    /** 失效矩阵本体（唯一事实源）：详情族级联 + 列表精失效，顺序无关 */
    private void invalidate(Long dancerId) {
        dancerDetailCacheService.invalidate(dancerId);
        dancerListCacheService.invalidateByDancerId(dancerId);
    }
}
