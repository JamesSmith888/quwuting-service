package org.quwuting.quwutingservice.wxacode.service;

import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.auth.service.WechatService;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分享小程序码生成（2026-09-07，40-wxacode-share：非微信平台分享桥接）。
 * <p>
 * 两场景一套能力（scene 决定落地页）：
 * <ul>
 *   <li><b>门店码</b>：GET /venues/{id}/wxacode.jpg → page = venue-detail，
 *       scene = {@code id=<venueId>}（登录追加 {@code &s=<uid>} 归因，uid 取
 *       UserContext 服务端身份，匿名不带）——扫码直达门店详情，s 由落地页解析
 *       回填 shareFrom，沿用既有分享归因管线（同 share_from 语义）；</li>
 *   <li><b>平台码</b>：GET /wxacode/home.jpg → page = index，scene = "home"。</li>
 * </ul>
 * 微信调用复用 auth {@link WechatService#getUnlimitedQrCode}（access_token 缓存
 * 单例，避免第三个 token 缓存实例；JSON 错误 → 5001 已在内部处理）。
 * env_version = wechat.qrcode-env（默认 release；本地 profile 覆盖 develop，
 * 对齐 web-auth.qrcode-env 先例——码打开对应 env 的小程序版本）。
 * <p>
 * 缓存：page|scene → 码图字节 24h（同 scene 码相同，防重复外呼微信）；
 * 容量护栏 MAX_CACHE_SIZE=500 超限整体清空——键空间 = (page, scene) 组合，
 * 门店数 × 活跃用户有界，当前规模（DAU 5~36、门店数百）远达不到。
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
    /** 码图缓存时长（同 scene 码相同，24h 与 Cache-Control 对齐） */
    private static final long CACHE_TTL_MILLIS = 24 * 60 * 60 * 1000L;
    /** 缓存容量护栏：超限整体清空（键空间有界，防御性兜底防内存膨胀） */
    private static final int MAX_CACHE_SIZE = 500;

    private final WechatService wechatService;
    private final VenueRepository venueRepository;
    private final String qrcodeEnv;

    /** 码图缓存：page|scene → 图片字节 + 过期时间戳 */
    private final Map<String, CachedCode> codeCache = new ConcurrentHashMap<>();

    public WxacodeShareService(
            WechatService wechatService,
            VenueRepository venueRepository,
            @Value("${wechat.qrcode-env:release}") String qrcodeEnv
    ) {
        this.wechatService = wechatService;
        this.venueRepository = venueRepository;
        this.qrcodeEnv = qrcodeEnv;
    }

    /** 码图缓存条目 */
    private record CachedCode(byte[] bytes, long expiresAtMillis) {}

    /**
     * 获取门店分享码（JPEG 字节）。门店不存在/已删除 → 1001；微信失败 → 5001。
     * scene：{@code id=<venueId>}（&s=<uid> 登录归因）；32 字符超限防御性拒绝
     * （"id=1234567&s=7654321" 极限 20 字符，正常数据不可达）。
     */
    public byte[] getVenueWxacode(Long venueId) {
        venueRepository.findByIdAndDeletedFalse(venueId)
                .orElseThrow(() -> new BusinessException(1001, "门店不存在"));
        return getOrGenerate(VENUE_PAGE, buildVenueScene(venueId));
    }

    /** 获取平台分享码（首页，无归因）。微信失败 → 5001。 */
    public byte[] getHomeWxacode() {
        return getOrGenerate(HOME_PAGE, HOME_SCENE);
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

    /** 缓存优先取码图，miss 时调微信生成并回填（容量护栏：超限整体清空） */
    private byte[] getOrGenerate(String page, String scene) {
        String key = page + "|" + scene;
        CachedCode cached = codeCache.get(key);
        if (cached != null && System.currentTimeMillis() < cached.expiresAtMillis()) {
            return cached.bytes();
        }
        byte[] jpeg = wechatService.getUnlimitedQrCode(scene, page, qrcodeEnv);
        if (codeCache.size() >= MAX_CACHE_SIZE) {
            log.info("Wxacode cache size {} >= {}, evicting all", codeCache.size(), MAX_CACHE_SIZE);
            codeCache.clear();
        }
        codeCache.put(key, new CachedCode(jpeg, System.currentTimeMillis() + CACHE_TTL_MILLIS));
        return jpeg;
    }
}
