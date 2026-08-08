package org.quwuting.quwutingservice.dancer.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.dancer.dto.request.CreateDancerRequest;
import org.quwuting.quwutingservice.dancer.dto.request.UpdateDancerStatusRequest;
import org.quwuting.quwutingservice.dancer.dto.response.AdminDancerResponse;
import org.quwuting.quwutingservice.dancer.enums.DancerStatus;
import org.quwuting.quwutingservice.dancer.service.DancerService;
import org.quwuting.quwutingservice.security.UserContext;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

/**
 * 舞伴管理端接口（仅平台管理员）。
 * <ul>
 *   <li>GET /admin/dancers — 审核列表（含全部状态，status 可选筛选，按提交时间倒序）</li>
 *   <li>POST /admin/dancers — 后台创建舞伴资料（可信来源直通 status=NORMAL，无需再认证）</li>
 *   <li>PUT /admin/dancers/{id}/status — 状态切换（审核通过 PENDING→NORMAL / 驳回
 *       PENDING→REJECTED / 下架恢复 NORMAL↔HIDDEN；body.reason 可选操作说明，
 *       随站内信通知创建人，2026-08-08 新增）</li>
 *   <li>GET /admin/dancers/statuses — 状态字典回显</li>
 * </ul>
 * 认证是"先认证、后展示"隐私边界的管理员侧落点：舞伴主动注册的资料必须经本接口
 * 审核（PENDING → NORMAL / REJECTED）后才公开或明确驳回，审核结果经站内信
 * 通知创建人（见 AGENTS.md「舞伴审核与站内信」）。
 */
@RestController
@RequestMapping("/admin/dancers")
@RequiredArgsConstructor
public class AdminDancerController {

    private final DancerService dancerService;

    /**
     * 审核列表（仅 ADMIN，分页倒序）。status 可选（PENDING / NORMAL / REJECTED / HIDDEN），
     * 缺省返回全部——管理员从「审核中」筛选进入待办，可切换查看已处理历史。
     */
    @GetMapping
    public ApiResponse<Page<AdminDancerResponse>> list(
            @RequestParam(required = false) DancerStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UserContext.requireAdmin();
        return ApiResponse.ok(dancerService.listAdminDancers(status, page, size));
    }

    /** 后台创建舞伴资料（管理员，status=NORMAL 直接公开；createdBy = 管理员 ID） */
    @PostMapping
    public ApiResponse<Long> create(@Valid @RequestBody CreateDancerRequest request) {
        Long adminId = UserContext.requireAdmin();
        return ApiResponse.ok(dancerService.createDancer(adminId, request, true));
    }

    /** 舞伴状态切换（审核通过 / 驳回 / 隐藏 / 恢复；状态变化即站内信通知创建人） */
    @PutMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long id,
                                          @Valid @RequestBody UpdateDancerStatusRequest request) {
        UserContext.requireAdmin();
        dancerService.updateStatus(id, request.status(), request.reason());
        return ApiResponse.ok(null);
    }

    /** 状态字典回显（前端管理入口状态选项用；枚举即事实源） */
    @GetMapping("/statuses")
    public ApiResponse<DancerStatus[]> statuses() {
        UserContext.requireAdmin();
        return ApiResponse.ok(DancerStatus.values());
    }
}
