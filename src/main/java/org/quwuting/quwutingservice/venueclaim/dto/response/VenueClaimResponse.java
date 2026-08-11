package org.quwuting.quwutingservice.venueclaim.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.quwuting.quwutingservice.venueclaim.enums.ClaimStatus;

import java.time.LocalDateTime;

/**
 * 认领申请记录（「我的认领」数据源，GET /venues/claims/mine）。
 * <p>
 * 与 MyFeedbackResponse 同模式：附带场所名称（venueName，供列表直接展示，
 * 前端无需二次拼接）与处理结果（handleNote，审核通过/拒绝后回传申请人）。
 * 门店逻辑删除后 venueName 回退"已下架场所"占位（与反馈记录一致）。
 * <p>
 * 用户侧视图<b>不暴露</b>申请材料（realName/contactPhone/contactWechat/
 * licenseUrls）——材料是提交时的一次性信息，无需回显；管理端视图
 * （AdminVenueClaimResponse）才完整返回材料供审核核对。
 */
public record VenueClaimResponse(
        /** 认领记录 ID */
        Long id,

        /** 场所 ID（前端据此跳转场所详情页） */
        Long venueId,

        /** 场所名称（场所逻辑删除后回退"已下架场所"占位） */
        String venueName,

        /** 场所城市（列表展示"舞厅名 · 城市"） */
        String venueCity,

        /** 审核状态（PENDING / APPROVED / REJECTED / WITHDRAWN） */
        ClaimStatus status,

        /** 审核状态展示文案 */
        String statusDisplay,

        /** 审核结果说明（未处理或未填写为 null，随记录回传申请人） */
        String handleNote,

        /** 审核时间（未处理为 null） */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime handledAt,

        /** 申请时间 */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime createdAt
) {}
