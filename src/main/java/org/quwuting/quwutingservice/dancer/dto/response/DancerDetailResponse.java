package org.quwuting.quwutingservice.dancer.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.quwuting.quwutingservice.dancer.enums.DancerStatus;
import org.quwuting.quwutingservice.dancer.enums.DancerVerificationStatus;
import org.quwuting.quwutingservice.tagdict.dto.response.TagItemResponse;

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
        /**
         * 当前所在城市（2026-08-26 新增，V48；从已选城市中再选中一个——
         * 用户在「解锁联系方式-是否同城」时据此判断是否与舞伴同城）。
         * 可空 = 未填城市（纯线上舞伴）/存量舞伴（前端回退主城市 city 展示）。
         */
        String currentCity,
        /**
         * 资料标签（2026-08-24 管理员设置，字典化）：TagItemResponse 列表
         * （text + description——详情页长按/点击弹说明的权威文案），按字典排序。
         * 空列表 = 无标签，不渲染标签区块。与「认可标签聚合」（tags，用户行为产生）
         * 语义独立，字段名显式区分防混淆。
         */
        List<TagItemResponse> profileTags,
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
        /** 近30天收到积分（2026-08-26 起不再参与列表排序——排序 tie-break 改用近30天收藏数） */
        long pointsReceived30d,
        /** 收到礼物聚合（code → 件数，2026-08-12 礼物化：「收获的支持」礼物墙） */
        List<org.quwuting.quwutingservice.points.dto.GiftCountResponse> giftsReceived,
        /** 相册照片（服务层已按可见性过滤：非本人仅 PUBLIC；本人/管理员全量含待审态） */
        List<DancerPhotoResponse> photos,
        List<DancerTagStat> tags,
        List<DancerVenueInfo> venues,
        /**
         * 服务范围（2026-08-24 联系方式获取质量优化）：admin 录入的结构化服务列表
         * （类别/短标签/计费方式/地点范围/预约要求/规则）——详情页「服务范围」卡
         * 展示 + 需求弹层 chip 数据源。空列表 = 舞伴未录入服务（前端不渲染服务卡，
         * 需求弹层按"服务不可选"降级——见 AGENTS.md「舞伴服务与联系方式需求」）。
         */
        List<DancerServiceResponse> services,
        /**
         * 舞伴是否填写了联系方式（2026-08-24 晚 按需实时查询改版）：contact 或
         * contactImageUrl 任一非空即 true。详情接口对普通用户<b>恒不下发真实值</b>
         * （见 contact 注释），前端据此决定「联系方式」行是否渲染入口——真实值
         * 仅经 POST /points/unlock 点击获取时实时返回，此字段是普通用户侧唯一
         * 的"是否可获取"权威依据。本人/管理员（编辑回显）同时可读 contact。
         */
        boolean hasContact,
        /**
         * 联系方式脱敏预览（2026-08-29 打码直显，docs/agents/05-dancer-contact-reveal.md）：
         * 未配置服务范围（services 为空，不用填写邀约单）且非邀约中转的舞伴——详情页
         * 直接展示联系方式（<b>默认打码</b>），本字段 = 打码文案（文字联系方式保留
         * 首 2 尾 2 字符、中间 4 星；仅图片联系方式 = null，前端渲染马赛克遮挡块）。
         * 满足判定时<b>所有视角统一下发</b>（含本人/管理员——统一文字打码类型，避免
         * 无脱敏值被前端误判为图片马赛克）；其余场景恒 null。真实值恒不随本字段
         * 下发，点击揭示经 POST /points/unlock 实时查询（无门槛免费/有门槛扣费）。
         */
        String contactMasked,
        /**
         * 联系方式（2026-08-24 晚 改版：改为「用户获取时才实时查询」）：
         * 详情接口对<b>普通用户恒不下发真实值</b>（null）——防止内容随详情泄漏；
         * 用户点击「获取联系方式」时经 POST /points/unlock 实时查询返回（无门槛
         * 恒免费、有门槛每日首免、已解锁幂等，见 PointsService#unlock）。本人/
         * 管理员恒返回（dancer-edit 编辑回显 + 管理者天然可见），前端同样默认
         * 打码展示（2026-08-29 隐私回归：所有视角统一打码，点击揭示本地直用）。
         */
        String contact,
        /**
         * 联系方式图片 URL（2026-08-14 新增，二维码等）：与 contact 同一可见性——
         * 2026-08-24 晚 改版后详情接口对普通用户恒为 null（不下发真实值，防绕过），
         * 点击获取时经 POST /points/unlock 响应返回（UnlockResponse.contactImageUrl）；
         * 本人/管理员恒返回（前端默认打码展示，点击揭示后展示图片）。
         */
        String contactImageUrl,
        /**
         * 联系方式遮挡开关（2026-08-14，默认 true）：
         * true = 详情页打码展示（无门槛 = 免费获取 / 有门槛 = 积分解锁后获取）；
         * false = 不遮挡直接展示。2026-08-24 晚 改版后真实值一律按需经
         * POST /points/unlock 实时查询，本字段仅驱动前端入口文案/锁态。
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
        long earningsViews,
        /**
         * 加好友需告知位置（2026-08-26，per-dancer 开关）：
         * true = 需求确认层须二选一表态「同城 / 非同城·自行前往」且必填
         * （UserLocationOption，随需求记录落库）——面向需确认用户能否到达
         * 服务地点的舞伴（服务范围 location_scope 配套）；false = 不出现该
         * 字段（绝大多数舞伴，零影响）。用户无关，随详情下发。
         */
        boolean requireUserLocation,
        /**
         * 邀约中转开关（2026-08-26，22 号文档；per-dancer，用户无关，随详情下发）：
         * true = 该舞伴联系方式获取走「管理员中转 + 舞伴批准」流程（客人提交邀约后
         * 不立即拿微信，unlock 返回 PENDING）。前端详情页/获取联系方式页据此派生
         * 文案与流程；dancer-edit 回显。
         */
        boolean contactRelay,
        /** 24h 无回复自动降级策略（2026-08-26；仅 contactRelay 有意义；dancer-edit 回显） */
        boolean autoRelease,
        /**
         * 相册最近一次更新时间（2026-08-26 晚：详情页「最近更新了相册」信号——
         * = 最新一张 PUBLIC 照片 / 短视频的 created_at；无公开媒体 = null）。
         * 前端据 {@code lastContactUpdatedAt} 与本站择最近者、且均在 3 天内才提示。
         */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime lastAlbumUpdatedAt,
        /**
         * 联系方式最近一次变更时间（2026-08-26 晚：详情页「最近更新了联系方式」信号——
         * = Dancer.contactUpdatedAt；从未更新过 = null）。与本站择最近者提示。
         */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime lastContactUpdatedAt,
        /**
         * 我的进行中邀约摘要（2026-08-27，V56，docs/agents/25「邀约生命周期」）：
         * 当前用户与该舞伴最近一条邀约——详情页「进行中邀约」卡数据源（客人返回
         * 详情页不再"邀约单消失"，恒可见最近一次邀约的时间/状态/被查看/履约入口）。
         * 登录用户且与该舞伴有过邀约时非空；匿名/无邀约 = null。用户相关字段
         * （不入公共缓存，实时轻量查询）；本人邀约记录天然隔离，仅本人可见。
         */
        RecentDemand recentDemand
) {
    /**
     * 进行中邀约轻量摘要（2026-08-27，V56）：只下发驱动展示的最小字段——
     * id（跳邀约详情）、createdAt（「N 天前」）、status（徽标）、shareOpened
     * （「TA 已查看你的邀约」，分享闭环自动感知）、fulfilled（履约入口态）。
     * 联系方式/验证消息等敏感内容一律在邀约详情页（GET /points/demands/{id}）
     * 另行下发，此处零泄漏。
     */
    public record RecentDemand(
            Long id,
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdAt,
            String status,
            boolean shareOpened,
            boolean fulfilled
    ) {}
}
