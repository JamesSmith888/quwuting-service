package org.quwuting.quwutingservice.user.repository;

/**
 * 管理端「用户统计」口径的<b>单一事实源</b>（2026-09-15 根因修复，仅 ADMIN 消费）。
 * <p>
 * <b>为什么需要这个类（根因）</b>：此前「用户范围」与「有效活跃事实集」两段口径
 * 以<b>文本抄写</b>的形式散落在多个仓库里——{@code UserDailyStatsRepository} 抄了
 * 2 份、{@code SpendStatsRepository} 抄了 5 份、{@code UserRepository} 的
 * {@code countActiveSince} 又自己拼了一份「四源 MAX（含登录自动打卡）」。
 * 于是同一个「活跃」概念在<b>同一个页面上并存两套定义</b>：趋势图剔除
 * ADMIN/{@code test_}/审核号且只认真实互动，顶卡「近7日活跃」却把登录自动打卡算作
 * 活跃；注册序列剔 ADMIN/test_ 而互动序列只剔审核号。运营据此得出「用户还在」的
 * 结论，与同屏曲线互相矛盾——而这类漂移是<b>纯文本层面的不同步</b>，编译器与
 * HQL 语法测试都发现不了（同类先例见 {@code VenueAliasMatchMirrorTest}）。
 * <p>
 * <b>机制（长期方案）</b>：口径下沉为编译期常量，消费方只能引用、不能重写；
 * 配套零依赖门禁 {@code UserStatsSqlMirrorTest} 断言每个统计 @Query 都包含本类的
 * 谓词与事实集——「内联抄写」能通过编译，但会在门禁处失败。
 * 新增/调整口径 = 只改本类 + 同步 {@code docs/agents/35-dashboard-stats.md} 口径表。
 * <p>
 * <b>约定</b>：
 * <ul>
 *   <li>{@link #USER_SCOPE} 假定用户表别名为 {@code u}；</li>
 *   <li>{@link #ACTIVE_FACT_UNION} / {@link #PASSIVE_TRACE_FACT_UNION} 假定入参名
 *       为 {@code :sinceDay}（{@code LocalDate}），输出列固定 {@code user_id}/{@code day}；</li>
 *   <li>{@link #USER_SCOPE} 是<b>唯一</b>「平台真实用户」定义——累计注册 / 今日新增 /
 *       活跃 / 留存 / 公告触达分母 / 账本统计同一分母，禁止某处单独加严或放宽；</li>
 *   <li>行为表时间列由写路径（JPA {@code @PrePersist}）保证非空，窗口下界一律按严格
 *       {@code >=} 施加——不可归因的历史脏行按窗口外处理（宁少不多）。</li>
 * </ul>
 * <p>
 * <b>MySQL 8 方言</b>（生产 RDS MySQL），勿在 PG 环境执行。
 */
public final class UserStatsSql {

    private UserStatsSql() {
    }

    /**
     * 平台真实用户范围谓词（别名约定：{@code u} = {@code qwt_users}）。
     * <p>
     * 剔除三类账号（语义 = <b>统计去噪，不是处罚</b>，账号本身不删、功能不受影响）：
     * <ul>
     *   <li>{@code role='ADMIN'}：运营/测试号，不是产品用户；</li>
     *   <li>{@code open_id LIKE 'test\\_%'}：开发联调号（{@code _} 已转义为字面量）；</li>
     *   <li>{@code wechat_review=true}：微信审核/巡检账号（V17 标记体系，人工可增减）。</li>
     * </ul>
     * 应用方式：消费方 FROM 子句写 {@code qwt_users u}（或外层别名对齐后再引用）。
     */
    public static final String USER_SCOPE = """
            u.deleted = false AND u.role = 'USER'
              AND u.open_id NOT LIKE 'test\\_%'
              AND u.wechat_review = false""";

    /**
     * <b>运营/开发号谓词</b>——{@link #USER_SCOPE} 排除项之一（role=ADMIN 或 open_id 以
     * {@code test_} 开头），单独成常量以支撑「口径自证」查询
     * （{@code UserRetentionRepository#sumScopeAudit}：全部账号 = 有效用户 + 运营开发号 +
     * 微信审核号，各项互斥）。改动 {@link #USER_SCOPE} 的角色/开发号规则时，本常量必须同步
     * ——门禁 {@code UserStatsSqlMirrorTest} 断言自证查询引用了它。
     */
    public static final String OPS_ACCOUNT_PREDICATE = "u.role = 'ADMIN' OR u.open_id LIKE 'test\\_%'";

    /**
     * <b>微信审核账号谓词</b>——{@link #USER_SCOPE} 排除项之二，单独成常量理由同上。
     */
    public static final String REVIEW_ACCOUNT_PREDICATE = "u.wechat_review = true";

