package org.quwuting.quwutingservice.wxacode.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.wxacode.service.WxacodeImage;
import org.quwuting.quwutingservice.wxacode.service.WxacodeShareService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

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
 *
 * <h3>缓存策略（2026-10-03 补 ETag / 条件请求）</h3>
 * 旧实现只有 {@code Cache-Control: max-age=86400}，**没有内容标识**。由此：
 * <ul>
 *   <li>24h 一到，每个客户端都要把整张码图（实测 91099 B）重下一遍——哪怕内容一个
 *       字节都没变。这是"我的页底部那张码每次都慢"的最后一块（前两块在客户端：
 *       页面底部懒加载 + 码位占位材质，见 quwuting 仓 40 号 §5.2）；</li>
 *   <li>而 24h 内的"别重复下载"又完全托付给客户端本地缓存——微信 image 的缓存
 *       策略并不完全跟随我们的响应头（官方社区置顶帖：客户端强缓存，换图后连删
 *       小程序都不更新）。**把"别重复下载"只写在响应头里是不可控的。**</li>
 * </ul>
 * 反过来也<b>不能</b>改成 {@code immutable} + 一年：本端点的 URL 是**恒定**的
 * （{@code /wxacode/home.jpg} 不含内容指纹），真出现"码内容变了"（微信换码样、
 * 生产 {@code wechat.qrcode-env} 改 trial 联调）时，恒定 URL 下的长缓存会让客户端
 * **再也拿不到新图**——这正是既有 24h 值的真实来历（36 号 §7 的既定边界）。
 * <p>
 * 正解是补齐"内容标识"这一维：ETag = 内容指纹（{@link WxacodeImage#etag()}，
 * 与资产主键同源），24h 到期后客户端带 {@code If-None-Match} 回来，服务端回
 * <b>304 空响应</b>——内容没变就不再重传 90KB；内容变了（新指纹）自然 200 出新图。
 * <p>
 * ⚠️ 条件判定前仍会走一次取图（静态资产 = 一次主键点查，10ms 量级）：ETag 就是
 * 内容标识，脱离内容无法提前得知。为省这一次点查而另存一份"旁路标识"会造出第二个
 * 派生点——正是本域 V30 与 51 号踩过的同族缺陷（派生键不止一处），不为 10ms 付这个代价。
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
    public ResponseEntity<byte[]> venue(@PathVariable("id") Long id, WebRequest request) {
        return image(wxacodeShareService.getVenueWxacode(id), request);
    }

    /**
     * GET /wxacode/home.jpg → 平台分享码（image/jpeg，扫码直达首页）。
     */
    @GetMapping("/wxacode/home.jpg")
    public ResponseEntity<byte[]> home(WebRequest request) {
        return image(wxacodeShareService.getHomeWxacode(), request);
    }

    /**
     * 图片响应统一出口：条件请求命中 → 304 空响应；否则 200 + ETag + 24h 客户端缓存。
     * <p>
     * {@code checkNotModified} = Spring 的标准条件请求入口（读 If-None-Match /
     * If-Modified-Since，命中时自行把响应置 304 并补 ETag 头）。
     */
    private ResponseEntity<byte[]> image(WxacodeImage image, WebRequest request) {
        if (request.checkNotModified(image.etag())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .eTag(image.etag())
                .cacheControl(CacheControl.maxAge(Duration.ofHours(24)))
                .body(image.bytes());
    }
}
