package org.quwuting.quwutingservice.dancer.dto.response;

import org.quwuting.quwutingservice.dancer.enums.DancerStatus;

import java.util.List;

/**
 * 舞伴详情页数据源。
 * <p>
 * 页面目标（让用户快速判断"这个舞伴是否值得认识"）的字段分组：
 * 身份区（nickname/avatarUrl/gender/city/status/bio）→ 认可统计（stats）→
 * 标签云（tags，来源 = 用户认可行为）→ 常去/出现舞厅（venues）→ 我的认可态（myRecognizedToday）。
 * <p>
 * isMine = 当前用户是否为创建人（本人可见自己 PENDING/HIDDEN 的资料，见可见性规则）；
 * 详情页仅展示本记录关联的场所，不公开任何用户关系。
 */
public record DancerDetailResponse(
        Long id,
        String nickname,
        String avatarUrl,
        String bio,
        String gender,
        String city,
        DancerStatus status,
        boolean isMine,
        boolean myRecognizedToday,
        DancerRecognitionStats stats,
        List<DancerTagStat> tags,
        List<DancerVenueInfo> venues
) {}
