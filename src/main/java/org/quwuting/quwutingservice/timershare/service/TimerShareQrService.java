package org.quwuting.quwutingservice.timershare.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.auth.service.WechatService;
import org.quwuting.quwutingservice.wxacode.service.WxacodeImage;
import org.quwuting.quwutingservice.wxacode.service.WxacodeSpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 计时分享二维码图生成（2026-10-07，V42；文档 = docs/agents/54-timer-share.md §二维码）。
 * <p>
 * 复用 wxacode 域的两样东西，不另造：{@link WxacodeSpec}（码规格 → 指纹的唯一派生处，同时充当
 * 缓存键与 ETag）与 {@link WechatService#getUnlimitedQrCode}（access_token 缓存单例，禁第三实例）。
 * <p>
 * 生命周期归「个性化码」一类（wxacode 域 36 号文档的分层）：内容随 token 变、键空间无界、可再生无损失
 * ⇒ 内存缓存 + 容量上限，<b>不</b>物化进 {@code qwt_wxacode_assets}（那是给「内容永不变」的静态码的）。
 * 缓存时长略长于二维码有效期：同一张码在有效期内被反复拉取（弹层重开、自动刷新、保存）只外呼微信一次；
 * 过期后缓存自然回收，不会攒成无界集合。
 * <p>
 * 调用方必须已确认会话存在且可加入（{@code TimerShareStore#isJoinable}）——随便一个 token 不该
 * 触发微信接口调用（码生成有调用频次配额，是最便宜的耗尽配额手段）。
 */
@Slf4j
@Service
public class TimerShareQrService {

    private final WechatService wechatService;
    private final String appId;
    private final String qrcodeEnv;

    private final Cache<String, byte[]> cache = Caffeine.newBuilder()
            .maximumSize(TimerSharePolicy.QR_CACHE_MAX_SIZE)
            .expireAfterWrite(Duration.ofMillis(TimerSharePolicy.QR_CACHE_TTL_MS))
            .build();

    public TimerShareQrService(
            WechatService wechatService,
            @Value("${wechat.appid}") String appId,
            @Value("${wechat.qrcode-env:release}") String qrcodeEnv) {
        this.wechatService = wechatService;
        this.appId = appId;
        this.qrcodeEnv = qrcodeEnv;
    }

    /**
     * 取（或生成）某 token 的码图。token 须已通过 {@link TimerShareTokens#isWellFormed}。
     * 微信失败 → 抛 {@code BusinessException(5001)}（不缓存失败，下次请求重试）。
     */
    public WxacodeImage render(String token) {
        WxacodeSpec spec = new WxacodeSpec(appId, TimerSharePolicy.LANDING_PAGE,
                TimerShareTokens.scene(token), qrcodeEnv);
        String fingerprint = spec.fingerprint();
        byte[] bytes = cache.get(fingerprint,
                key -> wechatService.getUnlimitedQrCode(spec.scene(), spec.page(), spec.envVersion()));
        return new WxacodeImage(bytes, fingerprint);
    }
}
