package org.quwuting.quwutingservice.wxacode.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.wxacode.service.WxacodeShareService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 分享小程序码端点（2026-09-07，40-wxacode-share）。
 * <p>
 * 返回图片二进制（非 ApiResponse JSON——图片直出给前端 image 直连/保存落盘）。
 * <b>公开（匿名可调）</b>：码内容仅 page/scene（门店 id 与可选归因 uid，均为
 * 公开信息，落地 = 公开页），对齐舞伴码与分享上报软鉴权先例；归因 uid 取
 * UserContext 服务端身份（可信），不在请求参数暴露。
 * <p>
 * 路径说明：{@code /wxacode} 根路径已被舞伴域 WxacodeController 占用
 * （GET /wxacode?dancerId=），本控制器门店码走 /venues/{id}/wxacode.jpg
 * （对齐 /venues/{id}/photos 资源式路径），平台码走 /wxacode/home.jpg
 * （与 GET /wxacode 精确匹配不冲突，Spring 精确路径优先解析）。
 * 微信接口失败 → 5001 JSON（image 直连场景表现为加载失败，前端 binderror 兜底）。
 */
@RestController
@RequiredArgsConstructor
public class WxacodeShareController {

    private final WxacodeShareService wxacodeShareService;

    /**
     * GET /venues/{id}/wxacode.jpg → 门店分享码（image/jpeg）。
     * 扫码直达门店详情页；登录态生成时 scene 附带 s=&lt;uid&gt; 归因。
     */
    @GetMapping("/venues/{id}/wxacode.jpg")
    public ResponseEntity<byte[]> venue(@PathVariable("id") Long id) {
        return image(wxacodeShareService.getVenueWxacode(id));
    }

    /**
     * GET /wxacode/home.jpg → 平台分享码（image/jpeg，扫码直达首页）。
     */
    @GetMapping("/wxacode/home.jpg")
    public ResponseEntity<byte[]> home() {
        return image(wxacodeShareService.getHomeWxacode());
    }

    /** 图片响应统一出口（JPEG + 24h 客户端缓存，与服务端缓存 TTL 对齐） */
    private ResponseEntity<byte[]> image(byte[] bytes) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofHours(24)))
                .body(bytes);
    }
}
