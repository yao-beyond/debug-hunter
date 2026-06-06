/**
 * @name 金流端點缺速率限制與提款速度監控（velocity）
 * @description 提款/轉帳/OTP 等敏感金流端點未見速率限制或 velocity 控制。
 *              對應 PAT-SEC-112 / RULE-SEC-112。
 * @kind problem
 * @problem.severity warning
 * @security-severity 5.3
 * @precision low
 * @id debug-hunter/rule-sec-112-rate-limit
 * @tags security
 *       external/cwe/cwe-799
 *       external/cwe/cwe-307
 *       financial
 */

import java

predicate hasMappingAnnotation(Method m) {
  m.getAnAnnotation().getType().getName().matches("%Mapping")
}

/** 敏感金流端點 */
predicate isSensitiveMoneyEndpoint(Method m) {
  hasMappingAnnotation(m) and
  m.getName().toLowerCase().matches(["%withdraw%", "%transfer%", "%payout%", "%otp%", "%redeem%"])
}

/** 具備速率/velocity 控制（呼叫或註解） */
predicate hasRateOrVelocityGuard(Method m) {
  exists(MethodCall r |
    r.getEnclosingCallable() = m and
    r.getMethod().getName().toLowerCase().matches(["%ratelimit%", "%velocity%", "%acquire%", "%throttle%"])
  )
  or
  exists(Annotation a |
    a = m.getAnAnnotation() and
    a.getType().getName().toLowerCase().matches(["%ratelimit%", "%throttle%"])
  )
}

from Method m
where
  isSensitiveMoneyEndpoint(m) and
  not hasRateOrVelocityGuard(m)
select m,
  "敏感金流端點未見速率/velocity 限制，被盜帳號可在風控反應前快速掏空 (PAT-SEC-112 / RULE-SEC-112)。"
