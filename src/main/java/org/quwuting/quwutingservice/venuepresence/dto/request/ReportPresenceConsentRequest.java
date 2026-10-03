package org.quwuting.quwutingservice.venuepresence.dto.request;

/**
 * 到店足迹开关状态上报请求体（POST /venues/presence-consent，2026-09-29 四轮 V34；
 * 2026-10-03 五轮增 {@code source}）。
 * <p>
 * 端上在两处产生状态确立：「我的-设置-到店足迹」拨动开关（USER）与到店首问回答（PROMPT）。
 * 2026-10-03 起服务端以最新一条显式行作为<b>采集门禁</b>，端上对本接口改为「失败留待下个
 * 采集周期补发」（不再是纯 fire-and-forget），否则同意记录缺失会让后续 ping 被拒收。
 *
 * @param enabled 确立后的开关状态（true = 采集开启）；缺失按 1022 拒绝，禁猜默认值
 * @param source  确立来源 {@code USER} / {@code PROMPT}（WireEnums 宽容解析）。
 *                <b>缺失 = USER</b>：这不是猜测而是协议历史——10-03 之前的端只会在设置页
 *                拨开关时上报、且不带本字段；{@code DEFAULT} 或无法识别的值按 1022 拒绝
 *                （DEFAULT 是服务端历史补记来源，端上无权声明「默认」）
 */
public record ReportPresenceConsentRequest(Boolean enabled, String source) {
}
