package org.quwuting.quwutingservice.wxsubscribe.repository;

import org.quwuting.quwutingservice.wxsubscribe.entity.WxSubscribeQuota;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 微信订阅消息额度仓储（2026-09-07 新增，V11）。
 * <p>
 * 全部写路径走原子 SQL（nativeQuery=true，含真实表名/MySQL 方言）：
 * upsert 累加 / 发送扣减 / 43101 清零——禁读改写（并发授权与并发发送双写竞争）。
 * 上线前经 {@code -Drun.db.tests=true} 的 WxSubscribeSqlTest 对真实库验证
 * （native SQL 只在执行期由 DB 校验，见 AGENTS.md「native SQL 验证」）。
 */
public interface WxSubscribeQuotaRepository extends JpaRepository<WxSubscribeQuota, Long> {

    /**
     * 授权额度累加（用户收藏动作弹授权、用户点「允许」后前端上报触发）。
     * <p>
     * MySQL upsert：首授权插入（granted_total = available_count = 1），重复授权
     * 原子累加；available_count 封顶 100（LEAST 守卫——正常用户授权频次远低于此，
     * 防异常上报虚增额度导致发送时反复打 43101 浪费微信 API 调用）；granted_total
     * 只增不减（授权漏斗口径）。时间统一 Java 侧写入（不引入 DB 时钟，项目纪律）。
     */
    /**
     * 授权额度累加（用户收藏动作弹授权、用户点「允许」后前端上报触发）。
     * <p>
     * MySQL upsert：首授权插入（granted_total = available_count = 1），重复授权
     * 原子累加；available_count 封顶 100（LEAST 守卫——正常用户授权频次远低于此，
     * 防异常上报虚增额度导致发送时反复打 43101 浪费微信 API 调用）；granted_total
     * 只增不减（授权漏斗口径）。时间统一 Java 侧写入（不引入 DB 时钟，项目纪律）。
     * <p>
     * 踩坑记录（2026-09-07 WxSubscribeSqlTest 真实库抓到）：8.0.19+ 的行别名语法
     * （{@code VALUES (...) AS new ON DUPLICATE KEY UPDATE col = new.col}）在 RDS
     * MySQL 8.0.36 上报 {@code Column 'available_count' in field list is ambiguous}——
     * 声明行别名后 UPDATE 子句裸列名歧义，需逐列表名限定，可读性差；回退传统
     * {@code VALUES(col)} 语法（8.0.36 实测可用，deprecation 警告无害）。
     */
    @Modifying
    @Query(value = """
            INSERT INTO qwt_wx_subscribe_quota
                (user_id, template_id, available_count, granted_total, last_granted_at, created_at, updated_at, deleted)
            VALUES (:userId, :templateId, 1, 1, :now, :now, :now, false)
            ON DUPLICATE KEY UPDATE
                available_count = LEAST(available_count + 1, 100),
                granted_total = granted_total + 1,
                last_granted_at = VALUES(last_granted_at),
                updated_at = VALUES(updated_at)
            """, nativeQuery = true)
    void upsertGrant(@Param("userId") Long userId,
                     @Param("templateId") String templateId,
                     @Param("now") LocalDateTime now);

    /**
     * 发送成功后扣减一条额度（原子；available_count > 0 守卫防负数）。
     *
     * @return 影响行数（0 = 额度已不存在/已清零，不影响主流程）
     */
    @Modifying
    @Query(value = """
            UPDATE qwt_wx_subscribe_quota
            SET available_count = available_count - 1, updated_at = :now
            WHERE user_id = :userId AND template_id = :templateId
              AND deleted = false AND available_count > 0
            """, nativeQuery = true)
    int deductOne(@Param("userId") Long userId,
                  @Param("templateId") String templateId,
                  @Param("now") LocalDateTime now);

    /**
     * 微信返回 43101（用户未订阅/额度耗尽）时本地清零对账——微信侧额度是真相源，
     * 本地计数可能因异常漂移（如用户在小程序设置页取消了订阅），清零防持续无效发送。
     */
    @Modifying
    @Query(value = """
            UPDATE qwt_wx_subscribe_quota
            SET available_count = 0, updated_at = :now
            WHERE user_id = :userId AND template_id = :templateId AND deleted = false
            """, nativeQuery = true)
    void clearAvailable(@Param("userId") Long userId,
                        @Param("templateId") String templateId,
                        @Param("now") LocalDateTime now);

    /**
     * 状态变更发送收件人查询：给定关注者集合（事件方已按 deleted=false 过滤），
     * 一次往返取齐 openid + 剩余额度（性能第一约束：最少 DB 往返；join users 取
     * openid、quota 取额度，仅剩 available_count > 0 的用户进入发送）。
     * 调用方保证 userIds 非空（空 IN 列表 SQL 语法错误）。
     */
    @Query(value = """
            SELECT u.id AS userId, u.open_id AS openId, q.available_count AS availableCount
            FROM qwt_wx_subscribe_quota q
            JOIN qwt_users u ON u.id = q.user_id AND u.deleted = false
            WHERE q.template_id = :templateId AND q.deleted = false
              AND q.available_count > 0
              AND u.id IN (:userIds)
            """, nativeQuery = true)
    List<StatusChangeRecipient> findStatusChangeRecipients(@Param("templateId") String templateId,
                                                           @Param("userIds") List<Long> userIds);

    /**
     * 收件人投影（native 查询列别名对齐 getter）：openid 仅供微信 API 下发，
     * 不得外泄到任何响应体。
     */
    interface StatusChangeRecipient {
        Long getUserId();

        String getOpenId();

        int getAvailableCount();
    }

    /** 用户某模板额度记录（GET /user/wx-subscribe-status 数据源；无记录 = 从未授权） */
    Optional<WxSubscribeQuota> findByUserIdAndTemplateIdAndDeletedFalse(Long userId, String templateId);
}
