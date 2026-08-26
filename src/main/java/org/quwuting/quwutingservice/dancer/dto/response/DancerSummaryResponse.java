package org.quwuting.quwutingservice.dancer.dto.response;

import org.quwuting.quwutingservice.dancer.enums.DancerStatus;
import org.quwuting.quwutingservice.dancer.enums.DancerVerificationStatus;
import org.quwuting.quwutingservice.tagdict.dto.response.TagItemResponse;

import java.util.List;
import java.time.LocalDateTime;
/**
 * 舞伴列表条目（列表页卡片数据源）。
 * <p>
 * 卡片信息层级（AGENTS.md「舞伴生态体系」）：头像 + 昵称（身份）、"常去"（场所锚点）、
 * 认可数（❤️ N 人认可，展示 countAll——累计总量）、认可标签聚合（2026-08-19 起全量，列表 reaction 区域 chips）。
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
        /**
         * 资料标签（2026-08-24 管理员设置，字典化）：TagItemResponse 列表
         * （text + description——列表卡片长按/点击弹说明的权威文案），按字典排序。
         * 空列表 = 无标签，卡片不渲染标签行。与「认可标签聚合」（topTags，
         * 用户行为产生）语义独立，字段名显式区分防混淆。
         */
        List<TagItemResponse> profileTags,
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
        /**
         * 当前用户今日认可携带的标签（2026-08-19 新增：列表卡片 reaction 区域 chip
         * 活跃态数据源，镜像门店 ReactionBadge.reactedByMe 语义）。个人态实时查询
         * 不缓存；每日一票默认至多 1 枚，多选模式可多枚；未登录/未认可 = 空列表。
         */
        List<String> myTags,
        /**
         * 认可标签聚合（2026-08-19 起<b>全量</b>：列表卡片 reaction 区域 chips 数据源，
         * 同门店 topReactions 无截断契约；前端按近7天计数过滤展示）。
         */
        List<DancerTagStat> topTags,
        /**
         * 累计浏览量（2026-08-15 新增，镜像门店 VenueResponse.viewCount）：
         * qwt_dancer_views 行数（按天按来源去重 PV，含匿名，与 DancerStatsService
         * viewTrend 同源同口径的全量版）。驱动舞伴列表卡片右下角「👁 浏览数」展示；
         * 列表/收藏等卡片展示场景传真实值（批量查询见
         * {@code DancerViewRepository#countByDancerIds}），禁止传默认 0
         * （否则列表卡片浏览量恒为 0，同门店 isHot 历史缺陷模式）。
         */
        long viewCount,
        /**
         * 媒体预览列表（2026-08-24 晚：列表卡片多图预览，消息预览式——单张封面
         * coverPhotoUrl 升级为照片+视频混合的前 N 个 PUBLIC 媒体）。每项已按当前
         * 用户视角组装可见性：免费/已解锁 → url 清晰图；付费未解锁 → 仅 blurUrl
         * 薄码 + 锁语义（unlocked=false），前端渲染薄码 + 锁角标；视频条目带
         * 播放角标。空列表 = 无公开媒体，卡片不渲染预览行。
         */
        List<DancerMediaPreviewResponse> mediaPreviews,
        /**
         * 是否提供线上服务（2026-08-24：存在 ≥1 个在用且类别为 ONLINE_CHAT 的服务）。
         * 驱动列表卡片「线上」胶囊——线上舞伴可不绑定常驻城市（cities 为空），
         * 城市筛选「全部」仍可见（服务范围不限地域）；同时提供线下服务的舞伴
         * 本字段同样为 true（线上可约是独立于城市的服务属性）。
         */
        boolean onlineService,
        /**
         * 相册最近一次更新时间（2026-08-26 晚：列表卡片「最近更新了相册」信号——
         * = 最新一张 PUBLIC 照片 / 短视频的 created_at；无公开媒体 = null）。
         * 前端据 {@code lastContactUpdatedAt} 与本站择最近者、且均在 3 天内才提示。
         */
        LocalDateTime lastAlbumUpdatedAt,
        /**
         * 联系方式最近一次变更时间（2026-08-26 晚：列表卡片「最近更新了联系方式」信号——
         * = Dancer.contactUpdatedAt；从未更新过 = null）。与本站择最近者提示。
         */
        LocalDateTime lastContactUpdatedAt
) {}
