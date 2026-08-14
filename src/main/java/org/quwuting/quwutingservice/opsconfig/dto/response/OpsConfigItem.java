package org.quwuting.quwutingservice.opsconfig.dto.response;

import java.time.LocalDateTime;

/**
 * 运营配置项（管理端列表视图；value 为当前生效值，updatedAt 为最近修改时刻）。
 */
public record OpsConfigItem(String key, String value, LocalDateTime updatedAt) {
}
