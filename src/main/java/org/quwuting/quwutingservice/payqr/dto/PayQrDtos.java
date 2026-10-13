package org.quwuting.quwutingservice.payqr.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 收款码配置状态的接口载体（2026-10-13，V49）：端上上报请求 / 确认响应 / admin 汇总与名单。
 * 合并在一个文件里是因为它们是同一件事的四个面且都极小；admin 响应全字段 {@code ALWAYS}
 * （全局 non_null 会删 null，35 号教训）。
 */
public final class PayQrDtos {

    private PayQrDtos() {
    }

    /** 端上上报：当前本机持有的收款码张数（只有这一个字段——不接收任何图片 / 路径 / 摘要） */
    public record ReportRequest(Integer count) {
    }

    /** 上报确认：{@code accepted=false} 时 {@code reason} 为固定原因码（客户端静默） */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ReportResponse(boolean accepted, String reason) {
    }

    /**
     * admin 汇总（真实用户范围，口径同「计时 · 账本使用」面板）。
     *
     * @param configuredUsers     当前持有收款码（张数 &gt; 0）的用户数
     * @param everConfiguredUsers 曾配置过（含已清空）的用户数；{@code ever − configured} = 配置后又清空者
     * @param totalQrCount        当前持有的收款码张数合计
     * @param newUsers7d          近 7 天首次配置的用户数
     * @param newUsers30d         近 30 天首次配置的用户数
     * @param maxCount            单人张数上限（服务端权威；客户端镜像）
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AdminStats(long configuredUsers, long everConfiguredUsers, long totalQrCount,
                             long newUsers7d, long newUsers30d, int maxCount) {
    }

    /**
     * admin 名单行：配置过收款码的真实用户。用户字段全部由后端判定、前端零派生
     * （同 {@code AdminVenueVisitorItem} 纪律：{@code nicknameCustom} 是主标题显示什么的唯一判据）。
     * ⛔ 只含公开资料 + 张数 + 两个时刻；openId 等敏感字段绝不下发。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AdminUserRow(Long userId, String userCode, String nickname, boolean nicknameCustom,
                               String avatarUrl, int qrCount, boolean cleared,
                               @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime firstConfiguredAt,
                               @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime changedAt) {
    }

    /** admin 名单：超过 {@code limit} 时 {@code truncated=true}（⛔ 禁静默截断） */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AdminUsers(List<AdminUserRow> users, int limit, boolean truncated) {
    }
}
