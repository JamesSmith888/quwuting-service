package org.quwuting.quwutingservice.security;

import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.user.enums.UserRole;

/**
 * 线程级用户上下文，由 AuthInterceptor 在请求进入时设置（软模式：有 token 则解析，无则留空）。
 * <p>
 * 公开接口直接读取 {@code getCurrentUserId()}（可能为 null）。
 * 需要登录的接口调用 {@code requireAuth()}；需要管理员的接口调用 {@code requireAdmin()}。
 */
public final class UserContext {

    private static final ThreadLocal<Holder> CONTEXT = new ThreadLocal<>();

    private UserContext() {}

    public static void set(Long userId, UserRole role) {
        CONTEXT.set(new Holder(userId, role));
    }

    /** 获取当前用户 ID，未登录时返回 null */
    public static Long getCurrentUserId() {
        Holder h = CONTEXT.get();
        return h != null ? h.userId : null;
    }

    /** 获取当前用户角色，未登录时返回 null */
    public static UserRole getCurrentRole() {
        Holder h = CONTEXT.get();
        return h != null ? h.role : null;
    }

    /** 要求已登录，未登录时抛出 401 业务异常 */
    public static Long requireAuth() {
        Long userId = getCurrentUserId();
        if (userId == null) {
            throw new AuthRequiredException();
        }
        return userId;
    }

    /** 要求管理员角色，非管理员时抛出 403 业务异常 */
    public static Long requireAdmin() {
        Long userId = requireAuth();
        if (getCurrentRole() != UserRole.ADMIN) {
            throw new BusinessException(1003, "该操作仅限平台账号");
        }
        return userId;
    }

    public static void clear() {
        CONTEXT.remove();
    }

    /**
     * 需要登录但未登录时抛出的专用异常。
     * GlobalExceptionHandler 对此返回 HTTP 401（而非 200 + 业务码），
     * 以便前端 httpRequest 统一处理 401 → 清除凭证 → 提示重新登录。
     */
    public static class AuthRequiredException extends RuntimeException {
        public AuthRequiredException() {
            super("请先登录");
        }
    }

    private record Holder(Long userId, UserRole role) {}
}
