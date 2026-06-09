# Changelog

All notable changes to **debug-hunter** are documented here. This project adheres to
[Semantic Versioning](https://semver.org).

## [2.0.0] - 2026-06-09

### Added
- **Plugin packaging for Claude Code and Codex CLI.** The repository is now a self-hosted,
  single-plugin marketplace that installs on both platforms:
  - Claude Code: `.claude-plugin/plugin.json` + `.claude-plugin/marketplace.json`
  - Codex CLI: `.codex-plugin/plugin.json` + `.agents/plugins/marketplace.json`
- `AGENTS.md` orchestrator (Codex convention) alongside the existing `AGENT.md`.
- YAML frontmatter (`name` / `description`) on every agent in `agents/`, so they load as
  first-class subagents in Claude Code.
- `agents/root-cause.md` and `agents/verifier.md` — the two loop stages that `AGENT.md`
  referenced but did not ship.
- `commands/debug-hunt.md` — a `/debug-hunter:debug-hunt` slash command that kicks off the
  full closed loop on a target scope.
- The Bug-detection skill moved to the standard plugin layout at
  `skills/debug-hunter/SKILL.md` with proper frontmatter.

### Notes
- There is no public third-party submission flow to an official Anthropic or OpenAI
  marketplace. "Install" means adding **this repository** as a marketplace source.
