package org.quwuting.quwutingservice.wxsubscribe.service;

import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.auth.service.WechatService;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venuestatuswatcher.event.VenueStatusChangedEvent;
import org.quwuting.quwutingservice.wxsubscribe.repository.WxSubscribeQuotaRepository;
import org.quwuting.quwutingservice.wxsubscribe.repository.WxSubscribeQuotaRepository.StatusChangeRecipient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 微信订阅消息发送（「开门状态变更提醒」一次性订阅，2026-09-07 新增，V11，
 * 见 AGENTS.md「微信订阅消息通知」）。
 * <p>
 * 触发链路：门店营业状态实际变更 → {@code VenueStatusWatcherService#notifyStatusChanged}
 * （站内信同点）发布 {@link VenueStatusChangedEvent} → 本类 AFTER_COMMIT 消费——
 * 向「关注该门店且有剩余额度」的用户下发微信服务通知，与站内信互补（站内信需
 * 用户打开小程序才能消费，服务通知是唯一被动触达通道）。
 * <p>
 * 关键约束：
 * <ul>
 *   <li><b>AFTER_COMMIT + 全兜底</b>：通知是锦上添花，任何失败（微信接口异常/
 *       token 失败/额度对账）只记日志，绝不反噬已提交的状态变更主流程；</li>
 *   <li><b>额度模型</b>：一次性订阅额度是用户×模板维度（微信侧不区分门店），
 *       关注者中额度 &gt; 0 者才会被发送（一次 join 查询取齐 openid+额度，最少
 *       DB 往返）；发送成功扣一条，43101 清零对账（账本在 {@link WxSubscribeService}）；</li>
 *   <li><b>access_token 单例</b>：复用 auth {@link WechatService#getAccessToken()}，
 *       遵守 AGENTS.md 36 号「禁第三实例」纪律（多实例各自刷新会互相顶掉 token）；</li>
 *   <li><b>模板字段 key（2026-09-07 22:56 已与后台核对）</b>：data = phrase1（消息
 *       类型，phrase 类 ≤5 字）/ thing2（当前状态，thing 类 ≤20 字）/ time3（时间，
 *       time 类）——与申请模板时的关键词顺序一致，改模板字段布局时必须同步本处；
 *       首测 47003「data.phrase1.value is empty」即旧版按 thing1 填充致 phrase1
 *       缺失（必填校验拒绝整单）；</li>
 *   <li><b>规模假设</b>：关注者个位数~数十人，逐用户串行 HTTP（每用户一次微信
 *       调用，微信 subscribe/send 无批量接口）；量级上来后再转异步队列，当前
 *       同步执行换取链路简单；</li>
 *   <li><b>突发限流（2026-09-08 V13）</b>：额度可无限囤（每次授权 +1），批量状态
 *       更新时若逐事件全发，关注 N 家的用户会一次收到 N 条服务通知 → 骚扰 →
 *       用户关闭订阅（不可逆）。故逐用户过 {@link WxSubscribeBurstLimiter}：
 *       突发窗口（默认 3 分钟）内超过该用户档位（默认 3 条，0 = 不限）的部分
 *       <b>跳过微信</b>——关注者站内信已由 {@code notifyStatusChanged} 发出，
 *       且本条<b>不扣额度</b>。限制的是我们打断用户的次数，不是用户授权额度。</li>
 * </ul>
 */
@Slf4j
@Service
public class WxSubscribeSendService {

    /** 订阅消息下发（POST） */
    private static final String SUBSCRIBE_SEND_URL =
            "https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token={token}";
    /** 状态变更通知落地页（deep link 直达触发门店详情页） */
    private static final String VENUE_DETAIL_PAGE = "pages/venue-detail/venue-detail?id=";
    /** 订阅通知落地标识（2026-09-07）：通知点击进入详情 → 前端自动弹营业状态详情弹窗
     *  承接「看这家店变成什么样」，并据此识别「额度补充」场景（subscribe=1 仅本通知
     *  落地携带，与浏览来源统计参数 from 隔离不复用） */
    private static final String SUBSCRIBE_ENTRY_QUERY = "&subscribe=1";
    /** thing 类字段长度上限（微信 thing 关键词 ≤20 字符，超限报 47003） */
    private static final int THING_MAX_LENGTH = 20;
    /** 「当前状态」字段内门店名与状态的分隔符 */
    private static final String STATUS_SEPARATOR = "·";
    /** 门店名超长截断省略号（占 1 字符预算） */
    private static final String TRUNCATE_SUFFIX = "…";
    /** time 类字段格式（微信支持「2019年10月1日 15:01」形态） */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm");
    /** 微信接口连接/读取超时（对齐 WechatService / WxacodeService） */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    /** 微信「用户未订阅或订阅额度耗尽」错误码 → 本地额度清零对账 */
    private static final int ERRCODE_QUOTA_EXHAUSTED = 43101;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final WechatService wechatService;
    private final WxSubscribeQuotaRepository quotaRepository;
    private final WxSubscribeService subscribeService;
    private final WxSubscribeBurstLimiter burstLimiter;
    private final String templateId;
    private final String miniprogramState;

    public WxSubscribeSendService(
            ObjectMapper objectMapper,
            WechatService wechatService,
            WxSubscribeQuotaRepository quotaRepository,
            WxSubscribeService subscribeService,
            WxSubscribeBurstLimiter burstLimiter,
            @Value("${wechat.subscribe.status-template-id}") String templateId,
            @Value("${wechat.subscribe.miniprogram-state:formal}") String miniprogramState) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
        this.objectMapper = objectMapper;
        this.wechatService = wechatService;
        this.quotaRepository = quotaRepository;
        this.subscribeService = subscribeService;
        this.burstLimiter = burstLimiter;
        this.templateId = templateId;
        this.miniprogramState = miniprogramState;
    }

    /**
     * 消费状态变更事件（AFTER_COMMIT：主事务提交后才执行，失败不回滚不影响主流程）。
     * 全方法兜底 try-catch——监听器抛异常只污染事件多播线程，但留日志便于排障。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVenueStatusChanged(VenueStatusChangedEvent event) {
        try {
            sendStatusChange(event);
        } catch (Exception e) {
            log.error("wx subscribe notify failed (venueId={}, to={}): {}",
                    event.venueId(), event.to(), e.getMessage(), e);
        }
    }

    /** 发送主流程：收件人筛选（有额度）→ token → 逐用户下发 → 扣减/对账 + 留痕 */
    private void sendStatusChange(VenueStatusChangedEvent event) {
        List<Long> userIds = event.watcherUserIds();
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        List<StatusChangeRecipient> recipients =
                quotaRepository.findStatusChangeRecipients(templateId, userIds);
        if (recipients.isEmpty()) {
            return; // 无有额度关注者：连 token 都不取（省微信 API 调用）
        }
        String accessToken = wechatService.getAccessToken();

        Map<String, Object> data = Map.of(
                // 消息类型 = phrase1（模板关键词 1，2026-09-07 22:56 用户后台核对：
                // phrase 类型 ≤5 字，故用 4 字「营业变更」，勿填 6 字超限）
                "phrase1", Map.of("value", "营业变更"),
                // 当前状态 = thing2（模板关键词 2）=「门店名·状态」——模板无独立门店
                // 名字段，用户的第一个问题永远是「哪家店」，拼进状态字段保住关键信息；
                // thing 类型 ≤20 字（composeStatusText 内截断守卫）
                "thing2", Map.of("value", composeStatusText(event.venueName(), event.to())),
                // 时间 = time3（模板关键词 3）= 状态变更时刻
                "time3", Map.of("value", LocalDateTime.now().format(TIME_FORMATTER)));

        for (StatusChangeRecipient recipient : recipients) {
            // 突发限流（2026-09-08 V13）：窗口内该用户已收满其档位条数 → 跳过微信。
            // 注意「不扣额度、不发微信」——关注者站内信已在 notifyStatusChanged 逐
            // watcher 发出，信息不丢，只是不打断用户。
            if (!burstLimiter.allow(recipient.getUserId(), recipient.getBatchLimit())) {
                continue;
            }
            sendToOne(recipient, event.venueId(), accessToken, data);
        }
    }

    /** 单用户下发：解析 errcode → 账本（成功扣减 / 43101 清零）+ 留痕；单点失败不中断其余收件人 */
    private void sendToOne(StatusChangeRecipient recipient, Long venueId,
                           String accessToken, Map<String, Object> data) {
        Map<String, Object> body = Map.of(
                "touser", recipient.getOpenId(),
                "template_id", templateId,
                "page", VENUE_DETAIL_PAGE + venueId + SUBSCRIBE_ENTRY_QUERY,
                "miniprogram_state", miniprogramState,
                "lang", "zh_CN",
                "data", data);
        Integer errcode = null;
        boolean delivered = false;
        try {
            String respBody = restClient.post()
                    .uri(SUBSCRIBE_SEND_URL, accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);
            SubscribeSendResponse resp = objectMapper.readValue(respBody, SubscribeSendResponse.class);
            errcode = resp.errcode() == null ? -1 : resp.errcode();
            delivered = errcode == 0;
            if (!delivered) {
                log.warn("wx subscribe send failed (userId={}, venueId={}): errcode={}, errmsg={}",
                        recipient.getUserId(), venueId, resp.errcode(), resp.errmsg());
            }
        } catch (Exception e) {
            // 网络/解析层失败：留痕 errcode=null，不影响其余收件人
            log.warn("wx subscribe send error (userId={}, venueId={}): {}",
                    recipient.getUserId(), venueId, e.getMessage());
        }
        try {
            subscribeService.recordDelivery(recipient.getUserId(), venueId, templateId, delivered, errcode);
        } catch (Exception e) {
            log.error("wx subscribe recordDelivery failed (userId={}, venueId={}): {}",
                    recipient.getUserId(), venueId, e.getMessage(), e);
        }
    }

    /**
     * 「当前状态」字段组装：「门店名·状态」（如「星海舞厅·暂停营业」）。
     * thing 字段 ≤{@value THING_MAX_LENGTH} 字符，超长截门店名（保「…」+ 状态——
     * 状态本身最长 4 字必须完整，门店名尾巴用省略号示意）。
     */
    private String composeStatusText(String venueName, VenueStatus status) {
        String statusText = status.getDisplayName();
        int nameBudget = THING_MAX_LENGTH - statusText.length() - STATUS_SEPARATOR.length();
        String name = venueName == null ? "" : venueName;
        if (name.length() > nameBudget) {
            int cut = Math.max(nameBudget - TRUNCATE_SUFFIX.length(), 1);
            name = name.substring(0, cut) + TRUNCATE_SUFFIX;
        }
        return name + STATUS_SEPARATOR + statusText;
    }

    /** 微信 subscribe/send 响应体 */
    private record SubscribeSendResponse(Integer errcode, String errmsg) {
    }
}
