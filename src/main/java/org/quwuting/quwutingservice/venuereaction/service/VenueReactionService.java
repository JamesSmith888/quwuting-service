package org.quwuting.quwutingservice.venuereaction.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.opsconfig.service.OpsConfigService;
import org.quwuting.quwutingservice.venue.service.VenueHeatService;
import org.quwuting.quwutingservice.venue.service.VenueLookupService;
import org.quwuting.quwutingservice.venuereaction.ReactionCode;
import org.quwuting.quwutingservice.venuereaction.ReactionWindow;
import org.quwuting.quwutingservice.venuereaction.dto.response.ReactionBadge;
import org.quwuting.quwutingservice.venuereaction.dto.response.ReactionStat;
import org.quwuting.quwutingservice.venuereaction.dto.response.ReactionStatsResponse;
import org.quwuting.quwutingservice.venuereaction.dto.response.ToggleReactionResult;
import org.quwuting.quwutingservice.venuereaction.entity.VenueReaction;
import org.quwuting.quwutingservice.venuereaction.repository.VenueReactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 场所 Reaction 服务：toggle 参与 + 个人状态实时查询 + 列表页徽标编排。
 * <p>
 * 2026-08 迁移为"每日一记"模型（根因见 AGENTS.md「Reaction 快速反馈系统」）：
 * 每次点击 = 插入今日记录，取消 = 物理删除今日记录（同日唯一约束 (userId, venueId, code, date)）。
 * 取代旧"toggle 软删 hold 模型"——旧模型下取消可能作用于 createdAt 超窗的旧记录，
 * 使近7天/近30天窗口计数无法本地精确推导，迫使前端发明"展示 countAll、排序 count30d"的双计数 hack；
 * 每日一记模型下取消只作用于当日记录，四个窗口计数本地 ±1 全部精确，hack 消失。
 * <p>
 * 2026-08-14 升级为<b>每日一票（可配置）</b>模型（用户驱动 + 根因分析，见
 * {@code OpsConfigService.KEY_REACTION_DAILY_SINGLE}）：同一用户对同一场所每天只能
 * 贡献一个 Reaction——点新表情 = 当日旧票<b>原子换票</b>（删旧插新）。根因：原模型下
 * 唯一约束按 code 维度（(userId, venueId, code, date)），用户可零成本"全选"多个表情，
 * 各场所计数同步膨胀、分布趋同，列表 chip 看不出差别（信号稀释）。一票约束为
 * <b>应用层语义</b>（无 DB 唯一约束），由 toggle 事务 + {@code pg_advisory_xact_lock}
 * 保证，运营可即时开关恢复多选。
 * <p>
 * 替代原"标签点赞"，见 AGENTS.md「Reaction 快速反馈系统」章节。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VenueReactionService {

    /**
     * topReactions 是<b>纯众包完整展示</b>：返回所选窗口（默认近7天）内<b>所有用户</b>
     * 点击过的<b>全部</b> Reaction 表情（count>0 的 code 一个不落，按所选窗口计数降序），
     * **不做任何截断**（需求 2026-08-09：取所有用户的所有已点击表情全部展示）。
     * 个人参与状态（reactedByMe）只是徽标的标注属性（驱动"点击即知是否已参与"），
     * 不参与集合构成。
     * 前端卡片 chips 容器为 flex-wrap 换行布局，可容纳全部表情（见 venue-card.wxss
     * .reaction-chips——2026-08-09 契约纠正：原先"Top 4 截断"（含 2026-08-08 的"当前
     * 用户已参与 code 不受截断"豁免、以及 2026-08-09 上午的"纯 Top N"口径）均属错误
     * 理解——需求本义是"近7天全部用户数据"的全部展示，不做截断。
     */

    private final VenueReactionRepository venueReactionRepository;
    private final VenueReactionAggregateService aggregateService;
    private final VenueLookupService venueLookupService;
    private final VenueHeatService venueHeatService;
    private final OpsConfigService opsConfigService;

    /**
     * 切换 Reaction 参与状态（toggle：今日未参与→参与，今日已参与→取消）。
     * <p>
     * 每日一票模型（开关 {@link OpsConfigService#KEY_REACTION_DAILY_SINGLE} 开启，默认）：
     * <ul>
     *   <li>当日无票 → 插入（reactionDate = 今天），贡献 +1（所有窗口），replacedFrom=null；</li>
     *   <li>当日票 = 目标 code → 物理删除（取消当日票），贡献 -1，replacedFrom=null；</li>
     *   <li>当日票 ≠ 目标 code → <b>原子换票</b>：同事务删旧票 + 插新票
     *       （贡献不变，旧 code -1 新 code +1），replacedFrom=旧 code；</li>
     *   <li>并发串行化：pg_advisory_xact_lock 锁 (userId, venueId, date)，事务级自动释放。</li>
     * </ul>
     * 开关关闭（多选模式）：退化为原"每日每 code 独立"语义（legacy），replacedFrom 恒 null。
     *
     * @return 操作结果（reacted=目标 code 当前是否已参与；replacedFrom=被替换的旧 code，非换票为 null）
     */
    @Transactional
    public ToggleReactionResult toggle(Long userId, Long venueId, String code) {
        if (!ReactionCode.isValid(code)) {
            throw new BusinessException(1007, "无效的 Reaction 类型");
        }
        venueLookupService.findById(venueId); // 存在性校验（缓存命中时 <1ms）

        LocalDate today = LocalDate.now();
        boolean dailySingle = opsConfigService.isEnabled(OpsConfigService.KEY_REACTION_DAILY_SINGLE, true);
        ToggleReactionResult result = dailySingle
                ? toggleSingleTicket(userId, venueId, code, today)
                : toggleLegacy(userId, venueId, code, today);
        // 聚合/热度缓存失效必须延后到事务提交后（2026-08-08 根因修复）：
        // 事务提交前失效存在竞态窗口——另一线程读到 cache miss → 回源重算 → 读不到本事务
        // 未提交数据 → 缓存陈旧值（refreshAfterWrite 60s 内持续返回）。afterCommit 注册的
        // 回调在提交完成后执行，此时任何回源都能读到已提交数据。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                aggregateService.invalidate(venueId);
                venueHeatService.invalidate(venueId); // Reaction 总量是热度公式输入之一
            }
        });
        return result;
    }

    /**
     * 每日一票模式：一人一店一日一票（换票语义）。
     * 咨询锁串行化后，当日查询至多一行（V22 已清理历史多行；findFirst 防御残留不抛异常）。
     */
    private ToggleReactionResult toggleSingleTicket(Long userId, Long venueId, String code, LocalDate today) {
        // 事务级咨询锁：串行化同 user+venue+date 的并发换票（提交/回滚自动释放）
        venueReactionRepository.lockDailyTicket("reaction:" + userId + ":" + venueId + ":" + today);

        Optional<VenueReaction> existing = venueReactionRepository
                .findFirstByUserIdAndVenueIdAndReactionDateOrderByIdAsc(userId, venueId, today);
        if (existing.isPresent()) {
            VenueReaction row = existing.get();
            if (row.getReactionCode().equals(code)) {
                // 取消当日票（物理删除："取消当天 Reaction"语义 = 当日贡献移除）
                venueReactionRepository.delete(row);
                return new ToggleReactionResult(false, null);
            }
            // 换票：删旧票 + 插新票（同事务原子；咨询锁下无并发竞态，23505 幂等仅作防御）
            venueReactionRepository.delete(row);
            insertReaction(userId, venueId, code, today);
            return new ToggleReactionResult(true, row.getReactionCode());
        }
        insertReaction(userId, venueId, code, today);
        return new ToggleReactionResult(true, null);
    }

    /**
     * 多选模式（开关关闭）：原"每日每 code 独立"语义——每个 code 各自 toggle，
     * 同日不同 code 可并存（唯一约束 (userId, venueId, code, date) 兜底）。
     */
    private ToggleReactionResult toggleLegacy(Long userId, Long venueId, String code, LocalDate today) {
        var existing = venueReactionRepository
                .findByUserIdAndVenueIdAndReactionCodeAndReactionDate(userId, venueId, code, today);
        if (existing.isPresent()) {
            // 取消当日 Reaction：物理删除（"取消当天 Reaction"语义 = 当日贡献移除）
            venueReactionRepository.delete(existing.get());
            return new ToggleReactionResult(false, null);
        }
        insertReaction(userId, venueId, code, today);
        return new ToggleReactionResult(true, null);
    }

    /**
     * 插入今日 Reaction 记录（确定性原子 upsert，2026-08-20 根因修复：替代
     * 「save + catch 23505 + entityManager.clear()」——PG 语句失败后事务中止
     * （25P02），吞掉冲突后继续使用同一事务依赖 JPA 不可靠行为；ON CONFLICT
     * DO NOTHING 恒 1 次往返零异常，命中 V1 唯一索引即幂等视为已参与）。
     */
    private void insertReaction(Long userId, Long venueId, String code, LocalDate today) {
        venueReactionRepository.upsertReaction(userId, venueId, code, today, LocalDateTime.now());
    }

    /**
     * 详情页完整 Reaction 统计：字典内全部代码，按声明顺序返回（无数据时计数为 0）。
     * 聚合计数走缓存共享；个人参与状态（今日已参与）单独实时查询，不与聚合数据混存。
     */
    @Transactional(readOnly = true)
    public ReactionStatsResponse getStats(Long venueId, Long currentUserId) {
        Map<String, long[]> aggregate = aggregateService.getAggregate(venueId);
        Set<String> myCodes = currentUserId != null
                ? new HashSet<>(venueReactionRepository.findTodayCodesByUserAndVenue(
                        currentUserId, venueId, LocalDate.now()))
                : Collections.emptySet();

        List<ReactionStat> stats = new ArrayList<>();
        for (ReactionCode rc : ReactionCode.values()) {
            long[] counts = aggregate.get(rc.name());
            long countAll = counts != null ? counts[0] : 0L;
            long countToday = counts != null ? counts[1] : 0L;
            long count7d = counts != null ? counts[2] : 0L;
            long count30d = counts != null ? counts[3] : 0L;
            stats.add(new ReactionStat(
                    rc.name(), rc.getEmoji(), rc.getLabel(),
                    countToday, count7d, count30d, countAll,
                    myCodes.contains(rc.name())));
        }
        return new ReactionStatsResponse(stats);
    }

    /**
     * 单场所的 Reaction 徽标（详情基础响应用），按所选窗口计数排序，count=0 的不展示。
     * <b>完整展示</b>：返回所选窗口（默认近7天）内所有用户点击过的全部表情，**不做任何
     * 截断**（2026-08-09 需求定稿，见 {@link #buildTopBadgesFromCounts} javadoc）。
     * 复用聚合缓存的窗口分量，个人状态（reactedByMe）单独实时查询（成本为一次按
     * userId+venueId+date 的索引查询），仅作徽标标注属性、不参与集合构成。
     *
     * @param window 徽标排序/筛选窗口（null → 默认近7天）
     */
    @Transactional(readOnly = true)
    public List<ReactionBadge> getBadges(Long venueId, Long currentUserId, ReactionWindow window) {
        Map<String, long[]> aggregate = aggregateService.getAggregate(venueId);
        Set<String> myCodes = currentUserId != null
                ? new HashSet<>(venueReactionRepository.findTodayCodesByUserAndVenue(
                        currentUserId, venueId, LocalDate.now()))
                : Collections.emptySet();
        return buildTopBadges(aggregate, myCodes, window);
    }

    /**
     * 批量场所的 Reaction 徽标（列表页用），一次 IN 查询覆盖整页场所的
     * countAll + count7d + count30d（单条 SQL，见 {@link VenueReactionRepository#countByVenueIdsGroupByCode}）
     * + 一次 IN 查询覆盖个人状态。不缓存——列表页请求的场所集合每次不同（翻页/筛选变化），
     * 复用单场所聚合缓存收益低，与既有 batchGetTagLikeCounts 的"批量查询不缓存"约定一致。
     * <p>
     * <b>完整展示</b>：集合构成 = 所选窗口（默认近7天）内所有用户点击过的全部表情
     * （count>0 全返回，**不做任何截断**，2026-08-09 需求定稿）。个人状态（reactedByMe）
     * 仅为徽标标注属性（驱动"点击即知是否已参与"），不参与集合构成。
     *
     * @param window 徽标排序/筛选窗口（null → 默认近7天）
     */
    @Transactional(readOnly = true)
    public Map<Long, List<ReactionBadge>> batchGetBadges(List<Long> venueIds, Long currentUserId, ReactionWindow window) {
        if (venueIds == null || venueIds.isEmpty()) {
            return Collections.emptyMap();
        }
        LocalDate today = LocalDate.now();
        LocalDateTime since7d = LocalDateTime.now().minusDays(7);
        LocalDateTime since30d = LocalDateTime.now().minusDays(30);

        // 单条 SQL 同时聚合 countAll/count7d/count30d：排序/筛选按所选窗口，展示同窗口计数
        Map<Long, Map<String, Long>> countAllByVenue = new HashMap<>();
        Map<Long, Map<String, Long>> count7dByVenue = new HashMap<>();
        Map<Long, Map<String, Long>> count30dByVenue = new HashMap<>();
        for (Object[] row : venueReactionRepository.countByVenueIdsGroupByCode(venueIds, since7d, since30d)) {
            Long venueId = (Long) row[0];
            String code = (String) row[1];
            Long countAll = row[2] != null ? ((Number) row[2]).longValue() : 0L;
            Long count7d = row[3] != null ? ((Number) row[3]).longValue() : 0L;
            Long count30d = row[4] != null ? ((Number) row[4]).longValue() : 0L;
            countAllByVenue.computeIfAbsent(venueId, k -> new HashMap<>()).put(code, countAll);
            count7dByVenue.computeIfAbsent(venueId, k -> new HashMap<>()).put(code, count7d);
            count30dByVenue.computeIfAbsent(venueId, k -> new HashMap<>()).put(code, count30d);
        }

        Map<Long, Set<String>> myCodesByVenue = new HashMap<>();
        if (currentUserId != null) {
            for (Object[] row : venueReactionRepository.findTodayCodesByUserAndVenueIds(currentUserId, venueIds, today)) {
                Long venueId = (Long) row[0];
                String code = (String) row[1];
                myCodesByVenue.computeIfAbsent(venueId, k -> new HashSet<>()).add(code);
            }
        }

        Map<Long, List<ReactionBadge>> result = new HashMap<>();
        for (Long venueId : venueIds) {
            Map<String, Long> countAlls = countAllByVenue.getOrDefault(venueId, Collections.emptyMap());
            Map<String, Long> count7ds = count7dByVenue.getOrDefault(venueId, Collections.emptyMap());
            Map<String, Long> count30ds = count30dByVenue.getOrDefault(venueId, Collections.emptyMap());
            Set<String> myCodes = myCodesByVenue.getOrDefault(venueId, Collections.emptySet());
            result.put(venueId, buildTopBadgesFromCounts(countAlls, count7ds, count30ds, myCodes, window));
        }
        return result;
    }

    private List<ReactionBadge> buildTopBadges(Map<String, long[]> aggregate, Set<String> myCodes,
                                               ReactionWindow window) {
        // aggregate value: long[]{countAll, countToday, count7d, count30d}
        Map<String, Long> countAllByCode = new HashMap<>();
        Map<String, Long> count7dByCode = new HashMap<>();
        Map<String, Long> count30dByCode = new HashMap<>();
        for (Map.Entry<String, long[]> entry : aggregate.entrySet()) {
            long[] v = entry.getValue();
            countAllByCode.put(entry.getKey(), v[0]);
            count7dByCode.put(entry.getKey(), v[2]);
            count30dByCode.put(entry.getKey(), v[3]);
        }
        return buildTopBadgesFromCounts(countAllByCode, count7dByCode, count30dByCode, myCodes, window);
    }

    /**
     * 从三窗口计数 Map 构建 Reaction 徽标（**全部 count>0 的 code，不做任何截断**）。
     * 排序/筛选以所选窗口（{@link ReactionWindow}）的计数为准，徽标内同时携带三个窗口计数
     * 供前端按窗口展示 + 乐观更新本地 ±1（每日一记模型下全部窗口均精确）——
     * count=0 的条目不展示：Reaction 只在有人参与后才出现，创建新 Reaction 的入口是
     * 前端 Picker 表情选择器（长按卡片 / 点击"+"触发），参见 AGENTS.md「Reaction 快速反馈系统」。
     * <p>
     * <b>完整展示（2026-08-09 需求定稿）</b>：返回所选窗口内<b>所有用户</b>点击过的<b>全部</b>
     * 表情（不做 Top N 截断）。历史口径演进：① 2026-08-08 "当前用户已参与的 code 不受
     * Top 4 截断"豁免——把交互层状态保持问题错误上升为数据契约变更；② 2026-08-09 上午
     * "纯 Top N"——仍保留 4 条截断，同样违背"全部展示"需求本义。两版均已撤销。
     * 个人参与状态（myCodes）只作徽标标注属性（reactedByMe），不参与集合构成。
     */
    private List<ReactionBadge> buildTopBadgesFromCounts(Map<String, Long> countAllByCode,
                                                         Map<String, Long> count7dByCode,
                                                         Map<String, Long> count30dByCode,
                                                         Set<String> myCodes,
                                                         ReactionWindow window) {
        Map<String, Long> sortKeyByCode = window == ReactionWindow.DAYS_30
                ? count30dByCode
                : (window == ReactionWindow.ALL ? countAllByCode : count7dByCode);
        List<Map.Entry<String, Long>> ranked = sortKeyByCode.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > 0)
                // 2026-08-08 防御：枚举外的残留 code（历史数据/seed 旧值/未来误写）跳过不崩——
                // 枚举删除/改名后，库中旧 code 仍可能被聚合查询返回（如 V3 迁移前的
                // YOUNG_PARTNER/OLD_PARTNER）。裸 valueOf 会抛 IllegalArgumentException
                // 让整个详情/列表接口 500。与 getStats（ReactionCode.values() 遍历 + filter）
                // 和 VenueHeatService（values() 流）的"枚举外 code 优雅忽略"行为对齐。
                .filter(e -> ReactionCode.isValid(e.getKey()))
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toList());
        // 完整展示：全部 count>0 的 code 均返回，不做任何截断（2026-08-09 需求定稿，见方法 javadoc）
        List<ReactionBadge> badges = new ArrayList<>();
        for (Map.Entry<String, Long> e : ranked) {
            ReactionCode rc = ReactionCode.valueOf(e.getKey());
            badges.add(new ReactionBadge(rc.name(), rc.getEmoji(), rc.getLabel(),
                    countAllByCode.getOrDefault(e.getKey(), 0L),
                    count7dByCode.getOrDefault(e.getKey(), 0L),
                    count30dByCode.getOrDefault(e.getKey(), 0L),
                    myCodes.contains(rc.name())));
        }
        return badges;
    }
}
