package org.quwuting.quwutingservice.dancer.dto.response;

import org.quwuting.quwutingservice.dancer.enums.DancerStatus;

import java.util.List;

/**
 * 舞伴详情页数据源。
 * <p>
 * 页面目标（让用户快速判断"这个舞伴是否值得认识"）的字段分组：
 * 身份区（nickname/avatarUrl/gender/city/status/bio）→ 认可统计（stats）→
 * 相册（photos，本人/管理员含待审态，非本人仅 PUBLIC）→
 * 标签云（tags，来源 = 用户认可行为）→ 常去/出现舞厅（venues）→ 我的认可态（myRecognizedToday）
 * → 收到积分（pointsReceivedTotal/pointsReceived30d，2026-08-10 V2 新增：
 * 用户"表达支持"的量化信号，驱动舞伴列表次级排序，见 DancerRepository#findPublicPage）。
 * <p>
 * isMine = 当前用户是否为创建人（本人可见自己 PENDING/HIDDEN 的资料，见可见性规则）；
 * 详情页仅展示本记录关联的场所，不公开任何用户关系。
 */
public record DancerDetailResponse(
        Long id,
        String nickname,
        String avatarUrl,
        String bio,
        String gender,
        String city,
        DancerStatus status,
        boolean isMine,
        boolean myRecognizedToday,
        DancerRecognitionStats stats,
        /** 收到积分总数（target_type='DANCER' 全量，2026-08-10 V2） */
        long pointsReceivedTotal,
        /** 近30天收到积分（驱动舞伴列表次级排序信号） */
        long pointsReceived30d,
        /** 相册照片（服务层已按可见性过滤：非本人仅 PUBLIC；本人/管理员全量含待审态） */
        List<DancerPhotoResponse> photos,
        List<DancerTagStat> tags,
        List<DancerVenueInfo> venues
) {}
