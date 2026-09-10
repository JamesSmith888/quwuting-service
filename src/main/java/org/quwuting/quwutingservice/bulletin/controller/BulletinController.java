package org.quwuting.quwutingservice.bulletin.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.bulletin.dto.response.BulletinDetailResponse;
import org.quwuting.quwutingservice.bulletin.dto.response.BulletinSummaryResponse;
import org.quwuting.quwutingservice.bulletin.service.BulletinService;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 行业快讯用户端接口（2026-09-10，docs/agents/47-bulletins.md，需登录）。
 * <p>
 * <b>只读单向</b>：小程序端只有列表与详情两个消费接口，<b>零写接口</b>——
 * 快讯由平台在管理后台或经 Agent 通道发布，用户端不产生任何内容（这是
 * 小程序审核的生死线：个人主体不得出现「用户自行生成内容的发布/分享/交流」）。
 * <p>
 * <b>无已读回执</b>：与公告不同，快讯不进红点、不计未读数——新鲜度由列表的
 * 时间戳表达。管理端在 {@link AdminBulletinController}。
 */
@RestController
@RequestMapping("/bulletins")
@RequiredArgsConstructor
public class BulletinController {

    private final BulletinService bulletinService;

    /** 可见快讯列表（分页，时间倒序；一期不支持城市筛选） */
    @GetMapping
    public ApiResponse<Page<BulletinSummaryResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UserContext.requireAuth();
        return ApiResponse.ok(bulletinService.listVisible(page, size));
    }

    /** 快讯详情（markdown 原文，小程序侧 towxml 渲染；已下线/已删 → 404） */
    @GetMapping("/{id}")
    public ApiResponse<BulletinDetailResponse> detail(@PathVariable Long id) {
        UserContext.requireAuth();
        return ApiResponse.ok(bulletinService.detail(id));
    }
}
