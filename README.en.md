<p align="center">
  <img src="docs/logo.jpg" alt="debug-hunter mascot: Fenyuan 🫧" width="180"/>
</p>

<h1 align="center">Debug-Hunter</h1>
<p align="center">
  <b>AI-Driven Closed-Loop Fintech Debugging Framework</b><br/>
  <sub>Mascot: Fenyuan 🫧 — locked onto every financial bug the way she's locked onto that bubble</sub>
</p>

<p align="center">
  <a href="https://github.com/yao-beyond/debug-hunter/releases/latest"><img src="https://img.shields.io/github/v/release/yao-beyond/debug-hunter?label=release&color=success" alt="Latest release"/></a>
  <a href="https://github.com/yao-beyond/debug-hunter/actions/workflows/ci.yml"><img src="https://github.com/yao-beyond/debug-hunter/actions/workflows/ci.yml/badge.svg" alt="CI"/></a>
  <a href="https://github.com/yao-beyond/debug-hunter/actions/workflows/codeql.yml"><img src="https://github.com/yao-beyond/debug-hunter/actions/workflows/codeql.yml/badge.svg" alt="CodeQL"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-yellow.svg" alt="License: MIT"/></a>
  <img src="https://img.shields.io/badge/Java-21-orange.svg" alt="Java 21"/>
  <img src="https://img.shields.io/badge/Spring%20Boot-3-6DB33F.svg" alt="Spring Boot 3"/>
  <img src="https://img.shields.io/badge/Semgrep-rules%207%2F7-1B87C9.svg" alt="Semgrep rules 7/7"/>
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
- **Governance-driven**: every knowledge entry follows [`knowledge-schema.md`](plugins/debug-hunter/knowledge-base/knowledge-schema.md), is machine-parsable into detection actions, and self-evolves safely via RECYCLE (guarding against semantic drift / false-positive pollution).
- **Quantified risk**: severity is annualized loss expectancy (ALE) from [`severity-loss-model.md`](plugins/debug-hunter/knowledge-base/severity-loss-model.md), not a subjective 1–5.
- **End-to-end verification**: property-based testing (PBT) and an attack regression corpus ensure every bug is reproducibly caught and permanently killed.

---

## 📦 Install

debug-hunter is itself a **self-hosted plugin marketplace** that installs on both Claude Code and Codex CLI.

> ℹ️ Neither Anthropic nor OpenAI currently offers a public third-party submission flow to an "official" marketplace. "Install" here means **adding this repository as a marketplace source** and installing the `debug-hunter` plugin from it.

### Option A: Claude Code (plugin marketplace)
```bash
# Inside a Claude Code session:
/plugin marketplace add yao-beyond/debug-hunter
/plugin install debug-hunter@debug-hunter-marketplace
```
Then run `/debug-hunter:debug-hunt <scope>` to kick off the full loop. The subagents (`threat-modeler`, `detector`, `security-fraud-detector`, `reproducer`, `root-cause`, `verifier`, `knowledge-writer`) show up in `/agents`.

### Option B: Codex CLI (plugin marketplace)
```bash
codex plugin marketplace add yao-beyond/debug-hunter
codex            # interactive mode
/plugins         # enable debug-hunter
```

### Option C: Run from source (no plugin install)
```bash
git clone https://github.com/yao-beyond/debug-hunter.git
cd debug-hunter
```
The plugin body lives in `plugins/debug-hunter/`; point Claude Code at its `AGENT.md` (see Usage below).

### Optional tools (as needed)
- **Semgrep** — to run the bundled static rules: `pipx install semgrep` or `brew install semgrep`
- **JDK 21+** — to run the end-to-end demo (pure JDK, zero third-party deps)

---

## 📁 Repository layout

The repo root is itself a **self-hosted marketplace**; the plugin body lives in
`plugins/debug-hunter/` (self-contained, copied on install by both platforms). Two catalogs
point at the same plugin without interfering.

```text
debug-hunter/                            # repo root = self-hosted plugin marketplace
├── .claude-plugin/marketplace.json      # Claude Code catalog  ─┐
├── .agents/plugins/marketplace.json     # Codex catalog        ─┤→ both point to ↓
└── plugins/debug-hunter/                # ◀ the plugin (shared by both, self-contained)
    ├── .claude-plugin/plugin.json       #   Claude Code manifest
    ├── .codex-plugin/plugin.json        #   Codex manifest
    ├── AGENT.md · AGENTS.md             #   7-stage closed-loop orchestrator (AGENTS.md = Codex)
    ├── agents/                          #   7 subagents: threat-modeler · detector ·
    │                                    #   security-fraud-detector · reproducer ·
    │                                    #   root-cause · verifier · knowledge-writer
    ├── skills/debug-hunter/SKILL.md     #   financial bug-detection skill
    ├── commands/debug-hunt.md           #   slash command /debug-hunter:debug-hunt
    ├── knowledge-base/                  #   knowledge base (patterns / invariants / evidence)
    ├── rules/                           #   Semgrep + CodeQL rules
    └── examples/                        #   23 pure-JDK closed-loop demos
```

```text
Install flow
  Claude Code:  /plugin marketplace add yao-beyond/debug-hunter
                /plugin install debug-hunter@debug-hunter-marketplace
  Codex CLI:    codex plugin marketplace add yao-beyond/debug-hunter  →  /plugins
                       └─ both copy the whole plugins/debug-hunter/ into the local plugin cache
```

