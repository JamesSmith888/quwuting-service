package org.quwuting.quwutingservice.dancer.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建/编辑舞伴资料请求体（创建与编辑共用同一领域对象 = 全量覆盖可编辑字段，
 * 与 venue 域 CreateVenueRequest 复用于 create/update 的模式一致——资料是可演进
 * 的持续对象，不是一次性表单；见 AGENTS.md「舞伴生态体系 · 本人编辑」）。
 * <p>
 * 三条通道共用本请求：
 * <ul>
 *   <li>{@code POST /dancers}（舞伴主动注册）：createdBy = 当前用户，status = PENDING（审核中）；</li>
 *   <li>{@code PUT /dancers/{id}}（舞伴本人/管理员编辑）：全量覆盖可编辑字段；
 *       REJECTED 资料编辑后自动回到 PENDING（重新送审，兑现驳回通知"可修改资料后重新提交"承诺）；</li>
 *   <li>{@code POST /admin/dancers}（后台创建）：createdBy = 管理员，status = NORMAL（可信来源直通）。</li>
 * </ul>
 * 隐私边界：昵称/简介/常驻城市均为公开展示资料，不采集联系方式与私人信息；
 * homeVenueId 为可选常驻舞厅（校验存在性后落 DancerVenue 关系表，编辑时 = HOME 关系的
 * 完整替换语义：传 null 清除全部 HOME）。
 */
public record UpsertDancerRequest(
        @NotBlank(message = "昵称不能为空")
        @Size(max = 30, message = "昵称最长30个字符")
        String nickname,

        @Size(max = 500, message = "头像地址过长")
        String avatarUrl,

        @Size(max = 300, message = "简介最长300个字符")
        String bio,

        /** 性别（可选，null = 未声明，前端不展示） */
        @Size(max = 20, message = "性别字段过长")
        String gender,

        @Size(max = 50, message = "城市最长50个字符")
        String city,

        /** 常驻舞厅 ID（可选；落 DancerVenue HOME 关系，仅作"常去"展示，不构成强绑定） */
        Long homeVenueId
) {}
