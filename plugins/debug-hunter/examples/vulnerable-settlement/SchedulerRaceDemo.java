// 端到端示範：分散式排程多 Worker 資料競爭（PAT-SCH-001）的「偵測 → 復現 → 驗收」閉環。
// 零外部依賴（純 JDK 並發），可直接 `javac SchedulerRaceDemo.java && java SchedulerRaceDemo`。
// 對應知識庫：PAT-SCH-001 / INV-T-02（冪等不重複）/ RULE-SCH-001。
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class SchedulerRaceDemo {

    static final int WORKERS = 4;
    static final int[] PENDING = {101, 102, 103, 104, 105, 106, 107, 108}; // 8 筆待結算訂單

    // settled[orderId] = 被結算的次數（>1 即重複結算）
    static final ConcurrentHashMap<Integer, AtomicInteger> settled = new ConcurrentHashMap<>();
    static void settle(int orderId) {
        settled.computeIfAbsent(orderId, k -> new AtomicInteger()).incrementAndGet();
    }
    static int maxTimes() { return settled.values().stream().mapToInt(AtomicInteger::get).max().orElse(0); }
    static int distinct() { return settled.size(); }
    static void reset() { settled.clear(); }

    // ====== 漏洞版：每個 Worker 都撈全量並結算（廣播模式無分片/冪等）======
    static void runVulnerable() throws InterruptedException {
        reset();
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        CountDownLatch done = new CountDownLatch(WORKERS);
        for (int w = 0; w < WORKERS; w++) {
            pool.submit(() -> {
                try { for (int id : PENDING) settle(id); }     // ← 各 Worker 撈到同一批全量
                finally { done.countDown(); }
            });
        }
        done.await();
        pool.shutdown();
    }

    // ====== 修復版 A：分片隔離（Worker i 只處理 id % WORKERS == i）======
    static void runFixedSharded() throws InterruptedException {
        reset();
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        CountDownLatch done = new CountDownLatch(WORKERS);
        for (int w = 0; w < WORKERS; w++) {
            final int idx = w;
            pool.submit(() -> {
                try { for (int id : PENDING) if (id % WORKERS == idx) settle(id); } // ← 分片
                finally { done.countDown(); }
            });
        }
        done.await();
        pool.shutdown();
    }

    // ====== 修復版 B：冪等認領（即使全量掃描，單筆只被第一個 Worker 認領）======
    static void runFixedIdempotent() throws InterruptedException {
        reset();
        java.util.Set<Integer> claimed = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        CountDownLatch done = new CountDownLatch(WORKERS);
        for (int w = 0; w < WORKERS; w++) {
            pool.submit(() -> {
                try { for (int id : PENDING) if (claimed.add(id)) settle(id); } // add 回 false = 已被認領
                finally { done.countDown(); }
            });
        }
        done.await();
        pool.shutdown();
    }

    public static void main(String[] args) throws Exception {
        int failures = 0;
        System.out.println("=== debug-hunter 端到端 demo：PAT-SCH-001 排程多 Worker 資料競爭 ===\n");
        System.out.println("[DETECT]  並發: " + WORKERS + " 個 Worker 廣播執行同一排程，撈全量待結算且無分片/冪等 → candidate finding");

        System.out.println("\n[REPRODUCE] PoC：" + WORKERS + " 個 Worker 各自結算全部 " + PENDING.length + " 筆");
        {
            runVulnerable();
            boolean violated = maxTimes() > 1;
            System.out.println("           漏洞版：每筆最多被結算 " + maxTimes() + " 次（distinct=" + distinct()
                    + "），INV-T-02 違反=" + violated);
            if (!violated) { System.out.println("  ✗ PoC 無效"); failures++; }
            else System.out.println("           ✔ PoC 成功（每筆被重複結算 " + maxTimes() + " 次 = 重複入帳）");
        }

        System.out.println("\n[VERIFY]   修復版 A（分片隔離）重跑");
        {
            runFixedSharded();
            boolean ok = maxTimes() == 1 && distinct() == PENDING.length;
            System.out.println("           分片版：每筆最多結算 " + maxTimes() + " 次，覆蓋 " + distinct() + "/" + PENDING.length
                    + " 筆，INV-T-02 恆成立=" + (maxTimes() <= 1));
            if (!ok) { System.out.println("  ✗ 分片驗收失敗"); failures++; }
            else System.out.println("           ✔ 驗收通過（每筆恰好一次、全覆蓋、無重複）");
        }

        System.out.println("\n[VERIFY+]  修復版 B（冪等認領，即使全量掃描）");
        {
            runFixedIdempotent();
            boolean ok = maxTimes() == 1 && distinct() == PENDING.length;
            System.out.println("           冪等版：每筆最多結算 " + maxTimes() + " 次，覆蓋 " + distinct() + "/" + PENDING.length + " 筆");
            if (!ok) { System.out.println("  ✗ 冪等驗收失敗"); failures++; }
            else System.out.println("           ✔ 驗收通過（單筆只被第一個 Worker 認領）");
        }

        System.out.println("\n=== 結果：" + (failures == 0 ? "全部通過 ✅ 閉環成立" : (failures + " 項失敗 ❌")) + " ===");
        System.exit(failures == 0 ? 0 : 1);
    }
}
