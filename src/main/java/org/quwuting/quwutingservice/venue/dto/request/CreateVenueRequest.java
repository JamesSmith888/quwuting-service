package org.quwuting.quwutingservice.venue.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.quwuting.quwutingservice.venue.dto.BusinessHoursEntry;
import org.quwuting.quwutingservice.venue.dto.PartnerFeeEntry;
import org.quwuting.quwutingservice.venue.dto.TicketEntry;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.enums.VenueType;

import java.util.List;

public record CreateVenueRequest(

        @NotBlank(message = "场所名称不能为空")
        @Size(max = 100)
        String name,

        VenueStatus status,

        /**
         * 门店类型（2026-09-13 新增，V24）：舞厅 / KTV / 歌友会。
         * <p>
         * 空值语义与 {@code status} / {@code sortWeight} 对齐——<b>null = 保留原值不覆盖</b>
         * （编辑场景），新建时由 Service 回退默认 HALL。本接口恒 {@code requireAdmin}
         * （平台代发），类型不向普通用户开放自助选择，避免名录被随意扩充品类。
         */
        VenueType venueType,

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

        Integer sortWeight,

        /**
         * 变更来源声明（2026-09-14 新增，V25；方案见 docs/agents/48）。
         * <p>
         * 本接口（{@code POST /venues/{id}/update}）默认是<b>人工编辑通道</b>：状态变更视为
         * 人工判断，会打「人工锁」（锁内外部舞讯通道不得覆盖）。
         * 但 Agent/Skill 也会经此接口做程序化写库（CLOSED → OPEN 恢复营业、营业时段回填），
         * 那属于外部通道，必须受门禁约束，否则本接口就是绕过人工锁的后门。
         * <p>
         * 取值：{@code "AGENT_BATCH"} = 程序化外部写库（受门禁约束，被拦时**资料照改、状态不动**）；
         * {@code null} / 其他 = 人工编辑（默认，前端编辑表单不传即走此路）。
         */
        @Size(max = 20)
        String changeSource
) {}
