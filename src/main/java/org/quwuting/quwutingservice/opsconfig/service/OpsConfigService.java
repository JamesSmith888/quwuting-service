package org.quwuting.quwutingservice.opsconfig.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.opsconfig.dto.response.OpsConfigItem;
import org.quwuting.quwutingservice.opsconfig.entity.OpsConfig;
import org.quwuting.quwutingservice.opsconfig.repository.OpsConfigRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 运营配置服务（2026-08-14 新增，feature flag 设施）。
 * <p>
 * 定位：可热更新的动态产品规则（区别于 {@code application.yaml} 部署期常量）。
 * 读路径（高频——每次 Reaction toggle 读一次开关）走服务内嵌 Caffeine LoadingCache
 * （聚合缓存同款模式，见 CacheConfig javadoc）：单飞回源 + 30s 短 TTL 兜底 +
 * 写路径显式 {@code invalidate} 即时生效。
 * <p>
 * 键名即代码契约（{@link #KEY_REACTION_DAILY_SINGLE} 等常量），
 * 新增配置键必须同时：Flyway 迁移插入默认行 + 本服务定义常量 + 前端描述表登记。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsConfigService {

    /**
     * Reaction「每日唯一表情」开关（默认 true = 一票制生效；关闭恢复多选）：
     * 同一用户对同一场所每天只能贡献一个 Reaction，点新表情 = 当日旧票原子替换。
     */
    public static final String KEY_REACTION_DAILY_SINGLE = "reaction.daily.single";

    /**
     * 舞伴认可「每日唯一表情」开关（2026-08-15，默认 true = 一票制生效；关闭恢复多选）：
     * 同一用户对同一舞伴每天只能点一枚认可表情，点新表情 = 当日旧票原子换票
     * （语义与 {@link #KEY_REACTION_DAILY_SINGLE} 完全对齐，见 V31 迁移注释）。
     */
    public static final String KEY_DANCER_RECOGNITION_DAILY_SINGLE = "dancer.recognition.daily.single";

    /**
     * 联系方式「每日首免」开关（2026-08-26，默认 false = 下线；开启恢复）：
     * 每个用户每天对「有积分门槛（cost>0）」的舞伴第一次获取联系方式免费
     * （hasGatedContactUnlockToday 判定，任意有门槛舞伴消耗额度），无门槛舞伴
     * 恒免费（gate 不存在）与首免正交、不受本开关影响。见 V49 迁移注释。
     */
    public static final String KEY_DANCER_CONTACT_DAILY_FREE = "dancer.contact.daily.free";

    /**
     * 数据更新公告「自动生成」开关（2026-09-01，默认 false = 下线；V7 迁移插入默认行）：
     * venuesync 写库成功后（batch-create / 营业状态 batch 反转）自动创建 SYSTEM 来源
     * 数据更新公告。关闭时 createDataUpdateAnnouncement 直接返回（不产生公告）。
     */
    public static final String KEY_ANNOUNCEMENT_DATA_UPDATE_ENABLED = "announcement.data_update.enabled";

    /**
     * 数据更新公告正文模板（2026-09-01，V7 迁移插入默认行）：占位符 {new} = 新增门店数、
     * {reversed} = 恢复营业数；AnnouncementService.createDataUpdateAnnouncement 渲染后
     * 写入公告 content（禁业务硬编码——模板可运营配置）。
     */
    public static final String KEY_ANNOUNCEMENT_DATA_UPDATE_TEMPLATE = "announcement.data_update.template";

    /**
     * 数据更新公告自动下线时长（小时，2026-09-02，缺省 24；≤0 = 不自动下线）：
     * SYSTEM 数据更新公告是"当日信息"，创建时 offline_at = publish_at + 本时长，
     * 到点由 @Scheduled 强转 OFFLINE——每日公告天然自过期，不永久霸占可见列表。
     * 运营可改值调整生命周期；无需迁移插入默认行（代码缺省兜底）。
     */
    public static final String KEY_ANNOUNCEMENT_DATA_UPDATE_AUTO_OFFLINE_HOURS = "announcement.data_update.auto_offline_hours";

    /**
     * 热度统计「内部账号排除名单」（2026-09-19，V31 迁移插入默认行，值默认空）：
     * 逗号分隔的 user id；命中的账号**不参与任何热度公式输入**（浏览/收藏/评分/正向反馈/
     * 收到积分），也不进列表排序与热门判定。
     * <p>
     * 根因（生产实证）：热度公式的最高权重输入是「主动信号」（收藏×8 / 评分×8 / 反馈×3），
     * 这类信号与到访无关、可零成本重复产出。2026-09-19「约翰（歌友会）」6 天登顶全国第 1，
     * 97% 分数来自 15 个账号点击，其中 13 个是平台早期批量注册账号（含 ADMIN）；全网口径
     * 14 个 id ≤ 20 的账号贡献 76% 反馈行 / 49% 收藏行——榜单度量的是内部点击而非用户人气。
     * <p>
     * 与 ADMIN 的关系：ADMIN 角色由代码侧**无条件派生**（客观字段，零配置）；本配置项只承载
     * 「非 ADMIN 但同样是内部/测试」的账号——这类账号无法由任何客观字段推断，只能人工登记。
     * 两部分在 {@code HeatAccountExclusionService} 合并为一个排除集合。
     */
    public static final String KEY_HEAT_EXCLUDED_USER_IDS = "heat.excluded.user.ids";

    private final OpsConfigRepository opsConfigRepository;

    /** 单键配置缓存（LoadingCache + Optional 承载"键不存在"——Caffeine 禁 null 值） */
    private LoadingCache<String, Optional<String>> cache;

    /** 全量配置缓存固定键（单值集合缓存） */
    private static final String ALL_VALUES_KEY = "all";

    /** 全量配置缓存（2026-08-30 性能优化）：GET /ops-config 首页冷启动必拉，实测 221ms。
     *  配置低频变化（管理端改，写路径 {@link #setValue} 显式失效即时生效），
     *  60s TTL 兜底 + 单飞回源（同单键缓存模式）。 */
    private LoadingCache<String, Map<String, String>> allValuesCache;

    @PostConstruct
    void initCache() {
        cache = Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfterWrite(30, TimeUnit.SECONDS)
                .build(key -> opsConfigRepository.findByKey(key).map(OpsConfig::getValue));
        allValuesCache = Caffeine.newBuilder()
                .maximumSize(2)
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .build(key -> opsConfigRepository.findAll().stream()
                        .collect(Collectors.toMap(OpsConfig::getKey, OpsConfig::getValue)));
    }

    /**
     * 读取单键配置值（缓存优先，miss 单飞回源）。
     *
     * @return 配置值；键不存在（未发版登记的键 / 尚未 seed）时为 {@link Optional#empty()}
     */
    public Optional<String> getValue(String key) {
        return cache.get(key);
    }

    /**
     * 布尔开关语义读取：值为 "true"/"1"（忽略大小写）→ true；其他值 → false；
     * 键不存在 → 返回调用方提供的默认值（产品规则的代码侧默认，见各常量 javadoc）。
     */
    public boolean isEnabled(String key, boolean defaultValue) {
        String v = getValue(key).orElse(null);
        if (v == null) return defaultValue;
        return "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    /**
     * 整数配置语义读取（2026-09-14 新增，供人工锁时长等数值型开关使用）：
     * 值可解析为整数即返回；不可解析（空/非数字）或键不存在 → 返回调用方提供的默认值。
     * 非法值不抛异常——运营手滑写错不该让写库主流程 500，退回代码侧默认更安全。
     */
    public int getInt(String key, int defaultValue) {
        String v = getValue(key).orElse(null);
        if (v == null) return defaultValue;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            log.warn("ops config {} 值非整数（{}），退回默认 {}", key, v, defaultValue);
            return defaultValue;
        }
    }

    /**
     * 公开读取全部配置（前端 feature flag 初始化；值为非敏感的开关字符串，无需鉴权）。
     * 缓存优先（60s TTL + 单飞）；管理端 setValue 写路径显式失效，新值即时生效。
     */
    public Map<String, String> getAllValues() {
        return allValuesCache.get(ALL_VALUES_KEY);
    }

    /**
     * 管理端配置列表（含最近修改时刻，供管理页渲染）。
     */
    public List<OpsConfigItem> listAll() {
        return opsConfigRepository.findAll().stream()
                .map(c -> new OpsConfigItem(c.getKey(), c.getValue(), c.getUpdatedAt()))
                .collect(Collectors.toList());
    }

    /**
     * 更新单键配置（管理端调用，调用方负责 requireAdmin）。
     * key 必须已存在（新增键的唯一通道 = Flyway 迁移）——防止手滑造出无人消费的配置。
     * 保存后显式失效缓存，新值即时生效（不等 30s TTL）。
     */
    public void setValue(String key, String value, Long adminId) {
        OpsConfig cfg = opsConfigRepository.findByKey(key).orElseThrow(() ->
                new BusinessException(1016, "配置项不存在：" + key));
        cfg.setValue(value);
        cfg.setUpdatedBy(adminId);
        cfg.setUpdatedAt(LocalDateTime.now());
        opsConfigRepository.save(cfg);
        cache.invalidate(key);
        allValuesCache.invalidate(ALL_VALUES_KEY);
        log.info("ops config updated: {} = {} (by user {})", key, value, adminId);
    }
}
