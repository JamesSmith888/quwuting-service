package org.quwuting.quwutingservice.venue.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 门店离线快照响应（2026-09-08 弱网离线韧性，GET /venues/snapshot）。
 *
 * <p>同步协议 = 时间戳游标增量（timestamp-based sync，无侵入写路径）：
 * <ul>
 *   <li>since 为空 → 全量下发（首次同步），removedIds 恒空；</li>
 *   <li>since 非空 → 下发 updatedAt ≥ since 的活跃行 + 同窗口内被软删的门店 id
 *       （removedIds，客户端从本地包剔除）。</li>
 * </ul>
 *
 * <p>为什么不发全局版本号：版本号要求每条写路径（创建/更新/状态回滚/批量导入/别名）
 * 都记得 bump，漏一处即静默漂移——时间戳游标靠 {@code BaseEntity.updatedAt} 自动维护，
 * 零写路径侵入，天然无遗漏。时钟偏差由客户端只存服务端下发的 {@code serverTime}
 * 作为下次游标规避（不信客户端时钟）。
 */
public record VenueSnapshotResponse(
        /** 服务端当前时间（客户端原样存为下次同步游标，禁止用客户端本地时间拼接） */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime serverTime,
        /** 活跃门店快照条目（全量 = 全部活跃行；增量 = updatedAt ≥ since 的活跃行） */
        List<VenueSnapshotItem> venues,
        /** since 窗口内被软删除的门店 id（客户端据此从本地包剔除；全量同步恒空） */
        List<Long> removedIds
) {}
