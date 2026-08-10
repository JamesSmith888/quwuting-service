package org.quwuting.quwutingservice.points.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.points.dto.AdminAdjustRequest;
import org.quwuting.quwutingservice.points.service.PointsService;
import org.quwuting.quwutingservice.security.UserContext;
import org.springframework.web.bind.annotation.*;

/**
 * 积分管理端接口（仅 ADMIN）。
 * 人工调整 = 积分系统的运营纠偏通道（纠正误发/惩罚刷分/异常检测处置落点）。
 */
@RestController
@RequestMapping("/admin/points")
@RequiredArgsConstructor
public class AdminPointsController {

    private final PointsService pointsService;

    /** 人工调整（POST /admin/points/adjust，需 ADMIN；delta 可正可负） */
    @PostMapping("/adjust")
    public ApiResponse<Void> adjust(@Valid @RequestBody AdminAdjustRequest request) {
        pointsService.adjust(UserContext.requireAdmin(), request.userId(), request.delta(), request.reason());
        return ApiResponse.ok(null);
    }
}
