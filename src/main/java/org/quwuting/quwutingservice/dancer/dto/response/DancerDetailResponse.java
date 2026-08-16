package org.quwuting.quwutingservice.dancer.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.quwuting.quwutingservice.dancer.enums.DancerStatus;
import org.quwuting.quwutingservice.dancer.enums.DancerVerificationStatus;

import java.time.LocalDateTime;
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
        /** 主城市（= cities 第一个；列表/分享/详情 meta 展示位，2026-08-14 多城市） */
        String city,
        /**
         * 多城市列表（2026-08-14 新增，最多 3 个，按选择序；首个 = 主城市）。
         * 编辑页预填回显用；列表筛选/词表消费方仍走主城市/子表聚合，不依赖本字段。
         */
        List<String> cities,
        DancerStatus status,
        /**
         * 信息核验状态（2026-08-14 官方认证——「信息已核验」标识）。
         * 展示规则：VERIFIED 全员可见徽标+说明；PENDING_REVIEW 仅本人/管理员可见
         * 「待复核」（对外语义 = 未认证，认证可回退）；UNVERIFIED 无徽标。
         */
        DancerVerificationStatus verificationStatus,
        /** 最近一次授予认证的时间（仅 VERIFIED 时有值，前端可展示"已于 X 核验"） */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime verifiedAt,
        boolean isMine,
        boolean myRecognizedToday,
        /**
         * 今日认可携带的标签（2026-08-15 单票模型：至多 1 个；旧多标签历史数据可能 >1；
         * 未认可 = 空列表）。驱动详情页认可 chip 的活跃态——前端据此派生
         * "我今日的票是哪一枚表情"，不参与计数展示（计数以 tags 聚合为准）。
         */
        List<String> myTags,
        /**
         * 当前用户是否已收藏（2026-08-14 舞伴收藏）。
         * 服务端权威字段（登录时按收藏表实时判定；匿名/未收藏恒 false）——替代 venue 域
         * 详情页用 URL fav 参数传递收藏态的 hack（分享深链等无参数入口会丢失状态）。
         */
        boolean favorite,
        DancerRecognitionStats stats,
        /** 收到积分总数（target_type='DANCER' 全量，2026-08-10 V2） */
        long pointsReceivedTotal,
        /** 近30天收到积分（驱动舞伴列表次级排序信号） */
        long pointsReceived30d,
        /** 收到礼物聚合（code → 件数，2026-08-12 礼物化：「收获的支持」礼物墙） */
        List<org.quwuting.quwutingservice.points.dto.GiftCountResponse> giftsReceived,
        /** 相册照片（服务层已按可见性过滤：非本人仅 PUBLIC；本人/管理员全量含待审态） */
        List<DancerPhotoResponse> photos,
        List<DancerTagStat> tags,
        List<DancerVenueInfo> venues,
        /**
         * 联系方式（2026-08-14 积分解锁）：<b>仅当无门槛、当前用户已解锁、
         * 或未开启遮挡时返回</b>；有门槛且未解锁返回 null（不下发真实值，防绕过），
         * 经 POST /points/unlock 解锁后返回。本人/管理员恒返回（管理者天然可见）。
         */
        String contact,
        /**
         * 联系方式图片 URL（2026-08-14 新增，二维码等）：与 contact 同一可见性——
         * 有门槛且未解锁时返回 null（不下发真实值，防绕过），解锁后经
         * POST /points/unlock 响应返回（UnlockResponse.contactImageUrl）。
         */
        String contactImageUrl,
        /**
         * 联系方式遮挡开关（2026-08-14，默认 true）：
         * true = 详情页打码展示（无门槛 = 免费遮罩点击直显 / 有门槛 = 积分解锁后显示）；
         * false = 不遮挡直接展示。前端据此渲染遮罩/直显。
         */
        boolean hideContact,
        /** 查看联系方式所需积分（0 = 无门槛；有遮挡且无门槛时点击遮罩直显） */
        int contactCost,
        /** 当前用户是否已解锁联系方式（本人/管理员恒 true；匿名恒 false） */
        boolean contactUnlocked,
        /** 创作者收益计划是否开启（2026-08-14：开启后详情页接入激励视频广告） */
        boolean earningsEnabled,
        /** 激励视频广告位 ID（后端配置 app.dancer-ad.ad-unit-id 下发，前端零硬编码；
         *  未开启时为空串，前端不渲染广告入口） */
        String earningsAdUnitId,
        /** 舞伴累计获得的广告支持次数（收益线下结算依据，"已获得 N 次支持"） */
        long earningsViews
) {}
