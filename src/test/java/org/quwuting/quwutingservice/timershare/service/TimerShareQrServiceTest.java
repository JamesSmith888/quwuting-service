package org.quwuting.quwutingservice.timershare.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.auth.service.WechatService;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.wxacode.service.WxacodeImage;

import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 码图生成与预热（2026-10-07 加载优化）：把预热执行语义直跑断言——
 * 注入同步执行器（{@code Runnable::run}），不真起线程，行为完全确定。
 * <p>
 * 这里测的：预热后图片请求命中同一缓存（不重复外呼）、失败不缓存且静默、提交失败不波及主路径。
 * 不在这里测的：生产线程池的调度行为（那是构造器默认值的事）与 Service 层的提交时机
 * （{@link TimerShareServiceTest} 用录制型提交口断言）。
 */
@ExtendWith(MockitoExtension.class)
class TimerShareQrServiceTest {

    private static final String TOKEN = "AbC123xyz0";

    @Mock
    private WechatService wechatService;

    /** 同步执行器：prewarm 调用即执行 */
    private TimerShareQrService syncService() {
        return new TimerShareQrService(wechatService, "wx-test-appid", "release", Runnable::run);
    }

    @Test
    void prewarmGeneratesOnceAndTheFollowingRenderHitsTheSameCache() {
        when(wechatService.getUnlimitedQrCode(eq("t=" + TOKEN), eq("pages/timer-join/timer-join"), eq("release")))
                .thenReturn(new byte[]{1, 2, 3});
        TimerShareQrService qr = syncService();

        qr.prewarm(TOKEN);                     // 预热：外呼一次并写入缓存
        WxacodeImage image = qr.render(TOKEN); // 随后的图片请求：命中同一张，不再外呼

        assertArrayEquals(new byte[]{1, 2, 3}, image.bytes());
        verify(wechatService, times(1)).getUnlimitedQrCode(anyString(), anyString(), anyString());
    }

    @Test
    void prewarmFailureIsSilentAndNotCachedSoTheImageRequestRetries() {
        when(wechatService.getUnlimitedQrCode(anyString(), anyString(), anyString()))
                .thenThrow(new BusinessException(5001, "boom"));
        TimerShareQrService qr = syncService();

        assertDoesNotThrow(() -> qr.prewarm(TOKEN), "预热失败必须静默（优化不是承诺）");
        assertThrows(BusinessException.class, () -> qr.render(TOKEN), "失败不缓存：图片请求会再试一次");
        verify(wechatService, times(2)).getUnlimitedQrCode(anyString(), anyString(), anyString());
    }

    @Test
    void prewarmSubmitRejectionNeverTouchesTheMainPath() {
        TimerShareQrService qr = new TimerShareQrService(wechatService, "wx-test-appid", "release",
                task -> {
                    throw new RejectedExecutionException("queue full");
                });

        assertDoesNotThrow(() -> qr.prewarm(TOKEN), "提交失败也不许波及响应路径");
        verifyNoInteractions(wechatService);
    }
}
