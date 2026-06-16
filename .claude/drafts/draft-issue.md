---
title: "[Chore] Set up Java package structure, docs folder, and GitHub templates"
kind: issue
template: chore
labels: []
---

## Summary

The project currently has a single placeholder `Main.java` under `com.authlyn` with no package hierarchy, no `docs/` directory, and no GitHub issue/PR templates. Before Track 0 implementation begins, this task establishes the Java package layout that mirrors the spec's layered architecture, creates a `docs/` folder for architecture notes and design decisions, and adds GitHub issue and PR templates so all future work is filed consistently. This is pure scaffolding — no server logic is implemented.

## Context

The spec (`src/ember-spec (mini redis).md`) defines a layered architecture: NIO event loop → RESP parser → command dispatcher → keyspace → persistence. Each layer maps naturally to a Java package. Setting up this structure now prevents ad-hoc class placement as Track 0–F work begins. The GitHub templates align with the three issue types used in this project: bugs, implementation tasks (track stages), and chores.

Actor: Developer / maintainer (no end-user-facing change).

## Proposed Changes

1. Restructure under `src/main/java/com/authlyn/` into the following packages:

   ```
   com.authlyn
   ├── Main.java                    (entry point — stays here)
   ├── server/                      (ServerSocket / NIO Selector, connection lifecycle)
   ├── protocol/                    (RESP parser, RESP encoder)
   ├── command/                     (command dispatcher, individual command handlers)
   ├── store/                       (Keyspace, RedisObject, expires map)
   ├── persistence/                 (RDB reader, AOF writer — stubs for now)
   └── replication/                 (master/replica handshake — stub for now)
   ```

2. Add placeholder `.gitkeep` files (or stub classes with a one-line comment) in each package so git tracks the directories.

3. Create `docs/` at the repo root with two files:
   - `docs/architecture.md` — prose description of the layered architecture with the ASCII diagram from `README.md` expanded.
   - `docs/design-decisions.md` — a running log of the key design choices (single-threaded execution, incremental parser, skip list for ZSET, approximate LRU) with a one-paragraph rationale each.

4. Add GitHub issue and PR templates under `.github/`:
   - `.github/ISSUE_TEMPLATE/bug_report.md` — steps to reproduce, expected vs actual behavior, track/stage context.
   - `.github/ISSUE_TEMPLATE/implementation-task.md` — track stage work with spec-referenced acceptance criteria.
   - `.github/ISSUE_TEMPLATE/chore.md` — refactor, tooling, scaffolding, docs work.
   - `.github/pull_request_template.md` — Ember-specific what-changed checklist, `./gradlew` verification steps, `Closes #n` link.

## Acceptance Criteria

- [ ] All six packages exist under `com.authlyn` and are tracked by git.
- [ ] `Main.java` compiles and is reachable at its current location (entry point unchanged).
- [ ] `docs/architecture.md` exists and contains the architecture diagram + layer descriptions from the spec.
- [ ] `docs/design-decisions.md` covers at least: single-threaded execution, incremental RESP parser, skip list for sorted sets, approximate LRU eviction.
- [ ] All four GitHub templates exist under `.github/` and are visible when opening a new issue/PR on GitHub.
- [ ] `./gradlew build` passes — nothing broken by the restructure.
- [ ] No implementation logic added — packages contain stubs or `.gitkeep` only.

## Out of Scope

- Implementing any command or server logic (Track 0).
- Adding a `run` Gradle task (follow-up).
- Setting up CI / GitHub Actions workflows.
- Adding JUnit test stubs (Track 0 setup).

## Dependencies / Blockers

None known. Can be done directly on `main` or a short-lived `chore/` branch.

## References

- `docs/spec.md` — §3 High-level architecture, §6 Track breakdown
- `README.md` — Architecture section (ASCII diagram, key design decisions)
- `.github/ISSUE_TEMPLATE/` — templates created in this task
