package org.quwuting.quwutingservice.common.web;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 客户端 IP 解析（全局限流/频控共用，2026-08-07 从 VenueViewService /
 * VenueShareService 私有方法抽取收敛）。
 * <p>
 * 优先取 X-Forwarded-For 第一个地址（Cloudflare Tunnel 代理链路的真实来源），
 * 回退 remoteAddr。代理剥离 XFF 时两者同为网关地址——频控退化为"场所级防抖"，
 * 语义仍可接受（尽力而为，多 IP 分布式刷无法拦截）。
 * <p>
 * 非 Web 上下文（单元测试/异步任务）返回 null，调用方需自行降级。
 */
public final class ClientIpResolver {

    private ClientIpResolver() {
    }

    /** 解析客户端 IP；无请求上下文时返回 null */
    public static String resolve() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        String xff = attrs.getRequest().getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return attrs.getRequest().getRemoteAddr();
    }
}
