package org.quwuting.quwutingservice.wxsubscribe.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;

import java.time.LocalDateTime;

/**
 * 微信订阅消息额度账本（2026-09-07 新增，V11，见 AGENTS.md「微信订阅消息通知」）。
 * <p>
 * 微信机制约束：一次性订阅额度是<b>用户 × 模板</b>维度（微信侧不区分门店），
 * 故本表不挂 venue_id——用户在收藏门店时授权 N 次 = N 条发送额度，任意收藏
 * 门店营业状态变更均消耗池内额度发送（deep link 跳触发门店详情页）。
 * <p>
 * 唯一约束 (user_id, template_id)：upsert 累加（{@code ON DUPLICATE KEY UPDATE}，
 * 见 Repository）；发送扣减 / 43101 清零均走原子 UPDATE（禁读改写）。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_wx_subscribe_quota")
public class WxSubscribeQuota extends BaseEntity {

    /** 用户 ID */
    @Column(nullable = false)
    private Long userId;

    /** 订阅消息模板 ID（当前仅「开门状态变更提醒」一个，保留维度支持未来多模板） */
    @Column(nullable = false, length = 64)
    private String templateId;

    /** 剩余可发条数（授权 +1 / 发送成功 -1 / 43101 用户未订阅清零） */
    @Column(nullable = false)
    private int availableCount;

    /** 历史授权总条数（运营看授权漏斗用，只增不减） */
    @Column(nullable = false)
    private int grantedTotal;

    /** 最近一次授权时刻 */
    @Column
    private LocalDateTime lastGrantedAt;
}
