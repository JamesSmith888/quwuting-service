package org.quwuting.quwutingservice.exception;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 数据库瞬时故障的建议重试等待（秒）：连接池/网络抖动通常亚秒级恢复 */
    private static final int DB_TRANSIENT_RETRY_AFTER_SECONDS = 1;

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
     * 数据库连接类故障（连接池获取超时/连接中断/数据库不可达）→ HTTP 503 + {@code Retry-After}。
     * <p>2026-08-10 事故根因修复：此类异常多为瞬时故障，语义应为"暂时不可用"而非内部错误。
     * <p>2026-10-01 重试契约显式化：「可不可以重试」由服务端用 {@code Retry-After} 声明，
     * 客户端只对声明了短等待的 503（及网关层 502/504）重试，不再把一切 5xx 当瞬时故障
     * （见 12-api-conventions「重试契约」）。仅打 WARN 摘要不打堆栈。
     */
    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ApiResponse<Void> handleDataAccessFailure(DataAccessResourceFailureException ex,
                                                     HttpServletResponse response) {
        log.warn("Data access failure (transient DB issue): {}", ex.getMessage());
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(DB_TRANSIENT_RETRY_AFTER_SECONDS));
        return ApiResponse.fail(5003, "服务暂时不可用，请稍后重试");
    }

    /**
     * 外部依赖暂不可用（配额耗尽 / 限流 / 凭证失效 / 上游超时）→ HTTP 503 + code 5005 +
     * {@code Retry-After}（2026-10-01）。可预期的外部失败不是程序缺陷：只打 WARN 摘要，
     * 不进兜底处理器（否则每次请求一条 ERROR 堆栈，且被客户端当 500 重试放大外呼）。
     */
    @ExceptionHandler(ExternalServiceUnavailableException.class)
    public ApiResponse<Void> handleExternalUnavailable(ExternalServiceUnavailableException ex,
                                                       HttpServletResponse response) {
        log.warn("External dependency unavailable [{}] retryAfter={}s: {}",
                ex.getDependency(), ex.getRetryAfterSeconds(), ex.getMessage());
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
        return ApiResponse.fail(5005, ex.getUserMessage());
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
