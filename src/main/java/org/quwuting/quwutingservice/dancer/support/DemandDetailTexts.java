package org.quwuting.quwutingservice.dancer.support;

import org.quwuting.quwutingservice.dancer.entity.DancerService;
import org.quwuting.quwutingservice.dancer.enums.DemandDuration;
import org.quwuting.quwutingservice.dancer.enums.UserLocationOption;

import java.time.LocalDate;
import java.util.function.LongFunction;

/**
 * 邀约需求四要素文本派生（2026-08-26 从 PointsService 抽取公共方法）。
 * <p>
 * 服务/时间/时长/位置详情表述 + 多行详细文本——客人侧「我的邀约」详情
 * （{@code PointsService#getMyDemand}）与管理端邀约工作台详情
 * （{@code DemandRelayService#getDetail}）同源同序（复制即用），单一事实源，
 * 对齐 22 号文档「复用 getMyDemand 反推逻辑抽公共方法」的约定。
 * <p>
 * 背景：历史记录未存 subCategory（服务子选项，落库仅 service_ids），服务部分用
 * 服务<b>当前权威 label</b> 兜底（与详情页服务卡同源）。
 * <p>
 * 纯静态工具（无 Bean 依赖）：{@link #resolveServiceLabel} 的存储查询经
 * {@link LongFunction} 注入，调用方各自持有仓库引用。
 */
public final class DemandDetailTexts {

    private DemandDetailTexts() {
    }

    /** 需求时间「近3天内」相对槽 code（2026-08-26：需求弹层默认时间选项，
     *  qwt_demand_records.time_slots 原样存本 code） */
    public static final String TIME_WITHIN_3_DAYS = "WITHIN_3_DAYS";

    /** 需求时间「近3天内」消息拼接文案（2026-08-26；前端预览经同一常量镜像） */
    public static final String TIME_WITHIN_3_DAYS_TEXT = "近3天内";

    /** 具体日期 → 「M月D日」（如 8月1日；2026-08-25 消息拼接/详情表述共用） */
    public static String formatDate(LocalDate date) {
        return date.getMonthValue() + "月" + date.getDayOfMonth() + "日";
    }

    /**
     * 服务 label 解析（service_ids 逗号串 → 第一项在用服务 label）：
     * 逐段防御历史脏数据（空段/非法 id/软删服务 → 跳过），全部无效 → null
     * （前端省略该行）。存储查询经 loader 注入（调用方传
     * {@code id -> dancerServiceRepository.findByIdAndDeletedFalse(id).orElse(null)}）。
     */
    public static String resolveServiceLabel(String serviceIdsRaw, LongFunction<DancerService> loader) {
        if (serviceIdsRaw == null || serviceIdsRaw.isBlank()) {
            return null;
        }
        for (String raw : serviceIdsRaw.split(",")) {
            if (raw.isBlank()) {
                continue;
            }
            DancerService service;
            try {
                service = loader.apply(Long.parseLong(raw.trim()));
            } catch (NumberFormatException e) {
                service = null;
            }
            if (service != null) {
                return service.getLabel();
            }
        }
        return null;
    }

    /** 时间详情表述（WITHIN_3_DAYS = 「近3天内，具体哪天可与您协商」；具体日期 =
     *  「M月D日，具体时段可与您协商」；历史数据日期非法 → 防御性 null，不打断详情页） */
    public static String timeDetailLabel(String timeSlotCode) {
        if (timeSlotCode == null || timeSlotCode.isBlank()) {
            return null;
        }
        try {
            if (TIME_WITHIN_3_DAYS.equals(timeSlotCode)) {
                return TIME_WITHIN_3_DAYS_TEXT + "，具体哪天可与您协商";
            }
            return formatDate(LocalDate.parse(timeSlotCode)) + "，具体时段可与您协商";
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 时长详情表述（历史数据枚举异常 → 防御性 null，不打断详情页） */
    public static String durationLabel(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return DemandDuration.valueOf(code).display() + "，时长可商量";
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 位置详情表述（历史数据枚举异常 → 防御性 null，不打断详情页） */
    public static String locationLabel(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return UserLocationOption.valueOf(code).detailText();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 多行详细需求文本拼接（2026-08-26 邀约瘦身：只保留用户本次需求四要素行——
     * 服务/时间/时长/位置；行格式「标签：值」，空值行省略（时长/位置未选），
     * 行间 \n 连接（无前导换行——serviceLabel 缺失时其余行正常起始）。与表格
     * 结构化字段同源同序，粘贴微信聊天即完整需求说明）。
     */
    public static String detailText(String serviceLabel, String timeDetailLabel,
                                    String durationLabel, String locationLabel) {
        StringBuilder sb = new StringBuilder();
        if (serviceLabel != null) {
            sb.append("服务：").append(serviceLabel);
        }
        if (timeDetailLabel != null) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("时间：").append(timeDetailLabel);
        }
        if (durationLabel != null) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("时长：").append(durationLabel);
        }
        if (locationLabel != null) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("位置：").append(locationLabel);
        }
        return sb.toString();
    }
}
