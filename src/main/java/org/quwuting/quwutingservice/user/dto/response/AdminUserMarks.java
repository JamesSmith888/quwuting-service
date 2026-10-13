package org.quwuting.quwutingservice.user.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 用户信息里的「功能状态标记」（2026-10-13；管理端列表行 / 详情共用）：把散落在各业务域里、运营识别用户时
 * 需要一眼看到的几个事实收口到一个载体——到店足迹是否被用户关闭、是否配置过计时收款码。
 * <p>
 * <b>为什么单独成型</b>：这些事实各属一个业务域（venuepresence / payqr），之前每加一个都要
 * 去改 {@code AdminUserItem} + {@code AdminUserDetailResponse} 两处并各自查库；统一由
 * {@code AdminUserMarkService} 批量装配，新增标记只改这里与那个服务，列表与详情自动获得。
 * 所有"状态 + 展示文案"都由服务端判定并下发，前端零字典、零派生（同 {@code nicknameCustom} 纪律）。
 *
 * @param footprintSharing        到店足迹授权态枚举名（ENABLED / DISABLED / PENDING_PROMPT / NEVER_ASKED）
 * @param footprintSharingDisplay 上者的服务端展示文案
 * @param payQrCount              本机当前持有的计时收款码张数（0 = 无 / 已清空 / 无上报记录；只存张数不存图）
 * @param payQrState              收款码配置三态枚举名（CONFIGURED / CLEARED / NO_REPORT，见 {@code PayQrState}）
 * @param payQrDisplay            上者的服务端展示文案（用户详情「计时 · 账本」卡恒显示这一行；NO_REPORT 如实写明两种可能）
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminUserMarks(
        String footprintSharing,
        String footprintSharingDisplay,
        int payQrCount,
        String payQrState,
        String payQrDisplay) {
}
