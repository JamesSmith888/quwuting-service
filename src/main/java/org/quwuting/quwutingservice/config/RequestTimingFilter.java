package org.quwuting.quwutingservice.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 请求耗时过滤器：统一记录所有 HTTP 请求的端到端处理耗时，用于慢接口定位。
 *
 * <p>日志格式（固定前缀 [http]，便于 grep）：
 * <pre>
 *   INFO  [http] GET /venues/14/tags/stats -> 200 cost=9ms rid=r3-m1abc
 *   WARN  [http] GET /venues/14 -> 200 cost=2412ms rid=r4-m1abd [SLOW]
 * </pre>
 *
 * <p>语义与用法：
 * <ul>
 *   <li>cost 覆盖 Filter 链 → 拦截器（含 AuthInterceptor 查库）→ Controller → Service
 *       全链路，即"服务端处理耗时"。与前端 services/requestPerf.ts 的同 rid 日志对比：
 *       前端 cost − 后端 cost ≈ 网络传输开销（含 Cloudflare Tunnel）</li>
 *   <li>rid 来自前端 X-Request-Id 请求头；无此头的请求（curl 等）自动生成 s 前缀 ID</li>
 *   <li>达到 SLOW_THRESHOLD_MS 的请求升级为 WARN，便于日志中快速筛出慢请求</li>
 * </ul>
 *
 * <p>注册为最高优先级，确保计时覆盖 AuthInterceptor 及其后的全部处理。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTimingFilter extends OncePerRequestFilter {

    /** 慢请求阈值（ms）。依据：单次跨洲 DB 往返约 300~500ms，超过两次往返即视为慢请求 */
    private static final long SLOW_THRESHOLD_MS = 1000;

    /** 前后端日志关联请求头（由小程序 services/requestPerf.ts 生成并下发） */
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startMs = System.currentTimeMillis();
        String rid = request.getHeader(REQUEST_ID_HEADER);
        if (rid == null || rid.isEmpty()) {
            rid = "s" + Long.toString(startMs, 36);
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            long costMs = System.currentTimeMillis() - startMs;
            String query = request.getQueryString();
            String target = query != null ? request.getRequestURI() + "?" + query : request.getRequestURI();
            String line = "[http] " + request.getMethod() + " " + target
                    + " -> " + response.getStatus() + " cost=" + costMs + "ms rid=" + rid;
            if (costMs >= SLOW_THRESHOLD_MS) {
                log.warn(line + " [SLOW]");
            } else {
                log.info(line);
            }
        }
    }
}
