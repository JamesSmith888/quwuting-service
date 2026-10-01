package org.quwuting.quwutingservice.common.db;

import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

/**
 * 数据库完整性异常判定工具（2026-08-07 从 TagInteractionService 私有方法抽取收敛）。
 * <p>
 * 项目写路径并发竞态治理的统一约定（见 AGENTS.md「并发与幂等」）：
 * {@code catch (DataIntegrityViolationException)} 只允许吞**唯一键冲突**
 * （PG 23505 / MySQL 1062），其余完整性错误（NOT NULL / 列约束 / 外键）
 * 必须继续抛出——防止把真实数据错误误当并发竞态静默吞掉。
 */
public final class DbConstraintViolations {

    private static final String PG_UNIQUE_VIOLATION = "23505";
    private static final int MYSQL_DUPLICATE_ENTRY = 1062;

    private DbConstraintViolations() {
    }

    /**
     * 判定数据完整性异常是否为唯一键冲突（方言无关）。
     * 走 mostSpecificCause 穿透 Hibernate 包装层取底层 SQLException：
     * <ul>
     *   <li>PostgreSQL：SQLState {@code 23505}（unique_violation）；</li>
     *   <li>MySQL 8（2026-08-31 起生产库）：vendor code {@code 1062}（ER_DUP_ENTRY）——其 SQLState
     *       {@code 23000} 同时覆盖外键/非空等其它完整性错误，<b>不能</b>只看 SQLState。</li>
     * </ul>
     */
    public static boolean isUniqueViolation(DataIntegrityViolationException e) {
        Throwable cause = e.getMostSpecificCause();
        if (!(cause instanceof SQLException sql)) {
            return false;
        }
        return PG_UNIQUE_VIOLATION.equals(sql.getSQLState()) || sql.getErrorCode() == MYSQL_DUPLICATE_ENTRY;
    }

}
