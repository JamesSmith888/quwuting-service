package org.quwuting.quwutingservice.venuesync.dto.response;

import java.time.LocalDateTime;

/**
 * 门店状态权威层级 · 当前状态（2026-09-14，V25；方案见 docs/agents/48）。
 * <p>
 * 消费面 = Web 管理后台门店编辑页与门店列表徽标：管理员必须看得见
 * 「这个状态是人定的还是自动同步来的」「人工优先保护到哪天到点」，
 * 否则他既不知道自己改的有期限，也无从判断「要不要提前恢复自动同步 / 设为豁免」。
 *
 * @param venueId          平台门店 ID
 * @param name             门店名
 * @param status           当前营业状态枚举名
 * @param statusSource     状态来源（MANUAL 人工 / SYNC 自动 / null 旧数据）
 * @param statusLockedUntil 人工锁到期时刻（null = 无锁或已过期）
 * @param locked            人工锁当前是否生效（服务端判定，前端不再算一遍）
 * @param dailySyncExempt  是否永久豁免舞讯推断
 * @param syncNote         人工备注（可空）
 */
public record VenueGuardStateItem(
        long venueId,
        String name,
        String status,
        String statusSource,
        LocalDateTime statusLockedUntil,
        boolean locked,
        boolean dailySyncExempt,
        String syncNote
) {}
