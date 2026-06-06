<p align="center">
  <img src="docs/logo.jpg" alt="debug-hunter mascot: Fenyuan 🫧" width="180"/>
</p>

<h1 align="center">Debug-Hunter</h1>
<p align="center">
  <b>AI-Driven Closed-Loop Fintech Debugging Framework</b><br/>
  <sub>Mascot: Fenyuan 🫧 — locked onto every financial bug the way she's locked onto that bubble</sub>
</p>

<p align="center">
  <a href="https://github.com/yao-beyond/debug-hunter/actions/workflows/ci.yml"><img src="https://github.com/yao-beyond/debug-hunter/actions/workflows/ci.yml/badge.svg" alt="CI"/></a>
  <a href="https://github.com/yao-beyond/debug-hunter/actions/workflows/codeql.yml"><img src="https://github.com/yao-beyond/debug-hunter/actions/workflows/codeql.yml/badge.svg" alt="CodeQL"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-yellow.svg" alt="License: MIT"/></a>
  <img src="https://img.shields.io/badge/Java-21-orange.svg" alt="Java 21"/>
  <img src="https://img.shields.io/badge/Spring%20Boot-3-6DB33F.svg" alt="Spring Boot 3"/>
  <img src="https://img.shields.io/badge/Semgrep-rules%205%2F5-1B87C9.svg" alt="Semgrep rules 5/5"/>
</p>

<p align="center"><a href="README.md">正體中文</a> | <b>English</b></p>

`debug-hunter` is an **AI closed-loop debugging framework** built for fintech. A structured Knowledge Base guides an AI agent through the full lifecycle — **Threat-Model → Detect → Triage → Reproduce → Fix → Verify → Recycle** — to hunt down **financial and financial-security vulnerabilities**.

![debug-hunter v2.0 architecture: 7-stage loop × three-layer defense](docs/v2-architecture.png)

---

## 🤔 Why use this agent?

Generic SAST / AI scanners have three blind spots for financial systems. debug-hunter exists to close them:

| Pain point | Generic tools | debug-hunter |
|------------|---------------|--------------|
| **Blind to business logic** | Catch only syntax-level flaws (XSS, SQLi) | Ships with a money-flow map, ownership/authorization matrix, and settlement state machines — so it catches **IDOR fund withdrawal, TOCTOU double-spend, rounding theft, forged payment callbacks**: bugs where the code is "correct" but someone is attacking it |
| **Confidently wrong (false positives)** | Everything is "critical," drowning real issues | **Evidence gate**: a finding stays "candidate" until it has a *taint path + DB evidence + refutation check* — no evidence, no severity |
| **Finds but never fixes** | Hands you a list and stops | **Closed loop**: reproduce (attack PoC) → fix → verify (invariants hold) → distill each bug into a permanent rule + regression corpus so it's auto-blocked next time |

**In one line:** it doesn't just ask "can the code miscalculate?" — it asks "**can an attacker make it calculate for them?**" — and uses invariants like *conservation of money* as the last safety net that catches even unknown techniques.

---

## 🚀 Key features

- **Dual-track hunting**: a correctness hat (float errors, idempotency failures) + a security hat (privilege abuse, tampering, double-spend, forgery, injection).
- **Three-layer defense**: signature match (known patterns) → taint flow (known attack surface) → financial invariants (unknown consequences).
- **Governance-driven**: every knowledge entry follows [`knowledge-schema.md`](knowledge-base/knowledge-schema.md), is machine-parsable into detection actions, and self-evolves safely via RECYCLE (guarding against semantic drift / false-positive pollution).
- **Quantified risk**: severity is annualized loss expectancy (ALE) from [`severity-loss-model.md`](knowledge-base/severity-loss-model.md), not a subjective 1–5.
- **End-to-end verification**: property-based testing (PBT) and an attack regression corpus ensure every bug is reproducibly caught and permanently killed.

---

## 📦 Install

