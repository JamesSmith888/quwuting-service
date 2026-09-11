package org.quwuting.quwutingservice.bulletin.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.bulletin.dto.request.BulletinViewsRequest;
import org.quwuting.quwutingservice.bulletin.dto.response.BulletinDetailResponse;
import org.quwuting.quwutingservice.bulletin.dto.response.BulletinFeedItemResponse;
import org.quwuting.quwutingservice.bulletin.dto.response.BulletinReactionToggleResult;
import org.quwuting.quwutingservice.bulletin.service.BulletinReactionService;
import org.quwuting.quwutingservice.bulletin.service.BulletinService;
import org.quwuting.quwutingservice.bulletin.service.BulletinViewService;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 行业快讯用户端接口（2026-09-10，docs/agents/47-bulletins.md，需登录）。
 * <p>
 * <b>内容只读、表态可写</b>（2026-09-10 二次定稿）：
 * <ul>
 *   <li><b>内容侧零写接口</b>——快讯由平台在管理后台或经 Agent 通道发布，用户不能产生
 *       任何内容（这是小程序审核的生死线：个人主体不得出现「用户自行生成内容的
 *       发布/分享/交流」）；</li>
 *   <li><b>表态侧一个写接口</b>——{@code POST /bulletins/{id}/reactions/{code}} 只写
 *       "用户对已有内容的情绪反应"，<b>一人一条内容恒一个表情</b>（字典由平台固定、
 *       无自由文本、无评论、无转发），与门店列表卡片的 Reaction 同构——不新增内容
 *       生产面，故不改变上述审核前提（口径见 47 号文档「合规复查清单」）。</li>
 * </ul>
 * <b>无已读回执</b>：与公告不同，快讯不进红点、不计未读数——新鲜度由列表的时间戳表达。
 * 管理端在 {@link AdminBulletinController}。
 */
@RestController
@RequestMapping("/bulletins")
@RequiredArgsConstructor
public class BulletinController {

    private final BulletinService bulletinService;
    private final BulletinReactionService bulletinReactionService;
    private final BulletinViewService bulletinViewService;

    /**
     * 可见快讯流（分页，时间倒序；一期不支持城市筛选）。
     * <p>
     * 每项含 content 全文与 reactions 徽标——列表页内联渲染全文（TG 频道式气泡流），
     * 不再需要"点进详情才看到内容"。个人表态（reactedByMe）随列表下发，避免前端
     * 二次请求。
     */
    @GetMapping
    public ApiResponse<Page<BulletinFeedItemResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(bulletinService.listVisible(page, size, userId));
    }

    /** 快讯详情（markdown 原文 + 表态徽标；已下线/已删 → 404）。长文深读与分享落地通道 */
    @GetMapping("/{id}")
    public ApiResponse<BulletinDetailResponse> detail(@PathVariable Long id) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(bulletinService.detail(id, userId));
    }

    /**
     * 切换表态（一人一条内容恒一个表情：点新表情换票、点同款取消）。
     * POST /bulletins/{id}/reactions/{code}
     * <p>
     * 路由形状与门店域 {@code /venues/{venueId}/reactions/{code}} 保持一致（code 走路径，
     * 无请求体）——同一类交互两端同一形态，前端/联调无需在两套约定间切换。
     */
    @PostMapping("/{id}/reactions/{code}")
    public ApiResponse<BulletinReactionToggleResult> toggleReaction(@PathVariable Long id,
                                                                   @PathVariable String code) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(bulletinReactionService.toggle(userId, id, code));
    }

    /**
     * 批量上报信息流展示浏览（fire-and-forget 计数埋点，2026-09-11）。
     * POST /bulletins/views  body = {ids: [..]}
     * <p>
     * 口径 = <b>信息流展示即计</b>（docs/agents/47「九、浏览统计」）：信息流每成功加载
     * 一页即把该页条目 id 一次性上报，服务端按 (bulletin_id, user_id, view_date) 去重
     * （同一用户同一条同一天只计 1 次），前端失败静默不重试——计数埋点不阻塞内容展示。
     * 需登录（快讯接口全部登录门禁，userId 恒非空）。空集合/重复 id 由服务端去重收敛。
     */
    @PostMapping("/views")
    public ApiResponse<Void> recordViews(@RequestBody(required = false) BulletinViewsRequest request) {
        Long userId = UserContext.requireAuth();
        bulletinViewService.recordViews(request != null ? request.ids() : null, userId);
        return ApiResponse.ok(null);
    }
}
