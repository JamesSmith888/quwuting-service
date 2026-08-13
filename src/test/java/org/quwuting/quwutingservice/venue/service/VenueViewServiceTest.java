package org.quwuting.quwutingservice.venue.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.venue.enums.ViewSource;
import org.quwuting.quwutingservice.venue.repository.VenueViewRepository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * VenueViewService 浏览记录写路径单元测试（Mockito，不依赖数据库）。
 * <p>
 * 覆盖 2026-08-13「浏览来源-搜索结果折线恒 0」根因修复的核心语义：
 * <ol>
 *   <li><b>真实插入后热度缓存失效</b>：upsert 受影响行数 &gt; 0（首次进入/匿名每次）→
 *       注册事务 afterCommit 回调 → 提交后 {@link VenueHeatService#invalidate}——热度页
 *       不再在 60s refresh 窗口内命中"无新浏览记录"的旧缓存；</li>
 *   <li><b>冲突（同一来源当天已存在，DO NOTHING）不失效</b>：统计无变化，不触发无谓缓存逐出
 *       （与 FavoriteService「幂等无写入分支不逐出」同约定）；</li>
 *   <li><b>来源兜底</b>：null / 非法值写入 OTHER（既有语义回归保护）。</li>
 * </ol>
 * 去重粒度（V21 起）= 按天按来源：同一用户同一天不同来源各自计数（搜索/列表是不同流量，
 * 搜索进入必计 SEARCH）——upsert 冲突仅发生在"同来源重复"，故受影响行数 0 只代表
 * 同来源重复，不影响其他来源的新记录。
 * 失效时机遵循项目「失效时机约束」（05-venue-heat.md）：必须延后到事务提交后执行，
 * 避免"另一线程 cache miss → 回源重算 → 读不到未提交数据 → 缓存陈旧值"的竞态窗口。
 * 单元测试无 Spring 事务，用 {@link TransactionSynchronizationManager#initSynchronization()}
 * 手动开启同步并触发 afterCommit（标准技巧）。
 */
@ExtendWith(MockitoExtension.class)
class VenueViewServiceTest {

    @Mock
    private VenueViewRepository venueViewRepository;
    @Mock
    private VenueHeatService venueHeatService;

    private VenueViewService viewService;

    @BeforeEach
    void setUp() {
        viewService = new VenueViewService(venueViewRepository, venueHeatService);
    }

    @AfterEach
    void tearDown() {
        // 幂等清理：runWithCommit / 单测内 finally 已清理时不再重复 deactivate
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * 手动驱动事务提交（单元测试无 Spring 事务）：开启同步 → 执行写操作（内部注册
     * afterCommit 同步器）→ 触发全部同步器的 afterCommit → 清理。
     */
    private void runWithCommit(Runnable action) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            action.run();
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void recordView_withInsertedRow_invalidatesHeatAfterCommit() {
        // 已登录用户当天首次进入：真实插入（affected=1），source 原样写入
        when(venueViewRepository.upsertView(anyLong(), any(), any(), eq("SEARCH"), any())).thenReturn(1);

        runWithCommit(() -> viewService.recordView(1L, 100L, ViewSource.SEARCH));

        verify(venueHeatService).invalidate(1L);
    }

    @Test
    void recordView_withDuplicateRow_skipsInvalidate() {
        // 同一用户同一天同一来源重复进入：DO NOTHING（affected=0），浏览统计不变，不得失效缓存
        when(venueViewRepository.upsertView(anyLong(), any(), any(), any(), any())).thenReturn(0);

        runWithCommit(() -> viewService.recordView(1L, 100L, ViewSource.SEARCH));

        verifyNoInteractions(venueHeatService);
    }

    @Test
    void recordView_nullSource_normalizesToOtherAndInvalidates() {
        // 旧客户端不传 source → 兜底 OTHER；插入成功仍须失效（viewcount 变化与来源无关）
        when(venueViewRepository.upsertView(anyLong(), any(), any(), eq("OTHER"), any())).thenReturn(1);

        runWithCommit(() -> viewService.recordView(1L, 100L, null));

        verify(venueHeatService).invalidate(1L);
    }

    @Test
    void recordView_anonymousInserted_invalidatesHeatAfterCommit() {
        // 匿名（userId=null）每次访问均插入（60s 频控兜底；单元测试无请求上下文时
        // ClientIpResolver.resolve() 返回 null，首次访问不命中频控）——插入成功即失效
        when(venueViewRepository.upsertView(anyLong(), isNull(), any(), any(), any())).thenReturn(1);

        runWithCommit(() -> viewService.recordView(1L, null, ViewSource.LIST));

        verify(venueHeatService).invalidate(1L);
    }

    @Test
    void recordView_registersAfterCommitNotBefore() {
        // 回归：失效必须注册为 afterCommit 同步器，而非事务内立即调用——
        // 提交前失效存在"回源读到未提交数据"的竞态窗口（项目「失效时机约束」）
        when(venueViewRepository.upsertView(anyLong(), any(), any(), any(), any())).thenReturn(1);

        TransactionSynchronizationManager.initSynchronization();
        try {
            viewService.recordView(1L, 100L, ViewSource.SEARCH);
            // 提交前不得触发失效
            verifyNoInteractions(venueHeatService);
            assertTrue(TransactionSynchronizationManager.getSynchronizations().size() >= 1,
                    "真实插入后必须注册事务同步器");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
