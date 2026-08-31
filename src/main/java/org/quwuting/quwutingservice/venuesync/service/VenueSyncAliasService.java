package org.quwuting.quwutingservice.venuesync.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venuesync.dto.request.UpsertVenueSyncAliasRequest;
import org.quwuting.quwutingservice.venuesync.dto.response.VenueSyncAliasResponse;
import org.quwuting.quwutingservice.venuesync.entity.VenueSyncAlias;
import org.quwuting.quwutingservice.venuesync.repository.VenueSyncAliasRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 手动映射别名服务（2026-08-31，Web 管理后台「门店同步 → 映射管理」数据源）。
 * <p>
 * 等价于管线 Matcher 的别名表：管理员把「网上门店名称 + 城市」钉到具体平台门店，
 * 管线 {@code --refresh-aliases} 拉取后，下次匹配时该名称按 ALIAS 置信度命中
 * （优先级高于归一化精确匹配）。与 {@link VenueSyncReportService} 同属门店同步域。
 * <ul>
 *   <li>list：全部有效映射（带平台门店名，最近配置在前）；</li>
 *   <li>upsert：幂等（同城同名覆盖 venueId/note；软删行恢复重用）；</li>
 *   <li>delete：软删（保留审计痕迹，重配时恢复）；</li>
 *   <li>export：管线消费格式 {@code {city: {sourceName: venueName}}}，
 *       与 matcher 的 aliases.json 结构一致。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VenueSyncAliasService {

    private final VenueSyncAliasRepository aliasRepository;
    private final VenueRepository venueRepository;

    /** 全部有效映射（带平台门店名，最近配置在前） */
    @Transactional(readOnly = true)
    public List<VenueSyncAliasResponse> list() {
        List<VenueSyncAlias> aliases = aliasRepository.findByDeletedFalseOrderByUpdatedAtDesc();
        if (aliases.isEmpty()) {
            return List.of();
        }
        Map<Long, Venue> venues = venueRepository.findAllById(
                        aliases.stream().map(VenueSyncAlias::getVenueId).distinct().toList())
                .stream().collect(Collectors.toMap(Venue::getId, Function.identity()));
        return aliases.stream()
                .map(a -> {
                    Venue venue = venues.get(a.getVenueId());
                    return new VenueSyncAliasResponse(
                            a.getId(), a.getCity(), a.getSourceName(), a.getVenueId(),
                            venue != null ? venue.getName() : null,
                            venue != null ? venue.getCity() : null,
                            a.getNote(), a.getUpdatedAt());
                })
                .toList();
    }

    /** 幂等 upsert：同城同名覆盖 venueId/note（软删行恢复重用） */
    @Transactional
    public VenueSyncAliasResponse upsert(UpsertVenueSyncAliasRequest request) {
        String city = request.city().trim();
        String sourceName = request.sourceName().trim();
        requireVenue(request.venueId());

        VenueSyncAlias alias = aliasRepository
                .findByCityAndSourceNameAndDeletedFalse(city, sourceName)
                .orElse(null);
        if (alias == null) {
            alias = new VenueSyncAlias();
            alias.setCity(city);
            alias.setSourceName(sourceName);
        }
        alias.setVenueId(request.venueId());
        alias.setNote(request.note() == null ? "" : request.note().trim());
        VenueSyncAlias saved = aliasRepository.save(alias);
        log.info("[venue-sync] alias upsert: city={} source={} venueId={}",
                city, sourceName, request.venueId());

        Venue venue = venueRepository.findById(request.venueId()).orElse(null);
        return new VenueSyncAliasResponse(
                saved.getId(), saved.getCity(), saved.getSourceName(), saved.getVenueId(),
                venue != null ? venue.getName() : null,
                venue != null ? venue.getCity() : null,
                saved.getNote(), saved.getUpdatedAt());
    }

    /** 软删映射（保留审计痕迹） */
    @Transactional
    public void delete(Long id) {
        VenueSyncAlias alias = aliasRepository.findById(id)
                .filter(a -> !a.isDeleted())
                .orElseThrow(() -> new BusinessException(404, "映射不存在"));
        alias.setDeleted(true);
        aliasRepository.save(alias);
        log.info("[venue-sync] alias deleted: id={} city={} source={}",
                id, alias.getCity(), alias.getSourceName());
    }

    /**
     * 管线消费格式：{city: {sourceName: venueName}}（对齐 matcher 的 aliases.json）。
     * 平台门店已不存在（软删/真删）的映射自动跳过，避免 export 悬空。
     */
    @Transactional(readOnly = true)
    public Map<String, Map<String, String>> export() {
        List<VenueSyncAlias> aliases = aliasRepository.findByDeletedFalseOrderByUpdatedAtDesc();
        if (aliases.isEmpty()) {
            return Map.of();
        }
        Map<Long, Venue> venues = venueRepository.findAllById(
                        aliases.stream().map(VenueSyncAlias::getVenueId).distinct().toList())
                .stream().collect(Collectors.toMap(Venue::getId, Function.identity()));

        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (VenueSyncAlias a : aliases) {
            Venue venue = venues.get(a.getVenueId());
            if (venue == null || venue.isDeleted()) {
                continue; // 门店已不存在，跳过悬空映射
            }
            result.computeIfAbsent(a.getCity(), k -> new LinkedHashMap<>())
                    .put(a.getSourceName(), venue.getName());
        }
        return result;
    }

    private void requireVenue(Long venueId) {
        Venue venue = venueRepository.findById(venueId)
                .filter(v -> !v.isDeleted())
                .orElseThrow(() -> new BusinessException(400, "平台门店不存在"));
    }
}
