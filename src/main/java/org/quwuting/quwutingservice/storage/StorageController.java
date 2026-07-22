package org.quwuting.quwutingservice.storage;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 存储凭证接口（前端直传 Supabase Storage 模式）。
 * <p>
 * 前端流程：
 * 1. 选择文件后调用本接口获取上传凭证
 * 2. 用凭证中的 projectUrl + anonKey + bucket + uploadPath 直传 Supabase Storage
 * 3. 上传成功后将 publicUrl 写入业务字段随表单提交
 */
@RestController
@RequestMapping("/storage")
@RequiredArgsConstructor
public class StorageController {

    private final StorageService storageService;

    /**
     * 获取上传凭证（需登录）。
     * GET /storage/upload-token?category=VENUE_COVER&fileName=photo.jpg&fileSize=102400
     */
    @GetMapping("/upload-token")
    public ApiResponse<UploadTokenResponse> getUploadToken(
            @RequestParam FileCategory category,
            @RequestParam String fileName,
            @RequestParam long fileSize
    ) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(storageService.generateUploadToken(userId, category, fileName, fileSize));
    }
}
