package org.quwuting.quwutingservice.venuestatusreport.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.venuestatusreport.dto.response.AdminStatusReportResponse;
import org.quwuting.quwutingservice.venuestatusreport.service.StatusReportService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

/**
 * 场所暂停营业上报管理端接口（仅 ADMIN，2026-08-10 新增）。
 * <p>
 * 落实 AGENTS.md「场所状态上报」L693 "管理员可在管理后台查看活跃报告"的后续约定：
 * 管理端可见性 + 处置能力。路由前缀 /admin/status-reports 独立于公开的
 * /venues/{venueId}/status-reports（提交通道）与 /status-reports/mine（用户自查询）。
 * <p>
 * 处置语义：暂停报是实时众包信号（4h TTL 自动过期），管理动作分两类（2026-08-10 扩展）：
 * <ul>
 *   <li><b>移除</b>（{@code /{id}/remove}）= 清理虚假/失效信号（soft delete，公开视图即时消失）；</li>
 *   <li><b>采纳</b>（{@code /{id}/adopt}）= 核实暂停属实：门店营业状态随之改为「暂停营业」、
 *       上报者获得积分奖励并收到处理结果站内信（与 venuefeedback 采纳发分同模式）。</li>
 * </ul>
 * 与 venuefeedback 的状态机流转不同——两类上报的管理模型不同（见「统一用户上报」与
 * 「场所状态上报」边界章节）；status report 采纳后经 soft delete 退出活跃视图。
 */
@RestController
@RequestMapping("/admin/status-reports")
@RequiredArgsConstructor
public class AdminStatusReportController {

    private final StatusReportService statusReportService;

    /**
     * 活跃暂停报列表（需 ADMIN）。
     * GET /admin/status-reports?page=0&size=20
     * 跨场所分页倒序（TTL 窗口内全部活跃报告）；返回上报者真实昵称 + userId + note
     * （管理端上下文，note 仅管理端可见的审核安全约定）。
     */
    @GetMapping
    public ApiResponse<Page<AdminStatusReportResponse>> listReports(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(statusReportService.listAdminReports(page, size));
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
