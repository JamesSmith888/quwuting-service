package org.quwuting.quwutingservice.exception;

/**
 * 外部依赖暂不可用（2026-10-01，错误语义收敛，见 docs/agents/12-api-conventions.md「重试契约」）。
 * <p>
 * 根因：外部服务的<b>可预期失败</b>（配额耗尽、限流、凭证失效、上游超时）此前被包成
 * {@code IllegalStateException}，落进兜底处理器成为 HTTP 500「未预期错误」——
 * <ul>
 *   <li>运维侧：每次请求打一条 ERROR 全量堆栈，真正的程序缺陷被淹没；</li>
 *   <li>客户端：500 被小程序请求层当成「瞬时故障」自动重试，配额耗尽时<b>每次定位耗两次配额</b>，
 *       把「今天额度用完」放大成「额度更快用完」。</li>
 * </ul>
 * 本异常由全局处理器映射为 HTTP 503 + code 5005 + {@code Retry-After}：重试与否由服务端声明的
 * 等待时长决定（短 = 可重试，长 = 重试无意义），客户端不再猜测。
 */
public class ExternalServiceUnavailableException extends RuntimeException {

    /** 依赖名（日志用，如「腾讯位置服务」） */
    private final String dependency;

    /** 建议的最短重试等待（秒，≥1）；写入 Retry-After 响应头 */
    private final long retryAfterSeconds;

    /** 面向用户的提示（不含任何内部细节） */
    private final String userMessage;

    public ExternalServiceUnavailableException(String dependency, long retryAfterSeconds,
                                               String userMessage, String detail, Throwable cause) {
        super(dependency + " 暂不可用：" + detail, cause);
        this.dependency = dependency;
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
        this.userMessage = userMessage;
    }

    public String getDependency() {
        return dependency;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public String getUserMessage() {
        return userMessage;
    }
}
