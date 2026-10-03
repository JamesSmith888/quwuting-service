package org.quwuting.quwutingservice.venuepresence.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 到店足迹开关统计（GET /admin/venues/presence-consent-stats，2026-09-29 四轮 V34；
 * 2026-10-03 五轮改「到店首问」口径）。
 * <p>
 * 当前态 = 每用户<b>最新一条</b> consent 行；「已允许」与服务端采集门禁同一判据
 * （最新一条 enabled 且来源显式，{@code ConsentSource#isExplicit}）。全字段 ALWAYS 序列化
 * （non_null 全局策略会删 null/0 字段，35 号教训）。
 *
 * @param enabledUsers        当前采集开启的去重用户数（最新态 enabled + 显式来源 USER/PROMPT）
 * @param disabledUsers       当前采集关闭的去重用户数
 * @param legacyDefaultUsers  待补问人数：最新态仍是历史 DEFAULT（09-29 ~ 10-03 默认开启期被记录、
 *                            从未被询问）——门禁已停收其数据，下次到店首问后转入允许/关闭；
 *                            只减不增（DEFAULT 已停止写入），归零即补问完成
 * @param promptAllowedUsers  到店首问回答「允许」的去重用户数（首问文案效果度量）
 * @param promptDeclinedUsers 到店首问回答「不用了」的去重用户数
 * @param changes30d          近 30 天「我的-设置」手动变更次数（USER 来源行数）
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record VenuePresenceConsentStats(
        long enabledUsers,
        long disabledUsers,
        long legacyDefaultUsers,
        long promptAllowedUsers,
        long promptDeclinedUsers,
        long changes30d) {
}
