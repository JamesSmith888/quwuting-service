package org.quwuting.quwutingservice.venuepresence.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端「某用户的到访足迹开关」（2026-10-07；GET /admin/users/{userId}/visits 的一段；仅 ADMIN）。
 * <p>
 * <b>为什么挂在足迹接口而不是新开一个</b>：运营看「他来过哪家店」与「他是否允许我们记」是
 * 同一个判断动作——看到 20 条到访却不知道对方早已关闭采集，会直接得出「他在偷看别人」这类
 * 误判。同一次请求返回让两件事在屏幕上同屏，⛔ 拆成两个接口就必然出现「足迹已加载、开关还在转」
 * 的中间态，那种不一致比慢 100ms 危险得多。
 * <p>
 * <b>三段结构</b>：① 当前态（{@link PresenceConsentState} 派生，权威口径）② 变更流水
 * （{@link ConsentChange}，含被折叠的历史 DEFAULT 行——运营要能看见「他曾被默认开启过、
 * 后来才被问到」这段）③ 采集总开关（{@link #opsCollectEnabled}，运营侧第一个该看的排除项：
 * 全站停采时任何用户态都无意义）。
 * <p>
 * ⛔ 全字段 {@code @JsonInclude(ALWAYS)}（全局 non_null 会删 null，35 号教训）：
 * {@code lastChangedAt=null} 是「从未确立」这一事实的载体，删掉前端就得靠猜。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminUserConsentResponse(
        Long userId,
        /**
         * 当前采集态（四态派生，见 {@code PresenceConsentState}）。
         * <p>
         * ⛔ <b>不是</b>布尔：DEFAULT 历史行（用户从未被询问）单列为
         * {@code PENDING_PROMPT}，压成布尔会把「没问过他」显示成「他允许了」。
         */
        String state,
        /** 当前态展示文案（服务端权威，避免 admin 端把 DEFAULT 自行翻译成「已开启」） */
        String stateDisplay,
        /** 最新一条状态确立的原始开关值；null = 从未确立（无任何 consent 行） */
        Boolean enabled,
        /** 最新一条的来源（PROMPT / USER / DEFAULT）；null = 从未确立 */
        String source,
        /** 来源展示文案（服务端权威，取 {@code ConsentSource#getDisplayName}） */
        String sourceDisplay,
        /** 最新一次状态确立时刻；null = 从未确立 */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime lastChangedAt,
        /** 变更流水，按确立时刻倒序（首行 = 当前态） */
        List<ConsentChange> history,
        /** 流水是否被截断（达到单次响应上限）——⛔ 禁静默截断，否则上限会被读成「他只改过这么多次」 */
        boolean historyTruncated,
        /**
         * 全站采集总开关（{@code presence.collect.enabled}）当前是否开启。
         * <p>
         * <b>为什么必须下发</b>：它在服务端、admin 改不了，却是解释「为什么新数据不再进来」
         * 的第一个候选原因。只显示用户态会让运营在总开关关闭期间误判为「用户关了」，
         * 进而做出错误的用户侧沟通。
         */
        boolean opsCollectEnabled) {

    /**
     * 一次状态确立流水行。
     *
     * @param state 该行确立<b>当时</b>的采集态派生值（用于展示「默认开启期」这类历史行），
     *              与当前态同源派生（{@code PresenceConsentState}），非重复实现
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ConsentChange(
            Boolean enabled,
            String source,
            String sourceDisplay,
            /** 该行确立后的采集态（DEFAULT 行 = PENDING_PROMPT，如实呈现「当时并未被询问」） */
            String state,
            String stateDisplay,
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
            LocalDateTime changedAt) {
    }
}
