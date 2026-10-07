package org.quwuting.quwutingservice.venuecrowd;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quwuting.quwutingservice.message.enums.MessageType;
import org.quwuting.quwutingservice.message.repository.MessageRepository;
import org.quwuting.quwutingservice.venuecrowd.repository.VenueCrowdReportRepository;
import org.quwuting.quwutingservice.venuecrowd.stat.BusinessDay;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 热度域新增 / 改写的查询的<b>真实数据库</b>契约验证（2026-10-07，可选集成测试）。
 * <p>
 * 覆盖：认领人排除的两条原生 SQL（JOIN qwt_venues + 相关子查询）、按营业日的「我的上报」派生查询、
 * 按区间的常态人气 / 摘要查询、通知合并用的派生查询。它们只有真正连库执行才能证明「语句在 MySQL 上可解析、
 * 列名与实体映射一致」——本仓 2026-10-06 两次 P0（文本块拼接漏空格、无 FROM 子查询引用外层别名）
 * 都是「静态门禁全绿、只有真库炸」。
 *
 * <h3>⛔ 运行时机（务必读）</h3>
 * 加载 Spring 上下文会执行 Flyway。<b>V41（business_date 迁移）上线前</b>在本地带 mysql profile 运行本类，
 * 等于<b>把 V41 直接跑到生产库</b>（本地 mysql profile 连的就是生产 RDS）。所以本类只在<b>部署完成后</b>
 * 作为上线验收运行：{@code ./mvnw test -Drun.db.tests=true -Dspring.profiles.active=mysql -Dtest=CrowdReportQuerySqlTest}。
 * 本地提前验证 V41 用一次性 MySQL 实例（见 53 号文档「验证」节）。
 * <p>
 * 只读：全部用不存在的门店 / 用户 id（-1）查询，返回空集即通过；不造数据、不触发调度
 * （同 {@code VenueVisitDayQuerySqlTest} 的红线）。
 */
@SpringBootTest
@Transactional
@Tag("db")
@EnabledIfSystemProperty(named = "run.db.tests", matches = "true")
class CrowdReportQuerySqlTest {

    @Autowired
    private VenueCrowdReportRepository crowdReportRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Test
    void claimantExcludingListQueriesExecute() {
        LocalDateTime since = LocalDateTime.now().minusHours(6);
        assertNotNull(crowdReportRepository.countDistinctUsersByVenueIdsSince(List.of(-1L), since));
        assertNotNull(crowdReportRepository.findLatestByVenueIdsSince(List.of(-1L), since));
    }

    @Test
    void businessDateLookupsExecute() {
        LocalDateTime now = LocalDateTime.now();
        assertNotNull(crowdReportRepository.findByVenueIdAndUserIdAndBusinessDateAndDeletedFalse(
                -1L, -1L, BusinessDay.of(now)));
        assertNotNull(crowdReportRepository
                .findByVenueIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanAndDeletedFalse(
                        -1L, now.minusDays(30), now));
    }

    @Test
    void notificationMergeLookupExecutes() {
        assertNotNull(messageRepository
                .findFirstByUserIdAndTypeAndRelatedTypeAndRelatedIdAndReadAtIsNullAndDeletedFalseAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                        -1L, MessageType.CROWD_REPORT_LIKED, "VENUE", -1L, LocalDateTime.now().minusDays(1)));
    }
}