---

## 🛠️ Usage

### 1. Run the full loop with Claude Code (primary)
Point Claude Code at the orchestrator `AGENT.md`; it loads the knowledge base and runs the 7 stages:
```bash
claude --agent plugins/debug-hunter/AGENT.md "Scan src/settlement for all high-risk financial and security vulnerabilities"
```
It outputs evidence-backed findings, attack PoCs, fixes, and feedback rules.

### 2. Run a single stage / specialized agent
```bash
# Stage 0: threat modeling (think like the attacker first)
claude --agent plugins/debug-hunter/agents/threat-modeler.md "Threat-model every money endpoint in src/wallet"

# Stage 1: financial security / fraud detection (taint source→sink)
claude --agent plugins/debug-hunter/agents/security-fraud-detector.md "Scan src/settlement"

# Stage 1: correctness detection
claude --agent plugins/debug-hunter/agents/detector.md "Static scan of src/settlement"
```

### 3. Run the bundled Semgrep rules (CI-ready)
```bash
# Scan your source for financial-security patterns
semgrep --config plugins/debug-hunter/rules/semgrep/financial-security.yml src/

# Validate the rules themselves (pass/fail fixtures — should be 7/7)
semgrep --test plugins/debug-hunter/rules/semgrep/
```

### 4. Run the end-to-end demos (see the loop in action)
**23 pure-JDK closed-loop demos.** Each prints DETECT → REPRODUCE(PoC) → VERIFY, where "PoC success" means a financial invariant (or numeric exactness) is violated (all run in CI on every push):

| Category | demos |
|----------|-------|
| Security attacks (PAT-SEC) | Idor, AmountTamper, DoubleSpend, PaymentCallback, OracleManipulation, MassAssignment, Replay, MakerChecker, AuditTrail, Velocity, PromoAbuse, IdempotencyKey |
| Concurrency/scheduling (PAT-CON/SCH) | DoubleSpend, SchedulerRace, LockTtl |
| Precision/business (PAT-FIN/BIZ) | FloatMoney, BigDecimalEquals, DivideRounding, AssetScale, AllocationResidue, TimestampUnit, LongOverflow, SettlementGuard, TradingWindowRace |

```bash
cd plugins/debug-hunter/examples/vulnerable-settlement
for f in *Demo.java; do javac "$f" && java "${f%.java}"; done   # run all; exit 0 = loop holds
```

![demo suite run: all 23 demos pass](docs/demo-suite-run.png)

> **Full pattern → demo/rule matrix: [DEMO-COVERAGE.md](plugins/debug-hunter/knowledge-base/DEMO-COVERAGE.md)** — all 30 PATs are evidenced: 23 runnable demos, 3 covered by Semgrep/CodeQL static rules, 4 covered by `reproduce-scenarios` SCENEs. Per-demo notes: [examples README](plugins/debug-hunter/examples/vulnerable-settlement/README.md).

### 5. Let the knowledge base drive AI diagnosis
When you (or the AI) hit an anomaly, anchor the judgment to the knowledge base:
> "Per [`financial-invariants.md`](plugins/debug-hunter/knowledge-base/financial-invariants.md), check whether this finding violates balance conservation; map it against [`money-flow-map.md`](plugins/debug-hunter/knowledge-base/money-flow-map.md); and only assign severity after completing the evidence required by [`finding-evidence-standard.md`](plugins/debug-hunter/knowledge-base/finding-evidence-standard.md)."

---

## 🗺️ Knowledge Base

Knowledge is organized into four layers. Full map: [**MAP.md**](plugins/debug-hunter/knowledge-base/MAP.md); per-file index and consistency checks: [**KB-INDEX.md**](plugins/debug-hunter/knowledge-base/KB-INDEX.md).

1. **Meta-governance** — knowledge format & evidence standards: `knowledge-schema`, `finding-evidence-standard`
2. **Ground-truth** — money-flow map, ownership matrix, state machines, glossary
3. **Patterns** — vulnerability patterns (PAT-SEC/FIN/BIZ), invariants (INV), risk model
4. **Execution** — reproduction scripts, attack corpus, Semgrep rules, debug playbooks

---

## 📣 Outreach / listing tracker

| Platform / list | Link | Status |
|------|------|------|
| GitHub Release | [v2.0.0](https://github.com/yao-beyond/debug-hunter/releases/tag/v2.0.0) | ✅ Published |
| awesome-claude (curated list) | [suggestion issue #259](https://github.com/webfuse-com/awesome-claude/issues/259) | ⏳ Awaiting maintainer review |
| 🔒 Watch routine (internal) | [routine: #259 watcher](https://claude.ai/code/routines/trig_013bEfkhp1enjjmTF4BPHM1X) | 🤖 Daily auto-check at 09:01 |

> Tracks debug-hunter's external releases and third-party listing progress. Update the status as new outreach happens.
> 🔒 Rows marked "internal" are private monitoring tools; the link is only accessible from the maintainer's account (not usable by other readers).

---

## 📈 Vision
Eliminate "phantom bugs" in financial systems — automated interception and precise severity for core business vulnerabilities, so that every fixed bug never comes back.

---
© 2026 AetherCare Systems - Financial DevSecOps Division.
