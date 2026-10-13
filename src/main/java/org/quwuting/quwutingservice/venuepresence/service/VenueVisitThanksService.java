package org.quwuting.quwutingservice.venuepresence.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.message.enums.MessageType;
import org.quwuting.quwutingservice.message.service.MessageService;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venue.service.HeatAccountExclusionService;
import org.quwuting.quwutingservice.venuepresence.dto.response.VisitThanksResponse;
import org.quwuting.quwutingservice.venuepresence.dto.response.VisitThanksStatusResponse;
import org.quwuting.quwutingservice.venuepresence.repository.VenueVisitThankRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 到店足迹感谢服务（2026-10-13，V50；docs/agents/52-venue-presence.md「到访感谢」节）。
 * <p>
 * <b>定位</b>：还一笔已说出口的债——列表卡片「感谢 N 位舞友 · N 次到过这附近的足迹」里的
 * 「感谢」此前没有任何动作能力，贡献者（允许采集的舞友）也拿不到回音。本服务给用户一个
 * <b>零成本、指向人群而非个人</b>的致谢动作，并把感谢以匿名站内信送达贡献者
 * （人际认可层，与 27 号行级点赞同族；对「隐私让渡型贡献」只配情感回馈、⛔ 不配积分——
 * 到店足迹首问承诺「这项功能不产生任何收益」，且敏感信息同意不得以利益诱导）。
 *
 * <h3>机制（全部照抄已验证范式，不自创）</h3>
 * <ul>
 *   <li><b>对象 = 人群不是个人</b>：一次点击感谢「该店全部尚未感谢过的贡献者」，用户不挑选、
 *       也看不到名单（任何接口都不返回「谁到过这家店」——52 号「永不公开」裁决）；
 *       「这 N 位」的 N 与卡片数字同源（{@link VenuePresenceService#nearbyContributors}，
 *       ⛔ 禁另算一套——「展示口径一源」纪律）；</li>
 *   <li><b>幂等 = 唯一键</b>：三元组 (venue, thankUser, target) 全量唯一 + 无取消动作，
 *       同一对永不发第二次；重复点击由 {@code ON DUPLICATE KEY} 吸收、只对首次插入
 *       （affected==1）履行被感谢通知义务（对齐 {@code CrowdReportLikeService}）；
 *       <b>不能感谢自己</b>（贡献者集合先去掉自己，与点赞「自赞放开」的分界：赞的对象是
 *       内容（自赞=自我确认无害），感谢的对象是他人（自赞无语义））；</li>
 *   <li><b>通知合并没有时间分界</b>：同一收件人在同一门店的未读感谢合并成一条
 *       「有 N 位舞友感谢了你」（{@code MessageService#createOrMergeUnread} 重载）——
 *       「这家店的感谢」读到之前天然是一件事，不按时间拆（与点赞按上报行分界刻意不同，
 *       判据见该重载注释）；</li>
 *   <li><b>频控</b>：每用户每店 60s（Caffeine，进程内）——幂等已保证不重复通知，
 *       此限只防连点造成的请求风暴；上限于法无据地收紧会伤正常路径，放长又无意义
 *       （一次点击即答完该店全部）；</li>
 *   <li><b>上限</b> = {@link #MAX_TARGETS_PER_CLICK}（防数据异常一次写爆；按最近到访
 *       优先截断。现网单店贡献者最大个位数、天花板几十——上限永不在常态触达）。</li>
 * </ul>
 *
 * <h3>🚫 红线（与行级点赞同款，见实体注释）</h3>
 * 感谢数永不进任何算法（热度公式 / 到访项 / 可信度 / 角标）、不产生积分、
 * 不引入任何新的身份暴露（站内信不点名感谢者；接口不返回名单）。
 *
 * <h3>归属边界（为什么读排除集合要经本类的 {@link #displayExclusions}）</h3>
 * 「被感谢的人」与「卡片数字背后的人」必须逐人相等 ⇒ 排除账号集合必须与
 * {@code VenueVisitBadgeService#displayExclusions} 同源（同一个
 * {@link HeatAccountExclusionService} 调用、现状为空集）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VenueVisitThanksService {

    /**
     * 单次点击的感谢人数上限（防「数据异常 + 并集口径出错」把一次点击写爆；
     * 按最近到访优先截断）。现网单店贡献者最大 6 人（2026-10-13 生产只读），
     * 设计天花板为几十——上限只作保险丝，不作为限流手段（真正的防骚扰靠幂等 + 频控）。
     */
    private static final int MAX_TARGETS_PER_CLICK = 50;

    /** 每用户每店感谢频控窗口（ms）：软限流，防连点请求风暴 */
    private static final long RATE_LIMIT_WINDOW_MS = 60_000L;

    /** 站内信 relatedType（VENUE = 深链门店详情页；与 MessageType 注释约定一致） */
    private static final String RELATED_TYPE_VENUE = "VENUE";

    /** 被感谢站内信标题（单点常量：与前端消息中心的类型标签独立——那里按 code 渲染） */
    static final String THANKED_MESSAGE_TITLE = "收到舞友的感谢";

    private final VenuePresenceService venuePresenceService;
    private final VenueVisitThankRepository thankRepository;
    private final VenueRepository venueRepository;
    private final MessageService messageService;
    private final HeatAccountExclusionService heatAccountExclusionService;

    /**
     * 感谢频控（每用户每店）：键 = {@code userId:venueId}，写入即占坑、60s 自动过期。
     * 用 {@code asMap().putIfAbsent} 的原子语义（返回既有值 = 窗口内已点过），
     * 与到访写入的滑动窗口（{@code VenuePresenceService#exceedsWriteRate}）不同——
     * 那个限速率、这个限「同一动作的重复表达」，窗口固定即可。
     */
    private final Cache<String, Boolean> thanksRateCache = Caffeine.newBuilder()
            .expireAfterWrite(RATE_LIMIT_WINDOW_MS, TimeUnit.MILLISECONDS)
            .maximumSize(10_000)
            .build();

    /**
     * 感谢状态（浮层按钮态的权威读数）：可被感谢人数 + 我尚未感谢的人数。
     * 无需登录性以外的副作用；门店不存在 → 1022（到访域同码，见 12 号错误码表）。
     */
    @Transactional(readOnly = true)
    public VisitThanksStatusResponse status(Long venueId, Long userId) {
        requireVenue(venueId);
        Map<Long, LocalDateTime> contributors = contributorsFor(venueId, userId);
        Collection<Long> thankedTargets = thankRepository.findTargetUserIds(venueId, userId);
        return new VisitThanksStatusResponse(
                contributors.size(), pendingCount(contributors.keySet(), thankedTargets));
    }

    /**
     * 感谢一次（POST 的实现）：感谢该店全部尚未感谢过的贡献者，逐对幂等写入；
     * 仅「首次感谢」的对发送被感谢站内信（同事务）。
     * <p>
     * 判定序：门店存在 → 频控 → 取贡献者集合（同源）→ 排除自己 → 排序截断 → 逐对幂等写入。
     */
    @Transactional
    public VisitThanksResponse thank(Long venueId, Long userId) {
        Venue venue = requireVenue(venueId);
        requireRate(userId, venueId);
        Map<Long, LocalDateTime> contributors = contributorsFor(venueId, userId);
        // 最近到访优先（截断方向：保留更活跃的贡献者；常态永不触达上限）
        List<Long> targets = contributors.entrySet().stream()
                .sorted(Map.Entry.<Long, LocalDateTime>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .limit(MAX_TARGETS_PER_CLICK)
                .toList();
        int thankedPeople = 0;
        LocalDateTime now = LocalDateTime.now();
        for (Long target : targets) {
            if (thankRepository.thank(venueId, userId, target, now) == 1) {
                thankedPeople++;
                // 合并正文的 N = 该贡献者在该店累计收到的不同感谢者数（含本次）
                long thankers = thankRepository.countThankers(venueId, target);
                messageService.createOrMergeUnread(target, MessageType.VISIT_THANKED,
                        THANKED_MESSAGE_TITLE,
                        "有 " + thankers + " 位舞友在「" + venue.getName() + "」感谢了你的到店足迹，"
                                + "谢谢你让更多舞友知道这里",
                        RELATED_TYPE_VENUE, venueId);
            }
        }
        Collection<Long> thankedTargets = thankRepository.findTargetUserIds(venueId, userId);
        return new VisitThanksResponse(thankedPeople,
                pendingCount(contributors.keySet(), thankedTargets),
                contributors.size(), feedbackTextOf(thankedPeople));
    }

    /**
     * 可被感谢的贡献者 = 附近并集（30 天，与卡片数字同源）− 排除账号集合 − <b>自己</b>。
     * <p>
     * 排除集合经 {@link #displayExclusions}（全类唯一取点，同 {@code VenueVisitBadgeService}
     * 的纪律：任何一个面自行去问 {@code HeatAccountExclusionService} 都会让「同一集合」
     * 退化成约定，而约定正是上一次口径分叉的来路）。
     */
    private Map<Long, LocalDateTime> contributorsFor(Long venueId, Long thankUserId) {
        Map<Long, LocalDateTime> contributors = new HashMap<>(
                venuePresenceService.nearbyContributors(venueId, displayExclusions()));
        contributors.remove(thankUserId);
        return contributors;
    }

    /** 展示口径的排除账号集合（本类唯一取点；与 C 侧卡片 / admin 同源） */
    private List<Long> displayExclusions() {
        return heatAccountExclusionService.excludedUserIds();
    }

    /** 尚未感谢的人数 = 可被感谢集合 − 我已感谢过的集合（已感谢行可能在窗外，差值语义天然正确） */
    private static int pendingCount(Collection<Long> contributorIds, Collection<Long> thankedTargetIds) {
        return (int) contributorIds.stream().filter(id -> !thankedTargetIds.contains(id)).count();
    }

    /**
     * 提交反馈文案（纯函数，服务端权威）： thankedPeople &gt; 0 ⇒「已感谢 N 位舞友」；
     * 0 ⇒ 「已经感谢过这里的舞友」（竞态下按钮态已过期时的兜底祝辞——不报错，因为
     * 用户的意图已达成，只是没有新增）。
     */
    static String feedbackTextOf(int thankedPeople) {
        return thankedPeople > 0 ? "已感谢 " + thankedPeople + " 位舞友" : "已经感谢过这里的舞友";
    }

    /** 频控（每用户每店 60s）：窗口内重复点击 → 1022（到访域通用「过于频繁」码） */
    private void requireRate(Long userId, Long venueId) {
        String key = userId + ":" + venueId;
        if (thanksRateCache.asMap().putIfAbsent(key, Boolean.TRUE) != null) {
            log.warn("visit thanks rate limited: userId={}, venueId={}", userId, venueId);
            throw new BusinessException(1022, "感谢太频繁，请稍后再试");
        }
    }

    /** 门店存在且未删（1022，到访域同码——感谢与到访属同一域的错误面） */
    private Venue requireVenue(Long venueId) {
        return venueRepository.findById(venueId)
                .filter(v -> !v.isDeleted())
                .orElseThrow(() -> new BusinessException(1022, "门店不存在"));
    }
}
