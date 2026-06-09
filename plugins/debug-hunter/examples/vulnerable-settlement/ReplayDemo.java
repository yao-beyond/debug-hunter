// 端到端示範：請求重放（PAT-SEC-107）的「偵測 → 復現(PoC) → 驗收」閉環。
// 零外部依賴（純 JDK，HMAC 用 javax.crypto），可直接 `javac ReplayDemo.java && java ReplayDemo`。
// 與 PaymentCallbackDemo 的差異：那是支付回調冪等；這裡是攻擊者「重放一個原本合法的資金請求」。
// 對應知識庫：PAT-SEC-107 / INV-T-04（重放零增益）/ threat-catalog AB-07 / CORP-007。
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class ReplayDemo {

    static final String CLIENT_SECRET = "client_hmac_key_27d9";
    static final long MAX_AGE_MS = 300_000; // 請求時間窗 5 分鐘

    static String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(CLIENT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static Map<String, String> parse(String body) {
        Map<String, String> m = new HashMap<>();
        for (String kv : body.split("&")) { int i = kv.indexOf('='); if (i > 0) m.put(kv.substring(0, i), kv.substring(i + 1)); }
        return m;
    }

    static final class Bank {
        final Map<String, BigDecimal> bal = new HashMap<>();
        Bank set(String u, String v) { bal.put(u, new BigDecimal(v)); return this; }
        BigDecimal get(String u) { return bal.getOrDefault(u, BigDecimal.ZERO); }
        void transfer(String from, String to, BigDecimal amt) {
            bal.merge(from, amt.negate(), BigDecimal::add);
            bal.merge(to, amt, BigDecimal::add);
        }
        String snapshot() { return bal.toString(); }
    }

    // ====== 漏洞版：驗簽通過就執行，無 nonce / 時間窗 → 同一封包可無限重送 ======
    static void transferVulnerable(Bank bank, String rawBody, String sig) {
        if (!sign(rawBody).equals(sig)) throw new SecurityException("簽章驗證失敗");
        Map<String, String> f = parse(rawBody);
        bank.transfer(f.get("from"), f.get("to"), new BigDecimal(f.get("amount")));  // ← 重送會再轉一次
    }

    // ====== 修復版：驗簽 + 時間窗 + nonce 一次性消費 ======
    static void transferFixed(Bank bank, String rawBody, String sig, long now, Set<String> usedNonces) {
        if (!sign(rawBody).equals(sig)) throw new SecurityException("簽章驗證失敗");
        Map<String, String> f = parse(rawBody);
        if (Math.abs(now - Long.parseLong(f.get("ts"))) > MAX_AGE_MS)
            throw new SecurityException("請求過期");
        if (!usedNonces.add(f.get("nonce")))                      // setIfAbsent 語意：第二次回 false
            throw new SecurityException("重放偵測：nonce 已使用");
        bank.transfer(f.get("from"), f.get("to"), new BigDecimal(f.get("amount")));
    }

    public static void main(String[] args) {
        int failures = 0;
        long now = 1_700_000_000_000L;
        // 一筆「原本合法」的轉帳請求（alice → bob 100），含 nonce 與時間戳，客戶端簽章
        String body = "from=alice&to=bob&amount=100&nonce=req-7f3a9&ts=" + now;
        String sig = sign(body);

        System.out.println("=== debug-hunter 端到端 demo：PAT-SEC-107 請求重放 ===\n");
        System.out.println("[DETECT]  taint: 資金請求驗簽後直接執行，無 nonce/時間窗 → 同封包可重放 → candidate finding");

        System.out.println("\n[REPRODUCE] PoC：攻擊者攔截到合法轉帳封包，原樣重送一次");
        {
            Bank bank = new Bank().set("alice", "100").set("bob", "0");
            transferVulnerable(bank, body, sig);             // 第 1 次（合法）
            String afterFirst = bank.snapshot();
            transferVulnerable(bank, body, sig);             // 重放（攻擊）
            boolean violated = !bank.snapshot().equals(afterFirst);  // INV-T-04：重放不該改變狀態
            System.out.println("           漏洞版：第一次後 " + afterFirst + " → 重放後 "
                    + bank.snapshot() + "，INV-T-04 違反=" + violated);
            System.out.println("           alice 餘額=" + bank.get("alice") + "（被轉走兩次，變負）");
            if (!violated) { System.out.println("  ✗ PoC 無效"); failures++; }
            else System.out.println("           ✔ PoC 成功（重放使 bob 多收 100、alice 多扣 100）");
        }

        System.out.println("\n[VERIFY]   修復版（nonce 一次性 + 時間窗）重跑同一重放");
        {
            Bank bank = new Bank().set("alice", "100").set("bob", "0");
            Set<String> used = new HashSet<>();
            transferFixed(bank, body, sig, now, used);       // 第 1 次（合法）
            String afterFirst = bank.snapshot();
            boolean blocked = false;
            try { transferFixed(bank, body, sig, now, used); } // 重放
            catch (SecurityException e) { blocked = true; System.out.println("           重放被擋：" + e.getMessage()); }
            boolean unchanged = bank.snapshot().equals(afterFirst);
            boolean ok = blocked && unchanged && bank.get("alice").compareTo(BigDecimal.ZERO) == 0;
            System.out.println("           修復版：第一次後 " + afterFirst + "，重放後 " + bank.snapshot()
                    + "，INV-T-04 恆成立=" + unchanged);
            if (!ok) { System.out.println("  ✗ 驗收失敗"); failures++; }
            else System.out.println("           ✔ 驗收通過（重放零增益，狀態不變）");
        }

        System.out.println("\n[VERIFY+]  負面對照：另一筆新 nonce 的合法轉帳應成功");
        {
            Bank bank = new Bank().set("alice", "100").set("bob", "0");
            Set<String> used = new HashSet<>();
            transferFixed(bank, body, sig, now, used);       // 第一筆
            String body2 = "from=alice&to=bob&amount=30&nonce=req-aa12&ts=" + now;
            boolean ok = true;
            try { transferFixed(bank, body2, sign(body2), now, used); } catch (Exception e) { ok = false; }
            ok = ok && bank.get("bob").compareTo(new BigDecimal("130")) == 0;
            System.out.println("           兩筆不同 nonce 後 bob=" + bank.get("bob") + "，正常流程未被誤殺=" + ok);
            if (!ok) { System.out.println("  ✗ 正常流程被誤殺"); failures++; }
            else System.out.println("           ✔ 不同 nonce 正常放行");
        }

        System.out.println("\n[VERIFY++] 變體：過期請求（時間窗外）應被擋");
        {
            Bank bank = new Bank().set("alice", "100").set("bob", "0");
            Set<String> used = new HashSet<>();
            boolean blocked = false;
            try { transferFixed(bank, body, sig, now + 600_000, used); } // 比 ts 晚 10 分鐘
            catch (SecurityException e) { blocked = true; System.out.println("           過期被擋：" + e.getMessage()); }
            if (!blocked) { System.out.println("  ✗ 過期請求未被擋"); failures++; }
            else System.out.println("           ✔ 時間窗攔截過期請求");
        }

        System.out.println("\n=== 結果：" + (failures == 0 ? "全部通過 ✅ 閉環成立" : (failures + " 項失敗 ❌")) + " ===");
        System.exit(failures == 0 ? 0 : 1);
    }
}
