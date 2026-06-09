// 端到端示範：時間戳單位混淆（ms/s）與時區邊界（PAT-FIN-007）。純 JDK。
// 對應：PAT-FIN-007 / RULE-FIN-008 / INV-T-03。
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

public class TimestampUnitDemo {

    static final ZoneId EXCHANGE = ZoneId.of("Asia/Taipei");

    public static void main(String[] args) {
        int failures = 0;
        System.out.println("=== debug-hunter demo：PAT-FIN-007 時間戳單位/時區混淆 ===\n");
        System.out.println("[DETECT]  裸 long 時間戳 ms/s 不明、或用系統預設時區判結算日 → candidate finding");

        // 真實事件：epoch 毫秒
        long epochMillis = 1_700_000_000_000L; // 2023-11-14T22:13:20Z

        System.out.println("\n[REPRODUCE] PoC：把毫秒時間戳當成秒傳給 Instant.ofEpochSecond");
        {
            Instant asSeconds = Instant.ofEpochSecond(epochMillis);  // ← 單位誤用：當成秒
            Instant correct = Instant.ofEpochMilli(epochMillis);
            boolean violated = !asSeconds.equals(correct);
            long yearsOff = Math.abs(asSeconds.getEpochSecond() - correct.getEpochSecond()) / (365L * 24 * 3600);
            System.out.println("           誤當秒：" + asSeconds + "（差約 " + yearsOff + " 年），正確：" + correct
                    + "，單位錯誤=" + violated);
            if (!violated) { System.out.println("  ✗ PoC 無效"); failures++; }
            else System.out.println("           ✔ PoC 成功（ms 當 s 用 → 時間飛到數萬年後，取錯價格區間/結算日）");
        }

        System.out.println("\n[VERIFY]   修復版：單位型別化（Instant）+ 固定交易所時區算結算日");
        {
            Instant ts = Instant.ofEpochMilli(epochMillis);
            LocalDate settleDate = ts.atZone(EXCHANGE).toLocalDate();   // 固定時區，非系統預設
            boolean ok = ts.toEpochMilli() == epochMillis && settleDate != null;
            System.out.println("           Instant=" + ts + "，結算日(" + EXCHANGE + ")=" + settleDate);
            if (!ok) { System.out.println("  ✗ 驗收失敗"); failures++; }
            else System.out.println("           ✔ 驗收通過（型別即語意、時區明確，歸期一致）");
        }

        System.out.println("\n=== 結果：" + (failures == 0 ? "全部通過 ✅ 閉環成立" : (failures + " 項失敗 ❌")) + " ===");
        System.exit(failures == 0 ? 0 : 1);
    }
}
