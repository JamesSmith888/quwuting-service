package org.quwuting.quwutingservice.venuesync.service;

import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.venuesync.dto.response.PipelineRunStatusResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 门店营业管线执行器（2026-08-31，Web 管理后台「门店同步 → 拉取数据」）。
 * <p>
 * 由后端以子进程方式调用 quwuting-ops/venue-opening/main.py：
 * <pre>
 *   python3 main.py --source {source} --upload-report --refresh-aliases
 *                   [--refresh-snapshot] --base-url {baseUrl}
 * </pre>
 * 职责边界：
 * <ul>
 *   <li>只跑「抓取 + 匹配 + 上报报告存档」（--upload-report），<b>不写库</b>——
 *       写库仍是管理员在页面点「确认写库」的人工动作（EXACT/ALIAS 人工放行）。</li>
 *   <li>每次执行前自动刷新映射别名（--refresh-aliases），管理员在「映射管理」配的
 *       映射立即生效，无需单独跑脚本。</li>
 *   <li>子进程以<b>触发者</b>的管理端 token 为 ADMIN_TOKEN（管线调后端接口鉴权用），
 *       复用 Web 登录态，服务器无需额外配置敏感凭据。</li>
 * </ul>
 * 并发约束：单实例单槽位（同一时刻最多一个子进程在跑），重复触发抛 5004。
 */
@Slf4j
@Service
public class VenueSyncPipelineService {

    /** 日志环形缓冲上限（字符）；超限丢弃头部，只保尾部 */
    private static final int LOG_LIMIT = 200_000;

    /** 子进程超时：超时强杀（默认渠道正常 1~3 分钟，抓取失败也可能耗满） */
    private static final long TIMEOUT_MINUTES = 10;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String python;
    private final String script;
    private final String baseUrl;

    private final Object lock = new Object();
    private State state = State.IDLE;
    private Instant startedAt;
    private Instant finishedAt;
    private Integer exitCode;
    private final StringBuilder logBuf = new StringBuilder();

    public VenueSyncPipelineService(
            @Value("${venue-sync.pipeline.python:python3}") String python,
            @Value("${venue-sync.pipeline.script:./venue-opening/main.py}") String script,
            @Value("${venue-sync.pipeline.base-url:http://127.0.0.1:8080}") String baseUrl) {
        this.python = python;
        this.script = script;
        this.baseUrl = baseUrl;
    }

    private enum State {
        IDLE, RUNNING, SUCCEEDED, FAILED
    }

    /**
     * 触发一次管线执行（异步；立即返回）。
     *
     * @param source          渠道 id
     * @param refreshSnapshot 是否连库刷新门店快照
     * @param adminToken      触发者管理端 token（透传给子进程 ADMIN_TOKEN）
     */
    public void start(String source, boolean refreshSnapshot, String adminToken) {
        Path scriptPath = Paths.get(script).toAbsolutePath();
        synchronized (lock) {
            if (state == State.RUNNING) {
                throw new BusinessException(5004, "管线正在运行中，请等待本次完成");
            }
            if (!Files.isRegularFile(scriptPath)) {
                throw new BusinessException(1001,
                        "管线脚本不存在: " + scriptPath
                                + "（配置 venue-sync.pipeline.script 或环境变量 VENUE_PIPELINE_SCRIPT）");
            }
            if (adminToken == null || adminToken.isBlank()) {
                throw new BusinessException(1002, "缺少管理端凭据，无法启动管线");
            }
            state = State.RUNNING;
            startedAt = Instant.now();
            finishedAt = null;
            exitCode = null;
            logBuf.setLength(0);
        }
        Thread worker = new Thread(
                () -> runProcess(scriptPath, source, refreshSnapshot, adminToken),
                "venue-sync-pipeline");
        worker.setDaemon(true);
        worker.start();
    }

    /** 最近一次执行状态（供页面轮询） */
    public PipelineRunStatusResponse status() {
        synchronized (lock) {
            boolean running = state == State.RUNNING;
            Long duration = null;
            if (startedAt != null) {
                duration = (running || finishedAt == null ? Instant.now() : finishedAt).toEpochMilli()
                        - startedAt.toEpochMilli();
            }
            return new PipelineRunStatusResponse(
                    state.name(),
                    running,
                    startedAt == null ? null : FMT.format(LocalDateTime.ofInstant(startedAt, ZoneId.systemDefault())),
                    finishedAt == null ? null : FMT.format(LocalDateTime.ofInstant(finishedAt, ZoneId.systemDefault())),
                    exitCode,
                    duration,
                    logBuf.toString());
        }
    }

    private void runProcess(Path scriptPath, String source, boolean refreshSnapshot, String adminToken) {
        List<String> cmd = new ArrayList<>();
        cmd.add(python);
        cmd.add(scriptPath.toString());
        cmd.add("--source");
        cmd.add(source);
        cmd.add("--upload-report");
        cmd.add("--refresh-aliases");
        if (refreshSnapshot) {
            cmd.add("--refresh-snapshot");
        }
        cmd.add("--base-url");
        cmd.add(baseUrl);

        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // 管线输出/快照相对其自身目录（data/、output/），工作目录必须设为脚本所在目录
            pb.directory(scriptPath.getParent().toFile());
            pb.environment().put("ADMIN_TOKEN", adminToken);
            pb.redirectErrorStream(true); // stderr 并入 stdout，日志单流
            process = pb.start();
        } catch (IOException e) {
            log.error("启动管线子进程失败: {}", cmd, e);
            appendLog("[error] 启动管线失败: " + e.getMessage());
            finish(-1);
            return;
        }

        // 独立线程读 stdout：管道缓冲写满会阻塞子进程，必须在 waitFor 前持续排空
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    appendLog(line);
                }
            } catch (IOException ignored) {
                // 进程结束后流关闭属正常
            }
        }, "venue-sync-pipeline-log");
        reader.setDaemon(true);
        reader.start();

        int code;
        try {
            if (process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                code = process.exitValue();
            } else {
                process.destroyForcibly();
                appendLog("[error] 管线执行超过 " + TIMEOUT_MINUTES + " 分钟，已强制终止");
                code = -1;
            }
            reader.join(5_000); // 等日志排空（最多 5s，避免进程已退但管道仍有残流）
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            appendLog("[error] 管线执行被中断");
            code = -1;
        }
        log.info("管线执行结束 exitCode={} source={}", code, source);
        finish(code);
    }

    private void finish(int code) {
        synchronized (lock) {
            state = code == 0 ? State.SUCCEEDED : State.FAILED;
            exitCode = code;
            finishedAt = Instant.now();
        }
    }

    /** 环形缓冲追加日志（reader 线程写入，status 线程读取——同一把锁保护） */
    private void appendLog(String line) {
        synchronized (lock) {
            logBuf.append(line).append('\n');
            if (logBuf.length() > LOG_LIMIT) {
                logBuf.delete(0, logBuf.length() - LOG_LIMIT);
            }
        }
    }
}
