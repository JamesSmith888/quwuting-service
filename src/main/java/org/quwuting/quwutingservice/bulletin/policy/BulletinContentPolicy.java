package org.quwuting.quwutingservice.bulletin.policy;

import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.opsconfig.service.OpsConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * 行业快讯内容红线（2026-10-01，规则见 docs/agents/47-bulletins.md「内容红线的服务端执行」）。
 *
 * <h2>为什么必须在服务端</h2>
 * 「快讯只写服务可得性、禁写原因与事件经过」是名誉权层面的硬约束，此前只存在于发布 Skill
 * 的提示词里——而快讯的主要作者恰恰是 Agent（每日自动发布）。提示词是概率性的，一次措辞
 * 失误就会以平台名义发出「XX 舞厅因被查暂停营业」。服务端是所有发布通道（管理端手工 /
 * Agent 一步发布 / 编辑）唯一必经的点，红线必须落在这里。
 *
 * <h2>做法</h2>
 * 词表 = classpath 资源 {@value #TERMS_RESOURCE}（数据而非代码，可评审、可增删），启动时加载，
 * 资源缺失或为空 ⇒ 启动失败（宁可起不来，也不要静默放行）。命中任一词 ⇒ 1035 拒绝，
 * 消息点名命中词，便于作者改写为纯可得性表述。
 *
 * <h2>为什么有开关、且默认关闭</h2>
 * 用户 2026-09-10 拍板「不引入自动敏感词拦截」（词表化会把风控责任转移到代码、易产生虚假安全感，
 * 由发布前人工把控 + 管理端常驻边界提醒承担，见 47 号文档 §1.2）。此后快讯的主要作者变成了
 * 每日自动发布的 Agent，「发布前人工把控」的前提随之变弱——但是否改用服务端拦截仍是产品决定，
 * 不由代码替用户做。故执行受运营开关 {@value #KEY_ENABLED} 控制（V36 插入默认 {@code false}），
 * 开启即对 create / update / agent-publish 三条写路径生效，可在管理后台「运营配置」热切换。
 */
@Component
public class BulletinContentPolicy {

    static final String TERMS_RESOURCE = "content-policy/bulletin-forbidden-terms.txt";

    /** 执行开关（运营配置，V36 默认 false；见类注释「为什么有开关」） */
    public static final String KEY_ENABLED = "bulletin.content_redline.enabled";

    /** 越过内容红线（登记见 docs/agents/12-api-conventions.md） */
    static final int CODE_CONTENT_REDLINE = 1035;

    private final List<String> forbiddenTerms;
    private final BooleanSupplier enabled;

    @Autowired
    public BulletinContentPolicy(OpsConfigService opsConfigService) {
        this(loadTerms(TERMS_RESOURCE), () -> opsConfigService.isEnabled(KEY_ENABLED, false));
    }

    BulletinContentPolicy(List<String> forbiddenTerms, BooleanSupplier enabled) {
        if (forbiddenTerms.isEmpty()) {
            throw new IllegalStateException("快讯红线词表为空：" + TERMS_RESOURCE);
        }
        this.forbiddenTerms = List.copyOf(forbiddenTerms);
        this.enabled = enabled;
    }

    /** 开关开启时校验快讯正文；命中红线抛 {@link BusinessException}(1035)。开关关闭 ⇒ 不拦截 */
    public void check(String content) {
        if (!enabled.getAsBoolean()) {
            return;
        }
        List<String> hits = findHits(content);
        if (!hits.isEmpty()) {
            throw new BusinessException(CODE_CONTENT_REDLINE,
                    "快讯只写营业可得性（哪家店、哪个时段开或关），不写原因与事件经过：命中「"
                            + String.join("、", hits) + "」，请改写后再发布");
        }
    }

    /** 命中的红线词（按词表顺序去重；无命中返回空列表） */
    List<String> findHits(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String text = content.toLowerCase(Locale.ROOT);
        Set<String> hits = new LinkedHashSet<>();
        for (String term : forbiddenTerms) {
            if (text.contains(term)) {
                hits.add(term);
            }
        }
        return List.copyOf(hits);
    }

    static List<String> loadTerms(String resource) {
        ClassPathResource file = new ClassPathResource(resource);
        if (!file.exists()) {
            throw new IllegalStateException("快讯红线词表缺失：" + resource);
        }
        List<String> terms = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String term = line.strip();
                if (!term.isEmpty() && !term.startsWith("#")) {
                    terms.add(term.toLowerCase(Locale.ROOT));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("快讯红线词表读取失败：" + resource, e);
        }
        return terms;
    }
}
