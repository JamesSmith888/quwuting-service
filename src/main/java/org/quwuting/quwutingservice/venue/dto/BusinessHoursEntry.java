package org.quwuting.quwutingservice.venue.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;

/**
 * 营业时段条目（请求与响应共用，序列化进 qwt_venues.business_hours JSON 列）。
 * <p>
 * 设计动机：一个舞厅通常有多个营业时段（午场/下午场/晚场/夜场），时段数量与命名
 * 因店而异、且会变化——用「时段名 + 起止时间」的扁平列表表达，新增/调整场次
 * 无需变更表结构。与 {@link TicketEntry} / {@link PartnerFeeEntry} 同属
 * "变长结构化列表 → JSON 数组字符串列"模式（见 AGENTS.md「场所数据模型」）。
 * <p>
 * <b>跨天契约</b>：close &lt; open 表示结束于次日凌晨（如晚场 18:30 - 01:00），
 * 原样存取、展示端原样呈现，不额外引入 endNextDay 标记——该约定为行业通行做法，
 * 数据自解释。
 * <p>
 * name 为时段名（如"午场"），可空——空时段展示时省略时段名前缀，仅呈现起止时间；
 * open/close 必填（@Valid 级联校验，Service 层无需额外校验）。
 */
public record BusinessHoursEntry(
        @Size(max = 20, message = "时段名称最长20个字符")
        String name,

        @NotNull(message = "开始时间不能为空")
        @JsonFormat(pattern = "HH:mm")
        LocalTime open,

        @NotNull(message = "结束时间不能为空")
        @JsonFormat(pattern = "HH:mm")
        LocalTime close
) {
    /** 获取时段名，null 时返回空串（前端统一处理） */
    public String effectiveName() {
        return name != null ? name : "";
    }
}
