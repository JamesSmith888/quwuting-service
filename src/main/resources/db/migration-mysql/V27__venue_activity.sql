-- ============================================================================
-- V27: 门店营业活动（2026-09-15，quwuting 仓 docs/agents/49-venue-activities.md）
--
-- 根因：门店的优惠活动此前没有任何结构化载体。老板在门店群/纸质海报上发布
-- 「双节双时段男女门票买一送一 13:00-13:45 / 18:00-19:00」，平台侧只能塞进
-- 自由文本——而活动里**真正必须被程序理解的部分**（什么时候生效、什么时候自动
-- 过期）无法表达 ⇒ 活动要么永不亮（无法派生"此刻命中"），要么过期后长期挂在
-- 页面上变成虚假承诺（用户到店兑现不了）。二者都是信任损伤。
--
-- 设计要点：
-- ① **只给「调度」做策略模式，不给「活动语义」做**（本表的核心决策）：
--    活动语义是无限的（今天买一送一 / 明天送啤酒 / 后天请嘉宾），为它做策略
--    必然退化成活动模板 DSL；时间形态是有限的，两层组合即可覆盖绝大多数形态。
--    必须被程序理解的只有三样：调度、权益类别（枚举，供列表页 ≤4 字短标签）、
--    核销方式（枚举，决定用户侧动作）。其余（条件/备注）全是自由文本——
--    仅做展示，不进任何判定。
-- ② 两层调度：outer_type 定「活动有效期」（决定何时自动下线），inner_type 定
--    「日内/周内生效窗口」（决定此刻是否命中）。windows / weekday_mask 是变长
--    结构化列表 → JSON 字符串列，与 qwt_venues.business_hours 同一模式。
-- ③ **跨夜契约**：end < start 表示结束于次日凌晨（同 BusinessHoursEntry 约定）。
--    舞厅营业普遍开到凌晨 02:00，禁按「start <= now <= end」直接比较，否则
--    跨零窗口恒不命中、活动永不亮——这是本域最容易被忽略的一处。
-- ④ 活动到期由 30s 调度批量强转 OFFLINE（同公告域 offline_at 心智，权威在后端、
--    前端零分支）；**禁止在查询时过滤时效**，否则"单点状态机"原则被破坏。
-- ⑤ 无外键（全库约定）；venue_id 语义引用。
-- ⑥ 打卡表是「活动核销计数」的唯一落点，**只用于与门店对账，不参与热度公式**——
--    热度四问判据的「难伪造」一关过不了（同人在店反复打卡零成本，而地理围栏
--    又违背 dancer 地址域「克制采集、避免精确坐标」的既定立场）。
--    唯一键 (activity_id, user_id, activity_date) = 每人每活动每日一次，幂等靠它兜底。
-- ⑦ 列类型必须与实体 Java 类型匹配（V13/V14 tinyint×Integer 启动期事故先例）：
--    枚举列一律 varchar + EnumType.STRING；日期列 date ↔ LocalDate；
--    时段列 varchar ↔ String（JSON 原文，服务端不解析也不强 schema）。
-- ============================================================================

CREATE TABLE qwt_venue_activities (
    id bigint NOT NULL AUTO_INCREMENT,
    created_at datetime(6),
    updated_at datetime(6),
    deleted tinyint(1) NOT NULL,
    venue_id bigint NOT NULL,
    title varchar(60) NOT NULL,
    benefit_kind varchar(24) NOT NULL,
    badge_label varchar(8) NOT NULL,
    benefit_summary varchar(500),
    redemption_mode varchar(24) NOT NULL,
    redemption_hint varchar(200),
    platform_addon varchar(200),
    outer_type varchar(24) NOT NULL,
    start_date date,
    end_date date,
    weekday_mask varchar(16),
    windows varchar(500),
    status varchar(16) NOT NULL,
    sort_weight int NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY qwt_idx_venue_activity_venue (venue_id, status),
    KEY qwt_idx_venue_activity_expire (status, end_date)
);

CREATE TABLE qwt_venue_activity_checkins (
    id bigint NOT NULL AUTO_INCREMENT,
    created_at datetime(6),
    updated_at datetime(6),
    deleted tinyint(1) NOT NULL,
    activity_id bigint NOT NULL,
    venue_id bigint NOT NULL,
    user_id bigint NOT NULL,
    activity_date date NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT qwt_uk_venue_activity_checkin UNIQUE (activity_id, user_id, activity_date)
);
