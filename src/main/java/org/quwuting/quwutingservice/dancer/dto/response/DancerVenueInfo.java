package org.quwuting.quwutingservice.dancer.dto.response;

import org.quwuting.quwutingservice.dancer.enums.DancerVenueRelation;

/**
 * 舞伴-舞厅关系简述（详情页"常去"/"出现"区块与列表页"常去"摘要共用）。
 * relation 语义见 {@link DancerVenueRelation}。
 */
public record DancerVenueInfo(
        Long venueId,
        String venueName,
        String city,
        String district,
        DancerVenueRelation relation,
        String note
) {}