    /**
     * <b>有效活跃事实集</b>：用户<b>主动行为</b>的 12 表全集，逐行 {@code (user_id, day)}。
     * 这是「这个用户今天真的用了产品吗」的<b>唯一</b>判据——用于大盘「真实互动」序列、
     * 留存活跃判定、以及 {@code /admin/users/stats} 的「近 7 日活跃」。
     * <p>
     * <b>事实口径 = 动作发生过（行存在），不是「当前仍有效」</b>（2026-09-15 口径修正）：
     * 旧实现混入状态条件（{@code deleted = false}、{@code unfavorited_at IS NULL}），
     * 于是<b>历史会被事后改写</b>——用户今天取消收藏，他 8 月的「互动」就凭空消失，
     * 昨天的 DAU 与三个月前的批次留存会随今天的操作一起变。对趋势与留存系列这是
     * 致命缺陷（事件不可变是任何留存分析的前提）；同时它还把钱藏在暗处的用户误判成
     * 「从未互动」（噪音）——{@code qwt_venue_status_reports} 被采纳即置
     * {@code deleted=true}，于是「唯一动作是被采纳的上报」的老用户会被算成审核流量。
     * 修正后：<b>行为发生即入集，事后撤销不改写历史</b>。
     * <p>
     * <b>刻意排除（判据 = 是否用户主动发起的有意义行为）</b>：
     * <ul>
     *   <li>{@code qwt_daily_checkins} 每日打卡——登录后<b>自动</b>触发
     *       （{@code services/autoCheckIn.ts}），只代表「当天打开过」，是触达信号不是
     *       使用信号；把它算进「活跃」正是本次修复的那个错误决策；</li>
     *   <li>站内信 / 状态上报回执 / 招工联系 / 公告已读——被动或弱意图信号，见
     *       {@link #PASSIVE_TRACE_FACT_UNION}（只在「噪音判定」里作为「有痕迹」使用）；</li>
     *   <li>消费账本（{@code qwt_spend_entries}）与快讯表态/浏览——独立子域，纳入需
     *       单独立项评审（会改变大盘历史数字口径，禁静默改），登记为「扩展位」，
     *       见 docs/agents/35。</li>
     * </ul>
     * 入参约定：{@code :sinceDay}。窗口下界对所有分支等价成立，故同一片段可同时服务
     * 「近 N 天大盘」「注册后从未互动（噪音）」与「留存活跃」三类消费。
     * <p>
     * <b>本常量是生成物（2026-09-15 起）</b>：成员、顺序与每分支形状由行为事件目录
     * {@link UserBehaviorEvent}（{@link UserBehaviorEvent.Nature#ACTIVE}）声明并生成，
     * 门禁 {@code UserBehaviorCatalogMirrorTest} 断言本字面量与生成器输出<b>逐字相等</b>。
     * 加/删一个行为表请改目录（本处不再是人写的表清单）；事件级消费方（轨迹/类型分布/时段）
     * 用 {@link UserBehaviorSql}。
     */
    public static final String ACTIVE_FACT_UNION = """
            SELECT user_id, view_date AS day FROM qwt_venue_views WHERE user_id IS NOT NULL AND view_date >= CAST(:sinceDay AS DATE)
            UNION ALL SELECT user_id, view_date AS day FROM qwt_dancer_views WHERE user_id IS NOT NULL AND view_date >= CAST(:sinceDay AS DATE)
            UNION ALL SELECT user_id, DATE(created_at) AS day FROM qwt_venue_shares WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, DATE(created_at) AS day FROM qwt_dancer_shares WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, reaction_date AS day FROM qwt_venue_reactions WHERE user_id IS NOT NULL AND reaction_date >= CAST(:sinceDay AS DATE)
            UNION ALL SELECT user_id, DATE(created_at) AS day FROM qwt_favorites WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, DATE(created_at) AS day FROM qwt_dancer_favorites WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, DATE(created_at) AS day FROM qwt_demand_records WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, DATE(created_at) AS day FROM qwt_venue_status_watchers WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, report_date AS day FROM qwt_venue_crowd_reports WHERE user_id IS NOT NULL AND report_date >= CAST(:sinceDay AS DATE)
            UNION ALL SELECT user_id, DATE(created_at) AS day FROM qwt_venue_feedbacks WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, DATE(created_at) AS day FROM qwt_tag_interactions WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)""";

    /**
     * <b>被动痕迹事实集</b>：站内信 / 门店状态上报 / 招工联系 / 公告已读——列
     * {@code (user_id)} 去重（{@code UNION}）。只在「打卡型噪音」判定里使用：噪音的
     * 判据是「注册后<u>从未有任何痕迹</u>」（含被动），比有效活跃更宽——宁可漏判噪音，
     * 不可误判真实用户。
     * <p>
     * 同为「动作发生过」口径（不筛 {@code deleted}）：被采纳的状态上报会被写路径置
     * {@code deleted=true}，若按状态口径过滤，唯一动作恰好是「被采纳」的用户会被
     * 误判成审核流量。
     * <p>
     * 窗口等价性：噪音判定只考察 {@code created_at >= :sinceDay} 的注册用户，而痕迹
     * 必定晚于注册，故对痕迹同样施加 {@code :sinceDay} 下界语义恒等（避免全表扫）。
     * <p>
     * <b>本常量是生成物（2026-09-15 起）</b>：成员由行为事件目录
     * {@link UserBehaviorEvent}（{@link UserBehaviorEvent.Nature#PASSIVE}）声明并生成，
     * 门禁断言逐字相等（同 {@link #ACTIVE_FACT_UNION}）。
     */
    public static final String PASSIVE_TRACE_FACT_UNION = """
            SELECT user_id FROM qwt_messages WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION SELECT user_id FROM qwt_venue_status_reports WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION SELECT user_id FROM qwt_recruitment_contacts WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION SELECT user_id FROM qwt_announcement_reads WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)""";

    /**
     * <b>痕迹全集</b> = 有效活跃事实集 ∪ 被动痕迹事实集，列 {@code (user_id)} 去重
     * （内层 {@code UNION ALL} 的重复由外层 {@code UNION} 收敛）。打卡型噪音判定的
     * 唯一事实源（{@code NOT EXISTS (... WHERE act.user_id = u.id)}）。
     */
    public static final String TRACE_FACT_UNION =
            "SELECT user_id FROM (\n" + ACTIVE_FACT_UNION + "\n) act_user_ids\n"
                    + "UNION\n" + PASSIVE_TRACE_FACT_UNION;
}
