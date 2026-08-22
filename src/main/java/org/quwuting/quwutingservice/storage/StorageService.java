package org.quwuting.quwutingservice.storage;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.UUID;

/**
 * 存储服务：签发前端直传 Supabase Storage 的上传凭证。
 * <p>
 * 后端不接收文件流，职责仅为：
 * 1. 校验文件元信息（类型、大小）
 * 2. 生成唯一上传路径（分类前缀 + userId + UUID + 扩展名）
 * 3. 返回前端直传所需的完整凭证（projectUrl / anonKey / bucket / uploadPath / publicUrl）
 */
@Service
@RequiredArgsConstructor
public class StorageService {

    private final StorageProperties props;

    /**
     * 签发上传凭证。
     *
     * @param userId   当前登录用户 ID（路径隔离用）
     * @param category 文件分类（决定路径前缀）
     * @param fileName 原始文件名（提取扩展名用）
     * @param fileSize 文件大小（字节）
     * @return 前端直传所需的完整凭证
     */
    public UploadTokenResponse generateUploadToken(Long userId, FileCategory category,
                                                   String fileName, long fileSize) {
        validateFile(category, fileName, fileSize);

        String ext = extractExtension(fileName);
        String uploadPath = category.getPathPrefix() + "/" + userId + "/" + UUID.randomUUID() + ext;
        String publicUrl = props.projectUrl() + "/storage/v1/object/public/" + props.bucket() + "/" + uploadPath;

        return new UploadTokenResponse(
                props.projectUrl(),
                props.anonKey(),
                props.bucket(),
                uploadPath,
                publicUrl
        );
    }

    /** 视频分类（2026-08-22 舞伴短视频）——校验走视频扩展名 + 独立大小上限通道 */
    private static final java.util.Set<String> VIDEO_EXTENSIONS =
            java.util.Set.of(".mp4", ".mov");

    private static boolean isVideoCategory(FileCategory category) {
        return category == FileCategory.DANCER_VIDEO;
    }

    private void validateFile(FileCategory category, String fileName, long fileSize) {
        if (fileName == null || fileName.isBlank()) {
            throw new BusinessException(1005, "文件名不能为空");
        }
        if (fileSize <= 0) {
            throw new BusinessException(1005, "文件大小无效");
        }
        String ext = extractExtension(fileName);
        if (isVideoCategory(category)) {
            // 视频分类（2026-08-22 舞伴短视频）：视频扩展名 + 独立大小上限
            if (fileSize > props.videoMaxFileSize()) {
                long maxMb = props.videoMaxFileSize() / (1024 * 1024);
                throw new BusinessException(1005, "视频大小不能超过 " + maxMb + "MB");
            }
            if (!VIDEO_EXTENSIONS.contains(ext)) {
                throw new BusinessException(1005, "不支持的视频格式，仅允许: mp4, mov");
            }
            return;
        }
        if (fileSize > props.maxFileSize()) {
            long maxMb = props.maxFileSize() / (1024 * 1024);
            throw new BusinessException(1005, "文件大小不能超过 " + maxMb + "MB");
        }
        boolean allowed = Arrays.stream(props.allowedExtensions())
                .anyMatch(e -> e.equalsIgnoreCase(ext));
        if (!allowed) {
            throw new BusinessException(1005,
                    "不支持的文件类型，仅允许: " + String.join(", ", props.allowedExtensions()));
        }
    }

    private String extractExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            throw new BusinessException(1005, "文件缺少扩展名");
        }
        return fileName.substring(dotIndex).toLowerCase();
    }
}
