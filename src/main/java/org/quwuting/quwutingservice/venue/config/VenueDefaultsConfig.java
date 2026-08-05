package org.quwuting.quwutingservice.venue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Collections;
import java.util.List;

/**
 * 门店系统默认标签配置。
 * <p>
 * 所有门店自动继承此默认标签集，展示时与管理员自定义标签合并（去重）。
 * 默认标签不在数据库中存储（DB {@code qwt_venues.tags} 仅保存自定义标签），
 * 由各消费方在读取时注入合并——保证配置变更对所有门店即时生效。
 * <p>
 * 配置键：{@code venue.default.tags}（YAML 列表）。
 */
@ConfigurationProperties(prefix = "venue.default")
public record VenueDefaultsConfig(List<String> tags) {

    /** 空实例（默认标签缺失时的安全回退） */
    private static final VenueDefaultsConfig EMPTY = new VenueDefaultsConfig(Collections.emptyList());

    public VenueDefaultsConfig {
        if (tags == null) {
            tags = Collections.emptyList();
        }
    }

    /**
     * 返回不可变的默认标签列表（永不返回 null，缺失时回退空列表）。
     */
    public List<String> tags() {
        return tags != null ? tags : Collections.emptyList();
    }

    /**
     * 合并默认标签与自定义标签（去重，保持默认→自定义的顺序）。
     * <p>
     * 顺序约定：默认标签在前、自定义标签在后。前端据此区分"不可删除系统标签"
     * 与"可删除自定义标签"——索引 0..N-1 为默认标签（N 为首个非默认标签的位置）。
     *
     * @param customTags 管理员自定义标签（来自 DB 或请求）
     * @return 合并后的有效标签列表（新 List，不修改入参）
     */
    public List<String> merge(List<String> customTags) {
        List<String> result = new java.util.ArrayList<>(tags());
        if (customTags != null) {
            for (String tag : customTags) {
                if (!result.contains(tag)) {
                    result.add(tag);
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 从合并列表中过滤出仅属于管理员的自定义标签（移除默认标签）。
     * 用于更新/创建写路径：请求中的 tags 可能被前端误传入包含默认标签，
     * 后端防御性剥离，确保 DB 只存储纯粹的自定义标签。
     */
    public List<String> filterCustomOnly(List<String> mergedTags) {
        if (mergedTags == null || mergedTags.isEmpty()) {
            return Collections.emptyList();
        }
        return mergedTags.stream()
                .filter(tag -> !tags().contains(tag))
                .toList();
    }
}
