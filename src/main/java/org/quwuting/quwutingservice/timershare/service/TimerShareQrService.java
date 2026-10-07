package org.quwuting.quwutingservice.timershare.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.auth.service.WechatService;
import org.quwuting.quwutingservice.wxacode.service.WxacodeImage;
import org.quwuting.quwutingservice.wxacode.service.WxacodeSpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    /**
     * 预热执行器：单线程 daemon，只跑「提前生成码图」这一种任务（2026-10-07 加载优化）。
     * <p>
     * 单线程足够——弹层每次打开只提交一个任务，同一 token 的重复预热被 Caffeine 的键级单飞语义
     * 吸收（{@code cache.get(key, fn)} 对同一 key 至多执行一次 {@code fn}，并发方等待同一结果）；
     * 队列里排着别的 token 也不阻塞用户的码图请求（Caffeine 不同 key 互不阻塞）。
     * static：进程级单队列，随类加载创建；daemon 线程不阻止 JVM 退出。
     */
    private static final ExecutorService PREWARM_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "timer-share-qr-prewarm");
        t.setDaemon(true);
        return t;
    });

    private final WechatService wechatService;
    private final String appId;
    private final String qrcodeEnv;
    /** 预热任务提交口（单测注入直跑 / 丢弃桩；生产 = 上面的 daemon 单线程队列） */
    private final Executor prewarmSubmitter;

    private final Cache<String, byte[]> cache = Caffeine.newBuilder()
            .maximumSize(TimerSharePolicy.QR_CACHE_MAX_SIZE)
            .expireAfterWrite(Duration.ofMillis(TimerSharePolicy.QR_CACHE_TTL_MS))
            .build();

    @Autowired
    public TimerShareQrService(
            WechatService wechatService,
            @Value("${wechat.appid}") String appId,
            @Value("${wechat.qrcode-env:release}") String qrcodeEnv) {
        this(wechatService, appId, qrcodeEnv, PREWARM_EXECUTOR);
    }

    /** 可注入预热提交口的构造器（单测用：不真起线程；任务直跑或丢弃都成为确定行为） */
    TimerShareQrService(WechatService wechatService, String appId, String qrcodeEnv, Executor prewarmSubmitter) {
        this.wechatService = wechatService;
        this.appId = appId;
        this.qrcodeEnv = qrcodeEnv;
        this.prewarmSubmitter = prewarmSubmitter;
    }

    /**
     * 取（或生成）某 token 的码图。token 须已通过 {@link TimerShareTokens#isWellFormed}。
     * 微信失败 → 抛 {@code BusinessException(5001)}（不缓存失败，下次请求重试）。
     */
    public WxacodeImage render(String token) {
        WxacodeSpec spec = new WxacodeSpec(appId, TimerSharePolicy.LANDING_PAGE,
                TimerShareTokens.scene(token), qrcodeEnv);
        String fingerprint = spec.fingerprint();
        long startedAtMs = System.currentTimeMillis();
        byte[] bytes = cache.get(fingerprint,
                key -> wechatService.getUnlimitedQrCode(spec.scene(), spec.page(), spec.envVersion()));
        // 观测日志（2026-10-07 加载优化）：命中缓存 ≈0ms；未命中 ≈ 微信外呼耗时（并发等待计入本条）。
        // 不打 token——它是加入计时的凭据，不落日志。
        log.info("[timer-share] qr render: costMs={} bytes={}",
                System.currentTimeMillis() - startedAtMs, bytes == null ? 0 : bytes.length);
        return new WxacodeImage(bytes, fingerprint);
    }

    /**
     * 预热：在创建 / 刷新响应发出后提前生成码图，把「首次外呼微信」从图片请求时提前到与前端
     * 渲染窗口并行（2026-10-07 加载优化）。根因 = 码图是「POST 返回后前端才发起」的第二跳，
     * 而微信 {@code getwxacodeunlimit} 是这条链路上唯一的外部依赖（300ms~3s 量级）。
     * <p>
     * 为什么不会重复外呼：预热与随后的图片请求命中同一个缓存键（{@link WxacodeSpec} 指纹），
     * Caffeine 的 {@code get(key, fn)} 对同一 key 的并发调用至多执行一次 {@code fn}——
     * 预热先到则图片请求等待它的结果，图片请求先到则预热直接命中。
     * <p>
     * 失败一律静默：预热是优化不是承诺——失败时图片请求会自然重试（失败不缓存）；
     * 连「提交失败」（执行器拒绝）也不许波及主路径（响应已装配完毕，调用方直接返回）。
     */
    public void prewarm(String token) {
        try {
            prewarmSubmitter.execute(() -> {
                try {
                    render(token);
                } catch (RuntimeException e) {
                    log.info("[timer-share] qr prewarm failed (image request will retry): {}", e.getMessage());
                }
            });
        } catch (RuntimeException e) {
            log.debug("[timer-share] qr prewarm submit skipped: {}", e.getMessage());
        }
    }
}
