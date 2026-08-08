package org.quwuting.quwutingservice.venue.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.quwuting.quwutingservice.venue.dto.BusinessHoursEntry;
import org.quwuting.quwutingservice.venue.dto.PartnerFeeEntry;
import org.quwuting.quwutingservice.venue.dto.TicketEntry;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;

import java.util.List;

public record CreateVenueRequest(

        @NotBlank(message = "场所名称不能为空")
        @Size(max = 100)
        String name,

        VenueStatus status,

        @Size(max = 500)
        String imageUrl,

        /** 相册图片 URL 列表，最多 9 张 */
        @Size(max = 9)
        List<@Size(max = 500) String> photos,

        @Size(max = 500)
        String description,

        @NotBlank(message = "城市不能为空")
        String city,

        /** 区/县，选填（2026-08-08 放宽：行政区非业务必填；保留长度上限防超 varchar(50)） */
        @Size(max = 50)
        String district,

        @Size(max = 200)
        String address,

        Double longitude,
        Double latitude,

        /** 营业时段列表（午场/晚场等，跨天时段 close<open 表示次日结束），最多 10 条 */
        @Size(max = 10)
        List<@Valid BusinessHoursEntry> businessHours,

        /** 门票规则列表（固定票/免票/时段免票），最多 10 条 */
        @Size(max = 10)
        List<@Valid TicketEntry> tickets,

        /** 舞伴费用阶梯，最多 10 档 */
        @Size(max = 10)
        List<@Valid PartnerFeeEntry> partnerFees,

        @Size(max = 20)
        String contactPhone,

        @Size(max = 500)
        String wechatQr,

        @Size(max = 10)
        List<@Size(max = 20) String> tags,

        Integer sortWeight
) {}
