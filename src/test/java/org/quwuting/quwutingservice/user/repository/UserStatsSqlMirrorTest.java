package org.quwuting.quwutingservice.user.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理端用户统计口径「单一事实源」一致性静态校验（2026-09-15，零依赖：纯字符串断言 +
 * 注解反射；不连库、不起 Spring 容器）。
 * <p>
 * <b>为什么需要它</b>：2026-09-15 的根因是同一名词「活跃」在两处各自表述且互不知晓
 * ——大盘趋势按「真实互动」口径且剔 ADMIN/{@code test_}/审核号，顶卡「近 7 日活跃」
 * 却按「四源 MAX（含<b>登录自动打卡</b>）」统计且只剔审核号。这类缺陷的载体是<b>文本</b>
 * （谓词/事实集被抄写多份，改一处漏一处），编译器、HQL 语法测试、启动校验全部发现不了；
 * 而它的代价是运营对着一张自相矛盾的图做决策。详见 {@code docs/agents/35-dashboard-stats.md}
 * 「口径单一事实源」与 {@code UserStatsSql} 类注释。
 * <p>
 * <b>判据（三类）</b>：
 * <ol>
 *   <li>事实集<b>成员完整</b>：12 张主动行为表 + 4 张被动痕迹表一张不少（删成员 =
 *       静默把某类真实用户判成流失/噪音）；</li>
 *   <li>事实集<b>不含打卡</b>：{@code qwt_daily_checkins} 与「当前有效」类状态条件
 *       （{@code unfavorited_at}）不得出现在活跃事实里——前者就是本次修复的错误决策，
 *       后者会让历史被事后改写；</li>
 *   <li>消费方<b>只能引用常量</b>：各统计 @Query 必须包含 {@code UserStatsSql} 的谓词/
 *       事实集片段；并由「{@code countActiveUsers} 不得再出现四源 MAX 形状」与
 *       「留存查询不得触碰打卡」两条反向断言锁住根因修复不被回退。</li>
 * </ol>
 * <b>局限</b>：断言的是「引用了常量」这一外部契约，不校验谓词写法细节（如 ESCAPE /
 * 括号），也不校验函数式派生查询（{@code countByDeletedFalseAndWechatReviewFalseAndRole}
 * 之类无 SQL 可反射）；但「漏引用常量 / 事实集成员被删 / 打卡回潮」三类最高危形态
 * 可被完全拦住。
 */
class UserStatsSqlMirrorTest {

    /** 活跃事实集（用户主动行为）12 表——删任何一项都会静默改变活跃与留存口径 */
    private static final List<String> ACTIVE_TABLES = List.of(
            "qwt_venue_views", "qwt_dancer_views",
            "qwt_venue_shares", "qwt_dancer_shares",
            "qwt_venue_reactions", "qwt_favorites", "qwt_dancer_favorites",
            "qwt_demand_records", "qwt_venue_status_watchers",
            "qwt_venue_crowd_reports", "qwt_venue_feedbacks", "qwt_tag_interactions");

    /** 被动痕迹 4 表（仅用于噪音判定的「有过任何痕迹」） */
    private static final List<String> PASSIVE_TABLES = List.of(
            "qwt_messages", "qwt_venue_status_reports",
            "qwt_recruitment_contacts", "qwt_announcement_reads");

    /** 登录自动打卡表：只允许出现在「打开」序列，永不进活跃/留存事实集 */
    private static final String CHECKIN_TABLE = "qwt_daily_checkins";

    @Test
    void userScopeCarriesAllFourExclusions() {
        String scope = UserStatsSql.USER_SCOPE;
        assertTrue(scope.contains("u.deleted = false"), "范围谓词丢失软删过滤——已删账号会重回统计分母");
        assertTrue(scope.contains("u.role = 'USER'"), "范围谓词丢失角色过滤——ADMIN 运营号会进真实用户分母");
        assertTrue(scope.contains("open_id NOT LIKE"), "范围谓词丢失 test_ 开发联调号过滤");
        assertTrue(scope.contains("u.wechat_review = false"), "范围谓词丢失微信审核账号过滤（V17 去噪口径）");
    }

