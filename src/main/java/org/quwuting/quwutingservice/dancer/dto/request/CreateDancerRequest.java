package org.quwuting.quwutingservice.dancer.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建/注册舞伴资料请求体。
 * <p>
 * 两条创建通道共用本请求：
 * <ul>
 *   <li>{@code POST /dancers}（舞伴主动注册）：createdBy = 当前用户，status = PENDING（审核中）；</li>
 *   <li>{@code POST /admin/dancers}（后台创建）：createdBy = 管理员，status = NORMAL（可信来源直通）。</li>
 * </ul>
 * 隐私边界：昵称/简介/常驻城市均为公开展示资料，不采集联系方式与私人信息；
 * homeVenueId 为可选常驻舞厅（校验存在性后落 DancerVenue 关系表，不写死绑定）。
 */
public record CreateDancerRequest(
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
