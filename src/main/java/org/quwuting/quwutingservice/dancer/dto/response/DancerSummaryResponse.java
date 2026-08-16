package org.quwuting.quwutingservice.dancer.dto.response;

import org.quwuting.quwutingservice.dancer.enums.DancerStatus;
import org.quwuting.quwutingservice.dancer.enums.DancerVerificationStatus;

import java.util.List;

/**
 * 舞伴列表条目（列表页卡片数据源）。
 * <p>
 * 卡片信息层级（AGENTS.md「舞伴生态体系」）：头像 + 昵称（身份）、"常去"（场所锚点）、
 * 认可数（❤️ N 人认可，展示 countAll——累计总量）、Top 标签（最多 3 个，认可理由）。
 * 排序由后端完成（近7天认可倒序），前端不重复排序。
 * <p>
 * status 仅对有权限者有意义（公开列表恒为 NORMAL；「我的舞伴主页」含 PENDING/HIDDEN），
 * 前端据 status 渲染审核中/已隐藏徽标。
 */
public record DancerSummaryResponse(
        Long id,
        String nickname,
        String avatarUrl,
        String bio,
        String gender,
        String city,
        DancerStatus status,
        /**
         * 信息核验状态（2026-08-14 官方认证）。展示规则：VERIFIED → 卡片「信息已核验」
         * 徽标；PENDING_REVIEW / UNVERIFIED → 不展示（对外语义 = 未认证）。
         */
        DancerVerificationStatus verificationStatus,
        /** 常驻舞厅名（取最早一条 HOME 关系；无则 null，前端不渲染"常去"行） */
        String homeVenueName,
        /** 相册封面（展示顺序最小的一张 PUBLIC 照片；无则 null，卡片不渲染封面图） */
        String coverPhotoUrl,
        /** 累计认可数（"❤️ N 人认可"主展示位） */
        long recognitionCount,
        /** 近7天认可数（排序依据 + "近7天"小字） */
        long recognitionCount7d,
        /** 今日认可数（"今天 +N"动态信号） */
        long recognitionCountToday,
        /** 今日是否已认可（登录用户；匿名恒 false） */
        boolean myRecognizedToday,
        /** Top 认可标签（最多 3 个，按计数倒序） */
        List<DancerTagStat> topTags,
        /**
         * 累计浏览量（2026-08-15 新增，镜像门店 VenueResponse.viewCount）：
         * qwt_dancer_views 行数（按天按来源去重 PV，含匿名，与 DancerStatsService
         * viewTrend 同源同口径的全量版）。驱动舞伴列表卡片右下角「👁 浏览数」展示；
         * 列表/收藏等卡片展示场景传真实值（批量查询见
         * {@code DancerViewRepository#countByDancerIds}），禁止传默认 0
         * （否则列表卡片浏览量恒为 0，同门店 isHot 历史缺陷模式）。
         */
        long viewCount
) {}
