package org.quwuting.quwutingservice.announcement.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.announcement.dto.response.AnnouncementDetailResponse;
import org.quwuting.quwutingservice.announcement.dto.response.AnnouncementSummaryResponse;
import org.quwuting.quwutingservice.announcement.service.AnnouncementService;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全局公告用户端接口（2026-09-01，docs/agents/34）。
 * <p>
 * <b>读接口匿名可读、用户级态接口需登录</b>（2026-09-30 审核根因修复）：
 * <ul>
 *   <li><b>匿名可读</b>：列表 {@link #list} / 详情 {@link #detail} —— 走
 *       {@link UserContext#getCurrentUserId()}（未登录返回 null），未登录时逐条
 *       {@code read/unread} 恒 false；</li>
 *   <li><b>需登录</b>：未读数 {@link #unreadCount} / 单条已读 {@link #markRead} /
 *       全部已读 {@link #markAllRead} —— 三者都是<b>用户级态</b>（回执表以 userId 为
 *       唯一键），匿名调用没有语义，保持 {@link UserContext#requireAuth()}。</li>
 * </ul>
 * <b>为什么要放开读</b>：公告在客户端既是「我的」页入口，也是<b>首页公告条</b>的数据源；
 * 此前读接口统一 requireAuth，而客户端用<b>同步</b>凭证快照做首屏门禁，凭证却由
 * {@code app.onLaunch} 的静默登录<b>异步</b>换取——冷启动首帧快照必然为空，页面把
 * "判据尚未就绪"当成"用户未登录"，于是<b>一进公告中心就被拦登录</b>，命中审核规则
 * 「不得未经体验功能服务即要求用户授权登录」（2026-09-30 审核失败实证，与快讯域同源）。
 * <p>
 * 小程序消费面：首页公告条（{@code pinned=true}）/ 公告中心列表 / 详情页（towxml 渲染 markdown）。
 * <p>
 * <b>已读机制</b>：回执表（用户 × 公告唯一）。两条收敛路径——
 * <ul>
 *   <li>{@code POST /{id}/read}：逐条（详情页打开即调，幂等）；</li>
 *   <li>{@code POST /read-all}：一次性全部（用户在公告中心主动点「全部已读」，2026-09-15）。</li>
 * </ul>
 * 未读口径 = 需触达（ALERT）的可见公告（2026-09-15 收敛，见 {@code AnnouncementTouchLevel}）。
 * 管理端（发布/下线/统计）在 {@link AdminAnnouncementController}。
 */
@RestController
@RequestMapping("/announcements")
@RequiredArgsConstructor
public class AnnouncementController {

    private final AnnouncementService announcementService;

    /**
     * 公告列表（分页倒序，pinned 优先；read 布尔已按当前用户派生）。
     * <p>
     * <b>匿名可读</b>（2026-09-30）：未登录时 userId 为 null，逐条 {@code read/unread}
     * 恒 false（"匿名没有回执"是事实，不是降级）。
     *
     * @param pinned 可选置顶过滤：true = 仅置顶（首页公告栏数据源，非置顶不进首页）；
     *               不传 = 全量（公告中心）
     */
    @GetMapping
    public ApiResponse<Page<AnnouncementSummaryResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Boolean pinned) {
        Long userId = UserContext.getCurrentUserId();
        return ApiResponse.ok(announcementService.listVisible(userId, page, size, pinned));
    }

    /**
     * 未读公告数（我的页「公告中心」入口徽标数据源）——<b>需登录</b>。
     * <p>
     * 口径 = <b>需触达（ALERT）</b>的可见未读公告数（2026-09-15 收敛）：SILENT 的流水类
     * 公告（数据更新 / 每日舞讯）恒不计入——否则日更公告会让徽标只增不减。详见
     * {@code AnnouncementTouchLevel} 与 docs/agents/34「触达等级」。
     * <p>
     * 保持登录门禁的理由：未读数是<b>针对某个人的</b>计数，匿名调用无意义（也没有回执表
     * 可查）。客户端只在已就绪的登录态下请求，未登录静默跳过。
     */
    @GetMapping("/unread-count")
    public ApiResponse<Long> unreadCount() {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(announcementService.unreadCount(userId));
    }

    /**
     * 公告详情（markdown 原文；已下线/已删 → 404）。
     * <p>
     * <b>匿名可读</b>（2026-09-30）：未登录时 {@code read} 恒 false。
     */
    @GetMapping("/{id}")
    public ApiResponse<AnnouncementDetailResponse> detail(@PathVariable Long id) {
        Long userId = UserContext.getCurrentUserId();
        return ApiResponse.ok(announcementService.detail(userId, id));
    }

    /**
     * 标记已读（幂等；详情页打开即调）——<b>需登录</b>。
     * <p>
     * 回执只能落在具体的人身上（唯一键 = 用户 × 公告），匿名没有可写对象；
     * 客户端在无有效登录态时<b>根本不该发起</b>本请求（省一次必然 401 的往返）。
     */
    @PostMapping("/{id}/read")
    public ApiResponse<Void> markRead(@PathVariable Long id) {
        Long userId = UserContext.requireAuth();
        announcementService.markRead(userId, id);
        return ApiResponse.ok(null);
    }

    /**
     * 全部已读（2026-09-15，docs/agents/34「未读收敛通道」）——<b>需登录</b>。
     * 一次性为全部未读的需触达公告补写已读回执，幂等（重复调用返回同一结果）。
     * <p>
     * <b>用户主动动作</b>——由用户在公告中心点「全部已读」触发，不是"进入列表即全读"
     * （公告是运营内容，不替用户做已读决定；语义边界见 Service#markAllRead）。
     * <p>
     * 返回收敛后的未读数（权威值，前端直接采用，省掉一次往返；对齐
     * {@code POST /user/wx-subscribe-settings} 的"写操作返回最新状态"先例）。
     */
    @PostMapping("/read-all")
    public ApiResponse<Long> markAllRead() {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(announcementService.markAllRead(userId));
    }
}
