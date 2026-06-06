// 端到端示範：餘額檢查與扣款非原子（PAT-SEC-103 TOCTOU 雙花）的「偵測 → 復現(PoC) → 驗收」閉環。
// 零外部依賴（純 JDK 並發），可直接 `javac DoubleSpendDemo.java && java DoubleSpendDemo`。
// 用 CyclicBarrier 強制所有執行緒「先檢查、再行動」，讓競態穩定復現（非靠運氣）。
// 對應知識庫：PAT-SEC-103 / INV-ST-01（餘額非負）/ threat-catalog AB-03 / CORP-003。
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class DoubleSpendDemo {

    static final int THREADS = 10;
    static final long START_CENTS = 10_000; // 金庫 $100.00
    static final long AMT_CENTS   = 10_000; // 每筆提 $100.00（只夠 1 筆）

    static String usd(long c) {
        return (c < 0 ? "-$" : "$") + Math.abs(c) / 100 + "." + String.format("%02d", Math.abs(c) % 100);
    }

    // ====== 漏洞版：check-then-act 跨非原子邊界（PAT-SEC-103）======
    // barrier 強制所有執行緒都「檢查完」才一起「扣款」→ 全部看到足額，全部放行 = 雙花
    static long[] runVulnerable() throws InterruptedException {
        AtomicLong vault = new AtomicLong(START_CENTS);
        AtomicInteger ok = new AtomicInteger();
        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch done = new CountDownLatch(THREADS);
        for (int i = 0; i < THREADS; i++) pool.submit(() -> {
            try {
                boolean enough = vault.get() >= AMT_CENTS;   // T1 檢查（此刻都看到 $100）
                barrier.await();                             // 對齊：所有執行緒都檢查完
                if (enough) {                                // T2 扣款（授權判斷已過時）
                    vault.addAndGet(-AMT_CENTS);
                    ok.incrementAndGet();
                }
            } catch (Exception ignore) {
            } finally { done.countDown(); }
        });
        done.await();
        pool.shutdown();
        return new long[]{ ok.get(), vault.get() };
    }

    // ====== 修復版：synchronized 讓「檢查＋扣款」原子（等同 UPDATE ... WHERE balance >= amt）======
    static final class SafeVault {
        private long cents;
        SafeVault(long c) { cents = c; }
        synchronized boolean withdraw(long amt) {            // 檢查與扣款在同一臨界區，不可被打斷
            if (cents >= amt) { cents -= amt; return true; }
            return false;
        }
        synchronized long get() { return cents; }
    }

    static long[] runFixed() throws InterruptedException {
        SafeVault vault = new SafeVault(START_CENTS);
        AtomicInteger ok = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1), done = new CountDownLatch(THREADS);
        for (int i = 0; i < THREADS; i++) pool.submit(() -> {
            try { start.await(); if (vault.withdraw(AMT_CENTS)) ok.incrementAndGet(); }
            catch (Exception ignore) {}
            finally { done.countDown(); }
        });
        start.countDown();                                   // 同時放行 10 個並發提款
        done.await();
        pool.shutdown();
        return new long[]{ ok.get(), vault.get() };
    }

    public static void main(String[] args) throws Exception {
        int failures = 0;
        System.out.println("=== debug-hunter 端到端 demo：PAT-SEC-103 TOCTOU 雙花 ===\n");
        System.out.println("[DETECT]  taint/並發: 餘額 check-then-act 跨非原子邊界，無鎖/原子 SQL → candidate finding");

        System.out.println("\n[REPRODUCE] PoC：" + THREADS + " 個並發提款，每筆 " + usd(AMT_CENTS)
                + "，金庫僅 " + usd(START_CENTS) + "（只夠 1 筆）");
        {
            long[] r = runVulnerable();
            long okCount = r[0], vault = r[1];
            boolean violated = vault < 0 || okCount > 1;      // INV-ST-01 破壞 或 超提
            System.out.println("           漏洞版：成功提款 " + okCount + " 筆，金庫餘額 " + usd(vault)
                    + "，INV-ST-01(餘額非負) 違反=" + (vault < 0));
            if (!violated) { System.out.println("  ✗ 競態未復現，PoC 無效"); failures++; }
            else System.out.println("           ✔ PoC 成功（" + okCount + " 筆都過檢查 → 超提 "
                    + usd(okCount * AMT_CENTS - START_CENTS) + "，金庫變負）");
        }

        System.out.println("\n[VERIFY]   修復版（原子扣款）重跑同樣 " + THREADS + " 個並發提款");
        {
            long[] r = runFixed();
            long okCount = r[0], vault = r[1];
            boolean ok = okCount == 1 && vault == 0;
            System.out.println("           修復版：成功提款 " + okCount + " 筆，金庫餘額 " + usd(vault)
                    + "，INV-ST-01 恆成立=" + (vault >= 0));
            if (!ok) { System.out.println("  ✗ 驗收失敗（應只成功 1 筆、餘額 0）"); failures++; }
            else System.out.println("           ✔ 驗收通過（僅 1 筆成功、餘額不為負、無雙花）");
        }

        System.out.println("\n=== 結果：" + (failures == 0 ? "全部通過 ✅ 閉環成立" : (failures + " 項失敗 ❌")) + " ===");
        System.exit(failures == 0 ? 0 : 1);
    }
}