    @Test
    void activeFactUnionCarriesCanonicalTables() {
        String union = UserStatsSql.ACTIVE_FACT_UNION;
        for (String table : ACTIVE_TABLES) {
            assertTrue(union.contains(table),
                    "活跃事实集缺少 " + table + "——该类行为不再算作活跃，"
                            + "会把真实用户静默判成流失（并连带进入留存分母的错误结论）。"
                            + "成员清单见 docs/agents/35-dashboard-stats.md 口径表");
        }
    }

    @Test
    void activeFactUnionRejectsCheckinAndStateConditions() {
        String union = UserStatsSql.ACTIVE_FACT_UNION;
        assertTrue(!union.contains(CHECKIN_TABLE),
                "活跃事实集混入了 " + CHECKIN_TABLE + "（登录自动打卡）——打卡只代表「打开过」，"
                        + "把它当活跃正是 2026-09-15 修复的错误决策；触达信号若需展示，"
                        + "请单独成序列并命名为「打开」");
        assertTrue(!union.contains("unfavorited_at"),
                "活跃事实集混入了状态条件 unfavorited_at——事实集必须是「动作发生过」（行存在），"
                        + "否则用户今天取消收藏会让他的历史互动凭空消失，批次留存被事后改写；"
                        + "见 UserStatsSql 类注释「事实口径」");
        assertTrue(union.contains(":sinceDay"),
                "活跃事实集丢失 :sinceDay 窗口下界（片段约定：入参名必须为 sinceDay）");
    }

    @Test
    void traceUnionCarriesPassiveTables() {
        String trace = UserStatsSql.TRACE_FACT_UNION;
        for (String table : PASSIVE_TABLES) {
            assertTrue(trace.contains(table),
                    "痕迹全集缺少 " + table + "——噪音判定（注册后从未有任何痕迹）会变宽，"
                            + "把只做过被动动作的真实用户误判成微信审核流量");
        }
        assertTrue(trace.contains(UserStatsSql.ACTIVE_FACT_UNION),
                "痕迹全集必须由活跃事实集组合而成（主动 + 被动），不得另抄一份主动行为清单");
    }

    @Test
    void dashboardQueryComposesSharedFragments() {
        String sql = queryOf(UserDailyStatsRepository.class, "countDailyStats", LocalDate.class);
        assertTrue(sql.contains(UserStatsSql.USER_SCOPE), "大盘四序列必须引用统一用户范围谓词");
        assertTrue(sql.contains(UserStatsSql.ACTIVE_FACT_UNION), "大盘「真实互动」必须引用统一活跃事实集");
        assertTrue(sql.contains(UserStatsSql.TRACE_FACT_UNION), "大盘「噪音」必须引用统一痕迹全集");
        assertTrue(sql.contains(CHECKIN_TABLE),
                "大盘「打开」序列应保留打卡表（它是唯一允许使用打卡的地方）——"
                        + "若已删除该序列，需同步下线 admin-web 趋势图的「打开」线并改本期文档");
    }

    @Test
    void statsQueriesComposeSharedScope() {
        assertTrue(queryOf(UserRepository.class, "countRealUsers").contains(UserStatsSql.USER_SCOPE),
                "累计用户分母必须走统一范围谓词（否则顶卡 ≠ 趋势线之和）");
        assertTrue(queryOf(UserRepository.class, "countRealUsersCreatedSince", LocalDateTime.class)
                        .contains(UserStatsSql.USER_SCOPE),
                "今日新增必须走统一范围谓词");
        assertTrue(queryOf(UserRepository.class, "countActiveUsers", LocalDate.class)
                        .contains(UserStatsSql.ACTIVE_FACT_UNION),
                "近 7 日活跃必须走统一活跃事实集");
        assertTrue(queryOf(UserRepository.class, "findIdsActiveSince", LocalDate.class)
                        .contains(UserStatsSql.ACTIVE_FACT_UNION)
                        && queryOf(UserRepository.class, "findIdsActiveSince", LocalDate.class)
                                .contains(UserStatsSql.USER_SCOPE),
                "列表「近期活跃」id 集合必须走统一事实集 + 统一用户范围谓词——"
                        + "否则列表筛选/行级标记与统计条「近 7 日活跃」会给出对不上的数字");
        for (String method : List.of("listCohortSizes", "listCohortRetention", "listDailyActivity", "sumActivity")) {
            assertTrue(queryOf(UserRetentionRepository.class, method, LocalDate.class)
                            .contains(UserStatsSql.USER_SCOPE),
                    "留存查询 " + method + " 必须引用统一范围谓词");
        }
        for (String method : List.of("sumSummary", "countDaily")) {
            assertTrue(queryOf(org.quwuting.quwutingservice.spend.repository.SpendStatsRepository.class,
                    method, LocalDate.class).contains(UserStatsSql.USER_SCOPE),
                    "账本统计 " + method + " 必须引用统一范围谓词");
        }
        assertTrue(queryOf(org.quwuting.quwutingservice.spend.repository.SpendStatsRepository.class,
                        "sumByCategory").contains(UserStatsSql.USER_SCOPE),
                "账本分类统计必须引用统一范围谓词");
        assertTrue(queryOf(org.quwuting.quwutingservice.spend.repository.SpendStatsRepository.class,
                        "sumByVenueTop", int.class).contains(UserStatsSql.USER_SCOPE),
                "账本门店 TOP 必须引用统一范围谓词");
        assertTrue(queryOf(org.quwuting.quwutingservice.spend.repository.SpendStatsRepository.class,
                        "listUsageUsers").contains(UserStatsSql.USER_SCOPE),
                "记账用户列表必须引用统一范围谓词");
    }

