// 端到端示範：金額參數未驗正負與上限（PAT-SEC-102）。純 JDK。
// 對應：PAT-SEC-102 / RULE-SEC-102 / INV-TXN-02。
import java.math.BigDecimal;

public class AmountTamperDemo {

    static BigDecimal balFrom, balTo;
    static final BigDecimal MAX_SINGLE = new BigDecimal("100000");
    static final int SCALE = 2;

    // 漏洞版：amount 未校驗就轉帳
    static void transferVulnerable(BigDecimal amount) {
        balFrom = balFrom.subtract(amount);
        balTo = balTo.add(amount);
    }
    // 修復版：signum>0 + scale + 上限校驗
    static void transferFixed(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("金額必須為正");
        if (amount.scale() > SCALE) throw new IllegalArgumentException("精度超出");
        if (amount.compareTo(MAX_SINGLE) > 0) throw new IllegalArgumentException("超單筆上限");
        balFrom = balFrom.subtract(amount);
        balTo = balTo.add(amount);
    }

    public static void main(String[] args) {
        int failures = 0;
        System.out.println("=== debug-hunter demo：PAT-SEC-102 金額參數竄改（負數/超上限）===\n");
        System.out.println("[DETECT]  amount 直接用於記帳，無 signum/上限/scale 校驗 → candidate finding");

        System.out.println("\n[REPRODUCE] PoC：attacker 送 amount = -100 轉帳（from→to）");
        {
            balFrom = new BigDecimal("0"); balTo = new BigDecimal("0");
            transferVulnerable(new BigDecimal("-100"));   // ← 負數：from 反而增加
            boolean violated = balFrom.signum() > 0;       // from 從 0 變正 = 憑空造錢
            System.out.println("           漏洞版：from=" + balFrom + "（負數轉帳使 from 反增），to=" + balTo
                    + "，INV-TXN-02 違反=" + violated);
            if (!violated) { System.out.println("  ✗ PoC 無效"); failures++; }
            else System.out.println("           ✔ PoC 成功（負數金額 → 反向入帳，憑空造錢）");
        }

        System.out.println("\n[VERIFY]   修復版：數值域校驗（正負/精度/上限）");
        {
            balFrom = new BigDecimal("0"); balTo = new BigDecimal("0");
            boolean negBlocked = false, overBlocked = false;
            try { transferFixed(new BigDecimal("-100")); } catch (Exception e) { negBlocked = true; }
            try { transferFixed(new BigDecimal("999999")); } catch (Exception e) { overBlocked = true; }
            // 正常金額放行
            balFrom = new BigDecimal("500"); balTo = new BigDecimal("0");
            transferFixed(new BigDecimal("100"));
            boolean normalOk = balFrom.compareTo(new BigDecimal("400")) == 0 && balTo.compareTo(new BigDecimal("100")) == 0;
            System.out.println("           負數被擋=" + negBlocked + "，超上限被擋=" + overBlocked + "，正常轉帳 from=" + balFrom + " to=" + balTo);
            if (!(negBlocked && overBlocked && normalOk)) { System.out.println("  ✗ 驗收失敗"); failures++; }
            else System.out.println("           ✔ 驗收通過（負數/超上限被擋、正常放行）");
        }

        System.out.println("\n=== 結果：" + (failures == 0 ? "全部通過 ✅ 閉環成立" : (failures + " 項失敗 ❌")) + " ===");
        System.exit(failures == 0 ? 0 : 1);
    }
}
