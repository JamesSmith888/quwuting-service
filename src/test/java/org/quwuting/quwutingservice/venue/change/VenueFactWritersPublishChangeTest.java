package org.quwuting.quwutingservice.venue.change;

import org.junit.jupiter.api.Test;
import org.quwuting.quwutingservice.venue.service.VenueHeatService;
import org.quwuting.quwutingservice.venue.service.VenueService;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 门店读模型失效契约（2026-10-01，零依赖：源码扫描 + 反射）。
 *
 * <h2>为什么需要它</h2>
 * 门店相关缓存（实体 / 详情公共部分 / 列表 / 城市分面 / 热度 / 热门集合 / 城市统计）曾由
 * 每条写路径各自「记得」失效其中几个：舞讯批量写库漏逐出实体缓存（详情 60s 内仍显示旧状态）、
 * 别名写路径漏失效列表（新别名搜不到）、活动到期调度什么都不失效、批量补坐标什么都不失效……
 * 每一处都是「新写路径没抄全清单」。2026-10-01 收敛为：写路径只经 {@link VenueChangePublisher}
 * 声明变更，缓存属主订阅 {@link VenueFactsChangedEvent} 自行失效。本测试锁住三条结构约束：
 * <ol>
 *   <li><b>写入方必须声明</b>：凡对门店事实表（门店 / 照片 / 别名 / 活动）的仓库执行写操作
 *       （CRUD 写方法或 {@code @Modifying} 方法）的类，必须经 {@code VenueChangePublisher#publish}；</li>
 *   <li><b>属主不暴露局部失效</b>：{@link VenueService} 不得再有 public {@code invalidate*} 方法
 *       （有就会被外部「只失效一部分」）；</li>
 *   <li><b>属主监听器在提交后执行</b>：各属主的监听器必须 AFTER_COMMIT + fallbackExecution。</li>
 * </ol>
 * 局限：第 1 条是「类级」检查——同一个类里新增的写方法忘了发布不会被发现（VenueService 本身
 * 就同时是写入方与发布方）；它拦住的是最常见的形态「新服务直接写仓库、完全没接事件」。
 */
class VenueFactWritersPublishChangeTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java");

    /** 门店事实表的仓库（类名 → 源文件相对路径）；新增门店事实表须登记于此 */
    private static final Map<String, String> FACT_REPOSITORIES = Map.of(
            "VenueRepository", "org/quwuting/quwutingservice/venue/repository/VenueRepository.java",
            "VenuePhotoRepository", "org/quwuting/quwutingservice/venue/repository/VenuePhotoRepository.java",
            "VenueAliasRepository", "org/quwuting/quwutingservice/venue/repository/VenueAliasRepository.java",
            "VenueActivityRepository",
            "org/quwuting/quwutingservice/venueactivity/repository/VenueActivityRepository.java");

    /** Spring Data 继承来的写方法 */
    private static final Set<String> CRUD_WRITE_METHODS = Set.of(
            "save", "saveAll", "saveAndFlush", "saveAllAndFlush",
            "delete", "deleteAll", "deleteById", "deleteAllById", "deleteAllInBatch", "deleteInBatch");

    private static final Pattern MODIFYING_METHOD = Pattern.compile(
            "@Modifying[\\s\\S]*?\\b(?:void|int|long|Integer|Long)\\s+(\\w+)\\s*\\(");

    @Test
    void everyVenueFactWriterPublishesChange() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles()) {
            String source = Files.readString(file);
            if (writesVenueFacts(source) && !source.contains("venueChangePublisher.publish(VenueFactChange.")) {
                offenders.add(MAIN_JAVA.relativize(file).toString());
            }
        }
        assertTrue(offenders.isEmpty(),
                "以下类写门店事实表却未经 VenueChangePublisher 声明变更（缓存会在 TTL 内显示旧数据）："
                        + offenders + "。写库后调用 venueChangePublisher.publish(VenueFactChange.X, venueIds)，"
                        + "见 docs/agents/29-performance.md「门店读模型失效：领域事件」");
    }

    @Test
    void scannerRecognisesKnownWriters() throws IOException {
        // 自检：扫描器若因正则失配而什么都识别不到，上一条测试会恒绿（形同不存在）
        Set<String> writers = new LinkedHashSet<>();
        for (Path file : javaFiles()) {
            if (writesVenueFacts(Files.readString(file))) {
                writers.add(file.getFileName().toString());
            }
        }
        for (String expected : List.of("VenueService.java", "DailyOpeningService.java",
                "VenueAliasService.java", "VenueActivityService.java", "VenueSyncDataService.java")) {
            assertTrue(writers.contains(expected), "扫描器未识别已知写入方 " + expected + "，识别到：" + writers);
        }
    }

    @Test
    void cacheOwnerExposesNoPartialInvalidation() {
        for (Method method : VenueService.class.getDeclaredMethods()) {
            assertFalse(Modifier.isPublic(method.getModifiers()) && method.getName().startsWith("invalidate"),
                    "VenueService 不得暴露 public 失效方法 " + method.getName()
                            + "——外部写路径应发布 VenueFactsChangedEvent，由属主统一失效");
        }
    }

    @Test
    void cacheOwnersListenAfterCommitWithFallback() throws NoSuchMethodException {
        for (Class<?> owner : List.of(VenueService.class, VenueHeatService.class, VenueSharedCacheInvalidator.class)) {
            Method listener = owner.getMethod("onVenueFactsChanged", VenueFactsChangedEvent.class);
            TransactionalEventListener annotation = listener.getAnnotation(TransactionalEventListener.class);
            assertTrue(annotation != null, owner.getSimpleName() + " 的监听器必须是 @TransactionalEventListener");
            assertEquals(TransactionPhase.AFTER_COMMIT, annotation.phase(),
                    owner.getSimpleName() + " 必须在提交后失效（提交前失效会被并发读回填旧值）");
            assertTrue(annotation.fallbackExecution(),
                    owner.getSimpleName() + " 必须 fallbackExecution=true（逐条独立提交的批量入口无外层事务）");
        }
    }

    private static boolean writesVenueFacts(String source) throws IOException {
        for (Map.Entry<String, String> repo : FACT_REPOSITORIES.entrySet()) {
            Matcher field = Pattern.compile("\\b" + repo.getKey() + "\\s+(\\w+)\\s*;").matcher(source);
            while (field.find()) {
                Set<String> writeMethods = new LinkedHashSet<>(CRUD_WRITE_METHODS);
                writeMethods.addAll(modifyingMethods(repo.getValue()));
                Pattern call = Pattern.compile("\\b" + Pattern.quote(field.group(1)) + "\\s*\\.\\s*("
                        + String.join("|", writeMethods) + ")\\s*\\(");
                if (call.matcher(source).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<String> modifyingMethods(String repositoryPath) throws IOException {
        String source = Files.readString(MAIN_JAVA.resolve(repositoryPath));
        Set<String> names = new LinkedHashSet<>();
        Matcher m = MODIFYING_METHOD.matcher(source);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("/repository/"))
                    .toList();
        }
    }
}