### Required
- **An agent runner**: [Claude Code CLI](https://claude.com/claude-code) recommended (it reads `AGENT.md` to drive the whole loop). Any LLM tool that supports a custom system prompt / agent file works too.

### Optional (as needed)
- **Semgrep** — to run the bundled static rules: `pipx install semgrep` or `brew install semgrep`
- **JDK 21+** — to run the end-to-end demo (pure JDK, zero third-party deps)

### Get the project
```bash
git clone <this-repo-url> debug-hunter
cd debug-hunter
```

---

## 🛠️ Usage

### 1. Run the full loop with Claude Code (primary)
Point Claude Code at the orchestrator `AGENT.md`; it loads the knowledge base and runs the 7 stages:
```bash
claude --agent AGENT.md "Scan src/settlement for all high-risk financial and security vulnerabilities"
```
It outputs evidence-backed findings, attack PoCs, fixes, and feedback rules.

### 2. Run a single stage / specialized agent
```bash
# Stage 0: threat modeling (think like the attacker first)
claude --agent agents/threat-modeler.md "Threat-model every money endpoint in src/wallet"

# Stage 1: financial security / fraud detection (taint source→sink)
claude --agent agents/security-fraud-detector.md "Scan src/settlement"

# Stage 1: correctness detection
claude --agent agents/detector.md "Static scan of src/settlement"
```

### 3. Run the bundled Semgrep rules (CI-ready)
```bash
# Scan your source for financial-security patterns
semgrep --config rules/semgrep/financial-security.yml src/

# Validate the rules themselves (pass/fail fixtures — should be 5/5)
semgrep --test rules/semgrep/
```

### 4. Run the end-to-end demos (see the loop in action)
9 pure-JDK attack/race closed-loop demos. Each prints DETECT → REPRODUCE(PoC) → VERIFY, where "PoC success" means a financial invariant is violated (all run in CI on every push):

| Demo | Vulnerability | Dimension | Oracle |
|------|---------------|-----------|--------|
| `IdorDemo` | Privilege fund withdrawal (PAT-SEC-101) | internal authz | INV-ST-01 |
| `PaymentCallbackDemo` | Forged payment callback (PAT-SEC-104) | external trust | INV-T-03 |
| `OracleManipulationDemo` | Oracle manipulation / stale price (PAT-SEC-105) | data integrity | INV-ST-03 |
| `DoubleSpendDemo` | TOCTOU double-spend (PAT-SEC-103) | concurrency atomicity | INV-ST-01 |
| `MassAssignmentDemo` | Mass-assignment balance tamper (PAT-SEC-106) | field whitelist | INV-ST-02/05 |
| `ReplayDemo` | Request replay (PAT-SEC-107) | time series | INV-T-04 |
| `SchedulerRaceDemo` | Scheduler multi-worker data race (PAT-SCH-001) | sharding/idempotency | INV-T-02 |
| `TradingWindowRaceDemo` | Order time-window race (PAT-BIZ-001) | business time window | INV-T-03/ST-05 |
| `LockTtlDemo` | Distributed-lock TTL defect (PAT-CON-003) | lock mutual-exclusion | INV-T-02 |

```bash
cd examples/vulnerable-settlement
javac IdorDemo.java               && java IdorDemo
javac PaymentCallbackDemo.java    && java PaymentCallbackDemo
javac OracleManipulationDemo.java && java OracleManipulationDemo
javac DoubleSpendDemo.java        && java DoubleSpendDemo
javac MassAssignmentDemo.java     && java MassAssignmentDemo
javac ReplayDemo.java             && java ReplayDemo
javac SchedulerRaceDemo.java      && java SchedulerRaceDemo
javac TradingWindowRaceDemo.java  && java TradingWindowRaceDemo
javac LockTtlDemo.java            && java LockTtlDemo
# exit 0 = loop holds; per-demo notes in examples/vulnerable-settlement/README.md
```

### 5. Let the knowledge base drive AI diagnosis
When you (or the AI) hit an anomaly, anchor the judgment to the knowledge base:
> "Per [`financial-invariants.md`](knowledge-base/financial-invariants.md), check whether this finding violates balance conservation; map it against [`money-flow-map.md`](knowledge-base/money-flow-map.md); and only assign severity after completing the evidence required by [`finding-evidence-standard.md`](knowledge-base/finding-evidence-standard.md)."

---

## 🗺️ Knowledge Base

Knowledge is organized into four layers. Full map: [**MAP.md**](knowledge-base/MAP.md); per-file index and consistency checks: [**KB-INDEX.md**](knowledge-base/KB-INDEX.md).

1. **Meta-governance** — knowledge format & evidence standards: `knowledge-schema`, `finding-evidence-standard`
2. **Ground-truth** — money-flow map, ownership matrix, state machines, glossary
3. **Patterns** — vulnerability patterns (PAT-SEC/FIN/BIZ), invariants (INV), risk model
4. **Execution** — reproduction scripts, attack corpus, Semgrep rules, debug playbooks

---

## 📈 Vision
Eliminate "phantom bugs" in financial systems — automated interception and precise severity for core business vulnerabilities, so that every fixed bug never comes back.

---
© 2026 AetherCare Systems - Financial DevSecOps Division.
