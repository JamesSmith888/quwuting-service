package org.quwuting.quwutingservice.venuereaction.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;

/**
 * 列表卡片展示的 Reaction 徽标（**完整展示**：所选统计窗口内所有用户点击过的全部表情，
 * 按窗口计数降序，该窗口计数=0 不展示；**不做任何截断**——需求 2026-08-09：取所有用户
 * 的所有已点击表情全部展示，见 {@code VenueReactionService#buildTopBadgesFromCounts} javadoc）。
 * <p>
 * 含 reactedByMe（个人状态）——打破"列表层不含个人状态"的惯例，但个人状态只是
 * 徽标的**标注属性**（驱动"点击即知是否已参与"的交互），**不参与徽标集合构成**。
 * 该字段来自单独的、不缓存的实时批量查询，不与场所级聚合缓存混在一起
 * （聚合计数仍然缓存共享，个人状态永远实时查询——与既有的"缓存内容强制约束"一致）。
 * <p>
 * 三窗口计数语义（2026-08 每日一记模型确立）：徽标同时携带 {@code countAll} / {@code count7d} /
 * {@code count30d} 三个窗口的计数。根因：每日一记模型下取消只可能作用于**当日**记录，
 * 因此一次 toggle 对三个窗口计数的本地 ±1 **全部精确**（新增的今日记录必落在所有窗口内，
 * 取消删掉的也必是今日记录）——前端乐观更新（Telegram 式：点击立即 ±1，失败回滚）不再需要
 * 旧模型的"展示 countAll、排序 count30d"双计数 hack，列表页时间筛选（近7天/近30天/全部）
 * 直接展示对应窗口计数，切换窗口仅重排序/过滤本地数据。见 AGENTS.md「Reaction 快速反馈系统」。
 * <p>
 * <b>个人参与记忆（2026-10-15）</b>：{@code reactedByMe} 是<b>写入侧</b>语义（「今天这一票」，
 * 每日限一票、次日恢复可点），<b>不能</b>同时承担读取侧的「我投过没有」——否则过零点后
 * 用户的高亮与「从没投过」无法区分（数字是累计、里面有我，却认不出哪份是我）。故另带
 * {@code lastReactedDate} 承载读取侧归属：两个字段互相独立、各管一事，前端三态
 * 「今日（实心）/ 曾投（描边）/ 未投」由二者纯函数派生。
 *
 * @param code            Reaction 代码
 * @param emoji           表情符号
 * @param label           文字说明（长按展示）
 * @param countAll        全部历史记录数（"全部"窗口展示值）
 * @param count7d         近7天计数（"近7天"窗口展示值，列表默认窗口）
 * @param count30d        近30天计数（"近30天"窗口展示值）
 * @param reactedByMe     当前用户今日是否已参与（未登录恒为 false；仅标注属性，不参与集合构成）
 * @param lastReactedDate 当前用户<b>今天之前</b>最近一次投该表情的日期（{@code yyyy-MM-dd}）；
 *                        从没投过 / 只有今天一票 / 未登录为 null（序列化时省略）。
 *                        与 {@code reactedByMe} 独立：今天又投了一次时两者可同时有值
 */
public record ReactionBadge(
        String code,
        String emoji,
        String label,
        long countAll,
        long count7d,
        long count30d,
        boolean reactedByMe,
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate lastReactedDate
) {}
