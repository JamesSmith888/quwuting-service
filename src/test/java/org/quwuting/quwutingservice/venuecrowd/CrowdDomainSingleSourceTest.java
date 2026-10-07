package org.quwuting.quwutingservice.venuecrowd;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 热度域「口径单一出处」静态门禁（2026-10-07，文档 = docs/agents/53-venue-crowd-stats.md「防复发」）。
 * <p>
 * <b>它防的是什么</b>：2026-09-03 的实现里，「确认态」被写了两遍（{@code resolveTier} 与
 * {@code WindowSnapshot.confirmed()}），靠一句注释「判定口径必须与 resolveTier 一致」维系；
 * 日期坐标是 {@code LocalDate.now()}（自然日）散落在服务里；阈值是服务类里的 {@code static final}。
 * 这三类问题的共性：<b>决策依据存在于 N 处，一致性由人记得</b>。它们不会在编译期、也不会在既有单测里暴露——
 * 改了一处漏了另一处，系统照常运行，只是悄悄不自洽。
 * <p>
 * 这里用机器断言钉住结构：
 * <ol>
 *   <li>数值型口径常量只许在 {@code CrowdPolicy} 声明（例外登记在 {@link #ALLOWED_LOCAL_CONSTANTS}，fail-closed）；</li>
 *   <li>包内禁止 {@code LocalDate.now()}（日期归属一律经 {@code BusinessDay}）与窗口算术的字面数字；</li>
 *   <li>一致性 / 确认阈值只在 {@code CrowdPolicy} 与 {@code CrowdConsensus} 的代码里出现；</li>
 *   <li>旧的第二份判定实现的名字不得回流；</li>
 *   <li>唯一键与「我的上报」查询走营业日；认领人排除在列表查询里存在。</li>
 * </ol>
 * 局限：文本级断言，不验数值语义——数值语义由 {@code CrowdConsensusTest} 等表驱动用例承担。
 */
class CrowdDomainSingleSourceTest {

    private static final Path ROOT = Path.of("src/main/java/org/quwuting/quwutingservice/venuecrowd");
    private static final Path MIGRATION = Path.of("src/main/resources/db/migration-mysql/V41__crowd_report_business_date.sql");

    /** 允许在非 CrowdPolicy 文件里声明的数值常量——新增必须先回答「为什么它不是口径」，再来这里登记。 */
    private static final Set<String> ALLOWED_LOCAL_CONSTANTS = Set.of(
            "HISTORY_PAGE_SIZE_LIMIT",   // 分页防深翻页（接口保护，非统计口径）
            "ADMIN_WINDOW_HOURS",        // 管理端运营视角窗口（非用户可见统计）
            "HIGH_MODIFY_THRESHOLD",     // 管理端刷量嫌疑启发式
            "EPS");                      // 浮点比较容差

    private static final Pattern NUMERIC_CONSTANT =
            Pattern.compile("static\\s+final\\s+(?:int|long|double|float)\\s+([A-Za-z_0-9]+)\\s*=");

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> files = Files.walk(ROOT)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
    }

    /** 去掉块注释与行注释后的代码文本（注释里写「禁 LocalDate.now()」不应触发门禁）。 */
    private static String code(Path path) throws IOException {
        String text = Files.readString(path);
        text = text.replaceAll("(?s)/\\*.*?\\*/", "");
        text = text.replaceAll("(?m)//.*$", "");
        return text;
    }

    private static boolean isPolicyFile(Path p) {
        return p.getFileName().toString().equals("CrowdPolicy.java");
    }

    @Test
    void numericPolicyConstantsAreDeclaredOnlyInCrowdPolicy() throws IOException {
        for (Path p : mainSources()) {
            if (isPolicyFile(p)) {
                continue;
            }
            Matcher m = NUMERIC_CONSTANT.matcher(code(p));
            while (m.find()) {
                String name = m.group(1);
                assertTrue(ALLOWED_LOCAL_CONSTANTS.contains(name),
                        p.getFileName() + " 声明了数值常量 " + name + "——口径常量必须放进 CrowdPolicy（单一出处）；"
                                + "若它确实不是口径（接口保护 / 运营启发式），到本类 ALLOWED_LOCAL_CONSTANTS 登记并写明理由");
            }
        }
    }

    @Test
    void dayCoordinatesAndWindowMathGoThroughBusinessDayAndPolicy() throws IOException {
        Pattern literalWindow = Pattern.compile("\\.(?:minus|plus)(?:Hours|Days|Minutes)\\(\\s*\\d");
        for (Path p : mainSources()) {
            String src = code(p);
            assertFalse(src.contains("LocalDate.now("),
                    p.getFileName() + " 使用了 LocalDate.now()——日期归属一律经 BusinessDay.of(now)（自然日 ≠ 营业夜，见 V41 根因）");
            assertFalse(literalWindow.matcher(src).find(),
                    p.getFileName() + " 的窗口算术里有字面数字——窗口长度 / 回看天数来自 CrowdPolicy");
        }
    }

    @Test
    void consensusThresholdsAreUsedOnlyByThePolicyAndTheConsensusFunction() throws IOException {
        Set<String> owners = Set.of("CrowdPolicy.java", "CrowdConsensus.java");
        for (String token : List.of("CONFIRM_SHARE", "CONFIRM_MIN_VOTERS", "AGREEMENT_TOLERANCE_LEVELS")) {
            for (Path p : mainSources()) {
                if (owners.contains(p.getFileName().toString())) {
                    continue;
                }
                assertFalse(code(p).contains(token),
                        p.getFileName() + " 直接使用了 " + token + "——「是否一致 / 是否确认」只许由 CrowdConsensus 判定，"
                                + "其余处读 CrowdVerdict（2026-09-03 的双份实现就是这样漂移的）");
            }
        }
    }

    @Test
    void theSecondDecisionImplementationNeverComesBack() throws IOException {
        for (Path p : mainSources()) {
            String src = code(p);
            for (String ghost : List.of("resolveTier", "WindowSnapshot", "isConfirmedWindow", "winnerOf", "femaleShare")) {
                assertFalse(src.contains(ghost),
                        p.getFileName() + " 出现了旧实现的标识符 " + ghost + "——确认 / 冲突判定只有 CrowdConsensus 一份");
            }
        }
    }

    @Test
    void uniqueKeyAndMineLookupUseTheBusinessDate() throws IOException {
        String repo = code(ROOT.resolve("repository/VenueCrowdReportRepository.java"));
        assertTrue(repo.contains("business_date"), "upsert 必须写 business_date（V41 唯一键）");
        assertTrue(repo.contains("findByVenueIdAndUserIdAndBusinessDateAndDeletedFalse"), "「我的上报」按营业日查");
        assertFalse(repo.contains("findByVenueIdAndUserIdAndReportDate"), "report_date 是自然日，不再是「我的上报」的坐标");

        String entity = code(ROOT.resolve("entity/VenueCrowdReport.java"));
        assertTrue(entity.contains("LocalDate businessDate"), "实体必须映射 business_date（ddl-auto=validate）");

        String migration = Files.readString(MIGRATION);
        assertTrue(migration.contains("business_date"));
        assertTrue(migration.contains("uk_key_qwt_idx_crowd_reports_user_night"), "唯一键生成列换成营业日");
        assertTrue(migration.contains("DROP INDEX qwt_idx_crowd_reports_user_day"), "旧的自然日唯一键必须退役");
    }

    @Test
    void listQueriesExcludeTheClaimant() throws Exception {
        for (String method : List.of("countDistinctUsersByVenueIdsSince", "findLatestByVenueIdsSince")) {
            String sql = org.quwuting.quwutingservice.venuecrowd.repository.VenueCrowdReportRepository.class
                    .getMethod(method, Collection.class, LocalDateTime.class)
                    .getAnnotation(Query.class).value();
            assertTrue(sql.contains("claimed_by"),
                    method + " 必须排除认领人（文档 27 早有此承诺，此前从未实现）：JOIN qwt_venues 过滤 claimed_by");
        }
        // 「最新上报」的相关子查询内外两层都要带条件，否则最大值取到店家那条、外层过滤后整店丢行
        String latest = org.quwuting.quwutingservice.venuecrowd.repository.VenueCrowdReportRepository.class
                .getMethod("findLatestByVenueIdsSince", Collection.class, LocalDateTime.class)
                .getAnnotation(Query.class).value();
        assertEquals(2, latest.split("v\\.claimed_by IS NULL OR", -1).length - 1, "子查询内外两层都要排除认领人");
    }
}
