package org.quwuting.quwutingservice.venueclaim.enums;

/**
 * 门店认领申请状态（用户侧 + 管理侧共用状态机）。
 * <p>
 * 流转：PENDING（待审核）→ APPROVED（已通过：置 qwt_venues.claimed_by，
 * 申请人获得管理权）/ REJECTED（已拒绝，可再次提交新申请）；
 * PENDING → WITHDRAWN（申请人主动撤回）。
 * APPROVED / REJECTED / WITHDRAWN 均为终态，固定不可回退。
 * <p>
 * displayName 是展示文案唯一事实源（前端「我的认领」状态 chip 与
 * 管理端列表状态标签共用），前后端各自镜像本枚举的展示文案。
 */
public enum ClaimStatus {
    PENDING("待审核"),
    APPROVED("已通过"),
    REJECTED("已拒绝"),
    WITHDRAWN("已撤回");

    private final String displayName;

    ClaimStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
