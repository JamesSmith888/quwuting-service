package org.quwuting.quwutingservice.venue.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.quwuting.quwutingservice.venue.dto.BusinessHoursEntry;
import org.quwuting.quwutingservice.venue.dto.PartnerFeeEntry;
import org.quwuting.quwutingservice.venue.dto.TicketEntry;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 门店离线快照条目（2026-09-08 弱网离线韧性，GET /venues/snapshot）。
 *
 * <p>定位：{@link VenueResponse} 的<strong>静态字段子集</strong>——只含渲染门店卡片与
 * 详情基础信息所必需的低频变更字段（名称/状态/地址/坐标/营业时间/消费/标签等），
 * 刻意不含全部动态信号（Reaction 徽标、热度角标、最新上报行、浏览量、照片列表）。
 * 动态信号天然不可离线（强时效），快照携带只会制造「看似新鲜实则过期」的误导。
 *
 * <p>体积边界：单条约 1KB，全量（当前约 1000 家）约 1MB。数据量到万级前无需分页；
 * 超限后再引入游标分页（snapshot 是幂等资源，游标分页可平滑追加）。
 *
 * <p>photos 刻意省略：图片是远程图床资源，断网时 URL 在手也无法加载，离线场景
 * 只有封面图 imageUrl 有弱网恢复价值；详情页相册离线时按空列表兜底（前端既有
 * {@code photos || []} 契约）。
 */
public record VenueSnapshotItem(
        Long id,
        String name,
        VenueStatus status,
        /** 状态展示文案（服务端权威，前端零拼接——对齐 VenueResponse.statusDisplay 契约） */
        String statusDisplay,
        String imageUrl,
        String description,
        String city,
        String district,
        String address,
        Double longitude,
        Double latitude,
        /** 营业时段列表，无数据时为空列表（口径同 VenueResponse） */
        List<BusinessHoursEntry> businessHours,
        /** 门票规则列表，无数据时为空列表 */
        List<TicketEntry> tickets,
        /** 舞伴费用阶梯，无数据时为空列表 */
        List<PartnerFeeEntry> partnerFees,
        String contactPhone,
        String wechatQr,
        /** 系统默认标签合并后的生效标签（口径同 VenueResponse.tags） */
        List<String> tags,
        /** 系统默认标签子集（前端据此区分不可删除的系统标签，口径同 VenueResponse） */
        List<String> defaultTags,
        Integer sortWeight,
        /** 数据最后更新时间（客户端同步游标判定的行级依据 + 离线数据龄展示） */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime updatedAt
) {}
