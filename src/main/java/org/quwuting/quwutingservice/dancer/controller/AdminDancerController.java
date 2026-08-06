package org.quwuting.quwutingservice.dancer.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.dancer.dto.request.CreateDancerRequest;
import org.quwuting.quwutingservice.dancer.dto.request.UpdateDancerStatusRequest;
import org.quwuting.quwutingservice.dancer.enums.DancerStatus;
import org.quwuting.quwutingservice.dancer.service.DancerService;
import org.quwuting.quwutingservice.security.UserContext;
import org.springframework.web.bind.annotation.*;

/**
 * 舞伴管理端接口（仅平台管理员）。
 * <ul>
 *   <li>POST /admin/dancers — 后台创建舞伴资料（可信来源直通 status=NORMAL，无需再认证）</li>
 *   <li>PUT /admin/dancers/{id}/status — 状态切换（PENDING → NORMAL 认证通过 / HIDDEN 下架）</li>
 * </ul>
 * 认证是"先认证、后展示"隐私边界的管理员侧落点：舞伴主动注册的资料必须经本接口
 * 认证（PENDING → NORMAL）后才对公众可见。
 */
@RestController
@RequestMapping("/admin/dancers")
@RequiredArgsConstructor
public class AdminDancerController {

    private final DancerService dancerService;

    /** 后台创建舞伴资料（管理员，status=NORMAL 直接公开；createdBy = 管理员 ID） */
    @PostMapping
    public ApiResponse<Long> create(@Valid @RequestBody CreateDancerRequest request) {
        Long adminId = UserContext.requireAdmin();
        return ApiResponse.ok(dancerService.createDancer(adminId, request, true));
    }

    /** 舞伴状态切换（认证通过 / 隐藏 / 恢复） */
    @PutMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long id,
                                          @Valid @RequestBody UpdateDancerStatusRequest request) {
        UserContext.requireAdmin();
        dancerService.updateStatus(id, request.status());
        return ApiResponse.ok(null);
    }

    /** 状态字典回显（前端管理入口状态选项用；枚举即事实源） */
    @GetMapping("/statuses")
    public ApiResponse<DancerStatus[]> statuses() {
        UserContext.requireAdmin();
        return ApiResponse.ok(DancerStatus.values());
    }
}
