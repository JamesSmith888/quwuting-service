# 37 · 微信订阅消息（开门状态变更提醒）

> 2026-09-07 新增（V11）。门店营业状态变更的**服务通知触达通道**，与站内信互补。
> 前端权威文档 = quwuting 仓 `docs/agents/41-wx-subscribe-notify.md`（时机纪律 / 授权交互）。

## 一、业务定位

- **痛点**：舞厅营业不稳定（随时暂停/恢复），「今天开不开门」是用户出门决策的第一信息；
  站内信/收藏角标要求用户打开小程序才能消费——「开门了」的价值恰恰在用户**未打开小程序
  的瞬间**，微信服务通知是唯一被动触达通道。
- **伪需求判断结论（2026-09-07 拍板）**：非伪需求。额度制（授权几条发几条）天然零骚扰、
  试错成本低；上线后盯三数：授权率（accept/弹窗）、额度消耗率、状态变更频率。

## 二、额度模型（微信机制约束）

- 一次性订阅额度是**用户 × 模板**维度，微信侧不区分门店——「订阅某门店」做不到门店级隔离。
  收藏多家店共享一个额度池，任意收藏门店状态变更消耗池内额度，deep link（`page=venue-detail?id=X`）
  跳触发门店详情页。
- 用户勾选「总是保持以上选择 + 允许」后，后续 `wx.requestSubscribeMessage` 静默 accept 并
  **累计额度**（每次收藏时调用即自动滚存）；「总是保持 + 拒绝」后调用直接 fail 不弹窗（零打扰）。

## 三、数据与链路

- **V11 双表**：
  - `qwt_wx_subscribe_quota`（额度账本，UNIQUE(user_id, template_id)，available_count/
    granted_total/last_granted_at）；upsert 累加（封顶 100）/ 发送扣减 / 43101 清零对账
    全部原子 SQL（`WxSubscribeQuotaRepository`）；
  - `qwt_wx_subscribe_logs`（发送留痕，每用户每次一行 success+errcode，运营复盘漏斗）。
- **授权上报**：`POST /user/wx-subscribe-grants`（需登录；仅认配置模板，其余静默忽略不落库）。
- **发送链路**：`VenueStatusWatcherService#notifyStatusChanged`（站内信同点）发布
  `VenueStatusChangedEvent`（venueId/venueName/from/to/watcherUserIds 快照）→
  `WxSubscribeSendService` `@TransactionalEventListener(AFTER_COMMIT)`：
  1. 一次 join 查询取齐「关注该门店 + 额度>0」用户的 openid+额度（最少 DB 往返）；
     无收件人连 token 都不取；
  2. 逐用户 POST `/cgi-bin/message/subscribe/send`（access_token 复用 auth `WechatService`
     单例——**禁第三实例**，AGENTS.md 36 号纪律）；成功扣减额度，43101 清零对账，留痕；
  3. AFTER_COMMIT + 全兜底 try-catch：通知失败绝不反噬已提交的状态变更主流程。
- **消息内容**：thing1「营业状态变更」/ thing2「门店名·状态」（模板无独立门店名字段，
  拼进状态字段；thing ≤20 字超长截门店名保「…」+状态）/ time3「yyyy年M月d日 HH:mm」。
- **规模假设**：关注者个位数~数十，串行 HTTP；量大再转异步队列。

## 四、配置

```yaml
wechat:
  subscribe:
    status-template-id: IDDzJtOxuOu7iX-7UegZfnw7yOwEP5QHqJoLOxi5TxQ  # 与小程序后台模板一致
    miniprogram-state: formal   # formal/trial/develop，联调切 trial
```

## 五、SQL 踩坑记录（WxSubscribeSqlTest 真实库抓到）

- `VALUES (...) AS new ON DUPLICATE KEY UPDATE col = new.col`（8.0.19+ 行别名语法）在
  RDS MySQL 8.0.36 报 `Column 'available_count' in field list is ambiguous`——UPDATE
  子句裸列名歧义；回退传统 `VALUES(col)` 语法（实测可用，deprecation 警告无害）。
- 验证载体：`WxSubscribeSqlTest`（`-Drun.db.tests=true`，类级 @Transactional 回滚不污染库）。

## 六、上线 checklist

1. 小程序后台确认模板「开门状态变更提醒」关键词**字段 key 顺序**与 data（thing1/thing2/time3）
   一致——不符报 47003（logs 留痕可定位）；
2. 体验版联调：`miniprogram-state: trial` 收到通知后切回 formal；
3. 「订阅消息」开关在 mp 后台正常（模板未封禁）；
4. 数据观察：`qwt_wx_subscribe_logs` success 率 + quota granted/available 比例。
