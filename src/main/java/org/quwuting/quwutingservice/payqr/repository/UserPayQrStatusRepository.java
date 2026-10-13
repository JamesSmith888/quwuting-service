package org.quwuting.quwutingservice.payqr.repository;

import org.quwuting.quwutingservice.payqr.entity.UserPayQrStatus;
import org.quwuting.quwutingservice.user.repository.UserStatsSql;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 收款码配置状态仓储（2026-10-13，V49）。写侧 = 单语句 upsert；读侧 = admin 汇总 / 名单 / 批量标记。
 * <p>
 * 用户范围恒引用 {@link UserStatsSql#USER_SCOPE}（剔 ADMIN 运营号 / test_ 开发号 / 微信审核号）——
 * 与同屏「计时 · 账本使用」面板同一分母；⛔ 禁内联抄写该谓词（门禁 {@code UserStatsSqlMirrorTest}）。
 * 拼接处两侧补空格是硬约束（文本块拼接漏空格事故，10-06）。
 */
public interface UserPayQrStatusRepository extends JpaRepository<UserPayQrStatus, Long> {

    /** 汇总行投影（getter 名与 SQL alias 逐字匹配，同仓惯例） */
    interface StatsRow {
        Long getConfiguredUsers();

        Long getEverConfiguredUsers();

        Long getTotalQrCount();

        Long getNewUsers7d();

        Long getNewUsers30d();
    }

    /** 名单行投影 */
    interface UserRow {
        Long getUserId();

        Integer getQrCount();

        LocalDateTime getFirstConfiguredAt();

        LocalDateTime getChangedAt();
    }

    /**
     * 幂等 upsert。<b>赋值顺序是硬约束</b>：MySQL 的 {@code ON DUPLICATE KEY UPDATE} 按书写顺序逐项赋值，
     * 后一项看到的是前一项赋值后的值——{@code updated_at} 必须排在 {@code qr_count} 之前，才能用
     * 「旧 qr_count ≠ 新值」判定张数是否真的变化；同值重复上报（每次启动都会校对一次）不推进 {@code updated_at}，
     * 它才能保持「最近一次变更」的语义。{@code first_configured_at} 用 COALESCE 保首次。
     * <p>
     * 时间口径同全库：{@code now} / {@code firstAt} 由 Java 传（JVM = 北京时间），禁 DB now()。
     */
    @Modifying
    @Query(value = "INSERT INTO qwt_user_pay_qr_status "
            + "(created_at, updated_at, deleted, user_id, qr_count, first_configured_at) "
            + "VALUES (:now, :now, false, :userId, :count, :firstAt) "
            + "ON DUPLICATE KEY UPDATE "
            + "updated_at = IF(qr_count <> VALUES(qr_count), VALUES(updated_at), updated_at), "
            + "first_configured_at = COALESCE(first_configured_at, VALUES(first_configured_at)), "
            + "qr_count = VALUES(qr_count)",
            nativeQuery = true)
    void upsert(@Param("userId") Long userId,
                @Param("count") int count,
                @Param("firstAt") LocalDateTime firstAt,
                @Param("now") LocalDateTime now);

    /**
     * 当前持有张数 + 首次配置时刻（批量，用户信息标记用）：Object[]{userId, qrCount, firstConfiguredAt}。
     * 带上首次配置时刻是为了区分「配置后清空」与「无记录」（{@code PayQrState}）。
     */
    @Query("SELECT s.userId, s.qrCount, s.firstConfiguredAt FROM UserPayQrStatus s "
            + "WHERE s.userId IN :userIds AND s.deleted = false")
    List<Object[]> findStatusByUserIds(@Param("userIds") Collection<Long> userIds);

    /** admin 汇总（真实用户范围）：当前持有 / 曾配置 / 张数合计 / 近 7、30 天新增配置人数 */
    @Query(value = "SELECT COALESCE(SUM(CASE WHEN s.qr_count > 0 THEN 1 ELSE 0 END), 0) AS configuredUsers, "
            + "COALESCE(SUM(CASE WHEN s.first_configured_at IS NOT NULL THEN 1 ELSE 0 END), 0) AS everConfiguredUsers, "
            + "COALESCE(SUM(s.qr_count), 0) AS totalQrCount, "
            + "COALESCE(SUM(CASE WHEN s.first_configured_at >= :since7 THEN 1 ELSE 0 END), 0) AS newUsers7d, "
            + "COALESCE(SUM(CASE WHEN s.first_configured_at >= :since30 THEN 1 ELSE 0 END), 0) AS newUsers30d "
            + "FROM qwt_user_pay_qr_status s JOIN qwt_users u ON u.id = s.user_id "
            + "WHERE s.deleted = 0 AND " + UserStatsSql.USER_SCOPE,
            nativeQuery = true)
    StatsRow sumStats(@Param("since7") LocalDateTime since7, @Param("since30") LocalDateTime since30);

    /**
     * admin 名单：<b>配置过</b>收款码的真实用户（含已清空者，由 qrCount=0 区分），按最近一次变更倒序。
     * 由调用方传上限（名单是运营核查面，不做深翻页）。
     */
    @Query(value = "SELECT s.user_id AS userId, s.qr_count AS qrCount, "
            + "s.first_configured_at AS firstConfiguredAt, s.updated_at AS changedAt "
            + "FROM qwt_user_pay_qr_status s JOIN qwt_users u ON u.id = s.user_id "
            + "WHERE s.deleted = 0 AND s.first_configured_at IS NOT NULL AND " + UserStatsSql.USER_SCOPE + " "
            + "ORDER BY s.updated_at DESC, s.id DESC LIMIT :limit",
            nativeQuery = true)
    List<UserRow> findConfiguredUsers(@Param("limit") int limit);
}
