package org.quwuting.quwutingservice.venue.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.quwuting.quwutingservice.venue.enums.TicketType;

import java.math.BigDecimal;

/**
 * 门票规则条目（请求与响应共用）。
 * <p>
 * label 为自由文本，描述该票种的适用条件（如"下午4点前"、"晚场"、"节假日"），
 * 空 label 表示全场通用票价。type=FIXED 时 price 必填（Service 层校验）。
 * <p>
 * 设计动机：舞厅门票形态多样（固定票、免票、时段免票），用"条件标签 + 类型 + 价格"
 * 的扁平规则列表表达，新增票种无需变更表结构。
 */
public record TicketEntry(
        @Size(max = 50, message = "门票条件说明最长50个字符")
        String label,

        @NotNull(message = "门票类型不能为空")
        TicketType type,

        @DecimalMin(value = "0.01", message = "票价必须大于0")
        BigDecimal price
) {}
