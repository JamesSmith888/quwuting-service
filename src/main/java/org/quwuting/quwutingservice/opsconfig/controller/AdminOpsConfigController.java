package org.quwuting.quwutingservice.opsconfig.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.opsconfig.dto.request.OpsConfigUpdateRequest;
import org.quwuting.quwutingservice.opsconfig.dto.response.OpsConfigItem;
import org.quwuting.quwutingservice.opsconfig.service.OpsConfigService;
import org.quwuting.quwutingservice.security.UserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 运营配置管理端接口（仅 ADMIN）。
 * 入口 = 列表页右下角 FAB「运营配置」菜单（qwt-fab adminOnly 过滤）。
 * 更新走 POST（项目禁 PUT/PATCH）；写入即时生效（缓存失效），无需发版。
 */
@RestController
@RequestMapping("/admin/ops-config")
@RequiredArgsConstructor
public class AdminOpsConfigController {

    private final OpsConfigService opsConfigService;

    /**
     * 管理端配置列表（GET /admin/ops-config，需 ADMIN；含最近修改时刻）。
     */
    @GetMapping
    public ApiResponse<List<OpsConfigItem>> list() {
        UserContext.requireAdmin();
        return ApiResponse.ok(opsConfigService.listAll());
    }

    /**
     * 更新配置（POST /admin/ops-config，需 ADMIN；key 必须已存在）。
     */
    @PostMapping
    public ApiResponse<Void> update(@Valid @RequestBody OpsConfigUpdateRequest request) {
        opsConfigService.setValue(request.key(), request.value(), UserContext.requireAdmin());
        return ApiResponse.ok(null);
    }
}
