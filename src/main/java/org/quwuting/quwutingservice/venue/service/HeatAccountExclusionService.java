package org.quwuting.quwutingservice.venue.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.opsconfig.service.OpsConfigService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 热度统计「内部账号排除集合」唯一供给方（2026-09-19）。
 * <p>
 * <b>根因（生产实证）</b>：门店热度/排序公式的最高权重输入是「主动信号」——
 * 收藏 ×8 / 评分 ×8 / 正向反馈 ×3（浏览虽同样在公式内，但被 {@code ln} 压缩到 ≈9 上限）。
 * 这类信号**与到访无关、零成本、可重复产出**：收藏是每人一次但可多账号，正向反馈更是
 * **每人每天一票**（唯一键含 {@code reaction_date}）。2026-09-19 排查「约翰（歌友会）」
 * 事件（6 天登顶全国第 1，行为热度 158.9）实测：97% 的分数来自 15 个账号 6 天内的点击，
 * 其中 13 个是平台 2026-08-20 ~ 08-22 批量注册的早期账号（含 1 个 ADMIN）；放大到全网——
 * <b>14 个 id ≤ 20 的账号贡献了 76% 的反馈行、49% 的收藏行</b>，而全站有 361 个有浏览
 * 记录的真实用户。也就是说：榜单度量的是平台自己人的点击，不是用户人气。
 * <p>
 * <b>排除集合的唯一来源（2026-09-19 用户决策）</b>：
 * <p>
 * <b>当前不排除任何账号</b>——集合 = 运营配置名单
 * （{@link OpsConfigService#KEY_HEAT_EXCLUDED_USER_IDS}，V31 迁移建键，默认空）+ 哨兵，
 * 默认即"谁都不排除"。
 * <p>
 * <b>为什么当前为空（决策留痕，勿擅自填值）</b>：名单里大概率是平台内部/测试账号，但
 * 这批账号在数据上<b>与真实用户无法区分</b>（默认昵称、正常注册流程、正常行为）——
 * 名单本质是"人肉认定"，<b>只能由用户拍板</b>。在用户确认前保持为空是刻意的安全缺省：
 * 宁可暂时不治理，也不误伤真实账号。
 * <p>
 * <b>ADMIN 自动排除曾实现、现已下线（2026-09-19 用户决策："先不要做任何排除"）</b>：
 * 初版把 {@code role = ADMIN} 无条件并入排除集合（理由是"ADMIN 是唯一可由客观字段推断的
 * 内部账号"）。用户明确要求<b>暂不做任何排除</b>，故该分支已摘除——恢复方式见
 * {@code UserRepository.findIdsByRoleAndDeletedFalse} 的注释（一行查询即可接回）。
 * <b>摘除后热度公式不再对 ADMIN 有任何特殊处理</b>：管理员账号的浏览/收藏/打分/反馈
 * 与普通用户同权计入。
 * <p>
 * <b>不排除的代价（必须知晓，勿当成"已修复"）</b>：只做「去重人数」口径修正，约翰
 * （歌友会）的行为热度从 158.9 降到 113.9，但抖舞（有 522 次真实浏览）也从 150.3 降到
 * 111.3 —— <b>两者相对次序没变，约翰仍为全国第 1（真库实测 113.9 vs 111.3）</b>。
 * 根因：约翰的 15 个反馈人里 13 个、8 个收藏人里 7 个是内部账号，**"人数"这个口径
 * 只在"同一个人重复点击"这一维度上收紧了，没有在"这个人是谁"这一维度上收紧**。
 * 想让他掉下来，只有两条路：① 填名单（本服务的配置项）；② P1 的结构改造（主动信号
 * 压缩 / 人气基数门槛——不依赖名单也能让"真人少的店"无法靠按钮登顶）。
 * <p>
 * <b>为什么走参数而不是 SQL 内子查询</b>：排除集合要进 6 处热度输入（三套 SQL 镜像里的
 * 浏览/收藏/评分/反馈/积分子查询），用 {@code NOT EXISTS (SELECT ... FROM users)} 会在每条
 * 列表主查询里再叠 5 次跨表子查询；而集合规模是「十位数、分钟级稳定」，由本服务做一次
 * 查询 + Caffeine 缓存后以参数注入，SQL 侧只剩一个 {@code NOT IN :excludedUserIds}。
 * <p>
 * <b>恒非空契约（必须遵守）</b>：所有消费方把本列表直接拼进 {@code NOT IN}，空列表会渲染成
 * {@code NOT IN ()} 造成 SQL 语法错误。故返回值**恒含哨兵 {@link #SENTINEL_USER_ID}**（-1，
 * 不存在于任何业务表），消费方无需再判空。
 * <p>
 * <b>缓存语义</b>：30s TTL（与 {@link OpsConfigService} 单键缓存同量级）+ 单飞回源。
 * 名单是运营可改的策略，改后最长 30s 生效——热度聚合缓存本身是 60s refresh-ahead，
 * 两者量级一致，不会让"改名单"看起来没反应。不做写路径显式失效：本服务不持有写入口
 * （名单由管理端改 ops config），跨服务失效只为一个低频操作引入依赖，不值得。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HeatAccountExclusionService {

    /**
     * 哨兵 user id：保证 {@code NOT IN :excludedUserIds} 的集合恒非空。
     * 负数在 qwt_users 中不可能出现（IDENTITY 从 1 起），故对所有真实账号恒不命中。
     */
    private static final long SENTINEL_USER_ID = -1L;

    /** 缓存键（单值缓存，值恒定，键仅为占位——LoadingCache 不支持 null 键） */
    private static final String CACHE_KEY = "excluded";

    /** 名单缓存 TTL：与 OpsConfigService 单键缓存（30s）同量级 */
    private static final long CACHE_TTL_SECONDS = 30;

    private final OpsConfigService opsConfigService;

    private LoadingCache<String, List<Long>> cache;

    @PostConstruct
    void initCache() {
        cache = Caffeine.newBuilder()
                .maximumSize(1)
                .expireAfterWrite(CACHE_TTL_SECONDS, TimeUnit.SECONDS)
                .build(key -> loadExcludedUserIds());
    }

    /**
     * 热度统计排除集合（恒非空，末尾含哨兵 -1）。见类注释「恒非空契约」。
     * <p>
     * 返回 {@link List} 而非 {@link Set}：消费方是 Spring Data 的 {@code IN} 参数绑定
     * （按顺序绑定进 SQL 的 {@code IN (...)}），List 可直接使用；内部已用
     * {@link LinkedHashSet} 去重（配置名单自身可能有重复 id），
     * 保序只为让日志/排查可复现。
     */
    public List<Long> excludedUserIds() {
        return cache.get(CACHE_KEY);
    }

    /**
     * 缓存 loader：运营配置名单 ∪ 哨兵。
     * <p>
     * <b>不含 ADMIN 自动排除</b>——2026-09-19 用户决策「先不要做任何排除」，该分支已摘除，
     * 恢复方式见 {@code UserRepository.findIdsByRoleAndDeletedFalse} 注释。
     */
    private List<Long> loadExcludedUserIds() {
        Set<Long> ids = new LinkedHashSet<>();
        // ① 运营配置名单（V31 迁移建立的键；键不存在/值为空 = 空集，不抛错）。
        //    2026-09-19 现状：默认空 = 不排除任何账号（见类注释「当前不排除任何账号」）。
        ids.addAll(parseIds(opsConfigService
                .getValue(OpsConfigService.KEY_HEAT_EXCLUDED_USER_IDS)
                .orElse(null)));
        // ② 哨兵：恒非空契约（见类注释）
        ids.add(SENTINEL_USER_ID);
        List<Long> result = new ArrayList<>(ids);
        log.debug("热度排除集合：配置名单 {} 个（含哨兵）", result.size());
        return result;
    }

    /**
     * 解析逗号分隔的 user id 名单。
     * <p>
     * <b>容错取向（同 {@link OpsConfigService#getInt}）</b>：单个 token 非法（非数字 / 超范围）
     * 只跳过该 token 并告警，**不整份丢弃**——运营手滑写错一个 id 不该让整个排除名单失效
     * （那会让已经治理过的账号静默回到榜单里）。空 token（连续逗号 / 尾随逗号 / 换行）
     * 静默跳过，属正常书写习惯而非错误。
     */
    private List<Long> parseIds(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<Long> ids = new ArrayList<>();
        for (String token : raw.split("[,，\\s]+")) {
            if (token.isBlank()) continue;
            try {
                long id = Long.parseLong(token.trim());
                if (id > 0) {
                    ids.add(id);
                } else {
                    log.warn("热度排除名单：忽略非正数 id {}", token);
                }
            } catch (NumberFormatException e) {
                log.warn("热度排除名单：忽略非法 token「{}」（不影响其余 id 生效）", token);
            }
        }
        return ids;
    }
}
