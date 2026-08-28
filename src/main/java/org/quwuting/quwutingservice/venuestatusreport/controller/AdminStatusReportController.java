package org.quwuting.quwutingservice.venuestatusreport.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.venuestatusreport.dto.response.AdminStatusReportResponse;
import org.quwuting.quwutingservice.venuestatusreport.service.StatusReportService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

/**
 * 场所突发事件管理端接口（仅 ADMIN，2026-08-10 新增；2026-08-28 处置三分级 +
 * 已处理视图）。
 * <p>
 * 落实 AGENTS.md「场所状态上报」L693 "管理员可在管理后台查看活跃报告"的后续约定：
 * 管理端可见性 + 处置能力。路由前缀 /admin/status-reports 独立于公开的
 * /venues/{venueId}/status-reports（提交通道）与 /status-reports/mine（用户自查询）。
 * <p>
 * 处置语义：突发事件是实时众包信号（公示期 2 天自动撤下，2026-08-21 起统一公示期），
 * 管理动作三分级（2026-08-28 定稿，与置信度分层同哲学）：
 * <ul>
 *   <li><b>采纳</b>（{@code /{id}/adopt}）= 核实属实：状态类联动门店营业状态、
 *       上报者获得积分奖励并收到处理结果站内信（公告区保留带"已核实"标记）；</li>
 *   <li><b>保留</b>（{@code /{id}/keep}）= 无法核实但非恶意：信号继续公示至 TTL
 *       过期（中性"未经核实"展示），<b>不联动营业状态、不奖励、不发信</b>；</li>
 *   <li><b>移除</b>（{@code /{id}/remove}）= 恶意/虚假信号清理（soft delete，
 *       公开视图即时消失，管理端「已处理」历史保留审计留痕）。</li>
 * </ul>
 * 与 venuefeedback 的状态机流转不同——两类上报的管理模型不同（见「统一用户上报」与
 * 「场所状态上报」边界章节）；status report 处置后退出活跃视图，经「已处理」视图
 * 复盘（status=HANDLED）。
 */
@RestController
@RequestMapping("/admin/status-reports")
@RequiredArgsConstructor
public class AdminStatusReportController {

    private final StatusReportService statusReportService;

    /**
     * 突发事件列表（需 ADMIN，两档视图）。
     * GET /admin/status-reports?page=0&size=20&type=SUSPENDED&status=ACTIVE
     * 跨场所分页倒序；返回上报者真实昵称 + userId + note
     * （管理端上下文，note 仅管理端可见的审核安全约定）+ 同店同类型聚簇计数 peerCount。
     * <ul>
     *   <li>status 缺省/ACTIVE = 待处理：TTL 窗口内全部活跃报告（2026-08-11 新增
     *       type 可选类型筛选：null/空 = 全部类型，传枚举值 = 按类型筛选）；</li>
     *   <li>status=HANDLED = 已处理：全部已处置记录（ADOPTED/KEPT/REMOVED，含
     *       soft delete 的移除记录与已过公示期历史），只读复盘（2026-08-28 新增）。</li>
     * </ul>
     */
    @GetMapping
    public ApiResponse<Page<AdminStatusReportResponse>> listReports(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "ACTIVE") String status
    ) {
        return ApiResponse.ok(statusReportService.listAdminReports(page, size, type, status));
    }

    /**
     * 保留突发事件报告（需 ADMIN，幂等，2026-08-28 新增——处置三分级）。
     * POST /admin/status-reports/{id}/keep
     * 无法核实但非恶意：信号继续公示至 TTL 自然过期（中性"未经核实"展示），不联动
     * 门店营业状态、不奖励积分、不发处理结果站内信（平台不背书）；移出待办队列。
     * 与采纳（adopt）的差异：采纳 = 核实属实 → 改状态 + 发分 + 通知；保留 = 存疑
     * 不背书 → 仅标记 + 失效缓存。已处置/不存在幂等处理。
     */
    @PostMapping("/{id}/keep")
    public ApiResponse<Void> keepReport(@PathVariable Long id) {
        statusReportService.keepReport(id);
        return ApiResponse.ok(null);
    }

    /**
     * 移除暂停报（需 ADMIN，幂等）。
     * POST /admin/status-reports/{id}/remove
     * 平台清理虚假/失效信号：soft delete 后公开视图即时消失（无需等 TTL 过期），
     * 同时失效 venueHeat 缓存。已移除/不存在幂等处理。
     */
    @PostMapping("/{id}/remove")
    public ApiResponse<Void> removeReport(@PathVariable Long id) {
        statusReportService.removeReport(id);
        return ApiResponse.ok(null);
    }

    /**
     * 采纳暂停报（需 ADMIN，幂等）。
     * POST /admin/status-reports/{id}/adopt
     * 管理员核实暂停属实后采纳：门店营业状态随之改为「暂停营业」（写状态变迁日志），
     * 上报者获得积分奖励并收到处理结果站内信；报告软删后不再作为活跃信号。
     * 与移除（remove）的差异：移除 = 虚假/失效信号清理（无副作用），采纳 = 信号属实
     * → 改状态 + 发分 + 通知（同事务，见 StatusReportService.adoptReport）。
     * 已处置/不存在幂等处理。
     */
    @PostMapping("/{id}/adopt")
    public ApiResponse<Void> adoptReport(@PathVariable Long id) {
        statusReportService.adoptReport(id);
        return ApiResponse.ok(null);
    }
}
