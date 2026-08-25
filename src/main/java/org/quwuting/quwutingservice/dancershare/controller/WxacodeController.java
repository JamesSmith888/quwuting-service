package org.quwuting.quwutingservice.dancershare.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.dancershare.service.WxacodeService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 舞伴小程序码端点（2026-08-26，21-demand-detail-card P1：解锁结果卡「生成图片发TA」
 * 图片底部合成小程序码——scene = dancerId，舞伴长按识别进详情页）。
 * <p>
 * 返回 PNG/JPEG 图片二进制（非 ApiResponse JSON——图片直出给前端 canvas 合成）。
 * <b>公开（匿名可调）</b>：码内容仅 dancerId（无敏感信息，落地 = 舞伴详情页公开页），
 * 且前端 canvas createImage 加载图片无法携带登录 header——对齐分享上报软鉴权先例
 * （DancerShareController）；防滥用可后续加频控。微信接口失败 → 5001，
 * 前端捕获后图片不带码降级。
 */
@RestController
@RequestMapping("/wxacode")
@RequiredArgsConstructor
public class WxacodeController {

    private final WxacodeService wxacodeService;

    /**
     * GET /wxacode?dancerId=123 → 小程序码图片（image/png 或 image/jpeg）。
     */
    @GetMapping
    public ResponseEntity<byte[]> get(@RequestParam Long dancerId) {
        byte[] png = wxacodeService.getWxacode(dancerId);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(png);
    }
}
