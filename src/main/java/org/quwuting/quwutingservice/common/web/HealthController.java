package org.quwuting.quwutingservice.common.web;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 就绪探针（2026-10-01，docs/agents/14-deployment-and-schema.md「部署就绪检查」）。
 * <p>
 * GET /health → 数据库可用即 {@code {"status":"UP"}}；数据库不可用时抛出的连接类异常由
 * 全局处理器映射为 HTTP 503（5003）。部署脚本在 {@code systemctl restart} 之后轮询本接口，
 * 「进程起来了」≠「能服务了」——旧脚本 {@code sleep 3} 后打印 systemd 状态即结束，
 * 启动失败（迁移校验失败、配置缺失）要等用户报障才被发现。
 * <p>
 * 公开、无副作用、不缓存；不返回版本号/主机名等任何内部信息。
 */
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/health")
    public ApiResponse<Map<String, String>> health() {
        jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        return ApiResponse.ok(Map.of("status", "UP"));
    }
}
