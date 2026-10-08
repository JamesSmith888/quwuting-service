package org.quwuting.quwutingservice.announcement;

import org.junit.jupiter.api.Test;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementCategory;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementTouchLevel;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 首页公告位（home slot）不变量静态校验（2026-10-08，零依赖：不连库、不起 Spring）。
 *
 * <p><b>为什么需要它</b>：本轮修复的是「置顶动作系统性不兑现」——根因是首页公告位
 * 容量为 1 的稀缺资源，却用布尔列 {@code pinned} 表达，且没有任何一层声明过这个容量。
 * 修复分三层设防（领域层 HomeSlotService / 数据层 V44 唯一索引 / 自愈 reconcile），
 * 三层都<b>依赖同一份判据</b>：位置口径的判据单点在
 * {@link AnnouncementTouchLevel#eligibleForHomeSlot()}（2026-10-08 16:57 用户拍板后
 * = 全档放行；容量=1 由 HomeSlotService + V44 保证）。
 * <p>
 * 纯文本层面的镜像引用必须有断言锁住，否则只能等用户肉眼发现（本仓教训：
 * 门店别名域「注释改了谓词没改」）。
 * <p>
 * <b>锁定的不变量</b>：
 * <ul>
 *   <li><b>位置判据单点</b>：占位资格唯一在 {@code eligibleForHomeSlot()} 声明
 *       （当前恒放行）；{@code AnnouncementService#resolvePinned} 必须消费它而非另写 if/else；</li>
 *   <li><b>下线 / 软删必须释放位</b>：这是历史 36 条幽灵置顶的形成机制
 *       （"永不下线 + 置顶"），两条路径都必须调release；</li>
 *   <li><b>数据层兜底存在</b>：V44 迁移必须含生成列 + UNIQUE INDEX，
 *       否则"绕过应用层直接改库"仍能造出两条占位；</li>
 *   <li><b>SQL 不用 bulk UPDATE 改 pinned</b>：{@code @Modifying} 绕过持久化上下文，
 *       会让"已被 bulk 清成 false 的行在 PC 里仍是 true"，后续 save 因字段无变化而不发
 *       UPDATE ⇒ DB 与实体永久分叉。</li>
 * </ul>
 */
class HomeSlotContractTest {

    /** 取后端源码文件（相对模块根） */
    private static String source(String relative) throws Exception {
        Path p = Path.of("src/main/java/org/quwuting/quwutingservice/announcement", relative);
        assertTrue(Files.exists(p), "源码不存在（路径变更？）：" + p);
        return Files.readString(p);
    }

    /**
     * 去掉注释（判"代码里有没有某串"时必须先剥）。
     *
     * <p>⛔ 本仓契约大量写在 Javadoc 里 —— 判据原文就是「禁在别处直接
     * {@code setPinned(true)}」，不剥注释会命中<b>这条警告自己</b>，判据永远为真 = 假绿。
     * （初版就踩了：Java 测试与 Python 门禁各踩一次，同一个坑两次。）
     */
    private static String code(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    // ── 判据本身（纯枚举，零IO） ─────────────────────────────────

    @Test
    void bothLevelsEligibleForHomeSlotAfterDecoupling() {
        // 2026-10-08 16:57 用户拍板「舞讯依旧要顶置，以我为准」：位置与打扰解耦，全档放行。
        // （上午版曾断言 SILENT 不可占位——该判据当日被用户推翻；沿革见 eligibleForHomeSlot。）
        assertTrue(AnnouncementTouchLevel.ALERT.eligibleForHomeSlot(), "ALERT 档可占首页位");
        assertTrue(AnnouncementTouchLevel.SILENT.eligibleForHomeSlot(),
                "SILENT 档（含每日舞讯）也可显式占位——位置是显式决策，容量=1 由 HomeSlotService/V44 保证");
    }

    @Test
    void dataUpdateDefaultsToSilentAndMayStillPin() {
        // 默认档派生（未读口径不变）：DATA_UPDATE 缺省 SILENT——档位只决定「是否计入未读」。
        assertEquals(AnnouncementTouchLevel.SILENT,
                AnnouncementCategory.DATA_UPDATE.defaultTouchLevel(),
                "数据更新/每日舞讯的缺省档仍为 SILENT（不产生未读打扰）");
        // 位置口径已解耦（16:57 用户拍板）：缺省档同样可被显式置顶。
        assertTrue(AnnouncementCategory.DATA_UPDATE.defaultTouchLevel().eligibleForHomeSlot(),
                "DATA_UPDATE（含每日舞讯）凭显式 pinned=true 可占首页位（2026-10-08 用户拍板）");
    }

    // ── SYSTEM 通道显式不占位 ────────────────────────────────────

    @Test
    void systemDataUpdateChannelExplicitlyDoesNotClaimHomeSlot() throws Exception {
        String svc = source("service/AnnouncementService.java");
        // createDataUpdateAnnouncement 方法体内必须显式 setPinned(false)
        int start = svc.indexOf("public Optional<Announcement> createDataUpdateAnnouncement");
        assertTrue(start > 0, "找不到 createDataUpdateAnnouncement");
        int end = svc.indexOf("// ── 定时任务", start);
        assertTrue(end > start, "createDataUpdateAnnouncement 方法边界丢失（注释结构变了？）");
        String body = svc.substring(start, end);
        assertTrue(body.contains("a.setPinned(false)"),
                "自动数据更新通道保持显式 setPinned(false)——批处理自动生成、不自动抢占运营位；"
                        + "每日舞讯的置顶由显式发布链路 claim（2026-10-08 用户拍板「舞讯依旧要顶置」）。"
                        + "显式声明而非隐式默认的理由不变");
    }

    // ── 下线 / 软删释放位 ────────────────────────────────────────

    @Test
    void offlineAndDeleteBothReleaseHomeSlot() throws Exception {
        String svc = source("service/AnnouncementService.java");
        // 抽 offline 与 delete 两个方法体，各自必须调release
        assertTrue(methodBody(svc, "public AdminAnnouncementResponse offline(").contains("homeSlotService.release"),
                "下线必须释放首页位：否则过期公告永久霸占首页位（历史 36 条幽灵置顶的机制之一）");
        assertTrue(methodBody(svc, "public void delete(").contains("homeSlotService.release"),
                "软删必须释放首页位（生成列条件含 deleted=0，但实体侧须同步落 false 才与 PC 一致）");
    }

    /** 抽取「方法签名 → 到下一个顶层方法声明为止」的源码片段（按签名首行定位） */
    private static String methodBody(String src, String signatureStart) {
        int start = src.indexOf(signatureStart);
        assertTrue(start > 0, "找不到方法：" + signatureStart);
        int next = src.indexOf("\n    public ", start + signatureStart.length());
        return next > start ? src.substring(start, next) : src.substring(start);
    }

    // ── 置顶写入单点（禁散落 setPinned(true)） ──────────────────

    @Test
    void pinnedIsOnlyWrittenInSinglePlace() throws Exception {
        String svcCode = code(source("service/AnnouncementService.java"));
        assertFalse(svcCode.contains("setPinned(true)"),
                "AnnouncementService 里出现 setPinned(true)：占位是独占语义，必须经"
                        + " applyPinned → HomeSlotService.claim（含冲突校验与清场），"
                        + "散落的裸赋值会绕过唯一索引之外的全部设防");
        // 占位唯一写入点必须真的提供 claim（否则上面那条约束无处落地）
        String slotCode = code(source("service/HomeSlotService.java"));
        assertTrue(slotCode.contains("public void claim(Announcement target)"),
                "HomeSlotService 缺 claim（占位的唯一写入点缺失）");
        assertTrue(slotCode.contains("assertClaimable"),
                "HomeSlotService 缺 assertClaimable（冲突必须在写前显式拒绝，不自动抢占）");
        assertTrue(slotCode.contains("int reconcile()"),
                "HomeSlotService 缺 reconcile（自愈兜底缺失，脏数据将长期占位）");
    }

    // ── 数据层兜底 ──────────────────────────────────────────────

    @Test
    void migrationHasUniqueIndexBackstop() throws Exception {
        Path p = Path.of("src/main/resources/db/migration-mysql/V44__announcement_home_slot.sql");
        assertTrue(Files.exists(p), "V44 迁移缺失——数据层兜底（唯一索引）是三防线之一");
        String sql = Files.readString(p);
        assertTrue(sql.contains("GENERATED ALWAYS AS"), "V44 必须用生成列表达部分唯一约束（V7/V18 同款手法）");
        assertTrue(sql.contains("CREATE UNIQUE INDEX"), "V44 必须建 UNIQUE INDEX，否则绕过应用层仍可两条占位");
        assertTrue(sql.contains("'HOME_SLOT'"),
                "生成列必须取常量 'HOME_SLOT'（未置顶时取 NULL，唯一索引忽略 NULL）");
    }

    @Test
    void migrationDoesNotBulkUpdatePinned() throws Exception {
        Path p = Path.of("src/main/resources/db/migration-mysql/V44__announcement_home_slot.sql");
        String sql = Files.readString(p);
        // 存量归位允许 UPDATE（一次性数据修复，逐行语义等价）；禁的是 UPDATE ... JOIN /
        // 子查询式批量改 pinned —— 那在有并发写入时会与唯一索引打架。
        assertFalse(sql.contains("UPDATE qwt_announcements a"), "存量归位不应用多表 UPDATE（无法保证单条命中）");
        assertTrue(sql.contains("touch_level = 'ALERT'"),
                "存量归位的保留判据必须是 ALERT（值得打扰的那类），不得手抄具体 id 列表");
    }

    // ── 不走 bulk UPDATE（PC 分叉风险） ──────────────────────────

    @Test
    void repositoryDoesNotBulkUpdatePinned() throws Exception {
        String repo = source("repository/AnnouncementRepository.java");
        assertFalse(repo.contains("@Modifying\n    @Query(\"UPDATE Announcement a SET a.pinned"),
                "pinned 不得用 @Modifying bulk UPDATE 改：它绕过持久化上下文，会让"
                        + "\"已被 bulk 清成 false 的行在 PC 里仍是 true\"，后续 save 因字段无变化"
                        + "而不发 UPDATE ⇒ DB 与实体永久分叉");
        assertTrue(repo.contains("findAllPinned") && repo.contains("findTopByPinnedTrueAndDeletedFalseOrderByIdDesc"),
                "占位记账所需查询缺失（清场 / 自愈收敛 / 当前占位者）");
    }
}