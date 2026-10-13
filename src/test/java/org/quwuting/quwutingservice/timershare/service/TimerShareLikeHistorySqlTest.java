package org.quwuting.quwutingservice.timershare.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quwuting.quwutingservice.timershare.repository.TimerShareJoinRepository;
import org.quwuting.quwutingservice.timershare.repository.TimerShareLikeRepository;
import org.quwuting.quwutingservice.timershare.repository.TimerShareRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 点赞记录三条取数查询的<b>真实数据库</b>验证（可选集成测试，对齐 {@code WxSubscribeSqlTest} 先例；2026-10-13）。
 * <p>
 * 运行方式：{@code ./mvnw test -Drun.db.tests=true -Dspring.profiles.active=<一个可丢弃的库>}。
 * 默认（未设该属性）整个测试类被 JUnit 条件禁用，<b>不加载 Spring 上下文</b>。
 * ⚠️ 本仓 {@code application-mysql.yaml} 指向生产 RDS 且 Flyway 开——<b>不要</b>拿它跑；用本机一次性 MySQL 8。
 * <p>
 * 覆盖：{@code findRecentIdsByHost} / {@code findRecentShareIdsByUser}（JPQL 只取 id + 分页 + 双键排序）与
 * {@code findInvolving}（{@code IN :shareIds} + 或谓词 + 排序 + 分页）。JPQL 在应用启动时由 Spring Data 解析，
 * 这里额外保证<b>执行期</b>也合法（方言 / 列引用 / 分页改写）。幻影用户 id，类级 {@code @Transactional} 回滚，只读。
 */
@SpringBootTest
@Transactional
@Tag("db")
@EnabledIfSystemProperty(named = "run.db.tests", matches = "true")
class TimerShareLikeHistorySqlTest {

    private static final long PHANTOM_USER_ID = 999_999_902L;

    @Autowired
    private TimerShareRepository shareRepository;
    @Autowired
    private TimerShareJoinRepository joinRepository;
    @Autowired
    private TimerShareLikeRepository likeRepository;

    @Test
    void historyQueriesExecuteAgainstRealDatabase() {
        PageRequest page = PageRequest.of(0, TimerSharePolicy.LIKE_HISTORY_MAX_SHARES);
        List<Long> asHost = shareRepository.findRecentIdsByHost(PHANTOM_USER_ID, page);
        List<Long> asJoiner = joinRepository.findRecentShareIdsByUser(PHANTOM_USER_ID, page);
        assertNotNull(asHost);
        assertNotNull(asJoiner);
        assertTrue(asHost.isEmpty() && asJoiner.isEmpty(), "幻影用户没有任何会话");

        // 空 IN 列表在部分方言下非法——服务层在无会话时根本不发这条查询；这里用一个不存在的会话 id 验证执行合法性
        assertTrue(likeRepository.findInvolving(List.of(-1L), PHANTOM_USER_ID,
                PageRequest.of(0, TimerSharePolicy.LIKE_HISTORY_MAX_ROWS)).isEmpty());
    }
}
