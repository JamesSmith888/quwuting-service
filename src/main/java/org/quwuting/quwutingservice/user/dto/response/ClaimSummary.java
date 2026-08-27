package org.quwuting.quwutingservice.user.dto.response;

import java.util.Map;

/**
 * 门店认领概览（管理端用户详情内嵌，2026-08-27 用户管理增强）。
 * <p>
 * 语义：用户门店认领行为聚合（qwt_venue_claims）——总数 + 按状态分布
 * （ClaimStatus code → 计数，key 顺序 = TreeMap 字典序）。认领携带实名材料
 * （真实姓名/手机号），是用户可信度的重要信号；PENDING = 待审核、APPROVED =
 * 通过（已获门店管理权）、REJECTED = 拒绝、WITHDRAWN = 主动撤回。
 */
public record ClaimSummary(
        /** 认领申请总数 */
        long total,
        /** 按状态分布（ClaimStatus code → 计数） */
        Map<String, Long> byStatus
) {}
