// CodeQL 測試 fixture：供 build-mode:none 原始碼抽取，讓 4 條查詢有可命中的標的。
// 不參與 javac 編譯（位於 rules/codeql/fixtures，非 examples/）。
// 本地註解型別，確保 CodeQL 能解析出 "PostMapping" 名稱。
package fixtures;

import java.math.BigDecimal;

@interface PostMapping { String value(); }

class WalletService {
    BigDecimal getBalance(Long a) { return BigDecimal.ZERO; }
    void debit(Long a, BigDecimal amt) {}
    void debitIfEnough(Long a, BigDecimal amt) {}
    void credit(Long a, BigDecimal amt) {}
}
class SignatureVerifier { boolean verify(String body, String sig) { return true; } }
class RateLimiter { void acquire(String key) {} }

public class CodeqlFixtures {
    WalletService walletService = new WalletService();
    SignatureVerifier signatureVerifier = new SignatureVerifier();
    RateLimiter rateLimiter = new RateLimiter();

    // RULE-SEC-103 命中：先查餘額再非原子扣款，無鎖
    void withdrawToctou(Long acc, BigDecimal amt) {
        BigDecimal b = walletService.getBalance(acc);
        if (b.compareTo(amt) >= 0) {
            walletService.debit(acc, amt);
        }
    }
    // RULE-SEC-103 不命中：原子條件式扣款
    void withdrawAtomic(Long acc, BigDecimal amt) {
        walletService.debitIfEnough(acc, amt);
    }

    // RULE-SEC-104 命中：回調入帳前無簽章驗證
    @PostMapping("/payment/callback")
    String onCallbackVuln(String body) {
        walletService.credit(1L, BigDecimal.TEN);
        return "ok";
    }
    // RULE-SEC-104 不命中：先驗簽
    @PostMapping("/payment/callback/safe")
    String onCallbackSafe(String body, String sig) {
        signatureVerifier.verify(body, sig);
        walletService.credit(1L, BigDecimal.TEN);
        return "ok";
    }

    // RULE-SEC-108 命中：後台調帳直接動帳，無雙人覆核
    @PostMapping("/admin/adjustBalance")
    String adjustVuln(Long acc, BigDecimal delta) {
        walletService.credit(acc, delta);
        return "ok";
    }

    // RULE-SEC-112 命中：提款端點無速率限制
    @PostMapping("/withdraw")
    String withdrawNoLimit(Long acc, BigDecimal amt) {
        walletService.debitIfEnough(acc, amt);
        return "ok";
    }
    // RULE-SEC-112 不命中：有速率限制
    @PostMapping("/transfer/safe")
    String transferRateLimited(Long from, Long to, BigDecimal amt) {
        rateLimiter.acquire("transfer:" + from);
        walletService.debitIfEnough(from, amt);
        return "ok";
    }
}
