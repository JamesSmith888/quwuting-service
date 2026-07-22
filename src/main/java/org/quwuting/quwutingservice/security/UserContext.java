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
            throw new BusinessException(1003, "仅管理员可执行此操作");
        }
        return userId;
    }

    /**
     * 要求门店管理权限：平台管理员或门店认领人。
     * 用于场所更新、动态发布等管理写操作——安全边界在后端，前端 canManage 仅驱动 UI 展示。
     *
     * @param claimedBy 门店认领人用户 ID（Venue.claimedBy），null 表示未被认领
     * @return 当前用户 ID
     */
    public static Long requireManageOrAdmin(Long claimedBy) {
        Long userId = requireAuth();
        if (getCurrentRole() == UserRole.ADMIN) {
            return userId;
        }
        if (claimedBy != null && claimedBy.equals(userId)) {
            return userId;
        }
        throw new BusinessException(1003, "无管理权限");
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
