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
 *   <li><b>模板字段 key</b>：data 按 thing1/thing2/time3 填充（消息类型/当前状态/
 *       时间，按模板关键词申请顺序编号）——<b>需与小程序后台模板详情实际 key 核对</b>，
 *       不符时微信报 47003（qwt_wx_subscribe_logs 留痕可定位）；</li>
 *   <li><b>规模假设</b>：关注者个位数~数十人，逐用户串行 HTTP（每用户一次微信
 *       调用，微信 subscribe/send 无批量接口）；量级上来后再转异步队列，当前
 *       同步执行换取链路简单。</li>
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
    private final String templateId;
    private final String miniprogramState;

    public WxSubscribeSendService(
            ObjectMapper objectMapper,
            WechatService wechatService,
            WxSubscribeQuotaRepository quotaRepository,
            WxSubscribeService subscribeService,
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
                // 消息类型（模板关键词 1；key 需与后台模板详情核对，不符报 47003）
                "thing1", Map.of("value", "营业状态变更"),
                // 当前状态（模板关键词 2）=「门店名·状态」——模板无独立门店名字段，
                // 用户的第一个问题永远是「哪家店」，拼进状态字段保住关键信息
                "thing2", Map.of("value", composeStatusText(event.venueName(), event.to())),
                // 时间（模板关键词 3）= 状态变更时刻
                "time3", Map.of("value", LocalDateTime.now().format(TIME_FORMATTER)));

        for (StatusChangeRecipient recipient : recipients) {
            sendToOne(recipient, event.venueId(), accessToken, data);
        }
    }

    /** 单用户下发：解析 errcode → 账本（成功扣减 / 43101 清零）+ 留痕；单点失败不中断其余收件人 */
    private void sendToOne(StatusChangeRecipient recipient, Long venueId,
                           String accessToken, Map<String, Object> data) {
        Map<String, Object> body = Map.of(
                "touser", recipient.getOpenId(),
                "template_id", templateId,
                "page", VENUE_DETAIL_PAGE + venueId,
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
