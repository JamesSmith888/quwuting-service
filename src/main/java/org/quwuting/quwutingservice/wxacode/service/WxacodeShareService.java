package org.quwuting.quwutingservice.wxacode.service;

import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.auth.service.WechatService;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.wxacode.repository.WxacodeAssetRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分享小程序码生成（2026-09-07，40-wxacode-share：非微信平台分享桥接）。
 * <p>
 * 两场景一套能力（{@link WxacodeSpec} 决定落地页）：
 * <ul>
 *   <li><b>门店码</b>：GET /venues/{id}/wxacode.jpg → page = venue-detail，
 *       scene = {@code id=<venueId>}（登录追加 {@code &s=<uid>} 归因，uid 取
 *       UserContext 服务端身份，匿名不带）——扫码直达门店详情；</li>
 *   <li><b>平台码</b>：GET /wxacode/home.jpg → page = index，scene = "home"。</li>
 * </ul>
 *
 * <h3>2026-09-19 重构：两类码按「内容是否会变」分层（V30）</h3>
 *
 * 重构前两类码共用一张 {@code page|scene → bytes} 的进程内缓存，由此产生三个缺陷
 * （完整根因见 V30 迁移注释）：① 静态码被赋予"缓存"生命周期——缓存失效条件
 * （TTL/重启/清空）与内容失效条件（规格变化）无关，部署重启必重生成；
 * ② 容量护栏全局清空，1 个键的静态码被乘积型键空间的门店码反复误伤；
 * ③ 缓存键漏 {@code env_version}，改配置后 24h 内静默返回旧环境的码。
 *
 * 分层后的两条通道：
 *
 * <table border="1">
 *   <tr><th></th><th>静态码（平台码）</th><th>个性化码（门店码）</th></tr>
 *   <tr><td>内容随用户变？</td><td>否（全平台同一张）</td><td>是（scene 带 uid 归因）</td></tr>
 *   <tr><td>键空间</td><td>环境数 × 场景数（≈3 × 1）</td><td>门店数 × 活跃用户数</td></tr>
 *   <tr><td>载体</td><td><b>DB 物化，永久只读</b></td><td>内存缓存（24h TTL）</td></tr>
 *   <tr><td>失效条件</td><td>仅内容指纹变化</td><td>TTL / 进程重启（可再生，无损失）</td></tr>
 * </table>
 *
 * 判据：<b>「内容永不变」与「内容按需再生」是两种生命周期，不能共用同一个失效策略</b>
 * ——把静态内容放进缓存，必然出现"内容没变但重新生成"；把它物化成资产，才是把
 * 失效条件对齐到内容本身。
 * <p>
 * 两条通道的键都来自 {@code WxacodeSpec.fingerprint()} 单点派生（不再有"两处各拼
 * 一遍"的可能，修缺陷③的结构性防线）。微信调用复用 auth
 * {@link WechatService#getUnlimitedQrCode}（access_token 缓存单例，不造第三个实例）。
 * env_version = {@code wechat.qrcode-env}（默认 release；本地 profile 覆盖 develop）。
 * 与舞伴域 dancershare WxacodeService 同构但独立（舞伴海报码 page/scene 契约不同，
 * 且舞伴域前端已删处于休眠，不合并以免耦合休眠代码）。
 */
@Slf4j
@Service
public class WxacodeShareService {

    /** 门店码落地页（小程序内路径；scene 参数由微信注入 onLoad(query).scene） */
    private static final String VENUE_PAGE = "pages/venue-detail/venue-detail";
    /** 平台码落地页（首页 tab） */
    private static final String HOME_PAGE = "pages/index/index";
    /** 平台码 scene（getwxacodeunlimit scene 必须有值；首页无归因消费方，固定占位） */
    private static final String HOME_SCENE = "home";
    /** scene 上限（微信 getwxacodeunlimit 硬限制 32 字符） */
    private static final int SCENE_MAX_LENGTH = 32;
    /** 物化资产与响应的内容类型（微信恒返回 JPEG） */
    private static final String IMAGE_CONTENT_TYPE = "image/jpeg";
    /** 个性化码缓存时长（同 scene 码相同，24h 与响应 Cache-Control 对齐） */
    private static final long PERSONAL_CACHE_TTL_MILLIS = 24 * 60 * 60 * 1000L;
    /**
     * 个性化码缓存容量护栏：超限整体清空。
     * <p>
     * <b>作用域严格限定在个性化码</b>——静态码走 DB 资产（本 Map 不承载），
     * 故"清空"不再连带驱逐静态键（重构前两类共用一张 Map，护栏按总键数惩罚、
     * 被清掉的却包含那张 1 个键的静态码，见 V30 注释缺陷②）。
     */
    private static final int PERSONAL_CACHE_MAX_SIZE = 500;

    private final WechatService wechatService;
    private final VenueRepository venueRepository;
    private final WxacodeAssetRepository assetRepository;
    private final String appId;
    private final String qrcodeEnv;

    /** 个性化码缓存（门店码）：指纹 → 图片字节 + 过期时间戳 */
    private final Map<String, CachedCode> personalCodeCache = new ConcurrentHashMap<>();

    /**
     * 静态资产生成锁：指纹 → 锁对象。
     * <p>
     * 防"缓存击穿"——两个请求同时遇到某指纹未物化时，只有一个进微信生成 + 落库，
     * 另一个在锁内双检后直接读库（微信外呼是外部依赖，不该被并发放大）。锁对象在
     * 生成结束后移出（静态指纹数量 = 环境数 × 场景数，恒个位数，无累积风险）。
     */
    private final Map<String, Object> assetLocks = new ConcurrentHashMap<>();

    public WxacodeShareService(
            WechatService wechatService,
            VenueRepository venueRepository,
            WxacodeAssetRepository assetRepository,
            @Value("${wechat.appid}") String appId,
            @Value("${wechat.qrcode-env:release}") String qrcodeEnv
    ) {
        this.wechatService = wechatService;
        this.venueRepository = venueRepository;
        this.assetRepository = assetRepository;
        this.appId = appId;
        this.qrcodeEnv = qrcodeEnv;
    }

    /** 码图缓存条目（个性化通道） */
    private record CachedCode(byte[] bytes, long expiresAtMillis) {}

    /**
     * 获取门店分享码（JPEG 字节）。门店不存在/已删除 → 1001；微信失败 → 5001。
     * scene：{@code id=<venueId>}（&s=<uid> 登录归因）；32 字符超限防御性拒绝
     * （"id=1234567&s=7654321" 极限 20 字符，正常数据不可达）。
     */
    public byte[] getVenueWxacode(Long venueId) {
        venueRepository.findByIdAndDeletedFalse(venueId)
                .orElseThrow(() -> new BusinessException(1001, "门店不存在"));
        return getPersonalCode(new WxacodeSpec(appId, VENUE_PAGE, buildVenueScene(venueId), qrcodeEnv));
    }

    /** 获取平台分享码（首页，无归因）。微信失败 → 5001。 */
    public byte[] getHomeWxacode() {
        return getStaticAsset(new WxacodeSpec(appId, HOME_PAGE, HOME_SCENE, qrcodeEnv));
    }

    /** 门店码 scene 构建：id 必带；s = 当前登录用户（UserContext 服务端权威，匿名不带） */
    private String buildVenueScene(Long venueId) {
        Long userId = UserContext.getCurrentUserId();
        String scene = "id=" + venueId;
        if (userId != null) {
            scene = scene + "&s=" + userId;
        }
        if (scene.length() > SCENE_MAX_LENGTH) {
            throw new BusinessException(5001, "小程序码参数超限");
        }
        return scene;
    }

    /**
     * 静态资产通道：内容指纹物化一次，之后永远只读。
     * <p>
     * 没有 TTL——"过期"这个概念对静态内容不成立；内容变化的唯一信号是规格变化，
     * 而规格变化会直接产生新指纹 ⇒ 新行 + 新生成（配置改了即刻生效）。
     * 外呼微信只发生在"该指纹首次被请求"这一次。
     */
    private byte[] getStaticAsset(WxacodeSpec spec) {
        String fingerprint = spec.fingerprint();
        byte[] stored = assetRepository.findImageBytes(fingerprint);
        if (stored != null && stored.length > 0) {
            return stored;
        }
        Object lock = assetLocks.computeIfAbsent(fingerprint, key -> new Object());
        try {
            synchronized (lock) {
                // 双检：等锁期间可能已被同指纹的并发请求物化
                byte[] recheck = assetRepository.findImageBytes(fingerprint);
                if (recheck != null && recheck.length > 0) {
                    return recheck;
                }
                byte[] jpeg = wechatService.getUnlimitedQrCode(spec.scene(), spec.page(), spec.envVersion());
                assetRepository.insertIfAbsent(spec, jpeg, IMAGE_CONTENT_TYPE);
                log.info("Wxacode static asset materialized: {}", fingerprint);
                return jpeg;
            }
        } finally {
            assetLocks.remove(fingerprint, lock);
        }
    }

    /** 个性化码通道：内存缓存优先，miss 时调微信生成并回填（容量护栏：超限整体清空本通道） */
    private byte[] getPersonalCode(WxacodeSpec spec) {
        String fingerprint = spec.fingerprint();
        CachedCode cached = personalCodeCache.get(fingerprint);
        if (cached != null && System.currentTimeMillis() < cached.expiresAtMillis()) {
            return cached.bytes();
        }
        byte[] jpeg = wechatService.getUnlimitedQrCode(spec.scene(), spec.page(), spec.envVersion());
        if (personalCodeCache.size() >= PERSONAL_CACHE_MAX_SIZE) {
            log.info("Wxacode personal cache size {} >= {}, evicting all",
                    personalCodeCache.size(), PERSONAL_CACHE_MAX_SIZE);
            personalCodeCache.clear();
        }
        personalCodeCache.put(fingerprint,
                new CachedCode(jpeg, System.currentTimeMillis() + PERSONAL_CACHE_TTL_MILLIS));
        return jpeg;
    }
}
