package org.quwuting.quwutingservice.user.repository;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户行为事实的<b>编译期目录（单一事实源）</b>——「用户做过什么」这件事的唯一定义处
 * （2026-09-15，仅 ADMIN 消费；docs/agents/35-dashboard-stats.md「行为事件目录」节）。
 *
 * <h2>为什么需要它（根因）</h2>
 * 在此之前，「行为事实」只以<b>SQL 文本</b>形式存在：{@code UserStatsSql.ACTIVE_FACT_UNION}
 * 是一条手写的 12 表 {@code UNION ALL} 字符串，{@code PASSIVE_TRACE_FACT_UNION} 是另一条手写的
 * 4 表字符串。这两条文本<b>只声明了「表名 + 列名」</b>，没有任何地方声明「这张表代表什么事件、
 * 属于哪一类、算不算活跃」。后果有三，且都是结构性的（不是某一处写错）：
 * <ol>
 *   <li><b>新增消费方 = 再抄一遍</b>：想做「行为轨迹」「类型分布」「活跃时段」时，只能把
 *       表清单与列名<b>再抄一份</b>写进新 SQL。抄写漂移是纯文本层面的不同步——编译器、
 *       HQL 语法测试、启动校验全都发现不了（2026-09-15 已因此出过一次事故：口径在 3 个仓库
 *       抄了 8 处）。</li>
 *   <li><b>新行为表没有落点</b>：站内信 / 快讯表态 / 消费账本要不要算「活跃」，只能靠人记、
 *       靠注释约束；没有任何地方能回答「哪些事件算活跃、为什么」。</li>
 *   <li><b>跨表叙事无法表达</b>：把 12 张表按时间合并成「一条轨迹」在旧结构下只能写成第 N 份
 *       手抄的 mega-query，且无法证明它与活跃口径同源。</li>
 * </ol>
 *
 * <h2>长期方案（本类承担）</h2>
 * 把行为事实从「SQL 文本」升级为<b>声明式目录</b>：每个事件在这里声明一次
 * （表 / 时间列 / 日列 / 口径档 / 分类 / 中文标签 / 关联对象类型 / 明细列），
 * 全部 SQL 片段（{@link #activeFactUnion()} / {@link #passiveTraceFactUnion()} /
 * {@link #allEventFactUnion()} / {@link #activeEventFactUnion()} / {@link #eventDetailUnion()}）
 * 由目录<b>生成</b>。于是：
 * <ul>
 *   <li>「新增一张行为表」= 只在本枚举加一行，活跃口径、轨迹、分布、时段统计<b>同时</b>获得它，
 *       不存在漏改一处；</li>
 *   <li>「某个事件算不算活跃」= 改它的 {@link Nature}，语义显式可读、可被门禁断言；</li>
 *   <li>门禁 {@code UserBehaviorCatalogMirrorTest} 双向锁定「目录 ⟺ 事实集 ⟺ 消费方引用」，
 *       任何绕过目录的内联抄写都会在测试处失败。</li>
 * </ul>
 *
 * <h2>为什么 SQL 最终仍以「字面量」承载（不是退步，是 Java 的硬约束）</h2>
 * 事实集片段被写进 Spring Data 的 {@code @Query(...)} 注解值，而<b>注解值必须是编译期常量</b>
 * —— 方法调用（哪怕是本类的生成器）不能出现在注解里，公共片段也无法在运行时拼给注解。
 * 因此本类扮演的是<b>「唯一声明处 + 期望值生成器」</b>：
 * <ul>
 *   <li>{@code UserStatsSql} / {@code UserBehaviorSql} 里的 SQL 字面量是<b>生成物</b>（载体）；</li>
 *   <li>门禁逐一断言「字面量 {@code equals} 生成器输出」——等价于给生成物配了一个<b>校验和</b>：
 *       只改枚举（漏改字面量）或只改字面量（漏改枚举）都会<b>立刻红</b>，且失败信息直接给出
 *       期望文本，复制粘贴即修复。</li>
 * </ul>
 * 于是「新增一个行为消费方要抄一遍表清单」这件事被结构性消灭：<b>抄写不再可能发生</b>
 * （不是「有人会记得同步」，而是「不同步就过不了门禁」）。
 *
 * <h2>口径档（{@link Nature}）——「活跃」之名的唯一裁决处</h2>
 * <ul>
 *   <li>{@link Nature#ACTIVE}：<b>用户主动发起</b>的有意义行为（12 个）——<b>这是管理端
 *       「活跃」的唯一含义</b>，进 {@link #activeFactUnion()}；</li>
 *   <li>{@link Nature#COLLAB}：协作类申请（门店认领）——用户主动、但属资料协作工作流，
 *       当前<b>不在</b>活跃口径内（纳入会改变大盘历史数字，须立项评审）；</li>
 *   <li>{@link Nature#SIGNAL}：系统/自动信号（每日打卡 = 登录后自动触发）——只代表「打开过」，
 *       <b>永不进活跃/留存</b>（2026-09-15 修复的那个错误决策就是对它用了「活跃」之名）；</li>
 *   <li>{@link Nature#PASSIVE}：被动痕迹（站内信 / 暂停上报 / 招工联系 / 公告已读）——
 *       进 {@link #passiveTraceFactUnion()}，只在「注册后是否留下过任何痕迹」的噪音判定里使用。</li>
 *   <li>{@link Nature#EXTENDED}（2026-10-09 新增）：用户主动使用的<b>扩展功能</b>（快讯 / 计时账本 /
 *       热度点赞 / 意见反馈…），尚未评审纳入「活跃」。轨迹与画像可见，<b>不进</b>活跃 / 留存 / 噪音
 *       任何一条口径事实集——「未纳入」是显式档位而不是沉默缺失，纳入 = 评审后把 Nature 改成
 *       {@code ACTIVE} 一行（同时按门禁给出的 expected 替换字面量，并在 docs/agents/35 登记数字变化）。</li>
 * </ul>
 * <b>契约</b>：{@code ACTIVE + PASSIVE} 的成员集合必须与 {@link UserStatsSql} 的两条事实集
 * 逐字一致（由本类生成，故天然一致；门禁再从反向断言一次）。
 *
 * <h2>下线事件（{@link #isRetired()}）——功能下线 ≠ 抹除事实</h2>
 * 舞伴系统（浏览 / 分享 / 收藏 / 邀约）与招工联系已下线，但<b>其历史行为仍是事实</b>：8 月有人只靠浏览舞伴
 * 活跃，他那几天就是活跃的；把这些事件从目录里删掉，会让已发布的历史 DAU / 留存曲线被事后改写
 * （与「事实口径 = 动作发生过」同一原则）。故下线事件<b>保留在目录与既有口径里</b>，只在<b>展示层</b>退场：
 * 窗口内 0 条时不再占用筛选 chips / 类型分布（下线功能恒为 0，留着只是噪音），有历史数据时照常展示并带
 * 「已下线」标记；各窗口滑过下线日后自然淡出。<b>新增的下线判定只改 {@code retiredNote} 一处</b>。
 *
 * <h2>归类门禁（防止目录再次沉默过期）</h2>
 * 目录在 2026-09-15 后无人维护，而产品随后新增了快讯 / 计时账本 / 计时同步等一批用户主动功能——
 * 它们在轨迹里整整缺席了三周，根因是「新建一张带用户列的表」与「登记进行为目录」之间<b>没有任何强制关联</b>。
 * 现由 {@code UserBehaviorCatalogMirrorTest#everyUserAttributedTableIsClassified} 回放全部 Flyway 迁移，
 * 要求每张含用户列的表<b>二选一</b>：在本目录登记，或在该测试的豁免表里写明理由——新表不表态就过不了门禁。
 *
 * <h2>新增行为的判据（先判断，再写代码）</h2>
 * <ol>
 *   <li>是不是<b>用户主动</b>发起的？不是 → 只能进 SIGNAL / PASSIVE；</li>
 *   <li>是不是「有意义的使用」而非「打开/接收」？否 → SIGNAL / PASSIVE；</li>
 *   <li>纳入 ACTIVE 会改变大盘历史数字（DAU / 留存曲线会跳变）⇒ 必须<b>立项评审</b>并在
 *       docs/agents/35 口径表登记「口径变更带来的数字变化是预期而非故障」，禁静默改。</li>
 * </ol>
 *
 * <h2>列约定</h2>
 * <ul>
 *   <li>{@code userColumn}：恒为 {@code user_id}（行为表均以此归因；游客行 {@code user_id IS NULL}
 *       一律排除——「活跃」是用户口径，游客浏览属于流量而非用户行为）；</li>
 *   <li>{@code dayColumn}：表自带的「业务日」列（{@code date} 型，如 {@code view_date}）。
 *       非空 = 该列是权威日（口径与 2026-09-15 前的既有事实集一致）；空 = 用
 *       {@code DATE(created_at)}；</li>
 *   <li>{@code created_at} 恒为时间戳列，用于轨迹排序与时段分布（{@code dayColumn} 型的表
 *       历史上可能有 {@code created_at} 为空的老行——轨迹按「日 0 点」降级展示、时段分布
 *       不统计它们，见 {@link #eventDetailUnion()}）；</li>
 *   <li>{@code refKind / refColumn}：事件的「关联对象」（门店/舞伴/招工/公告），供轨迹展示
 *       「对谁做的」；名称由 {@code BehaviorRefNameResolver} 批量解析（禁 N+1）；</li>
 *   <li>{@code detailColumn / detailDict}：事件的补充明细（分享渠道 / 标签名 / 上报类型…），
 *       由目录声明、服务层按 {@link DetailDict} 渲染，<b>前端零字典</b>。</li>
 * </ul>
 *
 * <h2>演进检查表（新增/调整事件时逐条过）</h2>
 * <ol>
 *   <li>在本枚举加一行，{@code code} 全局唯一、{@code label} 为运营可读中文；</li>
 *   <li>确认 {@link Nature}（见上「判据」），并同步 docs/agents/35 的口径表；</li>
 *   <li>跑 {@code ./mvnw -Dtest=UserBehaviorCatalogMirrorTest test}（零依赖门禁）；</li>
 *   <li>若该事件携带用户敏感列（手机号 / 真实姓名 / openId），<b>不得</b>声明为
 *       {@code detailColumn} 或 {@code refColumn}（门禁反向断言敏感列禁入）。</li>
 * </ol>
 *
 * <b>MySQL 8 方言</b>（生产 RDS MySQL），生成物勿在 PG 环境执行。
 */
public enum UserBehaviorEvent {


    // ── 主动行为（ACTIVE）：管理端「活跃」的唯一事实集 ────────────────────────────

    /** 浏览门店（列表/搜索/详情页进入均计，来源加权见 05/06 号文档） */
    VENUE_VIEW("VENUE_VIEW", "浏览门店", Category.BROWSE, Nature.ACTIVE,
            "qwt_venue_views", "view_date", RefKind.VENUE, "venue_id", null, null),

    /** 浏览舞伴主页 */
    DANCER_VIEW("DANCER_VIEW", "浏览舞伴", Category.BROWSE, Nature.ACTIVE,
            "qwt_dancer_views", "view_date", RefKind.DANCER, "dancer_id", null, null,
            "user_id", "created_at", Retired.DANCER),

    /** 分享门店（仅 SHARE 动作；OPEN 归因不计，与贡献档案口径一致） */
    VENUE_SHARE("VENUE_SHARE", "分享门店", Category.SHARE, Nature.ACTIVE,
            "qwt_venue_shares", null, RefKind.VENUE, "venue_id", "channel", DetailDict.SHARE_CHANNEL),

    /** 分享舞伴 */
    DANCER_SHARE("DANCER_SHARE", "分享舞伴", Category.SHARE, Nature.ACTIVE,
            "qwt_dancer_shares", null, RefKind.DANCER, "dancer_id", "channel", DetailDict.SHARE_CHANNEL,
            "user_id", "created_at", Retired.DANCER),

    /** 门店表情/评分（每日一记；明细码不渲染，避免把语义码泄漏成界面文案） */
    VENUE_REACTION("VENUE_REACTION", "门店表情评价", Category.INTERACT, Nature.ACTIVE,
            "qwt_venue_reactions", "reaction_date", RefKind.VENUE, "venue_id", null, null),

    /** 收藏门店（事实口径 = 收藏动作发生过，取消收藏不改写历史） */
    VENUE_FAVORITE("VENUE_FAVORITE", "收藏门店", Category.COLLECT, Nature.ACTIVE,
            "qwt_favorites", null, RefKind.VENUE, "venue_id", null, null),

    /** 收藏舞伴 */
    DANCER_FAVORITE("DANCER_FAVORITE", "收藏舞伴", Category.COLLECT, Nature.ACTIVE,
            "qwt_dancer_favorites", null, RefKind.DANCER, "dancer_id", null, null,
            "user_id", "created_at", Retired.DANCER),

    /** 邀约舞伴（需求单创建） */
    DANCER_DEMAND("DANCER_DEMAND", "邀约舞伴", Category.DEMAND, Nature.ACTIVE,
            "qwt_demand_records", null, RefKind.DANCER, "dancer_id", null, null,
            "user_id", "created_at", Retired.DANCER),

    /** 关注门店营业状态（2026-09-01 起与「收藏门店」耦合，但仍各自是独立事实） */
    VENUE_WATCH("VENUE_WATCH", "关注门店状态", Category.WATCH, Nature.ACTIVE,
            "qwt_venue_status_watchers", null, RefKind.VENUE, "venue_id", null, null),

    /** 上报门店热度（每日一记 + 多次修改；只计一次行存在，修改次数不入轨迹） */
    CROWD_REPORT("CROWD_REPORT", "上报门店热度", Category.CROWD, Nature.ACTIVE,
            "qwt_venue_crowd_reports", "report_date", RefKind.VENUE, "venue_id", null, null),

    /** 上报门店信息纠错（共享门店黄页的事实来源） */
    VENUE_FEEDBACK("VENUE_FEEDBACK", "上报门店信息", Category.REPORT, Nature.ACTIVE,
            "qwt_venue_feedbacks", null, RefKind.VENUE, "venue_id", "type", DetailDict.VENUE_FEEDBACK_TYPE),

    /** 标注门店标签（标签字典见 tagdict 域） */
    VENUE_TAG("VENUE_TAG", "标注门店标签", Category.TAG, Nature.ACTIVE,
            "qwt_tag_interactions", null, RefKind.VENUE, "venue_id", "tag", DetailDict.RAW_TEXT),

    // ── 协作行为（COLLAB）：主动发起但属资料协作工作流，当前不计入活跃 ────────────

    /**
     * 认领门店（提交认领申请）。
     * <p>
     * <b>为什么不是 ACTIVE</b>：认领是「成为资料维护者」的管理协作动作（走审核状态机），
     * 与「使用产品」不是同一件事；且纳入会改变既有 DAU / 留存历史数字 ⇒ 需立项评审
     * （见 docs/agents/35「扩展位」）。本档位存在的意义 = <b>把「未纳入」从沉默变成显式</b>。
     */
    VENUE_CLAIM("VENUE_CLAIM", "认领门店", Category.COLLAB, Nature.COLLAB,
            "qwt_venue_claims", null, RefKind.VENUE, "venue_id", null, null),

    // ── 系统信号（SIGNAL）：自动触发/派生，只服务「打开」叙事 ────────────────────

    /**
     * 打开小程序（每日打卡）。
     * <p>
     * 打卡由 {@code app.ts onLaunch} 登录后<b>自动</b>触发，只代表「当天打开过」。
     * 它出现在轨迹里是为了解释「一个账号天天打开却从不互动」这类形态（审核/巡检号的
     * 典型画像），<b>但永不进活跃与留存口径</b>——把打卡当活跃正是 2026-09-15 修复的
     * 那个错误决策。
     */
    CHECKIN("CHECKIN", "打开小程序", Category.OPEN, Nature.SIGNAL,
            "qwt_daily_checkins", "checkin_date", RefKind.NONE, null, null, null),

    // ── 被动痕迹（PASSIVE）：接收/告知类，只在「是否有过痕迹」的噪音判定里使用 ──────

    /** 收到站内信（由平台/他人行为触发，非用户主动） */
    MESSAGE("MESSAGE", "收到站内信", Category.INBOX, Nature.PASSIVE,
            "qwt_messages", null, RefKind.NONE, null, "title", DetailDict.RAW_TEXT),

    /**
     * 上报门店暂停营业（提交动作，但处置权在平台，故与被动的上报同档）。
     * <p>
     * <b>明细列 = {@code type}，不是 {@code reason}（2026-09-15 修复）</b>：V11 泛化把
     * 「暂停营业专用」扩成 8 类突发事件（{@code ReportType}），DDL 已 {@code ADD COLUMN type}
     * 并于同迁移末 {@code DROP COLUMN reason}——`reason` 在生产库<b>不存在</b>。此前的
     * {@code "reason"} 是从旧手写 SQL 抄来的残留列名，运行期表现为轨迹接口
     * {@code Unknown column 'reason' in 'field list'} 500。列名正确性由门禁
     * {@code UserBehaviorCatalogMirrorTest#catalogColumnsExistInSchema} 对 DDL 反向断言。
     */
    STATUS_REPORT("STATUS_REPORT", "上报暂停营业", Category.REPORT, Nature.PASSIVE,
            "qwt_venue_status_reports", null, RefKind.VENUE, "venue_id", "type", DetailDict.STATUS_REPORT_REASON),

    /** 联系招工（弱意图信号，不构成「使用产品」） */
    RECRUITMENT_CONTACT("RECRUITMENT_CONTACT", "联系招工", Category.DEMAND, Nature.PASSIVE,
            "qwt_recruitment_contacts", null, RefKind.RECRUITMENT, "recruitment_id", null, null,
            "user_id", "created_at", Retired.RECRUITMENT),

    /** 已读公告（回执性质，被动） */
    ANNOUNCEMENT_READ("ANNOUNCEMENT_READ", "已读公告", Category.INBOX, Nature.PASSIVE,
            "qwt_announcement_reads", null, RefKind.ANNOUNCEMENT, "announcement_id", null, null),

    // ── 扩展行为（EXTENDED）：用户主动发起，但所属子域尚未评审纳入「活跃」口径 ──────
    //
    // 2026-10-09 补录：目录自 2026-09-15 起没人再维护，而产品在此后新增了快讯 / 计时账本 /
    // 计时同步 / 热度点赞 / 意见反馈 / 门店活动打卡等一批用户主动功能——它们在管理端「行为轨迹」
    // 里完全不可见（一个只看快讯、只记账的用户，轨迹是空的）。补录进目录（让「做过什么」可见），
    // 但**刻意不入 ACTIVE**：UserStatsSql 早已把「消费账本与快讯表态/浏览」登记为需单独立项评审的
    // 扩展位——实测并入会让「近 7 日活跃」113 → 168（+49%）、「近 30 日活跃」379 → 512（+35%），
    // 其中快讯浏览一项（展示即计、同分钟批量写入，更接近曝光而非主动操作）占绝大部分增量。
    // 是否纳入、纳入哪几项是产品口径决策：评审通过后**只需把对应事件的 Nature 改为 ACTIVE**，
    // 再按门禁失败信息替换 UserStatsSql / UserBehaviorSql 字面量即可（本档位存在的意义 =
    // 把「未纳入」从沉默变成显式，且让决策成本降到一行）。

    /** 浏览快讯（信息流展示即计：列表/详情每成功加载即批量上报，同分钟常 4~10 条 ⇒ 轨迹层合并展示） */
    BULLETIN_VIEW("BULLETIN_VIEW", "浏览快讯", Category.NEWS, Nature.EXTENDED,
            "qwt_bulletin_views", "view_date", RefKind.BULLETIN, "bulletin_id", null, null),

    /** 快讯表态（一人一条恒一个表情；换票原地 UPDATE，故一行 = 该用户对该快讯的当前表态） */
    BULLETIN_REACTION("BULLETIN_REACTION", "快讯表态", Category.NEWS, Nature.EXTENDED,
            "qwt_bulletin_reactions", null, RefKind.BULLETIN, "bulletin_id",
            "reaction_code", DetailDict.BULLETIN_REACTION),

    /**
     * 点赞热度上报（行级点赞）。
     * <p>
     * <b>用户列是 {@code liker_id}</b>（点赞人，不是上报人 {@code user_id}）——目录因此支持
     * 事件级 {@code userColumn} 声明，生成物统一以 {@code AS user_id} 对齐 UNION 列名。
     */
    CROWD_LIKE("CROWD_LIKE", "点赞热度上报", Category.CROWD, Nature.EXTENDED,
            "qwt_venue_crowd_report_likes", null, RefKind.CROWD_REPORT, "report_id", null, null,
            "liker_id", "created_at", null),

    /**
     * 记账（计时结算自动入账 / 手动记账）。
     * <p>
     * <b>时间列是 {@code ts}（账目发生时刻）而非 {@code created_at}（同步上云时刻）</b>：
     * 账本是离线优先、批量同步的，created_at 会把「前几天发生、今天才同步」的账目全挤到同一刻，
     * 轨迹与活跃时段都会失真（实测 195 条中 15 条两者相差 &gt; 1 小时）。
     * 金额不进轨迹（财务数据已在详情页「计时 · 账本」卡单独展示）。
     */
    SPEND_ENTRY("SPEND_ENTRY", "记账", Category.TIMER, Nature.EXTENDED,
            "qwt_spend_entries", null, RefKind.VENUE, "venue_id", "source", DetailDict.SPEND_SOURCE,
            "user_id", "ts", null),

    /** 发起计时同步（主持人生成二维码；用户列是 {@code host_user_id}） */
    TIMER_SHARE_HOST("TIMER_SHARE_HOST", "发起计时同步", Category.TIMER, Nature.EXTENDED,
            "qwt_timer_shares", null, RefKind.VENUE, "venue_id", null, null,
            "host_user_id", "created_at", null),

    /** 扫码加入计时同步（被邀请方；传播 → 新用户转化的落点） */
    TIMER_SHARE_JOIN("TIMER_SHARE_JOIN", "扫码加入计时", Category.TIMER, Nature.EXTENDED,
            "qwt_timer_share_joins", null, RefKind.NONE, null, null, null),

    /** 提交意见反馈（应用级反馈，区别于 VENUE_FEEDBACK 的门店信息纠错） */
    APP_FEEDBACK("APP_FEEDBACK", "提交意见反馈", Category.SUGGEST, Nature.EXTENDED,
            "qwt_app_feedbacks", null, RefKind.NONE, null,
            "category", DetailDict.APP_FEEDBACK_CATEGORY),

    /** 门店活动打卡（V27；截至 2026-10-09 尚无一行数据，先登记落点——下一个功能上线不再沉默） */
    VENUE_ACTIVITY_CHECKIN("VENUE_ACTIVITY_CHECKIN", "门店活动打卡", Category.ACTIVITY, Nature.EXTENDED,
            "qwt_venue_activity_checkins", "activity_date", RefKind.VENUE, "venue_id", null, null);

    // ── 目录结构 ──────────────────────────────────────────────────────────────

    /** 事件的展示分类（轨迹分组标题 / 类型分布图例；与 {@link Nature} 正交：分类讲「做什么」，档位讲「算不算活跃」） */
    public enum Category {
        BROWSE("浏览"),
        SHARE("分享"),
        INTERACT("互动"),
        COLLECT("收藏"),
        DEMAND("邀约招工"),
        WATCH("关注"),
        CROWD("门店热度"),
        REPORT("信息上报"),
        TAG("标签标注"),
        INBOX("消息已读"),
        OPEN("打开"),
        COLLAB("协作"),
        /** 快讯（2026-10-09 补录） */
        NEWS("快讯"),
        /** 计时与账本（计时器结算入账 / 手动记账 / 计时同步） */
        TIMER("计时账本"),
        /** 应用级意见反馈 */
        SUGGEST("意见反馈"),
        /** 门店营业活动 */
        ACTIVITY("门店活动");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 口径档——「这个事件算不算活跃」的<b>唯一裁决处</b>（语义见类注释）。
     */
    public enum Nature {
        ACTIVE("主动行为", "用户主动发起的使用行为，计入「活跃」"),
        COLLAB("协作行为", "管理协作动作（认领门店），当前不计入活跃"),
        SIGNAL("系统信号", "登录自动打卡，只代表打开过，不计入活跃"),
        PASSIVE("被动痕迹", "接收/告知类，只在「是否有过痕迹」判定里使用"),
        EXTENDED("扩展行为",
                "用户主动使用的扩展功能（快讯 / 计时账本 / 热度点赞等），尚未评审纳入「活跃」口径："
                        + "轨迹与画像可见，不计入活跃与留存");

        private final String label;
        private final String hint;

        Nature(String label, String hint) {
            this.label = label;
            this.hint = hint;
        }

        public String label() {
            return label;
        }

        public String hint() {
            return hint;
        }
    }

    /** 事件关联对象类型（轨迹里「对谁做的」；名称批量解析见 BehaviorRefNameResolver） */
    public enum RefKind {
        NONE, VENUE, DANCER, RECRUITMENT, ANNOUNCEMENT,
        /** 快讯（{@code qwt_announcements} 中 category=FLASH 的行；展示名 = 正文摘要） */
        BULLETIN,
        /** 热度上报（点赞的对象；展示名取其所属门店名） */
        CROWD_REPORT
    }

    /**
     * 明细列的渲染字典（服务层权威渲染，前端零字典）。
     * <p>
     * 字典本体镜像各领域既有枚举的展示名（{@code FeedbackType#getDisplayName} /
     * {@code ReportType#getDisplayName}），不在此重复定义业务语义——本枚举只负责
     * 「这一列该按哪本字典读」的声明。
     */
    public enum DetailDict {
        /** 原样文本（站内信标题 / 标签名） */
        RAW_TEXT,
        /** 分享渠道（BUTTON / MENU / TIMELINE） */
        SHARE_CHANNEL,
        /** 门店信息上报类型（FeedbackType） */
        VENUE_FEEDBACK_TYPE,
        /** 暂停营业上报原因（ReportType） */
        STATUS_REPORT_REASON,
        /** 快讯表态码（emoji + 中文短名，BulletinReactionCode 权威） */
        BULLETIN_REACTION,
        /** 记账来源（DANCE 计时结算 / MANUAL 手动记账） */
        SPEND_SOURCE,
        /** 意见反馈类型（AppFeedbackCategory） */
        APP_FEEDBACK_CATEGORY
    }

    // ── 事件声明字段 ─────────────────────────────────────────────────────────

    private final String code;
    private final String label;
    private final Category category;
    private final Nature nature;
    private final String table;
    /** 业务日列（{@code date} 型）；null = 用 {@code DATE(created_at)} */
    private final String dayColumn;
    private final RefKind refKind;
    private final String refColumn;
    private final String detailColumn;
    private final DetailDict detailDict;
    /** 归因用户的列名（绝大多数表是 {@code user_id}；点赞是 {@code liker_id}、计时同步主持人是 {@code host_user_id}） */
    private final String userColumn;
    /** 事件时刻列（缺省 {@code created_at}；账本是 {@code ts}——发生时刻 ≠ 同步上云时刻） */
    private final String timeColumn;
    /** 下线说明；null = 现役。<b>下线 ≠ 抹除事实</b>，见类注释「下线事件」 */
    private final String retiredNote;

    /**
     * 下线说明文案（admin 展示用；改日期请同步 docs/agents/35）。
     * 放在嵌套类里而不是枚举体内的静态字段：枚举常量的参数里按简单名引用文本上位于其后的
     * 静态字段是非法前向引用，而经限定名引用嵌套类的编译期常量则合法。
     */
    private static final class Retired {
        /** 舞伴域（最后一条记录 2026-09-02） */
        static final String DANCER = "舞伴系统已下线（最后一条记录 2026-09-02）";
        /** 招工（最后一条记录 2026-08-30） */
        static final String RECRUITMENT = "招工功能已下线（最后一条记录 2026-08-30）";

        private Retired() {
        }
    }

    UserBehaviorEvent(String code, String label, Category category, Nature nature,
                      String table, String dayColumn,
                      RefKind refKind, String refColumn,
                      String detailColumn, DetailDict detailDict) {
        this(code, label, category, nature, table, dayColumn, refKind, refColumn,
                detailColumn, detailDict, "user_id", "created_at", null);
    }

    UserBehaviorEvent(String code, String label, Category category, Nature nature,
                      String table, String dayColumn,
                      RefKind refKind, String refColumn,
                      String detailColumn, DetailDict detailDict,
                      String userColumn, String timeColumn, String retiredNote) {
        this.userColumn = userColumn;
        this.timeColumn = timeColumn;
        this.retiredNote = retiredNote;
        this.code = code;
        this.label = label;
        this.category = category;
        this.nature = nature;
        this.table = table;
        this.dayColumn = dayColumn;
        this.refKind = refKind;
        this.refColumn = refColumn;
        this.detailColumn = detailColumn;
        this.detailDict = detailDict;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public Category category() {
        return category;
    }

    public Nature nature() {
        return nature;
    }

    public String table() {
        return table;
    }

    public String dayColumn() {
        return dayColumn;
    }

    public RefKind refKind() {
        return refKind;
    }

    public String refColumn() {
        return refColumn;
    }

    public String detailColumn() {
        return detailColumn;
    }

    public DetailDict detailDict() {
        return detailDict;
    }

    /** 事件级时间戳列（轨迹排序 / 时段分布；可为 null 的老行按「日」降级） */
    public String timeColumn() {
        return timeColumn;
    }

    /** 归因用户的列名（见字段注释） */
    public String userColumn() {
        return userColumn;
    }

    /** 下线说明；null = 现役 */
    public String retiredNote() {
        return retiredNote;
    }

    /**
     * 是否为下线功能的事件。
     * <p>
     * 下线事件<b>仍留在目录里、仍参与既有口径</b>（事实不可改写：8 月有人只靠浏览舞伴活跃，
     * 他那几天就是活跃的——从目录里删掉它会让历史 DAU / 留存曲线被事后改写）；
     * 它只影响<b>展示</b>：窗口内 0 条时不再出现在筛选 chips / 类型分布里（下线功能恒为 0，
     * 留着只是噪音），有历史数据时照常展示并带「已下线」标记。窗口滑过下线日后自然淡出。
     */
    public boolean isRetired() {
        return retiredNote != null;
    }

    /** 事实集 SELECT 里的用户列表达式：非 {@code user_id} 的列统一 {@code AS user_id} 对齐 UNION 列名 */
    private String userSelect() {
        return "user_id".equals(userColumn) ? userColumn : userColumn + " AS user_id";
    }

    /** 该事件的游客/空归因行排除谓词 */
    private String userFilter() {
        return userColumn + " IS NOT NULL";
    }

    /** 该事件的「日」表达式（业务日列优先，保证与既有事实集逐字等价） */
    public String dayExpression() {
        return dayColumn != null ? dayColumn : "DATE(" + timeColumn() + ")";
    }

    /**
     * 窗口下界的<b>比较列</b>：业务日列优先，否则用<b>原始时间戳列</b>。
     * <p>
     * 刻意<b>不</b>写成 {@code DATE(created_at) >= ...}：函数包住列会让 {@code created_at}
     * 上的索引失效（12 张行为表的逐日扫描），而 {@code created_at >= 当日 0 点} 与
     * {@code DATE(created_at) >= 当日} 在语义上等价（MySQL 会把右侧 DATETIME 与 DATE
     * 比较时按当日 0 点处理）。此写法与 2026-09-15 前的事实集逐字一致——口径等价是硬要求，
     * 索引可用是白拿的收益。
     */
    private String lowerBoundExpression() {
        return dayColumn != null ? dayColumn : timeColumn();
    }

    /** 该事件的日列类型（{@code DATE} 型列可直接比较 date，其余需按 datetime 比较） */
    private String dayCastKind() {
        return dayColumn != null ? "DATE" : "DATETIME";
    }

    // ── 生成物：日级事实（既有两条事实集，文本与 2026-09-15 前逐字等价） ──────────

    /**
     * 生成 {@link UserStatsSql#ACTIVE_FACT_UNION}：主动行为逐行 {@code (user_id, day)}。
     * <p>
     * 每行分支与既有手写文本逐字等价（{@code UNION ALL} 连接、单行一条 SELECT），
     * 以便零行为变化地替换掉那条手写字符串——门禁同时锁定「成员完整」与「文本等价」。
     */
    public static String activeFactUnion() {
        return of(Nature.ACTIVE).stream()
                .map(UserBehaviorEvent::dayFactBranch)
                .collect(Collectors.joining("\nUNION ALL "));
    }

    /**
     * 生成 {@link UserStatsSql#PASSIVE_TRACE_FACT_UNION}：被动痕迹的 {@code user_id} 去重集合
     * （{@code UNION} 而非 {@code UNION ALL}——只在「有没有痕迹」判定里用，去重是语义的一部分）。
     */
    public static String passiveTraceFactUnion() {
        return of(Nature.PASSIVE).stream()
                .map(e -> "SELECT " + e.userSelect() + " FROM " + e.table()
                        + " WHERE " + e.userFilter()
                        + " AND " + e.timeColumn() + " >= CAST(:sinceDay AS DATETIME)")
                .collect(Collectors.joining("\nUNION "));
    }

    /**
     * 生成<b>事件级事实集</b>：逐行 {@code (user_id, event_type, event_day, event_time)}，
     * 供轨迹、类型分布、活跃天数、时长分布等所有「事件维度」消费——
     * 这是本目录存在的直接原因（旧结构下每个消费方都要手抄一份表清单）。
     * <p>
     * {@code event_time} 保持 {@code created_at} 原值（<b>可空</b>）：日列型事件的历史脏行
     * 可能没有时间戳，此时只有 {@code event_day} 可信——消费方据「时段分布只统计有精确
     * 时刻的事件」处理，禁把 null 伪装成 0 点（否则凌晨会出现一条假的尖峰）。
     *
     * @param natures 需要的口径档（如 {@code Nature.ACTIVE} 或全部）
     */
    public static String eventFactUnion(Nature... natures) {
        return of(natures).stream()
                .map(e -> "SELECT " + e.userSelect() + ", '" + e.code() + "' AS event_type, "
                        + e.dayExpression() + " AS event_day, " + e.timeColumn() + " AS event_time"
                        + " FROM " + e.table()
                        + " WHERE " + e.userFilter()
                        + " AND " + e.lowerBoundExpression() + " >= CAST(:sinceDay AS " + e.dayCastKind() + ")")
                .collect(Collectors.joining("\nUNION ALL "));
    }

    /**
     * 生成<b>轨迹事件集</b>：在 {@link #eventFactUnion} 之上多带两列
     * （{@code ref_id} 关联对象、{@code detail_text} 明细原文），专供「一条时间线」视图。
     * <p>
     * <b>敏感列红线</b>：只允许声明 {@code refColumn} / {@code detailColumn} 指向的列，
     * 且两者在目录里不得指向手机号 / 真实姓名 / 微信 / openId（门禁反向断言）——
     * 轨迹是「用户做过什么」，不是「用户是谁的档案」。
     */
    public static String eventDetailUnion() {
        return Arrays.stream(values())
                .map(e -> "SELECT " + e.userSelect() + ", '" + e.code() + "' AS event_type, "
                        + e.dayExpression() + " AS event_day, " + e.timeColumn() + " AS event_time, "
                        + refSelect(e) + ", " + detailSelect(e)
                        + " FROM " + e.table()
                        + " WHERE " + e.userFilter()
                        + " AND " + e.lowerBoundExpression() + " >= CAST(:sinceDay AS " + e.dayCastKind() + ")")
                .collect(Collectors.joining("\nUNION ALL "));
    }

    private static String refSelect(UserBehaviorEvent e) {
        return e.refColumn == null
                ? "CAST(NULL AS SIGNED) AS ref_id"
                : e.refColumn + " AS ref_id";
    }

    private static String detailSelect(UserBehaviorEvent e) {
        return e.detailColumn == null
                ? "CAST(NULL AS CHAR) AS detail_text"
                : "CAST(" + e.detailColumn + " AS CHAR) AS detail_text";
    }

    private static String dayFactBranch(UserBehaviorEvent e) {
        return "SELECT " + e.userSelect() + ", " + e.dayExpression() + " AS day FROM " + e.table()
                + " WHERE " + e.userFilter()
                + " AND " + e.lowerBoundExpression() + " >= CAST(:sinceDay AS " + e.dayCastKind() + ")";
    }

    // ── 目录查询（服务层 / 前端目录下发共用，避免任何地方再硬编码文案） ──────────

    /** 全部事件的<b>事件级事实集</b>（含主动/协作/信号/被动——轨迹的完整性来源） */
    public static String allEventFactUnion() {
        return eventFactUnion(Nature.values());
    }

    /** 仅主动行为的<b>事件级事实集</b>（活跃天数 / 时段分布等一切「活跃」口径的消费方用） */
    public static String activeEventFactUnion() {
        return eventFactUnion(Nature.ACTIVE);
    }

    /**
     * 「打开」信号事件 = {@link Nature#SIGNAL} 档的唯一成员（登录自动打卡）。
     * <p>
     * 为什么提供这个方法而不是让消费方直接写 {@code UserBehaviorEvent.CHECKIN}：
     * 管理端的「打开」序列是<b>一个口径概念</b>（「账号当天打开过」），它今天恰好只有打卡
     * 一个来源，将来若接入启动上报等其他信号，语义会变而调用方不该跟着改。
     * 门禁断言 SIGNAL 恒为单一成员且指向打卡表。
     */
    public static UserBehaviorEvent openSignal() {
        return of(Nature.SIGNAL).getFirst();
    }

    /** 指定口径档的事件（枚举声明序 = 事实集分支序，勿调整顺序） */
    public static List<UserBehaviorEvent> of(Nature... natures) {
        List<Nature> wanted = Arrays.asList(natures);
        return Arrays.stream(values()).filter(e -> wanted.contains(e.nature())).toList();
    }

    /** 全部事件（声明序） */
    public static List<UserBehaviorEvent> all() {
        return List.of(values());
    }

    /** 按事件码查目录（管理端入参过滤用；非法码 → null，由调用方判空，禁猜默认值） */
    public static UserBehaviorEvent byCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String trimmed = code.trim();
        return Arrays.stream(values())
                .filter(e -> e.code.equals(trimmed))
                .findFirst()
                .orElse(null);
    }
}
