package org.quwuting.quwutingservice.common;

import org.junit.jupiter.api.Test;
import org.quwuting.quwutingservice.common.db.DbConstraintViolations;
import org.quwuting.quwutingservice.common.ratelimit.FailedAttemptLimiter;
import org.quwuting.quwutingservice.common.web.LogRedaction;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-01 新增的三个通用基础件（日志脱敏 / 失败尝试限制 / 方言无关唯一键判定）的单测。
 */
class CommonGuardsTest {

    // ── LogRedaction ──

    @Test
    void coordinatesAreCoarsenedAndSecretsMasked() {
        assertEquals("lat=32.01&lng=120.86&page=0&token=***",
                LogRedaction.redactQuery("lat=32.0123456&lng=120.8654321&page=0&token=abc.def"));
        assertEquals("latitude=-33.86&Longitude=151.20",
                LogRedaction.redactQuery("latitude=-33.8688&Longitude=151.2093"));
    }

    @Test
    void unrelatedParamsAndShortValuesArePreserved() {
        assertEquals("keyword=寻梦缘&lat=32.1&flag", LogRedaction.redactQuery("keyword=寻梦缘&lat=32.1&flag"));
        assertNull(LogRedaction.redactQuery(null));
        assertEquals("", LogRedaction.redactQuery(""));
    }

    // ── FailedAttemptLimiter ──

    @Test
    void blocksAfterMaxFailuresAndResetsOnSuccess() {
        FailedAttemptLimiter limiter = new FailedAttemptLimiter(3, Duration.ofMinutes(15), 100);
        String key = "ip:1.2.3.4";
        limiter.recordFailure(key);
        limiter.recordFailure(key);
        assertFalse(limiter.isBlocked(key));
        assertEquals(3, limiter.recordFailure(key));
        assertTrue(limiter.isBlocked(key));
        assertFalse(limiter.isBlocked("ip:5.6.7.8"), "其它来源不受影响");
        limiter.reset(key);
        assertFalse(limiter.isBlocked(key));
    }

    // ── DbConstraintViolations ──

    @Test
    void uniqueViolationRecognisedForMysqlAndPostgres() {
        assertTrue(DbConstraintViolations.isUniqueViolation(wrap(
                new SQLIntegrityConstraintViolationException("Duplicate entry", "23000", 1062))));
        assertTrue(DbConstraintViolations.isUniqueViolation(wrap(new SQLException("dup", "23505"))));
    }

    @Test
    void otherIntegrityErrorsAreNotTreatedAsUniqueViolation() {
        // MySQL 外键/非空同为 SQLState 23000，但 vendor code 不同——不得被当作并发撞键吞掉
        assertFalse(DbConstraintViolations.isUniqueViolation(wrap(
                new SQLIntegrityConstraintViolationException("cannot be null", "23000", 1048))));
        assertFalse(DbConstraintViolations.isUniqueViolation(wrap(
                new SQLIntegrityConstraintViolationException("fk", "23000", 1452))));
    }

    private static DataIntegrityViolationException wrap(SQLException cause) {
        return new DataIntegrityViolationException("wrapped", cause);
    }
}
