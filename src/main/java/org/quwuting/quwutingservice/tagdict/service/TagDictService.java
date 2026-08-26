package org.quwuting.quwutingservice.tagdict.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.common.text.TextSanitizer;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.tagdict.dto.request.CreateTagDictRequest;
import org.quwuting.quwutingservice.tagdict.dto.request.UpdateTagDictRequest;
import org.quwuting.quwutingservice.tagdict.dto.response.TagItemResponse;
import org.quwuting.quwutingservice.tagdict.entity.TagDict;
import org.quwuting.quwutingservice.tagdict.enums.TagScope;
import org.quwuting.quwutingservice.tagdict.repository.TagDictRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 通用标签字典服务（2026-08-24）。
 * <ul>
 *   <li>{@link #listActive} — 有效字典（编辑页表单可选标签数据源，公开读）；</li>
 *   <li>{@link #create} — 管理员新增标签（入字典立即可选；低频管理操作，无缓存）；</li>
 *   <li>{@link #resolveByIds} — 按 id 批量解析（详情/列表组装 profileTags 用，
 *       一次 IN 查询；含停用标签——历史关联不因停用而消失）；</li>
 *   <li>{@link #serializeIds} / {@link #deserializeIds} — profile_tags JSON 列
 *       读写（对齐门店 serializeStringList 先例，仅存储载体升级为稳定 id）。</li>
 * </ul>
 * 序列化/反序列化失败策略与门店一致：读侧脏数据回退空列表（防拖垮展示）、
 * 写侧失败抛业务异常（入参来自本服务，正常不可达）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TagDictService {

    /** 标签名最大长度（与 CreateTagDictRequest @Size 一致） */
    private static final int MAX_TAG_TEXT = 20;

    /** 说明最大长度（与 CreateTagDictRequest @Size 一致） */
    private static final int MAX_TAG_DESC = 300;

    private final TagDictRepository tagDictRepository;
    private final ObjectMapper objectMapper;

    /** 有效字典（active + 未删，按 sortOrder,id 升序）；scope 非法/缺省回退 DANCER */
    public List<TagItemResponse> listActive(String scope) {
        return tagDictRepository
                .findByScopeAndActiveTrueAndDeletedFalseOrderBySortOrderAscIdAsc(normalizeScope(scope))
                .stream().map(TagDictService::toItem).toList();
    }

    /** 管理员新增标签（低频管理操作；同 scope + text 已存在 → 冲突提示） */
    @Transactional
    public TagItemResponse create(Long adminId, CreateTagDictRequest request) {
        String text = TextSanitizer.sanitize(request.text(), MAX_TAG_TEXT);
        if (text.isEmpty()) {
            throw new BusinessException(1001, "标签名不能为空");
        }
        String scope = normalizeScope(request.scope() == null ? null : request.scope().name());
        if (tagDictRepository.findByScopeAndTextAndDeletedFalse(scope, text).isPresent()) {
            throw new BusinessException(1001, "该标签已存在");
        }
        TagDict tag = new TagDict();
        tag.setScope(scope);
        tag.setText(text);
        tag.setDescription(TextSanitizer.sanitize(request.description(), MAX_TAG_DESC));
        tag.setCreatedBy(adminId);
        return toItem(tagDictRepository.save(tag));
    }

    /**
     * 更新标签展示配色（2026-08-26，标签级配色；低频管理操作）：
     * color 为 null = 不修改；空串 = 清除配色；否则校验 hex 格式（DTO @Pattern 已校验）
     * 后落库。返回最新条目（含新 color，前端本地收敛无需重拉字典）。
     */
    @Transactional
    public TagItemResponse updateColor(Long adminId, Long id, UpdateTagDictRequest request) {
        TagDict tag = tagDictRepository.findById(id)
                .filter(t -> !t.isDeleted())
                .orElseThrow(() -> new BusinessException(1001, "标签不存在或已删除"));
        String color = request.color();
        if (color != null) {
            tag.setColor(color.isBlank() ? null : color);
        }
        return toItem(tagDictRepository.save(tag));
    }

    /**
     * 按 id 批量解析（一次 IN 查询；含停用标签——历史关联不因停用而消失）。
     * 返回 Map（消费方按自身 id 数组顺序取值，保序责任在消费方）。
     */
    public Map<Long, TagItemResponse> resolveByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return tagDictRepository.findByIds(ids).stream()
                .collect(Collectors.toMap(TagDict::getId, TagDictService::toItem));
    }

    /** id 数组 → profile_tags JSON 列值（空 = null，对齐门店 serializeStringList 语义） */
    public String serializeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (Exception e) {
            log.warn("Failed to serialize profile tags: {}", ids);
            throw new BusinessException(1001, "标签数据异常");
        }
    }

    /** profile_tags JSON 列值 → id 列表（null/空白/损坏 → 空列表，防脏数据拖垮展示） */
    public List<Long> deserializeIds(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            log.warn("Failed to deserialize profile tags: {}", json);
            return Collections.emptyList();
        }
    }

    /** 按 id 数组顺序解析为 TagItemResponse 列表（详情/列表组装单一入口） */
    public List<TagItemResponse> resolveOrdered(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, TagItemResponse> byId = resolveByIds(ids);
        List<TagItemResponse> result = new ArrayList<>(ids.size());
        for (Long id : ids) {
            TagItemResponse item = byId.get(id);
            if (item != null) {
                result.add(item);
            }
        }
        return result;
    }

    private static TagItemResponse toItem(TagDict t) {
        return new TagItemResponse(t.getId(), t.getText(), t.getDescription(), t.getColor());
    }

    /** scope 归一（非法/缺省 → DANCER，防脏参数产生空列表） */
    private static String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return TagScope.DANCER.name();
        }
        try {
            return TagScope.valueOf(scope.trim().toUpperCase()).name();
        } catch (IllegalArgumentException e) {
            return TagScope.DANCER.name();
        }
    }
}
