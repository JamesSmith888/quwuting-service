package org.quwuting.quwutingservice.venuepresence.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quwuting.quwutingservice.venuepresence.repository.VenueVisitThankRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 门店到访感谢写入的<b>真实数据库</b>契约验证（2026-10-13，V50；可选集成测试）。
 *
 * <h3>为什么必须有这条测试</h3>
 * {@code VenueVisitThankRepository#thank} 是一条 {@code nativeQuery = true} 的
 * {@code INSERT ... ON DUPLICATE KEY UPDATE}——<b>字符串形态的 SQL</b>：列名拼错、
 * 参数绑定个数不对、冲突更新子句写坏，对编译器与 Mock 单测<b>完全隐形</b>。
 * 本项目 2026-10-06 已在同族查询上付过这笔学费（{@code Date → LocalDate} 强转事故，
 * 单测全绿、生产调度首轮炸且被 catch 吞掉）。本域红线「改 native 必真库执行」源于此。
 * <p>
 * 它守住的是<b>幂等三态</b>：同一 (门店, 感谢者, 贡献者) 三元组首次插入 affected = 1
 * （这是唯一会产生站内信的返回值）、再次调用 affected ≠ 1（不重发通知）。
 * 这条判据一旦漂移，用户重复点击就会给贡献者发重复的感谢信（通知骚扰）。
 *
 * <h3>为什么用合成 ID 而不是「已存在的真实行」</h3>
 * 同 {@code VenueVisitDayQuerySqlTest} 的纪律：<b>不造依赖真实数据的用例</b>。
 * 本表无外键（全库约定），合成 ID 插入后被 {@code @Transactional} 回滚，
 * 既不污染任何统计（感谢数按红线不进任何算法），也不依赖库里恰好已有感谢行。
 *
 * <p>运行方式：{@code ./mvnw test -Dtest=VenueVisitThanksSqlTest -Drun.db.tests=true}。
 * 默认（未设属性）整类被 JUnit 条件禁用，<b>不加载 Spring 上下文</b>。
 * ⚠️ 前置：目标库须已执行 {@code V50__venue_visit_thanks.sql}——未建表 ⇒ 本类报
 * 「表不存在」，属预期结果而非测试缺陷。
 */
@SpringBootTest
@Transactional
@Tag("db")
@EnabledIfSystemProperty(named = "run.db.tests", matches = "true")
class VenueVisitThanksSqlTest {

    /** 合成 ID 段：远离真实数据区间，且无外键约束可违反 */
    private static final long VENUE_ID = 9_999_990_001L;
    private static final long OTHER_VENUE_ID = 9_999_990_002L;
    private static final long THANK_USER = 9_999_990_011L;
    private static final long SECOND_THANK_USER = 9_999_990_012L;
    private static final long TARGET_USER = 9_999_990_021L;

    @Autowired
    private VenueVisitThankRepository thankRepository;

    /**
     * 幂等三态：首次 = 1（履行通知义务），重复 ≠ 1（不再通知）。
     * 第二次的返回值按 MySQL 版本/时序可能是 0（同值同秒）或 2（改写 updated_at），
     * 两者都应不通知 ⇒ 断言「不等于 1」这条不变量，<b>不绑死具体返回值</b>。
     */
    @Test
    void thankIsIdempotentPerTriple() {
        LocalDateTime first = LocalDateTime.now().minusMinutes(2);
        LocalDateTime again = LocalDateTime.now().minusMinutes(1);

        assertEquals(1, thankRepository.thank(VENUE_ID, THANK_USER, TARGET_USER, first),
                "首次三元组插入应影响 1 行（唯一触发被感谢站内信的返回值）");

        int repeated = thankRepository.thank(VENUE_ID, THANK_USER, TARGET_USER, again);
        assertTrue(repeated != 1,
                "重复感谢不应命中「首次」分支（否则会给贡献者重发感谢信），实际 = " + repeated);

        Collection<Long> targets = thankRepository.findTargetUserIds(VENUE_ID, THANK_USER);
        assertEquals(1, targets.size(), "重复感谢后目标仍只有一行（唯一键吸收，非叠加）");

        // 唯一键含 venue_id：换门店 = 新的三元组 ⇒ 重新计一次「首次」
        assertEquals(1, thankRepository.thank(OTHER_VENUE_ID, THANK_USER, TARGET_USER, again),
                "换门店应是新的三元组（唯一键含 venue_id）");
    }

    /**
     * 「多少位舞友感谢了你」= COUNT(DISTINCT 感谢者) 而非行数——感谢人数与行数当前同值，
     * 但因为表达式写的是语义（人数），未来任何允许重复写入的改造都不会让合并正文的 N 漂移。
     */
    @Test
    void countThankersCountsDistinctPeople() {
        LocalDateTime now = LocalDateTime.now().minusMinutes(1);

        thankRepository.thank(VENUE_ID, THANK_USER, TARGET_USER, now);
        thankRepository.thank(VENUE_ID, SECOND_THANK_USER, TARGET_USER, now);
        thankRepository.thank(VENUE_ID, THANK_USER, TARGET_USER, now); // 重复：不计第二次

        assertEquals(2, thankRepository.countThankers(VENUE_ID, TARGET_USER),
                "合并正文的 N 应是「人数」而非「行数」：同一人重复感谢不重复计数");
        assertEquals(0, thankRepository.countThankers(OTHER_VENUE_ID, TARGET_USER),
                "感谢人数按门店维度隔离");
    }
}
