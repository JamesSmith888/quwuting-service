package org.quwuting.quwutingservice.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 软鉴权拦截器：尝试从 Authorization: Bearer <token> 中提取用户身份。
 * <p>
 * - 有合法 token → 解析用户写入 UserContext，请求继续
 * - 无 token 或 token 无效 → 不拦截，UserContext 为空，请求继续
 * <p>
 * 需要登录的接口由 Service/Controller 层显式调用 {@code UserContext.requireAuth()}。
 * 这种设计适配"黄页类"应用：浏览无需登录，仅收藏/管理等操作需要身份。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return true; // 无 token，放行（匿名访问）
        }

        String token = authHeader.substring(7);
        try {
            Long userId = jwtUtil.validateToken(token);
            User user = userRepository.findById(userId).orElse(null);
            if (user != null && !user.isDeleted()) {
                UserContext.set(user.getId(), user.getRole());
            }
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
