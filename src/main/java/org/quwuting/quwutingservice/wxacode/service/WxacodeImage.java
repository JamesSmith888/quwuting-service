package org.quwuting.quwutingservice.wxacode.service;

/**
 * 小程序码响应体：图片字节 + **内容标识**（2026-10-03）。
 *
 * <h3>为什么把"内容标识"和字节一起返回，而不是让控制器自己算</h3>
 * 这张图是**内容寻址**的资产：内容由 {@link WxacodeSpec#fingerprint()} 唯一确定。
 * 因此 HTTP 层要的 ETag 与资产表主键 `asset_key` 是**同一个事实**——只在
 * {@code WxacodeSpec} 派生一次，随内容一起交给控制器使用。若控制器另行拼接标识
 * （或改哈希），就会出现"两个内容标识"，而两者一旦漂移，症状是**客户端永远 304、
 * 永远拿不到新图**——正是本项目在 51 号（距离 memo 键漏坐标维度）与本域 V30
 * （缓存键漏 env_version）两次踩过的"派生键不止一处"同族缺陷。
 *
 * <p>为什么 ETag 直接用可读指纹、不做哈希：指纹本就是运维可读的内容标识
 * （`appId|envVersion|page|scene`，全部由公开配置与数字 id 构成，不含敏感信息），
 * 且与 `SELECT asset_key` 的结果逐字符一致——排查"为什么这个客户端拿到 304"时，
 * 拿到的值可以直接当查询条件用。哈希只会额外制造一层不可读的映射。
 *
 * @param bytes 图片原始字节（微信直出 JPEG）
 * @param etag  内容标识（{@link WxacodeSpec#fingerprint()}）；响应头与条件请求共用
 */
public record WxacodeImage(byte[] bytes, String etag) {
}
