import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

public class LedgerService {

    record Account(long ownerId, BigDecimal balance) {}

    final Map<Long, Account> accounts = new HashMap<>();

    LedgerService open(long accountId, long ownerId, String balance) {
        accounts.put(accountId, new Account(ownerId, new BigDecimal(balance)));
        return this;
    }

    BigDecimal balanceOf(long accountId) { return accounts.get(accountId).balance(); }

    static class AccessException extends RuntimeException {
        AccessException(String m) { super(m); }
    }

    BigDecimal withdraw(long callerId, long accountId, BigDecimal amount) {
        Account acc = accounts.get(accountId);
        if (acc.ownerId() != callerId)
            throw new AccessException("caller not owner of account " + accountId);
        if (amount.signum() <= 0)
            throw new IllegalArgumentException("amount must be positive");
        if (acc.balance().compareTo(amount) < 0)
            throw new IllegalArgumentException("insufficient balance");
        BigDecimal next = acc.balance().subtract(amount);
        accounts.put(accountId, new Account(acc.ownerId(), next));
        return next;
    }

    void transfer(long callerId, long fromId, long toId, BigDecimal amount) {
        Account from = accounts.get(fromId);
        if (from.ownerId() != callerId)
            throw new AccessException("caller not owner of source account " + fromId);
        if (amount.signum() <= 0)
            throw new IllegalArgumentException("amount must be positive");
        if (from.balance().compareTo(amount) < 0)
            throw new IllegalArgumentException("insufficient balance");
        Account to = accounts.get(toId);
        accounts.put(fromId, new Account(from.ownerId(), from.balance().subtract(amount)));
        accounts.put(toId, new Account(to.ownerId(), to.balance().add(amount)));
    }

    static BigDecimal[] split(BigDecimal total, int n) {
        BigDecimal[] out = new BigDecimal[n];
        BigDecimal each = total.divide(new BigDecimal(n), 2, RoundingMode.DOWN);
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            out[i] = (i == n - 1) ? total.subtract(allocated) : each;
            allocated = allocated.add(out[i]);
        }
        return out;
    }

    static BigDecimal applyRate(BigDecimal principal, BigDecimal rate) {
        return principal.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal accumulate(BigDecimal unit, int times) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < times; i++) sum = sum.add(unit);
        return sum;
    }
}
