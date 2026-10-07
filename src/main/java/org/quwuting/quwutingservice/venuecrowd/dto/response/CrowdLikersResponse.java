package org.quwuting.quwutingservice.venuecrowd.dto.response;

import java.util.List;

/**
 * 谁觉得有用（2026-10-07，GET /venues/{venueId}/crowd-reports/{reportId}/likers，公开读）。
 * <p>
 * <b>分层披露</b>（推翻 2026-09-03「赞者匿名」）：
 * <ul>
 *   <li>{@code FULL}：上报者本人 / 平台管理员——看到完整名单（头像、昵称、徽标、时间）；</li>
 *   <li>{@code SUMMARY}：其他人（含未登录）——只看人数与分层汇总（「3 人觉得有用 · 其中 1 位资深、2 位普通」），
 *       <b>名单为空</b>。</li>
 * </ul>
 * 为什么分层而不是全员公开：舞厅语境下，把「谁给某条上报点了赞」公开给路人，只有曝光 / 骚扰风险；
 * 而现网 98.5% 用户昵称仍是默认「微信用户」，全量名单对路人的信息量≈0。路人需要的是
 * 「有多少人、什么分量的人觉得有用」，不是名字；上报者需要的是「被谁看见」。
 * <p>
 * 文案全部服务端权威（前端零拼接）。赞数永不进算法的红线不变。
 *
 * @param viewMode    FULL / SUMMARY（前端据此决定渲染名单还是汇总，不自行推断观察者身份）
 * @param likeCount   当前赞数
 * @param summaryText 汇总文案（两种模式都下发；FULL = 「3 人觉得有用」，SUMMARY 带分层）
 * @param likers      完整名单（仅 FULL；最近点赞在前；SUMMARY 恒为空数组）
 */
public record CrowdLikersResponse(
        String viewMode,
        int likeCount,
        String summaryText,
        List<Liker> likers
) {

    public static final String MODE_FULL = "FULL";
    public static final String MODE_SUMMARY = "SUMMARY";

    /**
     * 点赞者条目（仅 FULL）。
     *
     * @param nickname  完整昵称（空兜底「匿名」）
     * @param avatarUrl 头像（空 = 未设头像，前端首字占位）
     * @param badgeText 徽标（资深 / 常客 / 普通 / 店家）
     * @param isMine    是否本人（自赞放开：上报者可能在名单里，前端标「我」）
     * @param ageText   点赞时间（相对）
     */
    public record Liker(
            Long userId,
            String nickname,
            String avatarUrl,
            String badgeText,
            boolean isMine,
            String ageText
    ) {
    }
}