    @Test
    void activeUserCountKeepsItsRootCauseFix() {
        String sql = queryOf(UserRepository.class, "countActiveUsers", LocalDate.class);
        assertTrue(!sql.contains("GREATEST("),
                "近 7 日活跃又回到了「四源 MAX」形状（资料更新/积分流水/邀约/打卡）——"
                        + "它的实义是「近 7 日打开过的号」，会与同屏「真实互动」曲线自相矛盾；"
                        + "「最后露面」是另一个指标，不得占用「活跃」之名");
        assertTrue(!sql.contains(CHECKIN_TABLE), "近 7 日活跃不得触碰打卡表");
        assertTrue(!sql.contains("qwt_points_transactions"),
                "近 7 日活跃不得用积分流水代替用户主动行为事实集（流水只覆盖积分动作）");
    }

    @Test
    void scopeAuditStaysComplementOfUserScope() {
        String sql = queryOf(UserRetentionRepository.class, "sumScopeAudit");
        assertTrue(sql.contains(UserStatsSql.OPS_ACCOUNT_PREDICATE),
                "口径自证查询必须引用「运营/开发号」常量——它与 USER_SCOPE 的排除项是同一套规则的"
                        + "正反两面，内联抄写会让两边漂移（自证等式就会算错，比不显示更糟）");
        assertTrue(sql.contains(UserStatsSql.REVIEW_ACCOUNT_PREDICATE),
                "口径自证查询必须引用「微信审核账号」常量（理由同上）");
        assertTrue(!sql.contains("role = 'USER'"),
                "口径自证统计的正是被 USER_SCOPE 排除的部分，不应再按 role='USER' 过滤"
                        + "（否则等式右侧恒为空）");
    }

    @Test
    void retentionQueriesNeverUseCheckin() {
        for (String method : List.of("listCohortSizes", "listCohortRetention", "listDailyActivity", "sumActivity")) {
            assertTrue(!queryOf(UserRetentionRepository.class, method, LocalDate.class).contains(CHECKIN_TABLE),
                    "留存查询 " + method + " 不得使用打卡——留存口径若含自动打卡，"
                            + "留存率会被「打开过就有留存」抬成假象");
        }
    }

    /**
     * 反射读取方法上的 {@code @Query}（RUNTIME 保留）。方法改名/改签名时本测试会以
     * NoSuchMethodException 失败——那是<b>有意的</b>：签名变化意味着「该统计口径的
     * 消费方需要重新确认」，不该静默通过（同 {@code VenueAliasMatchMirrorTest} 约定）。
     */
    private static String queryOf(Class<?> repository, String method, Class<?>... params) {
        try {
            Query query = repository.getDeclaredMethod(method, params).getAnnotation(Query.class);
            assertNotNull(query, repository.getSimpleName() + "#" + method + " 应为 @Query 注解方法"
                    + "（统计口径必须写成可校验的原生 SQL）");
            return query.value();
        } catch (NoSuchMethodException e) {
            throw new AssertionError(repository.getSimpleName() + "#" + method
                    + " 签名已变化——请同步确认本门禁与 docs/agents/35 的口径表", e);
        }
    }
}
