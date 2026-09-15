package org.quwuting.quwutingservice.user.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.user.dto.request.MarkWechatReviewRequest;
import org.quwuting.quwutingservice.user.dto.response.AdminDailyStatItem;
import org.quwuting.quwutingservice.user.dto.response.AdminUserBehaviorAnalysisResponse;
import org.quwuting.quwutingservice.user.dto.response.AdminUserBehaviorProfileResponse;
import org.quwuting.quwutingservice.user.dto.response.AdminUserBehaviorTimelineResponse;
import org.quwuting.quwutingservice.user.dto.response.AdminUserDetailResponse;
import org.quwuting.quwutingservice.user.dto.response.AdminUserItem;
import org.quwuting.quwutingservice.user.dto.response.AdminUserRetentionResponse;
import org.quwuting.quwutingservice.user.dto.response.AdminUserStatsResponse;
import org.quwuting.quwutingservice.user.dto.response.AdminUserStatsRow;
import org.quwuting.quwutingservice.user.enums.AdminUserStatsType;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.quwuting.quwutingservice.user.enums.UserSortMode;
import org.quwuting.quwutingservice.user.service.AdminDailyStatsService;
import org.quwuting.quwutingservice.user.service.AdminUserBehaviorAnalyticsService;
import org.quwuting.quwutingservice.user.service.AdminUserBehaviorService;
import org.quwuting.quwutingservice.user.service.AdminUserRetentionService;
import org.quwuting.quwutingservice.user.service.AdminUserService;
import org.quwuting.quwutingservice.user.service.AdminUserStatsDetailService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端用户接口（2026-08-27，docs/agents/23-user-contribution-and-fulfillment.md；
 * 仅 ADMIN；2026-08-27 用户管理增强：筛选/排序/统计概览/全维度详情）。
 * <p>
 * 定位：运营查用户/看贡献/识别异常的<b>完整工作台</b>——列表（昵称搜索 +
 * 角色/城市筛选 + 排序模式 + 行为信号行）+ 统计概览（总用户/今日新增/管理员/
 * 近 7 日活跃）+ 详情（完整画像：资料 + 积分收支 + 贡献 + 需求/上报/认领分布 +
 * 打卡连续性）。展示边界 = 管理端（requireAdmin）；<b>不建公开用户主页</b>
 * （2026-08-21 用户公开主页因审核驳回下线，见 AGENTS.md「小程序类目合规
 * UGC 红线」）。
 * <p>
 * 封禁等风控操作不在本期（需完整风控设计：封禁语义/解封/申诉/登录拦截，见
 * 23 号文档「后续规划」）——软删会被登录自动重建绕过（AuthService
 * findByOpenIdAndDeletedFalse → orElseGet createUser），不可当封禁用。
 */
