package org.quwuting.quwutingservice.user.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端「用户行为轨迹」响应（2026-09-15，
 * {@code GET /admin/users/{id}/behavior-timeline?days=&type=&limit=}；仅 ADMIN）。
 * admin-web 用户详情页「行为轨迹」卡数据源——把 18 个事件源按时间合并成<b>一条可读时间线</b>。
 *
 * <h2>口径</h2>
 * <ul>
 *   <li>事实集 = {@code UserBehaviorSql.EVENT_DETAIL_UNION}（由 {@code UserBehaviorEvent}
 *       行为事件目录生成：主动 / 协作 / 打开信号 / 被动 / <b>扩展</b>五档，全部事件）；</li>
 *   <li><b>轨迹含非活跃事件，这不是口径放宽</b>：每条事件都带 {@code nature/natureLabel}
 *       （主动行为 / 协作行为 / 系统信号 / 被动痕迹），「算不算活跃」由标签显式回答；
 *       一切活跃与留存指标仍只认主动行为事实集。把打开/被动事件排除在轨迹之外，
 *       只会让「天天打开却从不互动」这类形态（审核/巡检号画像）从界面上消失。</li>
 *   <li><b>同类连发合并成一行（2026-10-09）</b>：同一类型、相邻不超过 5 分钟的事件合并为一行
 *       （{@link Item#count} &gt; 1，{@link Item#timeFrom} 回显起点）。根因 = 快讯浏览是「展示即计」，
 *       打开一次快讯页就批量写入 4~10 行（实测 92% 的快讯浏览行落在同分钟 4+ 条的批次里），
 *       逐行平铺会让一次操作刷满整页轨迹，淹没真正有信息量的事件。{@link #limit} 与 {@link #total}
 *       仍按<b>原始事件数</b>计，{@link #shownEvents} 是已展示行所代表的原始事件数
 *       （&lt; total ⇒ 被截断，页面必须明写）。</li>
 *   <li><b>下线功能的类型选项</b>（舞伴 / 招工）：窗口内 0 条时不下发，有历史数据时下发并带
 *       {@link TypeOption#retired}（目录原则：下线 ≠ 抹除事实，见 {@code UserBehaviorEvent}）。</li>
 *   <li><b>单用户读取不过滤用户范围</b>（口径过滤发生在入口列表）：运营需要能对任意账号
 *       （含已标记的审核号）做取证式查看，页面上有「微信审核」标记提示其口径归属。</li>
 * </ul>
 *
 * <h2>空值语义（勿简化）</h2>
 * {@code time} 恒非空（{@code COALESCE(created_at, 日 0 点)}），但当日列型事件的历史脏行
 * 没有时间戳时，{@code timeApprox = true} —— 表示这条只能精确到<b>日</b>。
 * 前端据此渲染「仅日期」而非伪造一个时刻。
 */
public record AdminUserBehaviorTimelineResponse(
        /** 生效窗口天数（回显服务端钳制后的值，7~90） */
        int days,
        /** 生效的事件码过滤（空串 = 全部） */
        String type,
        /** 窗口内该用户的事件总条数（= Σ {@link TypeOption#count}；与 {@link #events} 长度比较即知是否被截断） */
        long total,
        /** {@link #events} 是否被 {@link #limit} 截断（true ⇒ 页面必须写「已显示最近 N 条」） */
        boolean truncated,
        /** 已展示的行所代表的原始事件数（Σ {@link Item#count}；与 {@link #total} 比较判断是否被截断） */
        long shownEvents,
        /** 生效的返回上限（回显服务端钳制后的值，20~200） */
        int limit,
        /** 事件类型目录（全量下发，含窗口内 0 条的类型——筛选 chips 的数据源，前端零硬编码字典） */
        List<TypeOption> typeOptions,
        /** 事件行（时间倒序） */
        List<Item> events
) {

    /**
     * 事件类型选项（来自行为事件目录 {@code UserBehaviorEvent}，服务端权威下发）：
     * 前端筛选 chips 与图例一律用它，<b>禁止在前端再写一份事件字典</b>。
     */
    public record TypeOption(
            /** 事件码（筛选入参 {@code type} 的取值） */
            String code,
            /** 事件中文名（如「浏览门店」） */
            String label,
            /** 分类码（BROWSE/SHARE/…） */
            String category,
            /** 分类中文名（如「浏览」） */
            String categoryLabel,
            /** 口径档码（ACTIVE/COLLAB/SIGNAL/PASSIVE/EXTENDED） */
            String nature,
            /** 口径档中文名（如「主动行为」） */
            String natureLabel,
            /** 口径档释义（解释该类事件为何计入/不计入活跃） */
            String natureHint,
            /** 对应功能已下线（舞伴 / 招工）；窗口内有历史数据才会出现在选项里，前端据此加「已下线」标记 */
            boolean retired,
            /** 下线说明（现役事件为空串；与 {@link #retired} 同进同出） */
            String retiredNote,
            /** 该用户在该窗口内、该事件类型的条数（0 = 窗口内没做过，chips 可弱化显示） */
            long count
    ) {
    }

    /**
     * 轨迹事件行（服务端已拼好文案，前端零拼接）：
     * 渲染 = {@code natureLabel 标签 + title（主文案）+ detail（明细）+ time}。
     */
    public record Item(
            /** 行键（同一响应内唯一；跨表 UNION 无全局 id，故由「事件码 + 序号」组成） */
            String key,
            /** 事件码 */
            String type,
            /** 口径档码 */
            String nature,
            /** 口径档中文名（徽标文案） */
            String natureLabel,
            /** 该事件对应的功能已下线（舞伴 / 招工历史记录；前端渲染「已下线」弱标记） */
            boolean retired,
            /**
             * 主文案（服务端拼装，如「浏览门店「金浪莎舞厅」」；无关联对象时为事件名本身；
             * 合并行带「×N」，同一对象重复出现时对象名保留，多个对象时对象名下沉到 {@link #detail}）
             */
            String title,
            /** 明细文案（渠道 / 标签名 / 上报类型 / 站内信标题；合并行为涉及的对象名；空串 = 不渲染） */
            String detail,
            /** 权威事件日（业务日列优先；可用它做「按天分组」） */
            LocalDate day,
            /** 事件时刻（恒非空 = COALESCE(created_at, 日 0 点)，可直接排序/展示） */
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
            LocalDateTime time,
            /** true = 原始记录没有时间戳，{@link #time} 只精确到日（前端渲染「仅日期」） */
            boolean timeApprox,
            /** 本行合并的原始事件数（1 = 单条；&gt;1 = 同类连发合并行，{@link #time} 为其中最晚一条） */
            int count,
            /** 合并行的起点时刻（最早一条；单条为 null ⇒ JSON 省略） */
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
            LocalDateTime timeFrom,
            /** 关联对象类型（VENUE / BULLETIN / ANNOUNCEMENT / …）；<b>仅当本行恰好指向唯一对象</b>时下发，前端据此做深链 */
            String refKind,
            /** 关联对象 id（与 {@link #refKind} 同进同出） */
            Long refId
    ) {
    }
}
