package org.quwuting.quwutingservice.venue.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.quwuting.quwutingservice.venue.enums.PartnerFeeUnit;

import java.math.BigDecimal;

/**
 * 舞伴费用档位（请求与响应共用）。
 * <p>
 * 设计动机：舞厅舞伴计费存在多种模式——江浙沪按时长阶梯（5分钟30元）、
 * 西安等地按连曲（3曲30元），同一店内还可能存在时段差异（5点前/后不同价格）。
 * 采用"label（条件）+ unit（计量单位）+ minutes（数量）+ price（总价）"的扁平规则列表，
 * 与 TicketEntry 的"label + type + price"模式对齐，新增计费形态只需扩展 unit 枚举。
 * <p>
 * label 为自由文本，描述该档位的适用条件（如"5点前"、"工作日"），空串/null 表示无条件。
 * unit 为计量单位（MINUTE 分钟 / SONG 曲数），请求中可省略（向后兼容，默认 MINUTE）。
 * minutes 为计量数量（当 unit=SONG 时语义为曲数），字段名保留 minutes 以兼容存量数据。
 */
public record PartnerFeeEntry(
        @Size(max = 50, message = "舞伴费用条件说明最长50个字符")
        String label,

        PartnerFeeUnit unit,

        @NotNull(message = "舞伴数量不能为空")
        @Min(value = 1, message = "舞伴数量至少为1")
        Integer minutes,

        @NotNull(message = "舞伴费用不能为空")
        @DecimalMin(value = "0.01", message = "舞伴费用必须大于0")
        BigDecimal price
) {
    /** 获取计量单位，null 时默认 MINUTE（兼容存量数据） */
    public PartnerFeeUnit effectiveUnit() {
        return unit != null ? unit : PartnerFeeUnit.MINUTE;
    }

    /** 获取条件标签，null 时返回空串（前端统一处理） */
    public String effectiveLabel() {
        return label != null ? label : "";
    }
}
