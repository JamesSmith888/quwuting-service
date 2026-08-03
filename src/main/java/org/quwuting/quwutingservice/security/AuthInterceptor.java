package org.quwuting.quwutingservice.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 软鉴权拦截器：尝试从 Authorization: Bearer <token> 中提取用户身份。
 * <p>
 * - 有合法 token → 解析用户写入 UserContext，请求继续
 * - 无 token 或 token 无效 → 不拦截，UserContext 为空，请求继续
 * <p>
 * 需要登录的接口由 Service/Controller 层显式调用 {@code UserContext.requireAuth()}。
 * 这种设计适配"黄页类"应用：浏览无需登录，仅收藏/管理等操作需要身份。
 * <p>
 * <b>用户缓存</b>：JWT 签名校验通过后，用户实体通过本地 Caffeine 缓存查询（2min TTL），
 * 避免每个带 token 请求都发起跨洲 DB 往返（实测 300-700ms/次）。role 取自 DB（经缓存），
 * 不取自 JWT payload——保证管理员调整角色后 2 分钟内生效，而非等 7 天 JWT 过期。
 * 缓存未命中时回源查库，首次请求仍支付完整延迟，但后续 2 分钟内的所有请求均命中缓存。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    /** 用户缓存：2min TTL，避免跨洲 DB 往返。maxSize=500 覆盖活跃用户量 */
    private final Cache<Long, Optional<User>> userCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(2, TimeUnit.MINUTES)
            .build();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return true; // 无 token，放行（匿名访问）
        }

        String token = authHeader.substring(7);
        try {
            long jwtStartNanos = System.nanoTime();
            Long userId = jwtUtil.validateToken(token);
            long jwtCostMs = (System.nanoTime() - jwtStartNanos) / 1_000_000;

            long lookupStartNanos = System.nanoTime();
            User user = userCache.get(userId, k -> {
                Optional<User> opt = userRepository.findById(k)
                        .filter(u -> !u.isDeleted());
                return opt.isPresent() ? opt : Optional.empty();
            }).orElse(null);
            long lookupCostMs = (System.nanoTime() - lookupStartNanos) / 1_000_000;

            if (user != null) {
                UserContext.set(user.getId(), user.getRole());
            }
            // 耗时埋点：lookupCost 在缓存命中时 <1ms，未命中时 = 完整 DB 往返
            log.info("[auth] uid={} jwtVerify={}ms lookup={}ms", userId, jwtCostMs, lookupCostMs);
        } catch (Exception e) {
            // token 无效/过期 → 视为匿名访问，不拦截
            log.debug("Token validation failed, treating as anonymous: {}", e.getMessage());
        }

        return true; // 始终放行
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }
}
