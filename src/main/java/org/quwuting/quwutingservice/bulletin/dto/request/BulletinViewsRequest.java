package org.quwuting.quwutingservice.bulletin.dto.request;

import java.util.List;

/**
 * 快讯展示浏览批量上报请求体（POST /bulletins/views，2026-09-11）。
 * <p>
 * ids = 本次信息流渲染出的快讯 id 集合（一页 ≤ 50 条，由前端触底加载语义天然约束；
 * 服务端批量 upsert 前再去重）。上报是 fire-and-forget 计数埋点，服务端不校验
 * 每条 id 的可见性——历史浏览行无需因内容下线而回滚。
 */
public record BulletinViewsRequest(List<Long> ids) {}
