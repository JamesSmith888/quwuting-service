package org.quwuting.quwutingservice.common.db;

import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

/**
 * 数据库完整性异常判定工具（2026-08-07 从 TagInteractionService 私有方法抽取收敛）。
 * <p>
 * 项目写路径并发竞态治理的统一约定（见 AGENTS.md「并发与幂等」）：
 * {@code catch (DataIntegrityViolationException)} 只允许吞**唯一键冲突**
 * （PostgreSQL SQLState 23505），其余完整性错误（NOT NULL / 列约束 / 外键）
 * 必须继续抛出——防止把真实数据错误误当并发竞态静默吞掉。
 */
public final class DbConstraintViolations {

    private DbConstraintViolations() {
    }

    /**
     * 判定数据完整性异常是否为唯一键冲突（PostgreSQL SQLState 23505）。
     * 走 mostSpecificCause 穿透 Hibernate 包装层取底层 SQLException。
     */
    public static boolean isUniqueViolation(DataIntegrityViolationException e) {
        Throwable cause = e.getMostSpecificCause();
        return cause instanceof SQLException
                && "23505".equals(((SQLException) cause).getSQLState());
    }
}
