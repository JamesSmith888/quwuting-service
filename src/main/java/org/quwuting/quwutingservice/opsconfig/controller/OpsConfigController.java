package org.quwuting.quwutingservice.opsconfig.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.opsconfig.service.OpsConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 运营配置公开读接口（前端 feature flag 初始化）。
 * 值均为非敏感的开关字符串（如 "true"/"false"），无需登录即可读取——
 * Reaction toggle 等写路径由后端服务直读配置，本接口只服务前端乐观层。
 */
@RestController
@RequestMapping("/ops-config")
@RequiredArgsConstructor
public class OpsConfigController {

    private final OpsConfigService opsConfigService;

    /**
     * 读取全部运营配置（GET /ops-config，公开）。
     * 返回 {@code {key: value}} 映射；前端按 key 常量取开关（键缺失时用前端默认值）。
     */
    @GetMapping
    public ApiResponse<Map<String, String>> getAll() {
        return ApiResponse.ok(opsConfigService.getAllValues());
    }
}
