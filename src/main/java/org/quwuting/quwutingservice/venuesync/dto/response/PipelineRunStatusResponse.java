package org.quwuting.quwutingservice.venuesync.dto.response;

/**
 * 管线执行状态（2026-08-31，Web 管理后台「门店同步 → 拉取数据」轮询用）。
 *
 * @param state      IDLE / RUNNING / SUCCEEDED / FAILED
 * @param running    是否正在执行（轮询终止条件）
 * @param startedAt  开始时间（yyyy-MM-dd HH:mm:ss，本地时区）
 * @param finishedAt 结束时间（未结束为 null）
 * @param exitCode   进程退出码（0=成功；-1=启动失败/超时强杀；未结束为 null）
 * @param durationMs 已用/耗时（毫秒）
 * @param tail       日志尾部（环形缓冲，最坏被截断到上限）
 */
public record PipelineRunStatusResponse(
        String state,
        boolean running,
        String startedAt,
        String finishedAt,
        Integer exitCode,
        Long durationMs,
        String tail) {

    public static PipelineRunStatusResponse idle() {
        return new PipelineRunStatusResponse("IDLE", false, null, null, null, null, "");
    }
}
