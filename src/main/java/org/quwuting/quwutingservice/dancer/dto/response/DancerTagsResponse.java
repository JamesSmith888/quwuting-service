package org.quwuting.quwutingservice.dancer.dto.response;

import org.quwuting.quwutingservice.dancer.DancerTagCode;

import java.util.List;

/**
 * 舞伴标签聚合接口响应（GET /dancers/{id}/tags，2026-08-19 扩展）。
 * <p>
 * tags = 四窗口标签聚合（用户无关，走详情公共缓存 {@code DancerDetailCacheService}
 * 60s refresh-ahead）；myTags = 当前用户今日认可携带的标签（软鉴权：未登录 /
 * 未认可 = 空列表）——驱动舞伴认可明细页（镜像门店 reaction-detail）的行活跃态。
 * <p>
 * 设计：标签聚合与个人投票态<b>分离下发</b>（对齐 {@code DancerDetailResponse} 的
 * myTags 范式）——个人态严禁进用户无关缓存，恒实时查询（每日一记模型下单次唯一
 * 索引命中，开销可忽略）。
 *
 * @param tags   四窗口标签聚合（声明序 = {@link DancerTagCode} 枚举序）
 * @param myTags 当前用户今日认可携带的标签（至多 1 枚 = 每日一票默认；多选模式可多枚）
 */
public record DancerTagsResponse(
        List<DancerTagStat> tags,
        List<String> myTags
) {}
