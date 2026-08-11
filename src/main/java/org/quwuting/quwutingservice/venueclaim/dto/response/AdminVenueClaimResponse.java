package org.quwuting.quwutingservice.venueclaim.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.quwuting.quwutingservice.venueclaim.enums.ClaimStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端认领申请项（GET /admin/venue-claims，仅 ADMIN 可见）。
 * <p>
 * 管理端上下文与用户侧视图的差异：完整返回申请材料（realName /
 * contactPhone / contactWechat / licenseUrls）供审核核对——管理员需要识别
 * 申请人身份、联系核实、查看营业执照；note 补充说明同材料一起展示。
 * <p>
 * 处理动作（approve / reject）在管理端操作区执行，result 字段承载：
 * <ul>
 *   <li>approve：审核通过 → Service 置 qwt_venues.claimed_by = userId，
 *       申请人自动获得管理权（canManage 判定生效）；</li>
 *   <li>reject：审核拒绝 → 工单 REJECTED，可填写拒绝原因随「我的认领」回传。</li>
 * </ul>
 */
public record AdminVenueClaimResponse(
        /** 认领记录 ID */
        Long id,

        /** 场所 ID（前端跳转详情核实） */
        Long venueId,

        /** 场所名称 */
        String venueName,

        /** 场所城市 */
        String venueCity,

        /** 场所地址 */
        String venueAddress,

        /** 申请人用户 ID */
        Long userId,

        /** 申请人真实昵称（管理端上下文不做脱敏） */
        String nickname,

        /** 真实姓名（申请材料，审核核对） */
        String realName,

        /** 手机号（审核联系） */
        String contactPhone,

        /** 微信号（选填） */
        String contactWechat,

        /** 营业执照照片 URL 列表（选填，最多 3 张） */
        List<String> licenseUrls,

        /** 补充说明（选填） */
        String note,

        /** 审核状态 */
        ClaimStatus status,

        /** 审核状态展示文案 */
        String statusDisplay,

        /** 审核结果说明 */
        String handleNote,

        /** 审核时间（未处理为 null） */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime handledAt,

        /** 申请时间 */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime createdAt
) {}
