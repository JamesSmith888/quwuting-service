-- 微信订阅消息额度与发送留痕（「开门状态变更提醒」2026-09-07 新增）
--
-- 背景：收藏门店用户在收藏动作时经 wx.requestSubscribeMessage 授权一次性订阅
-- 模板「开门状态变更提醒」（模板 ID 走配置 wechat.subscribe.status-template-id，
-- 不入库硬编码）；门店营业状态实际变更时（VenueStatusWatcherService#
-- notifyStatusChanged 挂点）向「关注该门店且有剩余额度」的用户下发微信服务通知，
-- 与既有站内信（VENUE_STATUS_CHANGED）互补——站内信需用户打开小程序才能消费，
-- 服务通知是唯一被动触达通道（「开门了」的价值恰恰在用户未打开小程序的瞬间）。
--
-- 额度模型（微信机制约束）：一次性订阅额度是「用户 × 模板」维度（微信侧不区分
-- 门店），故额度池不挂 venue_id——用户授权 N 次 = N 条发送额度，任意收藏门店
-- 状态变更均消耗池内额度发送（deep link 跳触发门店详情页）。
--
-- qwt_wx_subscribe_quota：额度账本（upsert 累加，available_count = 可发条数，
-- granted_total = 历史授权总条数，运营看授权漏斗用）。发送扣减与 43101 清零
-- 见 WxSubscribeQuotaRepository（原子 UPDATE，禁读改写）。
-- available_count 上限 100（upsert LEAST 守卫）：正常用户授权频次远低于此，
-- 防异常/恶意上报虚增额度导致发送时反复打 43101 浪费微信 API 调用。
--
-- qwt_wx_subscribe_logs：发送留痕（每用户每次发送一行，success + errcode），
-- 运营复盘授权→触达漏斗与 data 字段核对（模板字段 key 为 thing1/thing2/time3，
-- 需与小程序后台模板详情核对，见 WxSubscribeService 注释）。
--
-- 审计列（created_at/updated_at/deleted）对齐 V1 baseline 全表统一风格
-- （BaseEntity 四列，Java 侧写入）；唯一键 (user_id, template_id) 兼作
-- user_id 前缀查询索引（发送侧按 user_id IN (...) 取 openid+额度，一次往返）。

CREATE TABLE qwt_wx_subscribe_quota (
    id bigint NOT NULL AUTO_INCREMENT,
    created_at datetime(6),
    updated_at datetime(6),
    deleted tinyint(1) NOT NULL,
    user_id bigint NOT NULL,
    template_id varchar(64) NOT NULL,
    available_count int NOT NULL DEFAULT 0,
    granted_total int NOT NULL DEFAULT 0,
    last_granted_at datetime(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_wx_subscribe_quota_user_template (user_id, template_id)
);

CREATE TABLE qwt_wx_subscribe_logs (
    id bigint NOT NULL AUTO_INCREMENT,
    created_at datetime(6),
    updated_at datetime(6),
    deleted tinyint(1) NOT NULL,
    user_id bigint NOT NULL,
    venue_id bigint NOT NULL,
    template_id varchar(64) NOT NULL,
    success tinyint(1) NOT NULL,
    errcode int,
    PRIMARY KEY (id),
    KEY qwt_idx_wx_subscribe_logs_user (user_id),
    KEY qwt_idx_wx_subscribe_logs_venue (venue_id)
);
