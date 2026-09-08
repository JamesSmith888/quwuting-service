package org.quwuting.quwutingservice.venue.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.venue.dto.request.BatchImportVenueAliasRequest;
import org.quwuting.quwutingservice.venue.dto.request.UpsertVenueAliasRequest;
import org.quwuting.quwutingservice.venue.dto.response.BatchImportVenueAliasResponse;
import org.quwuting.quwutingservice.venue.dto.response.VenueAliasGroupResponse;
import org.quwuting.quwutingservice.venue.dto.response.VenueAliasVenueOption;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.entity.VenueAlias;
import org.quwuting.quwutingservice.venue.repository.VenueAliasRepository;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 门店别名服务（2026-09-07 门店别名域，docs/agents/38-venue-aliases.md）。
 * <p>
 * 管理员维护的门店「用户可见别名」（曾用名/俗称/圈内涵称）——与
 * {@code VenueSyncAliasService}（同步管线的店名映射配置）语义严格分离：
 * <ul>
 *   <li>list：已配置别名的门店聚合列表（Web 管理后台「门店别名」页数据源）；</li>
 *   <li>upsert：幂等（同店同名复活软删行，守住生成列部分唯一索引）；</li>
 *   <li>batchImport：批量导入（舞讯 Skill 别名沉淀灌库，逐条独立提交）；</li>
 *   <li>delete：软删（重配同名时复活重用）；</li>
 *   <li>searchVenues：配置时选店的门店候选（名称模糊，不限状态）。</li>
 * </ul>
 * 详情下发不走本服务——{@code VenueService#computeVenueDetailPublic} 直查
 * {@link VenueAliasRepository} 并入公共部分缓存体（冷启动 +1 查、命中零往返）；
 * 本服务所有写路径负责 {@link VenueService#invalidateDetailPublic} 失效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VenueAliasService {

    /** 管理端门店候选搜索上限（首次进入空关键词兜底同限） */
    private static final int VENUE_SEARCH_LIMIT = 20;

    private final VenueAliasRepository aliasRepository;
    private final VenueRepository venueRepository;
    private final VenueService venueService;

    /** 已配置别名的门店聚合列表（组序 = 最近配置在前；门店已删的组跳过） */
    @Transactional(readOnly = true)
    public List<VenueAliasGroupResponse> list() {
        List<VenueAlias> aliases = aliasRepository.findByDeletedFalseOrderByIdDesc();
        if (aliases.isEmpty()) {
            return List.of();
        }
        Map<Long, Venue> venues = venueRepository.findAllById(
                        aliases.stream().map(VenueAlias::getVenueId).distinct().toList())
                .stream().collect(Collectors.toMap(Venue::getId, Function.identity()));

        // 全局 id 倒序按店分组（LinkedHashMap 保序 → 组序即「最近配置在前」）
        Map<Long, List<VenueAlias>> byVenue = new LinkedHashMap<>();
        for (VenueAlias alias : aliases) {
            byVenue.computeIfAbsent(alias.getVenueId(), k -> new ArrayList<>()).add(alias);
        }
        return byVenue.entrySet().stream()
                .map(entry -> {
                    Venue venue = venues.get(entry.getKey());
                    if (venue == null || venue.isDeleted()) {
                        return null; // 门店已不存在，跳过悬空组
                    }
                    List<VenueAliasGroupResponse.AliasItem> items = entry.getValue().stream()
                            .map(a -> new VenueAliasGroupResponse.AliasItem(a.getId(), a.getAlias()))
                            .toList();
                    return new VenueAliasGroupResponse(venue.getId(), venue.getName(),
                            venue.getCity(), venue.getStatus(), items);
                })
                .filter(group -> group != null)
                .toList();
    }

    /** 幂等 upsert：同店同名复活（软删行重用），否则新增 */
    @Transactional
    public VenueAliasGroupResponse.AliasItem upsert(UpsertVenueAliasRequest request) {
        String alias = request.alias().trim();
        Venue venue = requireVenue(request.venueId());

        VenueAlias entity = aliasRepository.findByVenueIdAndAlias(request.venueId(), alias)
                .orElse(null);
        if (entity == null) {
            entity = new VenueAlias();
            entity.setVenueId(request.venueId());
            entity.setAlias(alias);
        }
        entity.setDeleted(false); // 软删行复活重用
        VenueAlias saved = aliasRepository.save(entity);
        // 详情公共部分缓存体含别名（computeVenueDetailPublic），写后失效
        venueService.invalidateDetailPublic(request.venueId());
        log.info("[venue-alias] upsert: venueId={} alias={} id={}",
                request.venueId(), alias, saved.getId());
        return new VenueAliasGroupResponse.AliasItem(saved.getId(), saved.getAlias());
    }

    /**
     * 批量导入别名（POST /admin/venue-aliases/batch-import，2026-09-08 舞讯 Skill 对接）。
     * <p>
     * 逐条独立提交（<b>外层不挂 @Transactional</b>）：{@code aliasRepository.save()}
     * 各自独立事务提交，单条失败不拖累同批其他条目（与 VenueSyncDataService#batchCreateVenues
     * 「禁跨批大事务」哲学一致——连接池仅 5 连接）。幂等口径：
     * <ul>
     *   <li>同店同名的有效行已存在 → skipped（幂等早退，不重复计数）；</li>
     *   <li>软删行同名 → 复活重用，计 imported；</li>
     *   <li>别名（trim 后）与门店主名同名 → skipped（无检索意义）；</li>
     *   <li>门店不存在/已删/非法输入 → failed（带原因，不抛出中断整批）。</li>
     * </ul>
     * 每条写后失效详情缓存（别名进 computeVenueDetailPublic 缓存体）。
     */
    public BatchImportVenueAliasResponse batchImport(BatchImportVenueAliasRequest request) {
        int imported = 0;
        int skipped = 0;
        List<BatchImportVenueAliasResponse.FailedItem> failed = new ArrayList<>();
        List<UpsertVenueAliasRequest> items = request.items();

        for (int i = 0; i < items.size(); i++) {
            UpsertVenueAliasRequest item = items.get(i);
            Long venueId = item.venueId();
            String alias = item.alias() == null ? "" : item.alias().trim();
            try {
                Venue venue = requireVenue(venueId);
                if (alias.isEmpty()) {
                    failed.add(new BatchImportVenueAliasResponse.FailedItem(i, venueId, alias, "别名不能为空"));
                    continue;
                }
                if (alias.equals(venue.getName())) {
                    skipped++; // 别名与主名同名，无检索意义
                    continue;
                }
                VenueAlias entity = aliasRepository.findByVenueIdAndAlias(venueId, alias)
                        .orElse(null);
                if (entity != null && !entity.isDeleted()) {
                    skipped++; // 幂等：有效行已存在
                    continue;
                }
                if (entity == null) {
                    entity = new VenueAlias();
                    entity.setVenueId(venueId);
                    entity.setAlias(alias);
                }
                entity.setDeleted(false); // 软删行复活重用
                aliasRepository.save(entity);
                venueService.invalidateDetailPublic(venueId);
                imported++;
            } catch (Exception e) {
                failed.add(new BatchImportVenueAliasResponse.FailedItem(i, venueId, alias,
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }
        log.info("[venue-alias] batch-import: total={} imported={} skipped={} failed={}",
                items.size(), imported, skipped, failed.size());
        return new BatchImportVenueAliasResponse(items.size(), imported, skipped, failed);
    }

    /** 软删（重复添加同名时复活重用，不留脏唯一键） */
    @Transactional
    public void delete(Long id) {
        VenueAlias alias = aliasRepository.findById(id)
                .filter(a -> !a.isDeleted())
                .orElseThrow(() -> new BusinessException(404, "别名不存在"));
        alias.setDeleted(true);
        aliasRepository.save(alias);
        venueService.invalidateDetailPublic(alias.getVenueId());
        log.info("[venue-alias] deleted: id={} venueId={} alias={}",
                id, alias.getVenueId(), alias.getAlias());
    }

    /** 配置时选店的门店候选：keyword 空 = 最近收录兜底；名称模糊（ESCAPE '!' 字面口径） */
    @Transactional(readOnly = true)
    public List<VenueAliasVenueOption> searchVenues(String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        String pattern = kw.isEmpty() ? null : "%" + escapeLikeLiteral(kw) + "%";
        List<Venue> venues = venueRepository.searchVenueAliasCandidates(pattern,
                PageRequest.of(0, VENUE_SEARCH_LIMIT));
        return venues.stream()
                .map(v -> new VenueAliasVenueOption(v.getId(), v.getName(), v.getCity(), v.getStatus()))
                .toList();
    }

    private Venue requireVenue(Long venueId) {
        return venueRepository.findById(venueId)
                .filter(v -> !v.isDeleted())
                .orElseThrow(() -> new BusinessException(400, "平台门店不存在"));
    }

    /**
     * LIKE 字面转义（与 {@link VenueService#escapeLikeLiteral} 同口径——HQL ESCAPE '!'）：
     * escape 字符 {@code !} 先转义自身（! → !!），再转义通配符 % / _（→ !% / !_）——
     * 顺序不可颠倒（若先转 %/_，其前缀 ! 会被后续的 !→!! 误转成 !!%）。
     */
    private static String escapeLikeLiteral(String term) {
        return term.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
