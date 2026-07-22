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
        validateFile(fileName, fileSize);

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

    private void validateFile(String fileName, long fileSize) {
        if (fileName == null || fileName.isBlank()) {
            throw new BusinessException(1005, "文件名不能为空");
        }
        if (fileSize <= 0) {
            throw new BusinessException(1005, "文件大小无效");
        }
        if (fileSize > props.maxFileSize()) {
            long maxMb = props.maxFileSize() / (1024 * 1024);
            throw new BusinessException(1005, "文件大小不能超过 " + maxMb + "MB");
        }
        String ext = extractExtension(fileName);
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
