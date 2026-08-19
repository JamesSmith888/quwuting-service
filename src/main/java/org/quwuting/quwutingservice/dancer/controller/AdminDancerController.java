package org.quwuting.quwutingservice.dancer.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.dancer.dto.request.UpsertDancerRequest;
import org.quwuting.quwutingservice.dancer.dto.request.UpdateDancerPhotoStatusRequest;
import org.quwuting.quwutingservice.dancer.dto.request.UpdateDancerStatusRequest;
import org.quwuting.quwutingservice.dancer.dto.request.UpdateDancerVerificationRequest;
import org.quwuting.quwutingservice.dancer.dto.response.AdminDancerPhotoResponse;
import org.quwuting.quwutingservice.dancer.dto.response.AdminDancerResponse;
import org.quwuting.quwutingservice.dancer.enums.DancerPhotoStatus;
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
 *   <li>POST /admin/dancers/{id}/status — 状态切换（审核通过 PENDING→NORMAL / 驳回
 *       PENDING→REJECTED / 下架恢复 NORMAL↔HIDDEN；body.reason 可选操作说明，
 *       随站内信通知创建人，2026-08-08 新增；2026-08-19 由 PUT 迁移对齐「只允许
 *       GET 和 POST」约定）</li>
 *   <li>GET /admin/dancers/statuses — 状态字典回显</li>
 *   <li>GET /admin/dancers/photos — 相册照片审核列表（status 可选，按上传时间倒序）</li>
 *   <li>POST /admin/dancers/photos/{photoId}/status — 照片审核（PENDING → PUBLIC / REJECTED，
 *       2026-08-19 由 PUT 迁移对齐「只允许 GET 和 POST」约定）</li>
 * </ul>
 * 认证是"先认证、后展示"隐私边界的管理员侧落点：舞伴主动注册的资料必须经本接口
 * 审核（PENDING → NORMAL / REJECTED）后才公开或明确驳回，审核结果经站内信
 * 通知创建人（见 AGENTS.md「舞伴审核与站内信」）；舞伴本人上传的相册照片同样
 * 必须经本接口逐张审核（见 AGENTS.md「相册与照片审核」）。
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
    public ApiResponse<Long> create(@Valid @RequestBody UpsertDancerRequest request) {
        Long adminId = UserContext.requireAdmin();
        return ApiResponse.ok(dancerService.createDancer(adminId, request, true));
    }

    /** 舞伴状态切换（审核通过 / 驳回 / 隐藏 / 恢复；状态变化即站内信通知创建人。
     *  2026-08-19：PUT → POST 对齐「只允许 GET 和 POST」约定） */
    @PostMapping("/{id}/status")
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

    /**
     * 舞伴信息核验操作（仅 ADMIN，2026-08-14 官方认证）：
     * VERIFY = 授予/复核确认「信息已核验」标识（UNVERIFIED / PENDING_REVIEW → VERIFIED，
     * 站内信通知创建人）；UNVERIFY = 撤销（VERIFIED / PENDING_REVIEW → UNVERIFIED，
     * reason 必填——撤销必须留痕理由，随站内信通知舞伴）。
     * 全部变迁写审计日志（qwt_dancer_verification_logs），见 AGENTS.md「舞伴官方认证」。
     * 2026-08-19：PUT → POST 对齐「只允许 GET 和 POST」约定。
     */
    @PostMapping("/{id}/verification")
    public ApiResponse<Void> updateVerification(@PathVariable Long id,
                                                @Valid @RequestBody UpdateDancerVerificationRequest request) {
        Long adminId = UserContext.requireAdmin();
        dancerService.updateVerification(adminId, id, request.action(), request.reason());
        return ApiResponse.ok(null);
    }

    /**
     * 相册照片审核列表（仅 ADMIN，分页倒序）。status 可选（PENDING / PUBLIC / REJECTED），
     * 缺省返回全部——管理员从「待审核」筛选进入待办，可切换查看已处理历史。
     */
    @GetMapping("/photos")
    public ApiResponse<Page<AdminDancerPhotoResponse>> listPhotos(
            @RequestParam(required = false) DancerPhotoStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UserContext.requireAdmin();
        return ApiResponse.ok(dancerService.listAdminPhotos(status, page, size));
    }

    /** 照片审核（仅 ADMIN）：PENDING → PUBLIC（通过）/ REJECTED（驳回，reason 可选审计）。
     *  2026-08-19：PUT → POST 对齐「只允许 GET 和 POST」约定） */
    @PostMapping("/photos/{photoId}/status")
    public ApiResponse<Void> updatePhotoStatus(@PathVariable Long photoId,
                                               @Valid @RequestBody UpdateDancerPhotoStatusRequest request) {
        Long adminId = UserContext.requireAdmin();
        dancerService.updatePhotoStatus(adminId, photoId, request.status(), request.reason());
        return ApiResponse.ok(null);
    }
}
