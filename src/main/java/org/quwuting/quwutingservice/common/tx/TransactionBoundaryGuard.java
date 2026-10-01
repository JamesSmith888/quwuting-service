package org.quwuting.quwutingservice.common.tx;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「远程 I/O 不得发生在数据库事务内」的运行期探针（2026-10-01，规则见
 * docs/agents/13-code-standards.md「事务边界」）。
 *
 * <h2>为什么需要</h2>
 * 连接池只有 5 个连接（application.yaml），事务期间连接一直被持有。事务内再去等一个
 * 外部 HTTP（微信 code2Session 读超时 10s、图片校验下载、地图 API），等待期间连接空转——
 * 5 个并发慢请求即可把整池占满，全站接口随之 10s 超时。这类问题在代码评审里极难看出来
 * （{@code @Transactional} 往往在调用栈上游两三层），而在单元测试里根本不出现。
 *
 * <h2>做法</h2>
 * 每个远程客户端在发起网络调用前调用 {@link #warnIfInTransaction}：处于活动事务中即打一条
 * WARN（含业务调用方类名/方法），同一「操作 × 调用方」10 分钟内只记一次，避免刷屏。
 * 只告警不抛异常——存量里仍有少数调用点（门店图片校验在编辑事务内），硬失败会直接打断
 * 业务；告警让它们在生产日志里可见、可逐个收敛。
 * <p>
 * 注意：{@code AFTER_COMMIT} 监听器里事务资源仍处于绑定态（连接在 afterCompletion 之后才归还），
 * 在那里做远程调用同样会被报告——这是事实而非误报。
 */
@Slf4j
public final class TransactionBoundaryGuard {

    /** 同一「操作 × 调用方」两次告警的最小间隔 */
    private static final long WARN_INTERVAL_MILLIS = 10 * 60 * 1000L;

    private static final String APP_PACKAGE = "org.quwuting.quwutingservice.";

    private static final Map<String, Long> LAST_WARNED = new ConcurrentHashMap<>();

    private TransactionBoundaryGuard() {
    }

    /**
     * @param operation    远程操作名（如 {@code wechat.code2Session}）
     * @param clientClass  远程客户端自身的类（栈回溯时跳过它，定位真正的业务调用方）
     */
    public static void warnIfInTransaction(String operation, Class<?> clientClass) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        String caller = findCaller(clientClass);
        String key = operation + "@" + caller;
        long now = System.currentTimeMillis();
        Long last = LAST_WARNED.get(key);
        if (last != null && now - last < WARN_INTERVAL_MILLIS) {
            return;
        }
        LAST_WARNED.put(key, now);
        log.warn("[tx-boundary] 远程调用 {} 发生在数据库事务内（等待网络期间持有连接），调用方={}；"
                + "请把远程调用移到事务之外（规则见 13-code-standards「事务边界」）", operation, caller);
    }

    private static String findCaller(Class<?> clientClass) {
        String clientName = clientClass.getName();
        String guardName = TransactionBoundaryGuard.class.getName();
        return StackWalker.getInstance().walk(frames -> frames
                .filter(f -> f.getClassName().startsWith(APP_PACKAGE))
                .filter(f -> !f.getClassName().equals(guardName))
                .filter(f -> !f.getClassName().startsWith(clientName))
                .filter(f -> !f.getClassName().contains("$$"))
                .findFirst()
                .map(f -> f.getClassName().substring(APP_PACKAGE.length()) + "#" + f.getMethodName())
                .orElse("unknown"));
    }
}
