package org.quwuting.quwutingservice.dancer.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.dancer.dto.request.RecognizeDancerRequest;
import org.quwuting.quwutingservice.dancer.dto.request.UpdateDancerVerificationRequest;
import org.quwuting.quwutingservice.dancer.dto.request.UpsertDancerRequest;
import org.quwuting.quwutingservice.dancer.dto.response.DancerDetailResponse;
import org.quwuting.quwutingservice.dancer.dto.response.DancerRecognitionStats;
import org.quwuting.quwutingservice.dancer.dto.response.RecognizeResponse;
import org.quwuting.quwutingservice.dancer.entity.Dancer;
import org.quwuting.quwutingservice.dancer.entity.DancerPhoto;
import org.quwuting.quwutingservice.dancer.entity.DancerRecognition;
import org.quwuting.quwutingservice.dancer.entity.DancerVenue;
import org.quwuting.quwutingservice.dancer.enums.DancerPhotoStatus;
import org.quwuting.quwutingservice.dancer.enums.DancerStatus;
import org.quwuting.quwutingservice.dancer.enums.DancerVenueRelation;
import org.quwuting.quwutingservice.dancer.enums.DancerVerificationAction;
import org.quwuting.quwutingservice.dancer.enums.DancerVerificationStatus;
import org.quwuting.quwutingservice.dancer.repository.DancerAdViewRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerFavoriteRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerPhotoRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerRecognitionRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerRecognitionTagRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerCityRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerVenueRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerVerificationLogRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerViewRepository;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.message.enums.MessageType;
import org.quwuting.quwutingservice.message.service.MessageService;
import org.quwuting.quwutingservice.opsconfig.service.OpsConfigService;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.quwuting.quwutingservice.venue.service.VenueLookupService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DancerService 单元测试（Mockito，不依赖数据库）。
 * 覆盖：创建（PENDING/NORMAL/清洗/常驻舞厅关联）、可见性规则（NORMAL 公开 /
 * PENDING/HIDDEN/REJECTED 仅创建人+管理员）、认可每日一记 toggle（插入+标签 / 取消+级联删标签）、
 * 标签字典校验、认可目标可见性、管理端状态切换（通过/驳回/隐藏 → 站内信通知创建人）、
 * 本人编辑（全量覆盖 / REJECTED 自动重审 / HOME 替换 / 权限）、相册上传删除与照片审核。
 */
@ExtendWith(MockitoExtension.class)
class DancerServiceTest {

    @Mock
    private DancerRepository dancerRepository;
    @Mock
    private DancerCityRepository dancerCityRepository;
    @Mock
    private DancerVenueRepository dancerVenueRepository;
    @Mock
    private DancerRecognitionRepository recognitionRepository;
    @Mock
    private DancerRecognitionTagRepository recognitionTagRepository;
    @Mock
    private DancerPhotoRepository photoRepository;
    @Mock
    private DancerDetailCacheService detailCacheService;
    @Mock
    private VenueLookupService venueLookupService;
    @Mock
    private MessageService messageService;
    @Mock
    private org.quwuting.quwutingservice.points.service.PointsService pointsService;
    @Mock
    private org.quwuting.quwutingservice.storage.ImageContentValidator imageValidator;
    @Mock
    private DancerAdViewRepository adViewRepository;
    @Mock
    private DancerVerificationLogRepository verificationLogRepository;
    @Mock
    private DancerFavoriteRepository dancerFavoriteRepository;
    @Mock
    private DancerViewRepository dancerViewRepository;
    @Mock
    private OpsConfigService opsConfigService;

    private DancerService dancerService;

    /** 详情缓存 stub（getDetail/toggleRecognize 响应组装用；仅聚合统计 + 联系方式门槛有值，其余空集） */
    private void stubDetailCache(long[] agg) {
        stubDetailCache(agg, 0);
    }

    private void stubDetailCache(long[] agg, int contactCost) {
        when(detailCacheService.get(1L)).thenReturn(detailPart(agg, contactCost));
    }

    private void stubDetailCacheAny(long[] agg) {
        when(detailCacheService.get(anyLong())).thenReturn(detailPart(agg, 0));
    }

