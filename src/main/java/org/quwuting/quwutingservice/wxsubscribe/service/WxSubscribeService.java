package org.quwuting.quwutingservice.wxsubscribe.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.wxsubscribe.dto.response.WxSubscribeStatusResponse;
import org.quwuting.quwutingservice.wxsubscribe.entity.WxSubscribeLog;
import org.quwuting.quwutingservice.wxsubscribe.entity.WxSubscribeQuota;
import org.quwuting.quwutingservice.wxsubscribe.repository.WxSubscribeLogRepository;
import org.quwuting.quwutingservice.wxsubscribe.repository.WxSubscribeQuotaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 微信订阅消息额度/留痕账本（2026-09-07 新增，V11）。
 * <p>
 * 与 {@link WxSubscribeSendService}（微信外呼 + 事件消费）分离：本类只做
 * <b>事务性账本写</b>（授权累加 / 发送扣减 / 43101 对账清零 / 留痕），每方法
 * 独立短事务——AFTER_COMMIT 监听器逐用户调用时各开新事务，无外层事务上下文。
 * SQL 全部原子写（upsert / 带守卫 UPDATE），禁读改写（并发授权与发送竞争）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WxSubscribeService {

    private final WxSubscribeQuotaRepository quotaRepository;
    private final WxSubscribeLogRepository logRepository;

    /**
     * 记录一次授权（前端 requestSubscribeMessage 返回 accept 后上报触发）。
     * upsert 累加一条额度；幂等语义（重复上报多计额度由微信侧真相源对账收敛：
     * 发送 43101 即清零，虚增额度不会造成多发）。
     */
    @Transactional
    public void recordGrant(Long userId, String templateId) {
        quotaRepository.upsertGrant(userId, templateId, LocalDateTime.now());
    }

    /**
     * 当前用户订阅额度状态（GET /user/wx-subscribe-status 数据源）：无记录 = 从未
     * 授权（granted/available 均 0，前端「微信提醒」子项未开启态）。
     */
    @Transactional(readOnly = true)
    public WxSubscribeStatusResponse queryStatus(Long userId, String templateId) {
        WxSubscribeQuota quota = quotaRepository
                .findByUserIdAndTemplateIdAndDeletedFalse(userId, templateId).orElse(null);
        if (quota == null) {
            return new WxSubscribeStatusResponse(templateId, 0, 0);
        }
        return new WxSubscribeStatusResponse(templateId,
                quota.getAvailableCount(), quota.getGrantedTotal());
    }

    /**
     * 记录一次发送结果并维护额度（发送成功扣减 / 43101 清零对账 / 其他失败不动）。
     * 留痕与额度更新同一事务原子提交。
     * <p>
     * <b>REQUIRES_NEW（2026-09-07 生产实测修复）</b>：本方法由
     * {@code WxSubscribeSendService#onVenueStatusChanged}（@TransactionalEventListener
     * AFTER_COMMIT 同步回调，同一请求线程）调用——afterCommit 阶段外层事务已提交但
     * 事务同步尚未清理（doCleanupAfterCompletion 未执行），REQUIRED 传播会误判
     * 「已有事务」而<b>加入已提交的失效事务</b>，Hibernate flush/commit 全部静默丢失
     * （无异常、无日志、无落库——生产首测发送失败 47003 后 qwt_wx_subscribe_logs 为空、
     * 额度未扣正是此现象）。REQUIRES_NEW 强制挂起并开独立事务，写入必然落库。
     *
     * @param delivered 微信 errcode == 0（服务通知已受理下发）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDelivery(Long userId, Long venueId, String templateId,
                               boolean delivered, Integer errcode) {
        LocalDateTime now = LocalDateTime.now();
        if (delivered) {
            quotaRepository.deductOne(userId, templateId, now);
        } else if (errcode != null && errcode == 43101) {
            // 用户未订阅/额度耗尽：本地清零对账（微信侧额度是真相源，本地计数防漂移）
            quotaRepository.clearAvailable(userId, templateId, now);
        }
        WxSubscribeLog logEntry = new WxSubscribeLog();
        logEntry.setUserId(userId);
        logEntry.setVenueId(venueId);
        logEntry.setTemplateId(templateId);
        logEntry.setSuccess(delivered);
        logEntry.setErrcode(errcode);
        logRepository.save(logEntry);
    }
}
