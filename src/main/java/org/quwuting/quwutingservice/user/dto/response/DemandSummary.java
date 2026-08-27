package org.quwuting.quwutingservice.user.dto.response;

import java.util.Map;

/**
 * 需求单概览（管理端用户详情内嵌，2026-08-27 用户管理增强）。
 * <p>
 * 语义：用户「获取联系方式」行为的风控留痕聚合（qwt_demand_records）——总数 /
 * 履约数（fulfilled_at 非空，2026-08-27 V54 履约闭环）/ 按状态分布
 * （DemandStatus code → 计数，key 顺序 = TreeMap 字典序）。
 * <p>
 * 状态口径：<b>存量 NULL 状态 = APPROVED</b>（V42 前无状态语义，历史客人当时已
 * 拿到微信，等价已发放）——COALESCE(status, 'APPROVED') 归组，与 22 号文档
 * 状态机语义一致；PENDING = 等待舞伴回复（中转中）。
 */
public record DemandSummary(
        /** 需求单总数 */
        long total,
        /** 履约确认次数（fulfilled_at 非空） */
        long fulfilled,
        /** 按状态分布（DemandStatus code → 计数；存量 NULL 归 APPROVED） */
        Map<String, Long> byStatus
) {}
