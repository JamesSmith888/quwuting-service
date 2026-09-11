package org.quwuting.quwutingservice.bulletin.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * Agent 一步发布快讯请求（POST /admin/bulletins/agent-publish，需 ADMIN）。
 * <p>
 * 面向 Agent skill 的机器友好通道：<b>一步 create + publish</b>（agent 场景不需要
 * 草稿态），source 由服务端固定为 AGENT（请求体不可指定，防伪造来源）。
 * <p>
 * 幂等契约（docs/agents/47）：{@code dedupKey} 非空时——
 * <ul>
 *   <li>命中已有未软删条目 → 直接返回该条目，<b>不改写其字段</b>
 *       （重跑语义是「确保这条存在」，不是「覆盖」，避免采集源纠错被重跑覆盖）；</li>
 *   <li>未命中 → 新建，并由 V18 生成列唯一索引兜底并发撞键。</li>
 * </ul>
 * 不传 dedupKey 则每次新建（允许，但 skill 侧约定必带）。
 *
 * @param publishAt 未来时刻 = 定时发布；缺省/null/过去时刻 = 立即发布
 * @param offlineAt 可选自动下线时间（须晚于当前时间）
 */
public record AgentPublishBulletinRequest(
        @NotBlank(message = "快讯内容不能为空")
        @Size(max = 50000, message = "快讯内容不能超过 50KB")
        String content,

        @Size(max = 32, message = "城市名称不能超过 32 字")
        String city,

        Long venueId,

        @Size(max = 64, message = "幂等键不能超过 64 字")
        String dedupKey,

        LocalDateTime publishAt,

        LocalDateTime offlineAt
) {}