    private static DancerDetailCacheService.PublicPart detailPart(long[] agg, int contactCost) {
        return new DancerDetailCacheService.PublicPart(
                new DancerRecognitionStats(agg[0], agg[1], agg[2], agg[3], Collections.emptyList()),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), 0L, 0L, contactCost, 0L);
    }

    private Dancer dancer;

    @BeforeEach
    void setUp() {
        dancerService = new DancerService(dancerRepository, dancerCityRepository, dancerVenueRepository, recognitionRepository,
                recognitionTagRepository, photoRepository, adViewRepository, verificationLogRepository,
                dancerFavoriteRepository, dancerViewRepository, detailCacheService, venueLookupService,
                messageService, pointsService, imageValidator, new org.quwuting.quwutingservice.config.DancerAdProperties(""),
                opsConfigService);

        dancer = new Dancer();
        dancer.setId(1L);
        dancer.setNickname("小雅");
        dancer.setCreatedBy(1L);
        dancer.setStatus(DancerStatus.NORMAL);
    }

    // ─── 创建 ───────────────────────────────────────────────────────────────

    @Test
    void createDancer_userRegistration_defaultsToPending() {
        when(dancerRepository.save(any(Dancer.class))).thenAnswer(inv -> {
            Dancer d = inv.getArgument(0);
            d.setId(10L);
            return d;
        });

        Long id = dancerService.createDancer(2L,
                new UpsertDancerRequest("  小雅  ", null, " 舞姿优秀 ", null, "杭州", null, null, null, null, null, null), false);

        assertEquals(10L, id);
        verify(dancerRepository).save(argThat(d ->
                d.getNickname().equals("小雅")           // 首尾空白被清洗
                && d.getBio().equals("舞姿优秀")
                && d.getCity().equals("杭州")
                && d.getStatus() == DancerStatus.PENDING   // 主动注册默认审核中（隐私第一道闸）
                && d.getCreatedBy().equals(2L)));
    }

    @Test
    void createDancer_adminCreatesNormal() {
        when(dancerRepository.save(any(Dancer.class))).thenAnswer(inv -> inv.getArgument(0));

        dancerService.createDancer(99L, new UpsertDancerRequest("后台舞伴", null, null, null, null, null, null, null, null, null, null), true);

        verify(dancerRepository).save(argThat(d ->
                d.getStatus() == DancerStatus.NORMAL && d.getCreatedBy().equals(99L)));
    }

    @Test
    void createDancer_blankNickname_throws() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> dancerService.createDancer(1L, new UpsertDancerRequest("   ", null, null, null, null, null, null, null, null, null, null), false));
        assertEquals(1001, ex.getCode());
        verify(dancerRepository, never()).save(any());
    }

    @Test
    void createDancer_withHomeVenue_attachesRelation() {
        when(dancerRepository.save(any(Dancer.class))).thenAnswer(inv -> {
            Dancer d = inv.getArgument(0);
            d.setId(7L);
            return d;
        });
        when(dancerVenueRepository.findByDancerIdAndVenueIdAndRelationAndDeletedFalse(
                eq(7L), eq(5L), any())).thenReturn(Optional.empty());

        dancerService.createDancer(1L, new UpsertDancerRequest("小雅", null, null, null, null, null, null, null, null, null, 5L), false);

        verify(venueLookupService).findById(5L); // 常驻舞厅存在性校验
        verify(dancerVenueRepository).save(argThat(dv ->
                dv.getDancerId().equals(7L) && dv.getVenueId().equals(5L)));
    }

    // ─── 可见性规则 ─────────────────────────────────────────────────────────

    @Test
    void getDetail_normal_isPublicToEveryone() {
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        stubDetailCache(new long[]{5L, 1L, 3L, 4L});

        DancerDetailResponse resp = dancerService.getDetail(1L, null, null);

        assertEquals("小雅", resp.nickname());
        assertEquals(5L, resp.stats().countAll());
        assertEquals(DancerStatus.NORMAL, resp.status());
    }

    @Test
    void getDetail_pending_hiddenFromOthers() {
        dancer.setStatus(DancerStatus.PENDING);
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> dancerService.getDetail(1L, 99L, UserRole.USER));
        assertEquals(1003, ex.getCode());
    }

    @Test
    void getDetail_pending_visibleToCreator() {
        dancer.setStatus(DancerStatus.PENDING);
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        stubDetailCache(new long[]{0L, 0L, 0L, 0L});
        when(recognitionRepository.findByUserIdAndDancerIdAndRecognitionDate(eq(1L), eq(1L), any()))
                .thenReturn(Optional.empty());

        DancerDetailResponse resp = dancerService.getDetail(1L, 1L, UserRole.USER);

        assertEquals(DancerStatus.PENDING, resp.status());
        assertTrue(resp.isMine(), "创建人应可见并标记 isMine");
    }

    @Test
    void getDetail_pending_visibleToAdmin() {
        dancer.setStatus(DancerStatus.HIDDEN);
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        stubDetailCache(new long[]{0L, 0L, 0L, 0L});
        when(recognitionRepository.findByUserIdAndDancerIdAndRecognitionDate(any(), eq(1L), any()))
                .thenReturn(Optional.empty());

        DancerDetailResponse resp = dancerService.getDetail(1L, 99L, UserRole.ADMIN);

        assertEquals(DancerStatus.HIDDEN, resp.status());
    }

    // ─── 联系方式遮挡（2026-08-14：默认遮挡 + 积分门槛正交） ─────────────────

    /** getDetail 联系方式可见性测试的公共 stub（匿名视角，isUnlocked mock 默认 false；
     *  contactCost 由详情缓存 stub 提供——2026-08-19 getDetail 改为经 DancerDetailCacheService
     *  读门槛，不再直调 pointsService.gateCost） */
    private void stubDetailForContact() {
        stubDetailForContact(0);
    }

    private void stubDetailForContact(int contactCost) {
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        stubDetailCache(new long[]{5L, 1L, 3L, 4L}, contactCost);
        when(photoRepository.findByDancerIdAndDeletedFalseOrderBySortOrderAscIdAsc(1L))
                .thenReturn(Collections.emptyList());
    }

    @Test
    void getDetail_hideContactDefaultTrue_withPaidGate_contactHiddenFromOthers() {
        dancer.setContact("wx:xiaoya");
        dancer.setHideContact(true); // 显式声明（实体默认即 true，此处防回归）
        stubDetailForContact(5);
        when(pointsService.isUnlocked(any(), any(), eq(1L))).thenReturn(false);

        DancerDetailResponse resp = dancerService.getDetail(1L, null, null);

        assertTrue(resp.hideContact(), "默认遮挡");
        assertEquals(5, resp.contactCost());
        assertNull(resp.contact(), "遮挡 + 有门槛 + 未解锁 → 不下发真实值（防绕过）");
    }

    @Test
    void getDetail_hideContactTrue_withFreeGate_contactDeliveredForFrontendMask() {
        dancer.setContact("wx:xiaoya");
        dancer.setHideContact(true);
        stubDetailForContact(0);

        DancerDetailResponse resp = dancerService.getDetail(1L, null, null);

        assertTrue(resp.hideContact());
        assertEquals(0, resp.contactCost());
        assertEquals("wx:xiaoya", resp.contact(),
                "遮挡 + 免费 → 下发真实值（内容本身免费），前端遮罩承载点击直显交互");
    }

    @Test
    void getDetail_hideContactFalse_contactAlwaysDelivered() {
        dancer.setContact("wx:xiaoya");
        dancer.setHideContact(false);
        stubDetailForContact(5); // 残留门槛
        when(pointsService.isUnlocked(any(), any(), eq(1L))).thenReturn(false);

        DancerDetailResponse resp = dancerService.getDetail(1L, null, null);

        assertFalse(resp.hideContact(), "不遮挡 → 恒直显");
        assertEquals("wx:xiaoya", resp.contact(), "不遮挡时忽略门槛下发真实值");
    }

    @Test
    void getDetail_hideContactTrue_ownerAlwaysSeesContact() {
        dancer.setContact("wx:xiaoya");
        dancer.setHideContact(true);
        stubDetailForContact(5);
        when(recognitionRepository.findByUserIdAndDancerIdAndRecognitionDate(any(), any(), any()))
                .thenReturn(Optional.empty());

        DancerDetailResponse resp = dancerService.getDetail(1L, 1L, UserRole.USER);

        assertEquals("wx:xiaoya", resp.contact(), "本人恒可见（showAllPhotos 短路解锁判定）");
    }

    // ─── 认可（每日一记 toggle） ─────────────────────────────────────────────

    @Test
    void toggleRecognize_firstTime_insertsRecognitionAndTags() {
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        // 2026-08-20 确定性化：首查空（走插入）→ 原子 upsert → 回查复用（两段式 stub）
        DancerRecognition created = new DancerRecognition();
        created.setId(100L);
        created.setUserId(2L);
        created.setDancerId(1L);
        created.setRecognitionDate(LocalDate.now());
        when(recognitionRepository.findByUserIdAndDancerIdAndRecognitionDate(2L, 1L, LocalDate.now()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(created));
        stubDetailCache(new long[]{1L, 1L, 1L, 1L});

        RecognizeResponse resp = dancerService.toggleRecognize(2L, 1L,
                new RecognizeDancerRequest(List.of("DANCE", "EASY_TALK"), null), UserRole.USER);

        assertTrue(resp.recognized());
        // 认可插入走确定性原子 upsert（UNIQUE(user, dancer, date) DO NOTHING）+ 回查复用
        verify(recognitionRepository).upsertRecognition(eq(1L), eq(2L), eq(LocalDate.now()), any(LocalDateTime.class));
        verify(recognitionRepository, never()).save(any());
        verify(recognitionTagRepository).upsertRecognitionTag(eq(100L), eq(1L), eq(2L), eq("DANCE"), any(LocalDateTime.class));
        verify(recognitionTagRepository).upsertRecognitionTag(eq(100L), eq(1L), eq(2L), eq("EASY_TALK"), any(LocalDateTime.class));
        verify(detailCacheService).invalidate(1L);
    }

    @Test
    void toggleRecognize_secondTime_cancelsAndDeletesTags() {
        DancerRecognition existing = new DancerRecognition();
        existing.setId(100L);
        existing.setUserId(2L);
        existing.setDancerId(1L);
        existing.setRecognitionDate(LocalDate.now());

        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        when(recognitionRepository.findByUserIdAndDancerIdAndRecognitionDate(2L, 1L, LocalDate.now()))
                .thenReturn(Optional.of(existing));
        stubDetailCache(new long[]{0L, 0L, 0L, 0L});

        RecognizeResponse resp = dancerService.toggleRecognize(2L, 1L, null, UserRole.USER);

        assertFalse(resp.recognized(), "取消当天认可后 recognized=false");
        verify(recognitionTagRepository).deleteByRecognitionId(100L); // 级联删除当日标签
        verify(recognitionRepository).deleteRecognitionById(100L); // 批量删除当日记录（@Modifying）
        verify(detailCacheService).invalidate(1L);
    }

    @Test
    void toggleRecognize_invalidTag_throws() {
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        when(recognitionRepository.findByUserIdAndDancerIdAndRecognitionDate(2L, 1L, LocalDate.now()))
                .thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> dancerService.toggleRecognize(2L, 1L,
                        new RecognizeDancerRequest(List.of("NOT_IN_DICT"), null), UserRole.USER));
        assertEquals(1001, ex.getCode());
        verify(recognitionRepository, never()).save(any());
    }

    @Test
    void toggleRecognize_tooManyTags_throws() {
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        when(recognitionRepository.findByUserIdAndDancerIdAndRecognitionDate(2L, 1L, LocalDate.now()))
                .thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> dancerService.toggleRecognize(2L, 1L,
                        new RecognizeDancerRequest(List.of("DANCE", "EASY_TALK", "GOOD_VIBE", "FUNNY"), null), UserRole.USER));
        assertEquals(1001, ex.getCode());
        verify(recognitionRepository, never()).save(any());
    }

    @Test
    void toggleRecognize_duplicateTags_deduplicated() {
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        // 2026-08-20 确定性化：首查空（走插入）→ 原子 upsert → 回查复用（两段式 stub）
        DancerRecognition created = new DancerRecognition();
        created.setId(100L);
        created.setUserId(2L);
        created.setDancerId(1L);
        created.setRecognitionDate(LocalDate.now());
        when(recognitionRepository.findByUserIdAndDancerIdAndRecognitionDate(2L, 1L, LocalDate.now()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(created));
        stubDetailCache(new long[]{1L, 1L, 1L, 1L});

        dancerService.toggleRecognize(2L, 1L,
                new RecognizeDancerRequest(List.of("DANCE", "DANCE", "EASY_TALK"), null), UserRole.USER);

        verify(recognitionTagRepository, times(1)).upsertRecognitionTag(eq(100L), eq(1L), eq(2L), eq("DANCE"), any(LocalDateTime.class));
        verify(recognitionTagRepository, times(1)).upsertRecognitionTag(eq(100L), eq(1L), eq(2L), eq("EASY_TALK"), any(LocalDateTime.class));
    }

    // ─── 认可单票换票（2026-08-15 交互模型变更：Reaction 风格 chip 单票） ────────

    @Test
    void toggleRecognize_singleTag_firstTime_createsWithSingleTag() {
        when(opsConfigService.isEnabled(anyString(), anyBoolean())).thenReturn(true); // 每日一票开
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        // 2026-08-20 确定性化：首查空（走插入）→ 原子 upsert → 回查复用（两段式 stub）
        DancerRecognition created = new DancerRecognition();
        created.setId(100L);
        created.setUserId(2L);
        created.setDancerId(1L);
        created.setRecognitionDate(LocalDate.now());
        when(recognitionRepository.findByUserIdAndDancerIdAndRecognitionDate(2L, 1L, LocalDate.now()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(created));
        stubDetailCache(new long[]{1L, 1L, 1L, 1L});

        RecognizeResponse resp = dancerService.toggleRecognize(2L, 1L,
                new RecognizeDancerRequest(null, "DANCE"), UserRole.USER);

        assertTrue(resp.recognized());
        assertNull(resp.replacedFrom(), "首次参与无换票");
        assertEquals(List.of("DANCE"), resp.myTags(), "单票模型今日票 = 点击的表情");
        verify(recognitionTagRepository).upsertRecognitionTag(eq(100L), eq(1L), eq(2L), eq("DANCE"), any(LocalDateTime.class));
        verify(recognitionTagRepository, never()).upsertRecognitionTag(eq(100L), eq(1L), eq(2L), eq("EASY_TALK"), any(LocalDateTime.class));
    }

    @Test
    void toggleRecognize_singleTag_sameTag_cancels() {
        when(opsConfigService.isEnabled(anyString(), anyBoolean())).thenReturn(true); // 每日一票开
        DancerRecognition existing = new DancerRecognition();
        existing.setId(100L);
        existing.setUserId(2L);
        existing.setDancerId(1L);
        existing.setRecognitionDate(LocalDate.now());

        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        when(recognitionRepository.findByUserIdAndDancerIdAndRecognitionDate(2L, 1L, LocalDate.now()))
                .thenReturn(Optional.of(existing));
        when(recognitionTagRepository.findTagsByRecognitionIds(List.of(100L)))
                .thenReturn(Collections.singletonList(new Object[]{100L, "DANCE"}));
        stubDetailCache(new long[]{0L, 0L, 0L, 0L});

        RecognizeResponse resp = dancerService.toggleRecognize(2L, 1L,
                new RecognizeDancerRequest(null, "DANCE"), UserRole.USER);

        assertFalse(resp.recognized(), "点自己今日的票 = 取消");
        assertTrue(resp.myTags().isEmpty(), "取消后今日无票");
        verify(recognitionTagRepository).deleteByRecognitionId(100L);
        verify(recognitionRepository).deleteRecognitionById(100L); // 批量删除当日记录（@Modifying）
        verify(recognitionTagRepository, never()).save(any());
    }

    @Test
    void toggleRecognize_singleTag_differentTag_replaces() {
        when(opsConfigService.isEnabled(anyString(), anyBoolean())).thenReturn(true); // 每日一票开
        DancerRecognition existing = new DancerRecognition();
        existing.setId(100L);
        existing.setUserId(2L);
        existing.setDancerId(1L);
        existing.setRecognitionDate(LocalDate.now());

        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        when(recognitionRepository.findByUserIdAndDancerIdAndRecognitionDate(2L, 1L, LocalDate.now()))
                .thenReturn(Optional.of(existing));
        when(recognitionTagRepository.findTagsByRecognitionIds(List.of(100L)))
                .thenReturn(Collections.singletonList(new Object[]{100L, "DANCE"}));
        stubDetailCache(new long[]{1L, 1L, 1L, 1L});

        RecognizeResponse resp = dancerService.toggleRecognize(2L, 1L,
                new RecognizeDancerRequest(null, "EASY_TALK"), UserRole.USER);

        assertTrue(resp.recognized(), "换票后仍为已认可");
        assertEquals("DANCE", resp.replacedFrom(), "换票返回被替换的旧票");
        assertEquals(List.of("EASY_TALK"), resp.myTags(), "换票后今日票 = 新表情");
        verify(recognitionTagRepository).deleteByRecognitionId(100L); // 旧标签清空
        verify(recognitionTagRepository).upsertRecognitionTag(eq(100L), eq(1L), eq(2L), eq("EASY_TALK"), any(LocalDateTime.class)); // 新标签写入
        verify(recognitionRepository, never()).deleteRecognitionById(anyLong()); // 认可记录本身不删（换票非取消）
    }

    // ─── 认可多选模式（2026-08-15：开关 dancer.recognition.daily.single 关闭） ─────

    @Test
    void toggleRecognize_multiMode_firstTime_createsWithSingleTag() {
        when(opsConfigService.isEnabled(anyString(), anyBoolean())).thenReturn(false); // 多选
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        // 2026-08-20 确定性化：首查空（走插入）→ 原子 upsert → 回查复用（两段式 stub）
        DancerRecognition created = new DancerRecognition();
        created.setId(100L);
        created.setUserId(2L);
        created.setDancerId(1L);
        created.setRecognitionDate(LocalDate.now());
        when(recognitionRepository.findByUserIdAndDancerIdAndRecognitionDate(2L, 1L, LocalDate.now()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(created));
        stubDetailCache(new long[]{1L, 1L, 1L, 1L});

        RecognizeResponse resp = dancerService.toggleRecognize(2L, 1L,
                new RecognizeDancerRequest(null, "DANCE"), UserRole.USER);

        assertTrue(resp.recognized());
        assertEquals(List.of("DANCE"), resp.myTags());
        verify(recognitionTagRepository).upsertRecognitionTag(eq(100L), eq(1L), eq(2L), eq("DANCE"), any(LocalDateTime.class));
    }

    @Test
    void toggleRecognize_multiMode_newTag_accumulates() {
        when(opsConfigService.isEnabled(anyString(), anyBoolean())).thenReturn(false); // 多选
        DancerRecognition existing = new DancerRecognition();
        existing.setId(100L);
        existing.setUserId(2L);
        existing.setDancerId(1L);
        existing.setRecognitionDate(LocalDate.now());
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        when(recognitionRepository.findByUserIdAndDancerIdAndRecognitionDate(2L, 1L, LocalDate.now()))
                .thenReturn(Optional.of(existing));
        when(recognitionTagRepository.findTagsByRecognitionIds(List.of(100L)))
                .thenReturn(Collections.singletonList(new Object[]{100L, "DANCE"}));
        stubDetailCache(new long[]{1L, 1L, 1L, 1L});

        RecognizeResponse resp = dancerService.toggleRecognize(2L, 1L,
                new RecognizeDancerRequest(null, "EASY_TALK"), UserRole.USER);

        assertTrue(resp.recognized(), "多选模式点新表情 = 累加");
        assertEquals(List.of("DANCE", "EASY_TALK"), resp.myTags(), "今日标签 = 旧票 + 新票");
        verify(recognitionTagRepository, never()).deleteByRecognitionId(100L); // 不整组删除
        verify(recognitionTagRepository).upsertRecognitionTag(eq(100L), eq(1L), eq(2L), eq("EASY_TALK"), any(LocalDateTime.class));
        verify(recognitionRepository, never()).deleteRecognitionById(anyLong());
    }

    @Test
    void toggleRecognize_multiMode_removeTag_keepsRecognition() {
        when(opsConfigService.isEnabled(anyString(), anyBoolean())).thenReturn(false); // 多选
        DancerRecognition existing = new DancerRecognition();
        existing.setId(100L);
        existing.setUserId(2L);
        existing.setDancerId(1L);
        existing.setRecognitionDate(LocalDate.now());
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        when(recognitionRepository.findByUserIdAndDancerIdAndRecognitionDate(2L, 1L, LocalDate.now()))
                .thenReturn(Optional.of(existing));
        when(recognitionTagRepository.findTagsByRecognitionIds(List.of(100L)))
                .thenReturn(Arrays.asList(new Object[]{100L, "DANCE"}, new Object[]{100L, "EASY_TALK"}));
        stubDetailCache(new long[]{1L, 1L, 1L, 1L});

        RecognizeResponse resp = dancerService.toggleRecognize(2L, 1L,
                new RecognizeDancerRequest(null, "DANCE"), UserRole.USER);

        assertFalse(resp.recognized(), "点已选表情 = 移除该枚");
        assertEquals(List.of("EASY_TALK"), resp.myTags(), "移除后仍保留其余票");
        verify(recognitionTagRepository).deleteByRecognitionIdAndTag(100L, "DANCE");
        verify(recognitionRepository, never()).deleteRecognitionById(anyLong()); // 仍有标签，认可保留
    }

    @Test
    void toggleRecognize_multiMode_removeLastTag_deletesRecognition() {
        when(opsConfigService.isEnabled(anyString(), anyBoolean())).thenReturn(false); // 多选
        DancerRecognition existing = new DancerRecognition();
        existing.setId(100L);
        existing.setUserId(2L);
        existing.setDancerId(1L);
        existing.setRecognitionDate(LocalDate.now());
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        when(recognitionRepository.findByUserIdAndDancerIdAndRecognitionDate(2L, 1L, LocalDate.now()))
                .thenReturn(Optional.of(existing));
        when(recognitionTagRepository.findTagsByRecognitionIds(List.of(100L)))
                .thenReturn(Collections.singletonList(new Object[]{100L, "DANCE"}));
        stubDetailCache(new long[]{0L, 0L, 0L, 0L});

        RecognizeResponse resp = dancerService.toggleRecognize(2L, 1L,
                new RecognizeDancerRequest(null, "DANCE"), UserRole.USER);

        assertFalse(resp.recognized());
        assertTrue(resp.myTags().isEmpty(), "末枚移除 = 今日无票");
        verify(recognitionTagRepository).deleteByRecognitionIdAndTag(100L, "DANCE");
        verify(recognitionRepository).deleteRecognitionById(100L); // 标签清空 → 认可记录删除
    }

    @Test
    void toggleRecognize_singleTag_invalidTag_throws() {
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> dancerService.toggleRecognize(2L, 1L,
                        new RecognizeDancerRequest(null, "NOT_IN_DICT"), UserRole.USER));
        assertEquals(1001, ex.getCode());
        verify(recognitionRepository, never()).save(any());
    }

    @Test
    void toggleRecognize_hiddenDancerByNonOwner_throws() {
        dancer.setStatus(DancerStatus.HIDDEN);
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> dancerService.toggleRecognize(99L, 1L, null, UserRole.USER));
        assertEquals(1003, ex.getCode());
        verify(recognitionRepository, never()).save(any());
    }

    // ─── 我的认可（同舞伴去重，取最近一条） ───────────────────────────────────

    @Test
    void listMyRecognitions_deduplicatesByDancer_keepsMostRecent() {
        // findMyRecognitions 按 createdAt 倒序：先返回最近一条（dancer 1 的 08-05），再返回历史（dancer 1 的 08-01）
        when(recognitionRepository.findMyRecognitions(2L)).thenReturn(List.of(
                new Object[]{200L, 1L, LocalDate.of(2026, 8, 5)},
                new Object[]{100L, 1L, LocalDate.of(2026, 8, 1)},
                new Object[]{300L, 2L, LocalDate.of(2026, 8, 4)}));
        Dancer d1 = new Dancer();
        d1.setId(1L);
        d1.setNickname("小雅");
        Dancer d2 = new Dancer();
        d2.setId(2L);
        d2.setNickname("阿丽");
        when(dancerRepository.findByIds(anyList())).thenReturn(List.of(d1, d2));
        when(recognitionTagRepository.findTagsByRecognitionIds(anyList())).thenReturn(
                List.of(new Object[]{200L, "DANCE"}, new Object[]{300L, "EASY_TALK"}));
        when(dancerVenueRepository.findVenueBriefsByDancerIds(anyList())).thenReturn(Collections.emptyList());

        var result = dancerService.listMyRecognitions(2L);

        assertEquals(2, result.size(), "同舞伴多日认可只保留最近一条");
        assertEquals(1L, result.get(0).dancerId());
        assertEquals(LocalDate.of(2026, 8, 5), result.get(0).recognizedOn());
        assertEquals(List.of("DANCE"), result.get(0).tagCodes());
        assertEquals(2L, result.get(1).dancerId());
    }

    // ─── 管理端状态切换（审核 → 站内信通知创建人） ────────────────────────────

    @Test
    void updateStatus_approvePending_sendsReviewMessage() {
        dancer.setStatus(DancerStatus.PENDING);
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));

        dancerService.updateStatus(1L, DancerStatus.NORMAL, null);

        verify(dancerRepository).save(argThat(d -> d.getStatus() == DancerStatus.NORMAL));
        // 审核通过 → 站内信（DANCER_REVIEW，收件人 = 创建人，软关联 DANCER 详情页）
        verify(messageService).create(eq(1L), eq(MessageType.DANCER_REVIEW),
                eq("舞伴主页审核通过"), contains("已通过审核"), eq("DANCER"), eq(1L));
    }

    @Test
    void updateStatus_rejectPending_sendsReviewMessageWithReason() {
        dancer.setStatus(DancerStatus.PENDING);
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));

        dancerService.updateStatus(1L, DancerStatus.REJECTED, "昵称与真实身份不符");

        verify(dancerRepository).save(argThat(d -> d.getStatus() == DancerStatus.REJECTED));
        // 驳回原因随站内信回传创建人（reason 清洗后拼入正文）
        verify(messageService).create(eq(1L), eq(MessageType.DANCER_REVIEW),
                eq("舞伴主页未通过审核"), contains("昵称与真实身份不符"), eq("DANCER"), eq(1L));
    }

    @Test
    void updateStatus_hideNormal_sendsStatusMessage() {
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));

        dancerService.updateStatus(1L, DancerStatus.HIDDEN, null);

        verify(dancerRepository).save(argThat(d -> d.getStatus() == DancerStatus.HIDDEN));
        verify(messageService).create(eq(1L), eq(MessageType.DANCER_STATUS),
                eq("舞伴主页已隐藏"), contains("已被隐藏"), eq("DANCER"), eq(1L));
    }

    @Test
    void updateStatus_sameStatus_idempotentNoMessage() {
        // dancer 初始 NORMAL，目标仍 NORMAL：无变更、无通知
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));

        dancerService.updateStatus(1L, DancerStatus.NORMAL, null);

        verify(dancerRepository, never()).save(any());
        verify(messageService, never()).create(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void updateStatus_rejectedToHidden_noMessage() {
        dancer.setStatus(DancerStatus.REJECTED);
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));

        dancerService.updateStatus(1L, DancerStatus.HIDDEN, null);

        verify(dancerRepository).save(argThat(d -> d.getStatus() == DancerStatus.HIDDEN));
        // REJECTED → HIDDEN 属管理侧内部流转，不产生用户通知
        verify(messageService, never()).create(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void getDetail_rejected_hiddenFromOthers() {
        dancer.setStatus(DancerStatus.REJECTED);
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> dancerService.getDetail(1L, 99L, UserRole.USER));
        assertEquals(1003, ex.getCode());
    }

    @Test
    void listAdminDancers_mapsRowsWithCreatorFallback() {
        // 管理端列表行：{id, nickname, avatar, bio, gender, city, status, created_at, u.nickname,
        //               u.avatar_url, verification_status, verified_at}（认证列 2026-08-14 追加）
        // 时间列按 Hibernate 7 native 查询映射为 java.time.LocalDateTime（禁 java.sql.Timestamp，
        // 见后端 AGENTS.md「native 查询时间列强转约定」——2026-08-08 DancerService 修复后
        // 本测试同步，否则 mock 类型与服务强转不一致抛 ClassCastException）。
        var row = new Object[]{1L, "小雅", null, "舞姿优秀", "FEMALE", "杭州", "PENDING",
                LocalDateTime.of(2026, 8, 8, 10, 0), null, null, "VERIFIED",
                LocalDateTime.of(2026, 8, 10, 9, 0)};
        org.springframework.data.domain.Page<Object[]> page =
                new org.springframework.data.domain.PageImpl<Object[]>(Collections.singletonList(row));
        when(dancerRepository.findAdminPage(eq("PENDING"), any())).thenReturn(page);

        var result = dancerService.listAdminDancers(DancerStatus.PENDING, 0, 20);

        assertEquals(1, result.getTotalElements());
        assertEquals("小雅", result.getContent().get(0).nickname());
        assertEquals("未知用户", result.getContent().get(0).creatorNickname(), "注册人已删时回退占位");
        assertEquals(DancerStatus.PENDING, result.getContent().get(0).status());
        assertEquals(DancerVerificationStatus.VERIFIED, result.getContent().get(0).verificationStatus());
    }

    // ─── 本人编辑（2026-08-10 新增：全量覆盖 / REJECTED 重审 / HOME 替换 / 权限） ───

    @Test
    void updateDancer_owner_overwritesEditableFields_keepsStatus() {
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        when(dancerVenueRepository.findByDancerIdAndRelationAndDeletedFalse(1L, DancerVenueRelation.HOME))
                .thenReturn(Collections.emptyList());
        // updateDancer 末尾调用 getDetail 组装返回：补齐其依赖
        stubDetailCache(new long[]{0L, 0L, 0L, 0L});
        when(recognitionRepository.findByUserIdAndDancerIdAndRecognitionDate(eq(1L), eq(1L), any()))
                .thenReturn(Optional.empty());
        when(photoRepository.findByDancerIdAndDeletedFalseOrderBySortOrderAscIdAsc(1L))
                .thenReturn(Collections.emptyList());

        DancerDetailResponse resp = dancerService.updateDancer(1L, 1L,
                new UpsertDancerRequest("小雅2", "https://cdn/x.jpg", "新简介", "FEMALE", "上海", null, null, null, null, null, null),
                UserRole.USER);

        assertEquals("小雅2", resp.nickname());
        verify(dancerRepository).save(argThat(d ->
                d.getNickname().equals("小雅2")
                        && d.getBio().equals("新简介")
                        && d.getCity().equals("上海")
                        && d.getStatus() == DancerStatus.NORMAL));
    }

    @Test
    void updateDancer_rejected_autoResubmitToPending() {
        dancer.setStatus(DancerStatus.REJECTED);
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        when(dancerVenueRepository.findByDancerIdAndRelationAndDeletedFalse(1L, DancerVenueRelation.HOME))
                .thenReturn(Collections.emptyList());
        stubDetailCache(new long[]{0L, 0L, 0L, 0L});
        when(recognitionRepository.findByUserIdAndDancerIdAndRecognitionDate(eq(1L), eq(1L), any()))
                .thenReturn(Optional.empty());
        when(photoRepository.findByDancerIdAndDeletedFalseOrderBySortOrderAscIdAsc(1L))
                .thenReturn(Collections.emptyList());

        DancerDetailResponse resp = dancerService.updateDancer(1L, 1L,
                new UpsertDancerRequest("小雅", null, null, null, null, null, null, null, null, null, null), UserRole.USER);

        assertEquals(DancerStatus.PENDING, resp.status(), "驳回后本人编辑 → 自动回到审核中（重新送审）");
    }

    @Test
    void updateDancer_replacesHomeVenue() {
        DancerVenue oldHome = new DancerVenue();
        oldHome.setDancerId(1L);
        oldHome.setVenueId(5L);
        oldHome.setRelation(DancerVenueRelation.HOME);
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        when(dancerVenueRepository.findByDancerIdAndRelationAndDeletedFalse(1L, DancerVenueRelation.HOME))
                .thenReturn(new java.util.ArrayList<>(List.of(oldHome)));
        when(dancerVenueRepository.findByDancerIdAndVenueIdAndRelationAndDeletedFalse(1L, 9L, DancerVenueRelation.HOME))
                .thenReturn(Optional.empty());
        // getDetail 组装依赖
        stubDetailCache(new long[]{0L, 0L, 0L, 0L});
        when(recognitionRepository.findByUserIdAndDancerIdAndRecognitionDate(eq(1L), eq(1L), any()))
                .thenReturn(Optional.empty());
        when(photoRepository.findByDancerIdAndDeletedFalseOrderBySortOrderAscIdAsc(1L))
                .thenReturn(Collections.emptyList());

        dancerService.updateDancer(1L, 1L,
                new UpsertDancerRequest("小雅", null, null, null, null, null, null, null, null, null, 9L), UserRole.USER);

        verify(venueLookupService).findById(9L);                    // 新常驻舞厅存在性校验
        verify(dancerVenueRepository).save(argThat(dv -> dv.isDeleted())); // 旧 HOME 软删（Lombok boolean → isDeleted）
        verify(dancerVenueRepository).save(argThat(dv ->
                dv.getVenueId().equals(9L) && dv.getRelation() == DancerVenueRelation.HOME)); // 新 HOME 建立
    }

    @Test
    void updateDancer_nonOwner_throws() {
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> dancerService.updateDancer(99L, 1L,
                        new UpsertDancerRequest("小雅", null, null, null, null, null, null, null, null, null, null), UserRole.USER));
        assertEquals(1003, ex.getCode());
        verify(dancerRepository, never()).save(any());
    }

    // ─── 相册（本人上传 → PENDING 审核；2026-08-10 新增） ──────────────────────

    @Test
    void addPhotos_owner_insertsPendingInUploadOrder() {
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        when(photoRepository.findByDancerIdAndDeletedFalseOrderBySortOrderAscIdAsc(1L))
                .thenReturn(Collections.emptyList()); // maxSortOrder = 0 → 新照片从 1 起
        when(photoRepository.save(any(DancerPhoto.class))).thenAnswer(inv -> inv.getArgument(0));

        dancerService.addPhotos(1L, 1L, List.of("https://cdn/a.jpg", "https://cdn/b.jpg"), null, UserRole.USER);

        verify(photoRepository, times(2)).save(argThat(p ->
                p.getStatus() == DancerPhotoStatus.PENDING && p.getDancerId().equals(1L)));
    }

    @Test
    void addPhotos_nonOwner_throws() {
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> dancerService.addPhotos(99L, 1L, List.of("https://cdn/a.jpg"), null, UserRole.USER));
        assertEquals(1003, ex.getCode());
        verify(photoRepository, never()).save(any());
    }

    @Test
    void addPhotos_nonHttpUrl_throws() {
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> dancerService.addPhotos(1L, 1L, List.of("file:///etc/passwd"), null, UserRole.USER));
        assertEquals(1001, ex.getCode());
        verify(photoRepository, never()).save(any());
    }

    @Test
    void removePhoto_owner_softDeletes() {
        DancerPhoto photo = new DancerPhoto();
        photo.setId(10L);
        photo.setDancerId(1L);
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        when(photoRepository.findByIdAndDeletedFalse(10L)).thenReturn(Optional.of(photo));

        dancerService.removePhoto(1L, 1L, 10L, UserRole.USER);

        verify(photoRepository).save(argThat(DancerPhoto::isDeleted)); // Lombok boolean → isDeleted
    }

    @Test
    void updatePhotoStatus_approvePending_public() {
        DancerPhoto photo = new DancerPhoto();
        photo.setId(10L);
        photo.setDancerId(1L);
        photo.setStatus(DancerPhotoStatus.PENDING);
        when(photoRepository.findByIdAndDeletedFalse(10L)).thenReturn(Optional.of(photo));

        dancerService.updatePhotoStatus(99L, 10L, DancerPhotoStatus.PUBLIC, null);

        verify(photoRepository).save(argThat(p -> p.getStatus() == DancerPhotoStatus.PUBLIC));
    }

    @Test
    void updatePhotoStatus_alreadyReviewed_throws() {
        DancerPhoto photo = new DancerPhoto();
        photo.setId(10L);
        photo.setDancerId(1L);
        photo.setStatus(DancerPhotoStatus.PUBLIC); // 已公开照片不可再审（幂等仅限同状态）
        when(photoRepository.findByIdAndDeletedFalse(10L)).thenReturn(Optional.of(photo));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> dancerService.updatePhotoStatus(99L, 10L, DancerPhotoStatus.REJECTED, null));
        assertEquals(1003, ex.getCode());
    }

    @Test
    void getDetail_publicOnlyReturnsPublicPhotos() {
        DancerPhoto pending = new DancerPhoto();
        pending.setId(1L);
        pending.setDancerId(1L);
        pending.setUrl("https://cdn/pending.jpg");
        pending.setStatus(DancerPhotoStatus.PENDING);
        DancerPhoto pub = new DancerPhoto();
        pub.setId(2L);
        pub.setDancerId(1L);
        pub.setUrl("https://cdn/pub.jpg");
        pub.setStatus(DancerPhotoStatus.PUBLIC);
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        stubDetailCache(new long[]{0L, 0L, 0L, 0L});
        when(photoRepository.findByDancerIdAndDeletedFalseOrderBySortOrderAscIdAsc(1L))
                .thenReturn(List.of(pending, pub));

        DancerDetailResponse resp = dancerService.getDetail(1L, null, null);

        assertEquals(1, resp.photos().size(), "公开视角仅 PUBLIC 照片");
        assertEquals("https://cdn/pub.jpg", resp.photos().get(0).url());
    }

    @Test
    void getDetail_ownerSeesAllPhotoStatuses() {
        DancerPhoto pending = new DancerPhoto();
        pending.setId(1L);
        pending.setDancerId(1L);
        pending.setUrl("https://cdn/pending.jpg");
        pending.setStatus(DancerPhotoStatus.PENDING);
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        when(recognitionRepository.findByUserIdAndDancerIdAndRecognitionDate(eq(1L), eq(1L), any()))
                .thenReturn(Optional.empty());
        stubDetailCache(new long[]{0L, 0L, 0L, 0L});
        when(photoRepository.findByDancerIdAndDeletedFalseOrderBySortOrderAscIdAsc(1L))
                .thenReturn(List.of(pending));

        DancerDetailResponse resp = dancerService.getDetail(1L, 1L, UserRole.USER);

        assertTrue(resp.isMine());
        assertEquals(1, resp.photos().size(), "本人视角含待审照片（编辑页回显状态）");
        assertEquals(DancerPhotoStatus.PENDING, resp.photos().get(0).status());
    }

    // ─── 信息核验（2026-08-14 官方认证：授予 / 撤销 / 编辑触发待复核） ───────────

    /** updateDancer 尾部 getDetail 组装依赖（可选链统一 stub，避免各测试重复 mock） */
    private void stubDetailDeps() {
        stubDetailCacheAny(new long[]{0L, 0L, 0L, 0L});
        when(recognitionRepository.findByUserIdAndDancerIdAndRecognitionDate(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(dancerVenueRepository.findByDancerIdAndRelationAndDeletedFalse(anyLong(), any()))
                .thenReturn(Collections.emptyList());
        when(photoRepository.findByDancerIdAndDeletedFalseOrderBySortOrderAscIdAsc(anyLong()))
                .thenReturn(Collections.emptyList());
        // 注：不 stub pointsService.isUnlocked——updateDancer 测试均为本人/管理员视角，
        // getDetail 中 showAllPhotos=true 短路该调用（isUnlocked 仅非本人/非管理员路径使用）
    }

    @Test
    void updateVerification_verifyFromUnverified_grantedNotifiedAndLogged() {
        dancer.setVerificationStatus(DancerVerificationStatus.UNVERIFIED);
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        when(dancerRepository.save(any(Dancer.class))).thenAnswer(inv -> inv.getArgument(0));

        dancerService.updateVerification(99L, 1L, DancerVerificationAction.VERIFY, "线下核实身份证件");

        assertEquals(DancerVerificationStatus.VERIFIED, dancer.getVerificationStatus());
        assertEquals(99L, dancer.getVerifiedBy(), "认证授予人留痕（快照）");
        assertNotNull(dancer.getVerifiedAt(), "认证授予时间快照");
        // 审计日志：from UNVERIFIED → to VERIFIED（reason 入日志）
        verify(verificationLogRepository).save(argThat(log ->
                log.getDancerId().equals(1L)
                        && log.getFromStatus().equals("UNVERIFIED")
                        && log.getToStatus().equals("VERIFIED")
                        && log.getOperatorId().equals(99L)
                        && log.getReason().equals("线下核实身份证件")));
        // 授予结果 → 站内信通知创建人（同事务）
        verify(messageService).create(eq(1L), eq(MessageType.DANCER_VERIFICATION),
                eq("信息核验通过"), contains("信息已核验"), eq("DANCER"), eq(1L));
    }

    @Test
    void updateVerification_verifyIdempotent_noChangeNoMessage() {
        dancer.setVerificationStatus(DancerVerificationStatus.VERIFIED);
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));

        dancerService.updateVerification(99L, 1L, DancerVerificationAction.VERIFY, null);

        verify(dancerRepository, never()).save(any());
        verify(verificationLogRepository, never()).save(any());
        verify(messageService, never()).create(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void updateVerification_unverifyBlankReason_throws() {
        dancer.setVerificationStatus(DancerVerificationStatus.VERIFIED);
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> dancerService.updateVerification(99L, 1L, DancerVerificationAction.UNVERIFY, "  "));

        assertEquals(1001, ex.getCode());
        verify(verificationLogRepository, never()).save(any());
        verify(messageService, never()).create(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void updateVerification_unverifyWithReason_revokedAndNotified() {
        dancer.setVerificationStatus(DancerVerificationStatus.VERIFIED);
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        when(dancerRepository.save(any(Dancer.class))).thenAnswer(inv -> inv.getArgument(0));

        dancerService.updateVerification(99L, 1L, DancerVerificationAction.UNVERIFY, "资料与真实信息不符");

        assertEquals(DancerVerificationStatus.UNVERIFIED, dancer.getVerificationStatus());
        assertNull(dancer.getVerifiedAt(), "撤销后认证快照清空（历史在审计日志）");
        assertNull(dancer.getVerifiedBy());
        // 审计日志：from VERIFIED → to UNVERIFIED
        verify(verificationLogRepository).save(argThat(log ->
                log.getFromStatus().equals("VERIFIED") && log.getToStatus().equals("UNVERIFIED")));
        // 撤销原因随站内信通知舞伴（被指涉方有申辩权）
        verify(messageService).create(eq(1L), eq(MessageType.DANCER_VERIFICATION),
                eq("信息核验标识已移除"), contains("资料与真实信息不符"), eq("DANCER"), eq(1L));
    }

    @Test
    void updateDancer_ownerEdit_verified_downgradesToPendingReview() {
        dancer.setVerificationStatus(DancerVerificationStatus.VERIFIED);
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        when(dancerRepository.save(any(Dancer.class))).thenAnswer(inv -> inv.getArgument(0));
        stubDetailDeps();

        DancerDetailResponse resp = dancerService.updateDancer(1L, 1L,
                new UpsertDancerRequest("小雅", null, null, null, null, null, null, null, null, null, null), UserRole.USER);

        assertEquals(DancerVerificationStatus.PENDING_REVIEW, resp.verificationStatus(),
                "本人编辑已认证资料 → 自动降级待复核（护栏：防认证挂在过期信息上）");
        // 审计日志：from VERIFIED → to PENDING_REVIEW（operator = 编辑者本人）
        verify(verificationLogRepository).save(argThat(log ->
                log.getFromStatus().equals("VERIFIED")
                        && log.getToStatus().equals("PENDING_REVIEW")
                        && log.getOperatorId().equals(1L)));
    }

    @Test
    void updateDancer_adminEdit_verified_keepsVerified() {
        dancer.setVerificationStatus(DancerVerificationStatus.VERIFIED);
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        when(dancerRepository.save(any(Dancer.class))).thenAnswer(inv -> inv.getArgument(0));
        stubDetailDeps();

        DancerDetailResponse resp = dancerService.updateDancer(99L, 1L,
                new UpsertDancerRequest("小雅", null, null, null, null, null, null, null, null, null, null), UserRole.ADMIN);

        assertEquals(DancerVerificationStatus.VERIFIED, resp.verificationStatus(),
                "管理员直改不触发待复核（已在进行管理动作，避免待办噪音）");
        verify(verificationLogRepository, never()).save(any());
    }

    @Test
    void updateDancer_ownerEdit_neverVerified_keepsUnverified() {
        dancer.setVerificationStatus(DancerVerificationStatus.UNVERIFIED);
        when(verificationLogRepository.existsByDancerIdAndToStatus(1L, "VERIFIED")).thenReturn(false);
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        when(dancerRepository.save(any(Dancer.class))).thenAnswer(inv -> inv.getArgument(0));
        stubDetailDeps();

        DancerDetailResponse resp = dancerService.updateDancer(1L, 1L,
                new UpsertDancerRequest("小雅", null, null, null, null, null, null, null, null, null, null), UserRole.USER);

        assertEquals(DancerVerificationStatus.UNVERIFIED, resp.verificationStatus(),
                "从未认证的舞伴编辑不制造待复核噪音");
        verify(verificationLogRepository, never()).save(any());
    }

    @Test
    void updateDancer_ownerEdit_revokedThenEdit_triggersPendingReview() {
        // 曾认证被撤销（UNVERIFIED + 日志存在 VERIFIED 记录）→ 再次编辑 → 待复核闭环
        dancer.setVerificationStatus(DancerVerificationStatus.UNVERIFIED);
        when(verificationLogRepository.existsByDancerIdAndToStatus(1L, "VERIFIED")).thenReturn(true);
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        when(dancerRepository.save(any(Dancer.class))).thenAnswer(inv -> inv.getArgument(0));
        stubDetailDeps();

        DancerDetailResponse resp = dancerService.updateDancer(1L, 1L,
                new UpsertDancerRequest("小雅", null, null, null, null, null, null, null, null, null, null), UserRole.USER);

        assertEquals(DancerVerificationStatus.PENDING_REVIEW, resp.verificationStatus(),
                "撤销后修改资料 → 重新待复核（撤销→修改→复核恢复闭环）");
    }
}
