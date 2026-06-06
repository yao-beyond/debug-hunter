// 端到端示範：分散式鎖 TTL 設計缺陷（PAT-CON-003）的「偵測 → 復現 → 驗收」閉環。
// 零外部依賴（純 JDK，可控時鐘讓鎖過期競態可重現），可直接 `javac LockTtlDemo.java && java LockTtlDemo`。
// 對應知識庫：PAT-CON-003 / INV-T-02（冪等不重複）/ RULE-CON-008。
public class LockTtlDemo {

    // 模擬 Redis 分散式鎖：單一 key，記錄持有者 token 與到期時間
    static final class FakeLock {
        String token;
        long expireAt;
        boolean held;

        // 取鎖：未持有或已過期才可取得（SETNX + TTL 語意）
        boolean tryLock(String tk, long ttl, long now) {
            if (!held || now >= expireAt) {
                token = tk; expireAt = now + ttl; held = true; return true;
            }
            return false;
        }
        // 漏洞版釋放：無條件刪除（可能刪到他人剛取得的鎖）
        void unlockUnsafe() { held = false; token = null; }
        // 修復版釋放：Lua 原子校驗持有者，token 不符則 no-op
        boolean unlockSafe(String tk) {
            if (held && tk.equals(token)) { held = false; token = null; return true; }
            return false;
        }
    }

    public static void main(String[] args) {
        int failures = 0;
        System.out.println("=== debug-hunter 端到端 demo：PAT-CON-003 分散式鎖 TTL 設計缺陷 ===\n");
        System.out.println("[DETECT]  鎖 TTL 短於業務執行時間，或釋放未校驗持有者 → 互斥失效 → candidate finding");

        // 情境設定：業務執行需 10 個時間單位
        final long BUSINESS = 10;

        // [REPRODUCE] TTL 過短：A 於 t=0 取鎖(TTL=5)，業務跑到 t=10；B 於 t=6 取鎖（A 鎖已過期）→ 兩者同處臨界區
        System.out.println("\n[REPRODUCE] PoC：TTL=5 < 業務時間=" + BUSINESS + "，A 鎖中途過期，B 於 t=6 取得鎖");
        {
            FakeLock lk = new FakeLock();
            int settledTimes = 0;
            boolean a = lk.tryLock("A", 5, 0);     // t=0：A 取鎖
            boolean b = lk.tryLock("B", 5, 6);     // t=6：A 的 5s TTL 已過期 → B 取得 ← 互斥失效
            if (a) settledTimes++;                  // A 在臨界區結算
            if (b) settledTimes++;                  // B 同時在臨界區結算 → 重複
            boolean violated = settledTimes > 1;
            System.out.println("           漏洞版：A 取鎖=" + a + "，B 取鎖=" + b + "，同一 runner 被結算 "
                    + settledTimes + " 次，INV-T-02 違反=" + violated);
            if (!violated) { System.out.println("  ✗ PoC 無效"); failures++; }
            else System.out.println("           ✔ PoC 成功（鎖提前過期，互斥失效 → 重複結算）");
        }

        // [VERIFY] TTL > 業務時間（或 watchdog 續租）：B 於 t=6 取鎖失敗，互斥成立
        System.out.println("\n[VERIFY]   修復版（TTL=20 > 業務時間，或 watchdog 續租）重跑");
        {
            FakeLock lk = new FakeLock();
            int settledTimes = 0;
            boolean a = lk.tryLock("A", 20, 0);    // TTL 涵蓋整段業務
            boolean b = lk.tryLock("B", 20, 6);    // 6 < 20 → 取鎖失敗
            if (a) settledTimes++;
            if (b) settledTimes++;
            boolean ok = settledTimes == 1 && a && !b;
            System.out.println("           修復版：A 取鎖=" + a + "，B 取鎖=" + b + "，結算 " + settledTimes
                    + " 次，INV-T-02 恆成立=" + (settledTimes <= 1));
            if (!ok) { System.out.println("  ✗ 驗收失敗"); failures++; }
            else System.out.println("           ✔ 驗收通過（TTL 涵蓋業務時間，互斥成立、單次結算）");
        }

        // [VERIFY+] 釋放鎖未校驗持有者：A 結束時無條件 del，刪掉 B 剛取得的鎖
        System.out.println("\n[VERIFY+]  釋放鎖持有者校驗：A 結束時釋放，不應刪到 B 的鎖");
        {
            // 不安全釋放
            FakeLock lk1 = new FakeLock();
            lk1.tryLock("A", 5, 0);
            lk1.tryLock("B", 5, 6);                 // A 過期 → B 持有（token=B）
            lk1.unlockUnsafe();                     // A 結束無條件 del → 刪掉 B 的鎖
            boolean cGotUnsafe = lk1.tryLock("C", 5, 7); // C 立刻取得 → B 仍在臨界區 → 再破互斥
            // 安全釋放
            FakeLock lk2 = new FakeLock();
            lk2.tryLock("A", 5, 0);
            lk2.tryLock("B", 5, 6);                 // B 持有
            boolean aDeleted = lk2.unlockSafe("A"); // A 校驗持有者 → token=B → no-op
            boolean cGotSafe = lk2.tryLock("C", 5, 7); // B 鎖仍在 → C 取鎖失敗
            System.out.println("           不安全釋放：C 於 t=7 取得鎖=" + cGotUnsafe + "（誤刪 B 鎖 → 破互斥）");
            System.out.println("           安全釋放：A 的 del 生效=" + aDeleted + "（no-op），C 取鎖=" + cGotSafe + "（B 鎖受保護）");
            boolean ok = cGotUnsafe && !aDeleted && !cGotSafe;
            if (!ok) { System.out.println("  ✗ 持有者校驗驗收失敗"); failures++; }
            else System.out.println("           ✔ 驗收通過（安全釋放校驗持有者，不誤刪他人鎖）");
        }

        // [VERIFY++] 負面對照：單一 worker 正常 取鎖 → 釋放 → 再取，皆成功
        System.out.println("\n[VERIFY++] 負面對照：單一 worker 正常 lock/unlock/relock");
        {
            FakeLock lk = new FakeLock();
            boolean l1 = lk.tryLock("A", 20, 0);
            boolean rel = lk.unlockSafe("A");
            boolean l2 = lk.tryLock("A", 20, 1);
            boolean ok = l1 && rel && l2;
            System.out.println("           取鎖=" + l1 + "，釋放=" + rel + "，再取=" + l2 + "，正常流程未被誤殺=" + ok);
            if (!ok) { System.out.println("  ✗ 正常流程被誤殺"); failures++; }
            else System.out.println("           ✔ 正常鎖流程正確");
        }

        System.out.println("\n=== 結果：" + (failures == 0 ? "全部通過 ✅ 閉環成立" : (failures + " 項失敗 ❌")) + " ===");
        System.exit(failures == 0 ? 0 : 1);
    }
}
