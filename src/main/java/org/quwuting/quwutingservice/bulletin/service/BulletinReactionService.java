package org.quwuting.quwutingservice.bulletin.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.bulletin.BulletinReactionCode;
import org.quwuting.quwutingservice.bulletin.dto.response.BulletinReactionBadge;
import org.quwuting.quwutingservice.bulletin.dto.response.BulletinReactionToggleResult;
import org.quwuting.quwutingservice.bulletin.entity.BulletinReaction;
import org.quwuting.quwutingservice.bulletin.repository.BulletinReactionRepository;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 快讯表态服务（2026-09-10，docs/agents/47-bulletins.md「快讯表态」）。
 * <p>
 * <b>语义：一人一条内容恒一个表情</b>（永久一票，不按日重置）——
 * <ul>
 *   <li>未表态 → 参与（插入一行）；</li>
 *   <li>已表态且同 code → 取消（物理删除该行）；</li>
 *   <li>已表态且异 code → <b>换票</b>（原地 UPDATE 同一行，计数此消彼长），
 *       {@code replacedFrom} 回传旧 code 供前端同步本地高亮。</li>
 * </ul>
 * <b>为什么不需要门店域那种 {@code SELECT ... FOR UPDATE} 串行化</b>：门店"每日一票"是
 * 应用层语义（唯一约束按 (user, venue, code, date) 建，一票维度落不到键上），并发下
 * "查当日票 → 删旧 → 插新"会破不变量，才必须加锁；本域唯一键
 * {@code (user_id, bulletin_id)} 正好就是语义维度，写入走单条
 * {@code INSERT ... ON DUPLICATE KEY UPDATE}（见仓储），并发只会收敛到同一行——
 * 用键表达不变量，就不用锁去模拟键。
 * <p>
 * <b>无缓存</b>：计数直接按整页 id 聚合查询（一次 IN + GROUP BY），不引入门店域那套
 * 聚合缓存 + 事务提交后失效链路——快讯单条内容的表态量级远小于门店，缓存收益低于
 * 陈旧风险（详情页与列表页共用 {@link #batchBadges}，口径天然一致）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BulletinReactionService {

    /** 字典声明序（并列计数的确定性排序兜底；同时是 Picker 展示序） */
    private static final List<String> DECLARED_ORDER = BulletinReactionCode.allCodes();

    private final BulletinReactionRepository bulletinReactionRepository;
    private final BulletinLookupService bulletinLookupService;

    /**
     * 切换表态（参与 / 取消 / 换票）。
     *
     * @param userId     当前用户（接口层已鉴权）
     * @param bulletinId 快讯 id（不可见 → 404，未发布内容不接受表态）
     * @param code       表态 code（字典外 → 1007）
     */
    @Transactional
    public BulletinReactionToggleResult toggle(Long userId, Long bulletinId, String code) {
        if (!BulletinReactionCode.isValid(code)) {
            throw new BusinessException(1007, "无效的表情类型");
        }
        bulletinLookupService.requirePublished(bulletinId);

        Optional<BulletinReaction> existing =
                bulletinReactionRepository.findByUserIdAndBulletinId(userId, bulletinId);
        if (existing.isPresent() && existing.get().getReactionCode().equals(code)) {
            // 取消：物理删除（同门店 Reaction「取消当天 Reaction = 贡献移除」口径；
            // 唯一键无 deleted 维度，硬删是保持"一键一行"不变量最简单也最不容易漂移的做法）
            bulletinReactionRepository.delete(existing.get());
            return new BulletinReactionToggleResult(false, null);
        }
        String replacedFrom = existing.map(BulletinReaction::getReactionCode).orElse(null);
        bulletinReactionRepository.upsertReaction(userId, bulletinId, code, LocalDateTime.now());
        return new BulletinReactionToggleResult(true, replacedFrom);
    }

    /** 单条快讯的表态徽标（详情接口用） */
    @Transactional(readOnly = true)
    public List<BulletinReactionBadge> badges(Long bulletinId, Long currentUserId) {
        return batchBadges(List.of(bulletinId), currentUserId)
                .getOrDefault(bulletinId, Collections.emptyList());
    }

    /**
     * 整页快讯的表态徽标（列表接口用）：两条 IN 查询覆盖全页（聚合计数 + 个人表态），
     * 无 N+1、无缓存。返回 map 恒含入参里的每个 id（无表态 = 空列表），调用方无需判空。
     */
    @Transactional(readOnly = true)
    public Map<Long, List<BulletinReactionBadge>> batchBadges(List<Long> bulletinIds, Long currentUserId) {
        if (bulletinIds == null || bulletinIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Map<String, Long>> countsByBulletin = new HashMap<>();
        for (Object[] row : bulletinReactionRepository.countByBulletinIdsGroupByCode(bulletinIds)) {
            Long bulletinId = (Long) row[0];
            String code = (String) row[1];
            long count = row[2] != null ? ((Number) row[2]).longValue() : 0L;
            countsByBulletin.computeIfAbsent(bulletinId, k -> new HashMap<>()).put(code, count);
        }
        Map<Long, String> myCodeByBulletin = new HashMap<>();
        if (currentUserId != null) {
            for (Object[] row : bulletinReactionRepository.findCodesByUserAndBulletinIds(currentUserId, bulletinIds)) {
                myCodeByBulletin.put((Long) row[0], (String) row[1]);
            }
        }
        Map<Long, List<BulletinReactionBadge>> result = new HashMap<>();
        for (Long bulletinId : bulletinIds) {
            Map<String, Long> counts = countsByBulletin.getOrDefault(bulletinId, Collections.emptyMap());
            result.put(bulletinId, buildBadges(counts, myCodeByBulletin.get(bulletinId)));
        }
        return result;
    }

    /**
     * 构建徽标行：只保留 count&gt;0 的合法字典 code（展示 = 真实用户行为，创建入口在前端 Picker），
     * 按人数降序、并列按字典声明序（确定性 —— 同样的数据每次返回同一顺序，前端不抖动）。
     * <p>
     * 字典外 code 优雅忽略（同门店域 buildTopBadgesFromCounts 的防御）：字典项被删/改名后，
     * 历史行仍存在于库中，裸 valueOf 会 500；此处过滤即可，历史数据无需迁移。
     */
    private List<BulletinReactionBadge> buildBadges(Map<String, Long> counts, String myCode) {
        List<String> codes = new ArrayList<>(counts.keySet());
        codes.removeIf(code -> !BulletinReactionCode.isValid(code) || counts.get(code) == null
                || counts.get(code) <= 0);
        codes.sort(Comparator
                .comparingLong((String code) -> counts.get(code)).reversed()
                .thenComparingInt(DECLARED_ORDER::indexOf));
        List<BulletinReactionBadge> badges = new ArrayList<>(codes.size());
        for (String code : codes) {
            badges.add(new BulletinReactionBadge(
                    code,
                    BulletinReactionCode.emojiOf(code),
                    BulletinReactionCode.labelOf(code),
                    counts.get(code),
                    code.equals(myCode)));
        }
        return badges;
    }
}
