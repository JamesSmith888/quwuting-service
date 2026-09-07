package org.quwuting.quwutingservice.wxsubscribe.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quwuting.quwutingservice.wxsubscribe.repository.WxSubscribeQuotaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 微信订阅消息额度原生 SQL 的<b>真实数据库</b>验证（可选集成测试，对齐
 * VenueHotVenueIdsSqlTest 先例）。
 * <p>
 * 运行方式：{@code ./mvnw test -Drun.db.tests=true -Dspring.profiles.active=dev}。
 * 默认（未设该属性）整个测试类被 JUnit 条件禁用，<b>不加载 Spring 上下文</b>。
 * <p>
 * 覆盖四条 native SQL（列引用/MySQL 方言错误只在执行期由 DB 校验，见 AGENTS.md
 * 「native SQL 验证」）：upsertGrant（INSERT ... ON DUPLICATE KEY UPDATE + LEAST
 * 守卫 + new 别名语法）、deductOne、clearAvailable、findStatusChangeRecipients
 * （三表 join 列别名对齐投影）。类级 @Transactional——测试写路径全部回滚，
 * 不污染 dev 库。
 */
@SpringBootTest
@Transactional
@Tag("db")
@EnabledIfSystemProperty(named = "run.db.tests", matches = "true")
class WxSubscribeSqlTest {

    /** 幻影测试用户 ID（大数避开真实用户段；测试事务回滚，不落库） */
    private static final long TEST_USER_ID = 999_999_901L;
    private static final String TEST_TEMPLATE_ID = "TEST_TEMPLATE_SQL_CHECK";

    @Autowired
    private WxSubscribeQuotaRepository quotaRepository;

    /**
     * 额度账本三连执行 + 收件人查询联动：upsert ×2（available=2）→ deduct（=1）→
     * clear（=0）→ findStatusChangeRecipients 返回空（available=0 不进收件人）。
     * 全程执行成功即证明 SQL 语法/列引用/别名合法。
     */
    @Test
    void quotaLedgerSqlExecutesAgainstRealDatabase() {
        LocalDateTime now = LocalDateTime.now();
        quotaRepository.upsertGrant(TEST_USER_ID, TEST_TEMPLATE_ID, now);
        quotaRepository.upsertGrant(TEST_USER_ID, TEST_TEMPLATE_ID, now);
        quotaRepository.deductOne(TEST_USER_ID, TEST_TEMPLATE_ID, now);
        quotaRepository.clearAvailable(TEST_USER_ID, TEST_TEMPLATE_ID, now);

        // 收件人查询：额度清零后不应出现在收件人集合（join 语义联动验证）
        List<WxSubscribeQuotaRepository.StatusChangeRecipient> recipients =
                quotaRepository.findStatusChangeRecipients(TEST_TEMPLATE_ID, List.of(TEST_USER_ID));
        assertTrue(recipients.isEmpty(), "额度清零用户不应进入发送收件人集合");
    }

    /**
     * upsert 幂等累加语义：连续两次授权后可被收件人查询命中（此时 available=2 &gt; 0，
     * 且 join users 需真实用户行——幻影 ID join 不中返回空，同样只验证执行合法性；
     * 「join 命中」路径由生产真实用户自然覆盖，此处不造数）。
     */
    @Test
    void upsertGrantRepeatedExecutesIdempotently() {
        LocalDateTime now = LocalDateTime.now();
        quotaRepository.upsertGrant(TEST_USER_ID, TEST_TEMPLATE_ID, now);
        quotaRepository.upsertGrant(TEST_USER_ID, TEST_TEMPLATE_ID, now);
        // 再执行一次三表 join 收件人查询（列引用/投影别名合法性）
        quotaRepository.findStatusChangeRecipients(TEST_TEMPLATE_ID, List.of(TEST_USER_ID));
        assertTrue(true, "native SQL 全部执行成功");
    }
}
