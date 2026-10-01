package org.quwuting.quwutingservice.common.web;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 客户端 IP 解析（全局限流/频控共用，2026-08-07 从 VenueViewService /
 * VenueShareService 私有方法抽取收敛）。
 *
 * <h2>信任边界（2026-10-01 根因修复）</h2>
 * 旧实现直接取 {@code X-Forwarded-For} 的<b>第一个</b>地址（注释写的是已退役的 Cloudflare
 * Tunnel 链路）。而生产 nginx 用 {@code $proxy_add_x_forwarded_for}（<b>追加</b>）：客户端自带一个
 * {@code X-Forwarded-For: 1.2.3.4} 头，最终变成 {@code 1.2.3.4, 真实IP}，取第一个 = 取到
 * <b>客户端自己声明的值</b>。于是所有按 IP 的频控（匿名浏览 / 分享 / 反馈 / 登录尝试）都可以
 * 被一个随机请求头绕过，而它们恰好是热度公式的输入。
 * <p>
 * 现在「哪些代理可信」由容器统一声明（{@code server.forward-headers-strategy: native} ⇒
 * Tomcat {@code RemoteIpValve}）：从 XFF <b>右侧</b>向左跳过可信代理（默认 = 回环 / 内网 /
 * CGNAT 段，即本机 nginx 与云内网负载均衡），第一个不可信地址写入 {@code remoteAddr}。
 * 本类因此只读 {@code remoteAddr}——应用代码不再各自解析转发头，信任模型只有一个声明处
 * （application.yaml）。部署拓扑变化（新增一层代理）时改的是那份配置，不是这里。
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
        return attrs.getRequest().getRemoteAddr();
    }
}
