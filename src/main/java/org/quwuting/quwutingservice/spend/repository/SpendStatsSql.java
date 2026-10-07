package org.quwuting.quwutingservice.spend.repository;

/**
 * 管理端「计时器 &amp; 计时账本使用情况」的<b>口径单一事实源</b>（2026-10-07 根因修复，
 * 仅 ADMIN 消费；docs/agents/35-dashboard-stats.md + 40-spend-ledger.md）。
 *
 * <h2>为什么需要它（根因）</h2>
 * <b>症状</b>：用户在小程序删除一条记账记录后，admin「计时 · 账本使用」的
 * 记账用户数 / 场次 / 活跃 / 分类 / 门店排行集体下跌——运营看到的"使用盘子"随
 * 用户的<b>数据撤回动作</b>一起消失。
 * <p>
 * <b>数据其实没丢</b>：删除走的是软删（墓碑携带原始 ts/amount 同步上行，
 * {@code SpendService#upsert} 只置 {@code deleted=true}），行连同金额、分类、门店、
 * 时刻<b>完整保留</b>在 {@code qwt_spend_entries}；全库无任何硬删路径。丢的是
 * <b>统计可见性</b>——{@code SpendStatsRepository} 的 7 条查询各自内联了
 * {@code e.deleted = 0}。
 * <p>
 * <b>真正的根因不是"少写了一个条件"，而是把两种不相容的语义塞进了同一列</b>：
 * <ul>
 *   <li><b>使用事实</b>（"这个人用过计时器/记过账"）——用户撤回的是<b>数据</b>，
 *       撤不回<b>行为</b>。这与项目既有约定同源：{@code UserBehaviorEvent#VENUE_FAVORITE}
 *       写明"事实口径 = 收藏动作发生过，取消收藏不改写历史"。</li>
 *   <li><b>账面金额</b>（"他实际花了多少"）——用户删除一条，恰恰是在表达"这笔不算"，
 *       把它算进消费总额会让运营误判真实消费水平。</li>
 * </ul>
 * 原实现让「计数」也走了账面口径，于是<b>用户的隐私/纠错动作被误读成了使用行为的否定</b>。
 * 更糟的是这个错误决策是<b>静默</b>的：SQL 正常执行、无异常、无告警，数字只是悄悄变小。
 * <p>
 * <b>结构性缺陷（本次一并修掉）</b>：判定"算不算"的那段谓词以<b>文本抄写</b>形式
 * 散落在 7 条查询里，无声明、无命名、无门禁——与 {@code UserStatsSql} 建立前
 * 「{@code USER_SCOPE} 被抄 5 份」的形态<b>完全同构</b>（同一个病，第二处发作）。
 * 也就是说：本次若只把 {@code deleted = 0} 删掉，下一个消费方会再次内联抄写，
 * 下一个人会再次想当然地把"计数"和"金额"绑在一起。
 *
 * <h2>机制（长期方案）</h2>
 * 口径下沉为编译期常量，消费方<b>只能引用、不能重写</b>；配套零依赖门禁
 * {@code SpendStatsScopeMirrorTest} 断言「计数列必须走事实口径、金额列必须走账面口径、
 * 无内联 {@code deleted} 抄写」——"内联抄写"能通过编译，但会在门禁处失败。
 * <p>
 * <b>两条不变量（新增统计消费方必须同时满足）</b>：
 * <ol>
 *   <li><b>计数走事实口径</b>（{@link #FACT_ENTRY}）：含软删行 ⇒ 用户撤回数据
 *       不会让"用过没有"消失；</li>
 *   <li><b>金额走账面口径</b>（{@link #LEDGER_ENTRY}）：仅未删行 ⇒ 撤回的金额
 *       不进入消费总额。</li>
 * </ol>
 * 于是同屏出现"笔数含已删、金额不含已删"是<b>设计意图</b>而非缺陷——两列回答的是
 * 两个不同问题。差异量由 {@code retractedEntries}（已撤回条目数）显式暴露在汇总里，
 * 避免明细条数与汇总对不上时无人能解释。
 *
 * <h2>为什么谓词写成 {@code deleted IN (0,1)} 而不是 {@code 1=1}</h2>
 * 事实口径<b>必须显式写出它对 {@code deleted} 的立场</b>。若退化成恒真条件
 * （{@code 1=1}），读者无法区分"有意包含软删"与"忘了写过滤"，而门禁也无法做
 * 有意义的断言——它必须能被<b>反向检出</b>，才能在有人改错时立刻变红。
 *
 * <h2>约定</h2>
 * <ul>
 *   <li>两个常量均假定账目表别名为 {@code e}（与 {@code SpendStatsRepository} 一致）；</li>
 *   <li>用户范围口径（剔 ADMIN / test_ / 微信审核号）恒引用
 *       {@link UserStatsSql#USER_SCOPE}，本类<b>不重复定义</b>——分母口径全库唯一；</li>
 *   <li><b>用户自己的</b>读接口（{@code /spend/overview}、{@code /spend/entries}）
 *       <b>不受本类约束</b>：那里是"我的账本"，用户撤回数据后理应不可见。
 *       本类只管 admin 的"使用盘子"与"账面"，两类消费方的语义本就不同，
 *       禁把 admin 口径倒灌回用户侧。</li>
 * </ul>
 *
 * <b>MySQL 8 方言</b>（生产 RDS MySQL），勿在 PG 环境执行。
 */
public final class SpendStatsSql {

    private SpendStatsSql() {
    }

    /**
     * <b>事实口径</b>：计入软删行（{@code deleted} 0 与 1 都算）——用于所有
     * <b>计数类</b>聚合（用户数 / 场次 / 条目数 / 活跃 / 分类笔数 / 门店笔数）。
     * <p>
     * 语义：回答<b>"有多少人用过、用过多少次"</b>。用户删除一条账目是在撤回
     * <b>数据</b>，不能顺带撤回<b>行为</b>——否则运营看到的使用盘子会随用户的
     * 正常纠错动作一起缩水。
     * <p>
     * ⚠️ <b>首尾的换行是契约的一部分，禁删</b>（2026-10-07 事故）：
     * 消费方以 {@code "..." + SpendStatsSql.FACT_ENTRY + "\n   ..."} 形式拼接，
     * 而 <b>Java 文本块会剥掉结束定界符前的那个换行</b>——若常量不带首尾换行，
     * 拼接结果会退化成 {@code AND e.deleted = 0ORDER BY}（token 粘连），
     * 表现为<b>接口 500</b>、前端「查不出数据」，而编译与既有门禁<b>全绿</b>
     * （无任何一处校验拼接后的 SQL）。门禁 {@code SpendStatsScopeMirrorTest}
     * 的 {@code noTokenGlue} 断言即守这条契约。
     */
    public static final String FACT_ENTRY = "\n" + "e.deleted IN (0, 1)" + "\n";

    /**
     * <b>账面口径</b>：仅未删行（{@code deleted = 0}）——用于所有<b>金额类</b>聚合
     * （支出总额 / 收入总额 / 分类金额 / 门店金额）。
     * <p>
     * 语义：回答<b>"他当前账面上实际花了多少"</b>。用户删掉一条，正是表达
     * "这笔不算"，把它计入消费总额会让运营误判真实消费水平。
     * <p>
     * ⚠️ 首尾换行同样是契约的一部分，原因见 {@link #FACT_ENTRY}。
     */
    public static final String LEDGER_ENTRY = "\n" + "e.deleted = 0" + "\n";
}