@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final AdminUserStatsDetailService statsDetailService;
    private final AdminDailyStatsService dailyStatsService;
    private final AdminUserRetentionService userRetentionService;
    private final AdminUserBehaviorService adminUserBehaviorService;
    private final AdminUserBehaviorAnalyticsService behaviorAnalyticsService;

    /**
     * 用户分页列表（GET /admin/users?page=&size=&keyword=&role=&city=&sort=）。
     * keyword = 昵称模糊；role = 角色筛选（ADMIN/USER）；city = 城市精确匹配；
     * sort = 排序模式（LATEST_JOINED 默认 / POINTS_DESC / LAST_ACTIVE_DESC）；
     * 全部可空/缺省。仅 ADMIN（requireAdmin）。
     */
    @GetMapping
    public ApiResponse<Page<AdminUserItem>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) UserRole role,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) UserSortMode sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UserContext.requireAdmin();
        return ApiResponse.ok(adminUserService.list(keyword, role, city, sort, page, size));
    }

    /**
     * 用户统计概览（GET /admin/users/stats，仅 ADMIN）：列表页顶部统计条——
     * 总用户 / 今日新增 / 管理员 / 近 7 日活跃。
     */
    @GetMapping("/stats")
    public ApiResponse<AdminUserStatsResponse> stats() {
        UserContext.requireAdmin();
        return ApiResponse.ok(adminUserService.stats());
    }

    /**
     * 运营大盘按日统计（GET /admin/users/daily-stats?days=30，仅 ADMIN；2026-09-06，
     * docs/agents/35-dashboard-stats.md）：近 N 天（含今日，缺省 30，钳制 7~90）逐日
     * 返回 注册/打开（打卡口径）/真实互动/打卡型噪音 四序列——admin-web Dashboard
     * 「30 天趋势 + 噪音占比」数据源，口径权威见 {@link AdminDailyStatsService}。
     * 注意：<b>MySQL 8 方言</b>（生产 RDS MySQL），PG 环境不可执行。
     */
    @GetMapping("/daily-stats")
    public ApiResponse<List<AdminDailyStatItem>> dailyStats(
            @RequestParam(defaultValue = "30") int days) {
        UserContext.requireAdmin();
        return ApiResponse.ok(dailyStatsService.dailyStats(days));
    }

    /**
     * 用户留存分析（GET /admin/users/retention?days=30，仅 ADMIN；2026-09-15，
     * docs/agents/35-dashboard-stats.md）：近 N 天（含今日，缺省 30，钳制 7~90）
     * 的<b>留存一屏</b>——汇总（有效用户 / 近 7 日活跃 / 其中老用户回访）+
     * 逐日新老活跃拆分 + 已到期批次加权的留存曲线 + 批次留存矩阵（D1/D3/D7/D14/D30）。
     * <p>
     * 活跃口径 = 有效活跃事实集（用户主动行为 12 表，<b>不含登录自动打卡</b>）——
     * 与大盘「真实互动」、顶卡「近 7 日活跃」同一事实源（{@code UserStatsSql}）。
     * 未到期的留存格返回 {@code null}（前端「—」），<b>不是 0</b>。
     * 注意：<b>MySQL 8 方言</b>（生产 RDS MySQL），PG 环境不可执行。
     */
    @GetMapping("/retention")
    public ApiResponse<AdminUserRetentionResponse> retention(
            @RequestParam(defaultValue = "30") int days) {
        UserContext.requireAdmin();
        return ApiResponse.ok(userRetentionService.retention(days));
    }

    /**
     * 用户详情（GET /admin/users/{id}；仅 ADMIN）：管理端列表行点击 → 用户详情——
     * 公开资料 + 积分账户收支 + 贡献档案完整明细 + 需求/上报/认领分布 + 打卡
     * 连续性（完整画像）。openId 等敏感字段绝不下发；用户不存在/已软删 → 1004。
     */
    @GetMapping("/{id}")
    public ApiResponse<AdminUserDetailResponse> detail(@PathVariable Long id) {
        UserContext.requireAdmin();
        return ApiResponse.ok(adminUserService.detail(id));
    }

    /**
     * 用户统计明细（GET /admin/users/{id}/stats-detail；仅 ADMIN，2026-08-28）：
     * 用户详情页<b>每条统计数据可点击下钻</b>——查看该统计的每条详细列表。
     * type = {@link AdminUserStatsType}（POINTS/REPORT_REWARD/CHECKIN/RECOGNITION/
     * CLAIM/SHARE/FAVORITE/DEMAND/REPORT）；status = 可选状态过滤（CLAIM/DEMAND/
     * REPORT 用，如 APPROVED/PENDING）；mode = POINTS 收支方向（ALL 默认/EARN/GIFT）。
     * 返回统一行结构（title/subtitle/time/badgeText/badgeCls），前端零分支渲染；
     * openId 绝不下发；用户不存在/已软删 → 1004。
     */
    @GetMapping("/{id}/stats-detail")
    public ApiResponse<List<AdminUserStatsRow>> statsDetail(
            @PathVariable Long id,
            @RequestParam AdminUserStatsType type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String mode) {
        UserContext.requireAdmin();
        return ApiResponse.ok(statsDetailService.detail(id, type, status, mode));
    }

    /**
     * 微信审核账号标记（POST /admin/users/{id}/wechat-review，仅 ADMIN；
     * 2026-09-09 V17）：body = {"marked": true|false}，幂等写 qwt_users.
     * wechat_review。语义 = <b>统计去噪不是处罚</b>——只把账号从管理端统计口径
     * （用户统计条/大盘按日趋势/公告触达分母）排除，不删除账号、不影响小程序端
     * 任何功能；存量名单（TO / last night's stars / 上报&gt;2 的审核号）由 V17
     * 迁移预标记，日常增减在本端点操作。
     */
    @PostMapping("/{id}/wechat-review")
    public ApiResponse<Void> markWechatReview(@PathVariable Long id,
                                              @RequestBody MarkWechatReviewRequest request) {
        UserContext.requireAdmin();
        adminUserService.setWechatReview(id, request.marked());
        return ApiResponse.ok(null);
    }

    // ── 用户行为轨迹与行为分析（2026-09-15，docs/agents/35-dashboard-stats.md） ──────

    /**
     * 用户行为统计分析（GET /admin/users/behavior-analysis?days=30，仅 ADMIN；2026-09-15）：
     * 挂在资料协作（用户）域下的平台级行为盘子——类型分布（全目录，含 0 次）+
     * 活跃分层（六层互斥完备，按「占可用天数比例」分档，避免把新注册判成沉默）+
     * 行为宽度分布 + 活跃时段直方图（24 格）+ 口径自证（账号盘子漏斗）。
     * <p>
     * 口径 = 主动行为事实集（{@code UserBehaviorEvent.Nature#ACTIVE}，<b>不含登录自动打卡</b>，
     * 打卡在类型分布里以「系统信号」档显式出现）；用户范围 = {@code UserStatsSql.USER_SCOPE}
     * （已剔除 ADMIN 运营号 / {@code test_} 开发联号 / 微信审核账号）。
     * 注意：<b>MySQL 8 方言</b>（生产 RDS MySQL），PG 环境不可执行。
     */
    @GetMapping("/behavior-analysis")
    public ApiResponse<AdminUserBehaviorAnalysisResponse> behaviorAnalysis(
            @RequestParam(defaultValue = "30") int days) {
        UserContext.requireAdmin();
        return ApiResponse.ok(behaviorAnalyticsService.analysis(days));
    }

    /**
     * 用户行为轨迹（GET /admin/users/{id}/behavior-timeline?days=30&type=&limit=50，仅 ADMIN；
     * 2026-09-15）：把 18 个事件源合并成<b>一条可读时间线</b>（时间倒序）——
     * 含主动行为、协作（认领）、系统信号（打卡=打开）与被动痕迹（站内信/公告已读等），
     * 每条带口径档标签（{@code natureLabel}）；「算不算活跃」由标签显式回答，
     * 一切活跃/留存指标仍只认主动行为事实集。
     * <p>
     * 返回含 {@code total} 与 {@code truncated}（前端须诚实地写「已显示最近 N 条」）；
     * {@code typeOptions} 为全目录下发（前端禁再写一份事件字典）。type 非法 → 1007。
     */
    @GetMapping("/{id}/behavior-timeline")
    public ApiResponse<AdminUserBehaviorTimelineResponse> behaviorTimeline(
            @PathVariable Long id,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "50") int limit) {
        UserContext.requireAdmin();
        return ApiResponse.ok(adminUserBehaviorService.timeline(id, days, type, limit));
    }

    /**
     * 用户行为统计画像（GET /admin/users/{id}/behavior-profile?days=30，仅 ADMIN；2026-09-15）：
     * 单账号窗口内的类型分布（条数/覆盖天数/最近一次）+ 活跃天数 + 打开天数 +
     * 近 7 日与此前 7 日对比 + 活跃时段直方图。
     * <p>
     * 「活跃天数」只认主动行为、「打开天数」只认登录自动打卡——两个数字并排展示，
     * 「天天打开却从不互动」这类形态才看得出来（审核/巡检号画像）。
     * 窗口内无主动行为时 {@code firstActiveAt/lastActiveAt} 显式下发 null（不是 0、不是缺失）。
     */
    @GetMapping("/{id}/behavior-profile")
    public ApiResponse<AdminUserBehaviorProfileResponse> behaviorProfile(
            @PathVariable Long id,
            @RequestParam(defaultValue = "30") int days) {
        UserContext.requireAdmin();
        return ApiResponse.ok(adminUserBehaviorService.profile(id, days));
    }
}
