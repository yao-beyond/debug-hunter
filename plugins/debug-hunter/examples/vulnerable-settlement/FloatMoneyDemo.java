// 端到端示範：double/float 處理金額（PAT-FIN-002）的「偵測 → 復現 → 驗收」閉環。
// 零外部依賴（純 JDK），可直接 `javac FloatMoneyDemo.java && java FloatMoneyDemo`。
// 對應知識庫：PAT-FIN-002 / RULE-FIN-003 /（長期累積侵蝕 INV-ST-03 資產守恆）。
import java.math.BigDecimal;
import java.math.RoundingMode;

public class FloatMoneyDemo {

    public static void main(String[] args) {
        int failures = 0;
        System.out.println("=== debug-hunter 端到端 demo：PAT-FIN-002 double/float 處理金額 ===\n");
        System.out.println("[DETECT]  金額以 double/float 儲存或計算、BigDecimal.doubleValue() 後再算、用 == 比較 → candidate finding");

        // [REPRODUCE] 以 double 累加 0.01 共 100 萬次（期望恰好 10000.00）
        System.out.println("\n[REPRODUCE] PoC：以 double 累加 0.01 共 1,000,000 次（期望 10000.00）");
        {
            double d = 0.0;
            for (int i = 0; i < 1_000_000; i++) d += 0.01;
            BigDecimal got = BigDecimal.valueOf(d);
            boolean violated = got.compareTo(new BigDecimal("10000.00")) != 0;
            System.out.printf("           double 結果=%.8f，誤差=%.8f，與精確值不符=%b%n", d, d - 10000.00, violated);
            if (!violated) { System.out.println("  ✗ PoC 無效（此平台未顯現誤差）"); failures++; }
            else System.out.println("           ✔ PoC 成功（浮點累積誤差：高流量下大量累加後偏離真值）");
        }

        // [REPRODUCE] 經典 0.1 + 0.2 == 0.3 ?
        System.out.println("\n[REPRODUCE] PoC：double 0.1 + 0.2 == 0.3 ?");
        {
            boolean eq = (0.1 + 0.2) == 0.3;
            System.out.println("           double：0.1 + 0.2 = " + (0.1 + 0.2) + "，== 0.3 → " + eq);
            if (eq) { System.out.println("  ✗ PoC 無效"); failures++; }
            else System.out.println("           ✔ PoC 成功（== 比較金額不可靠，0.1+0.2 ≠ 0.3）");
        }

        // [VERIFY] 修復版：全程 BigDecimal（字串建構）+ compareTo
        System.out.println("\n[VERIFY]   修復版（全程 BigDecimal，字串建構 + compareTo）");
        {
            BigDecimal sum = BigDecimal.ZERO;
            BigDecimal cent = new BigDecimal("0.01");
            for (int i = 0; i < 1_000_000; i++) sum = sum.add(cent);
            boolean exact = sum.compareTo(new BigDecimal("10000.00")) == 0;
            boolean cmp = new BigDecimal("0.1").add(new BigDecimal("0.2"))
                    .compareTo(new BigDecimal("0.3")) == 0;
            System.out.println("           BigDecimal 累加 100 萬次 = " + sum + "，精確=" + exact);
            System.out.println("           0.1 + 0.2 compareTo 0.3 == 0 → " + cmp);
            if (!(exact && cmp)) { System.out.println("  ✗ 驗收失敗"); failures++; }
            else System.out.println("           ✔ 驗收通過（無累積誤差、比較正確）");
        }

        // [VERIFY+] 對照：BigDecimal 轉 double 後再算，會把浮點誤差引回來
        System.out.println("\n[VERIFY+]  對照：BigDecimal → doubleValue() 後計算又引回浮點誤差");
        {
            BigDecimal amount = new BigDecimal("0.10");
            BigDecimal rate = new BigDecimal("1.10");           // 收益率 110%
            double bad = amount.doubleValue() * rate.doubleValue();          // ← 反例：轉 double
            BigDecimal good = amount.multiply(rate).setScale(8, RoundingMode.HALF_DOWN); // ✅ 全程 BigDecimal
            boolean badDeviates = BigDecimal.valueOf(bad).compareTo(good) != 0;
            System.out.println("           轉 double 計算=" + bad + "，全程 BigDecimal=" + good
                    + "，轉 double 偏離=" + badDeviates);
            if (!badDeviates) { System.out.println("  ✗ 對照無效"); failures++; }
            else System.out.println("           ✔ 對照成立（即使來源是 BigDecimal，中途轉 double 仍引回誤差）");
        }

        System.out.println("\n=== 結果：" + (failures == 0 ? "全部通過 ✅ 閉環成立" : (failures + " 項失敗 ❌")) + " ===");
        System.exit(failures == 0 ? 0 : 1);
    }
}
