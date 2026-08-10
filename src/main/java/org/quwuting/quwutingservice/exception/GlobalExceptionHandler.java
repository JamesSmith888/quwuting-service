package org.quwuting.quwutingservice.exception;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 需要登录但未登录 → HTTP 401，前端据此触发登录流程 */
    @ExceptionHandler(UserContext.AuthRequiredException.class)
    public ApiResponse<Void> handleAuthRequired(UserContext.AuthRequiredException ex,
                                                HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        return ApiResponse.fail(1002, ex.getMessage());
    }

    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handle(BusinessException ex) {
        log.warn("Business error [{}]: {}", ex.getCode(), ex.getMessage());
        return ApiResponse.fail(ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handle(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .findFirst()
                .orElse("参数校验失败");
        return ApiResponse.fail(1001, message);
    }

    /** 路由不存在（扫描器/爬虫探测）→ HTTP 404，仅 DEBUG 日志，不打堆栈 */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNoResource(NoResourceFoundException ex) {
        log.debug("Resource not found: {}", ex.getResourcePath());
        return ApiResponse.fail(1001, "资源不存在");
    }

    /**
     * 数据库连接类故障（连接池获取超时/连接中断/数据库不可达，Supabase 抖动常见）→ HTTP 503。
     * <p>2026-08-10 事故根因修复：此类异常多为瞬时故障，语义应为"暂时不可用"而非内部错误；
     * 前端对幂等 GET 的 5xx 自动重试一次即可自愈（见前端 auth.ts 请求层约定）。
     * 仅打 WARN 摘要不打堆栈——已知外部条件（Supabase 不稳定）下堆栈无诊断价值。
     */
    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ApiResponse<Void> handleDataAccessFailure(DataAccessResourceFailureException ex,
                                                     HttpServletResponse response) {
        log.warn("Data access failure (transient DB issue): {}", ex.getMessage());
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        return ApiResponse.fail(5003, "服务暂时不可用，请稍后重试");
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handle(Exception ex, HttpServletResponse response) {
        log.error("Unexpected error", ex);
        // 2026-08-10 根因修复：未预期异常必须返回 5xx——此前兜底异常以 HTTP 200 + code 5000
        // 返回，服务器错误对监控/代理/语义完全不可见，且前端 GET 5xx 重试无从触发。
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        return ApiResponse.fail(5000, "服务器内部错误");
    }
}
