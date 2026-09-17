package org.quwuting.quwutingservice.venue.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.quwuting.quwutingservice.venue.dto.BusinessHoursEntry;
import org.quwuting.quwutingservice.venue.dto.PartnerFeeEntry;
import org.quwuting.quwutingservice.venue.dto.TicketEntry;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.enums.VenueType;

import java.time.LocalDate;
import java.util.List;

public record CreateVenueRequest(

        @NotBlank(message = "场所名称不能为空")
        @Size(max = 100)
        String name,

        VenueStatus status,

        /**
         * 预期开业日（2026-09-17 新增，V29；方案见 docs/agents/50-venue-opening-plan.md）。
         * <p>
         * 与 {@code status} 构成「将来时 × 现在时」：设定本字段而 {@code status} 保持
         * 停业类，即表达「今天不可去、{该日} 起可去」——展示层派生 UPCOMING
         * （徽标读作「9月18日开业」），到点由调度器自动转 OPEN 并清空本字段。
         * <p>
         * <b>空值语义 = 清空（与同 record 其余可空字段的惯例刻意不同，判据见下）</b>：
         * 本字段存在合法的清空场景（开业计划取消 / 日期改期），必须给它一条通道。
         * {@code status} / {@code venueType} / {@code sortWeight} 用「null = 保留原值」
         * 是为了不让**存量调用方**（不带新字段的旧版管理端表单、脚本、Skill）静默改掉
         * 已有值；而本字段是纯新增、零存量，唯一调用方 = 管理端门店编辑页（与后端同轮
         * 接入），"旧表单清空"的窗口期没有任何数据可丢。故取<b>全量覆盖</b>语义，
         * 与 address / contactPhone / description 一致（本接口本就是全量覆盖语义，
         * 见 {@code VenueService#updateVenue} javadoc）。
         * <p>
         * 计划兑现（调度器转 OPEN）与人工置 OPEN 时，服务端会主动清空本字段——
         * 一个计划只兑现一次，不留两套真值。
         */
        LocalDate expectedOpenDate,

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
