# github-worker

A House Elf that fixes GitHub issues and reviews PRs using [Claude Code](https://claude.com/claude-code). It runs on a schedule, uses a dedicated bot account for all automated actions, and advances one step per run through a multi-stage stateful workflow.

## Prerequisites

- **Java 17+** (via SDKMAN or system package manager)
- **[JBang](https://www.jbang.dev/)** — `sdk install jbang` or `curl -Ls https://sh.jbang.dev | bash`
- **[GitHub CLI](https://cli.github.com/)** (`gh`) — authenticated with `gh auth login`
- **[Claude Code CLI](https://claude.com/claude-code)** (`claude`)
- **Gmail account** with an [App Password](https://myaccount.google.com/apppasswords) (for email notifications)
- **A bot GitHub account** with its own Personal Access Token

### Creating a bot GitHub account

1. Create a new GitHub account (e.g. `yourname-bot`)
2. Create a Personal Access Token for the bot:
   - Log in as the bot account
   - Go to https://github.com/settings/tokens/new?scopes=repo,read:org,write:discussion&description=github-worker-bot
   - Select scopes: `repo`, `read:org`, `write:discussion`
   - Copy the token — you'll need it during install

### Creating your own PAT

You also need a PAT for your own account (used for reading your issue assignments and reactions):

- Go to https://github.com/settings/tokens/new?scopes=repo,read:org&description=github-worker
- Select scopes: `repo`, `read:org`
- Copy the token

## Install

### Quick install (via JBang)

```bash
# Install the worker
jbang app install github-worker@House-elves/github-worker

# Interactive setup (prompts for tokens, email, schedule)
github-worker --install

# Install the dashboard (optional)
jbang app install github-worker-ui@House-elves/github-worker

# Set up the dashboard as a service
github-worker-ui --install
```

That's it. Both tools are on your PATH, scheduled to run automatically, and survive restarts.

### Manual install (from source)

```bash
git clone https://github.com/House-elves/github-worker.git
cd github-worker
./install.sh
```

### Dashboard

The dashboard is a Quarkus web app that monitors and controls the worker. After installing with `github-worker-ui --install`, it's available at:

- **http://github-worker.house-elves:7478** (if hostname was added to `/etc/hosts`)
- **http://localhost:7478**

Features:

- Live state view of tracked issues and reviews (auto-updates via WebSocket)
- State machine flow visualization (click a row to see the current step)
- On-demand topic-based discovery with 👀 and assign actions
- Manual item tracking via + button (paste a URL or `owner/repo#123`)
- Retry and remove buttons per tracked item
- Worker and Claude log tabs
- Config editor with toggle switches for boolean values
- Dark/light theme toggle
- Trigger Now button

## Configuration

Stored in `~/.config/github-worker/config`:

| Key | Description | Default |
|-----|-------------|---------|
| `GITHUB_USER` | Your GitHub username | — |
| `GITHUB_TOKEN` | Your GitHub Personal Access Token | — |
| `BOT_USER` | Bot GitHub username | — |
| `BOT_TOKEN` | Bot GitHub Personal Access Token | — |
| `EXCLUDE_ORGS` | Comma-separated orgs to skip | — |
| `GMAIL_ADDRESS` | Gmail sender address | — |
| `GMAIL_APP_PASSWORD` | Gmail App Password | — |
| `SEND_TO` | Email recipients (comma-separated) | — |
| `ACTIVE_HOURS` | Hours to run (24h, e.g. `07-22`) | `07-22` |
| `WORK_DIR` | Directory for worktrees and clones | `/tmp/github-worker` |
| `LOOKBACK_DAYS` | How far back to search for issues | `7` |
| `SCHEDULE` | Cron expression (e.g. `*/5 * * * *`) | `0 * * * *` |
| `AGENT` | Coding agent to use (see below) | `claude` |
| `EMAIL_NOTIFICATIONS` | Send email summaries (`true`/`false`) | `true` |
| `TOPICS` | Comma-separated topics for discovery | — |
| `ORGS` | Comma-separated orgs or repos for discovery scope | — |

`ORGS` supports both `quarkiverse` (whole org) and `quarkusio/quarkus` (specific repo).

Config can also be edited from the dashboard UI.

## Usage

```bash
# Preview — show eligible issues and reviews without processing
github-worker --preview

# Show current tracking state
github-worker --status

# Normal run — advance each tracked item by one step
github-worker

# Process exactly one item then exit
github-worker --once

# Full logic but skip all write operations
github-worker --dry-run

# Run topic-based discovery (used by the dashboard)
github-worker --discover
```

Or use the dashboard at `http://github-worker.house-elves:7478` to trigger runs and monitor progress.

## MCP Server

An MCP server is included so AI agents (Claude Code sessions) can check on progress, trigger runs, and manage tracked items without the dashboard.

### Setup

Add to your global Claude Code MCP config (`~/.claude/.mcp.json`):

```json
{
  "mcpServers": {
    "github-worker": {
      "command": "jbang",
      "args": ["github-worker-mcp@House-elves/github-worker"]
    }
  }
}
```

Or for a specific project, add to `.mcp.json` in the project root.

### Available tools

| Tool | Description |
|------|-------------|
| `worker_status` | Show all tracked issues and reviews with current state |
| `worker_trigger` | Trigger a worker run |
| `worker_preview` | Preview what the worker would pick up |
| `worker_retry` | Retry a stuck item (resets to previous state) |
| `worker_add` | Add an issue or PR to tracking by URL or key |
| `worker_remove` | Remove an item from tracking |
| `worker_discover` | Run topic-based discovery |
| `worker_logs` | Show recent worker logs |

## How it works

The worker uses two GitHub accounts:

- **Your account** — for reading (assigned issues, reactions, review requests, CI status)
- **Bot account** — for all actions (comments, PRs, reviews, pushes)

This makes it clear in the GitHub UI which actions are automated. The bot's PRs and comments are visually distinct from yours.

### Git worktrees

Instead of cloning repos from scratch each time, the worker maintains a persistent main clone per repo under `{WORK_DIR}/repos/`. For each issue, it creates a lightweight git worktree under `{WORK_DIR}/worktrees/`. This is fast (instant after the first clone) and disk-efficient (shared git object database).

For Maven/Quarkus projects, each worktree gets an isolated `.m2` directory via `.mvn/maven.config`, so SNAPSHOT builds don't pollute each other.

### Coding agents

The worker delegates all AI coding tasks to a pluggable `CodingAgent` interface. Claude Code CLI is the default, but other agents can be used by setting `AGENT` in the config.

**Built-in agents:**

| Agent | Config value | CLI tool | Description |
|-------|-------------|----------|-------------|
| Claude Code | `claude` (default) | `claude` | Full MCP server and skill access |

To switch agents, set `AGENT=agent-name` in `~/.config/github-worker/config`.

When using Claude Code (the default), the bot invokes `claude -p --dangerously-skip-permissions` with full access to all configured MCP servers and skills — Quarkus Agent, Chrome DevTools, etc. The only exception is security triage, which runs in a sandboxed mode with no filesystem access.

### Implementing a custom agent

The `CodingAgent` interface has two methods:

```java
public interface CodingAgent {
    // Full mode — agent works in a git checkout, can read/write files,
    // run builds, make commits
    String run(String prompt, Path workDir, int timeoutMinutes);

    // Bare mode — analysis only, NO filesystem access
    // Used for security triage and text generation
    String runBare(String prompt, int timeoutSeconds);
}
```

To add a new agent:

1. Create a new class implementing `CodingAgent` (e.g. `CodexAgent.java`):

```java
public class CodexAgent implements CodingAgent {
    @Override
    public String run(String prompt, Path workDir, int timeoutMinutes) {
        // Build the CLI command for your agent
        ProcessBuilder pb = new ProcessBuilder("your-agent", "--flags");
        pb.directory(workDir.toFile());
        // Write prompt to stdin, read stdout, handle timeout
        // Return the agent's text output, or null on failure
    }

    @Override
    public String runBare(String prompt, int timeoutSeconds) {
        // Same but with no filesystem access
        // For agents that don't have a sandboxed mode,
        // you can use a temp empty directory as workDir
    }
}
```

2. Add a `//SOURCES CodexAgent.java` line in `GitHubWorker.java`
3. Add a case in `CodingAgent.create()`:

```java
case "codex" -> new CodexAgent();
```

4. Set `AGENT=codex` in the config

The agent receives prompts as plain text containing full context (issue details, diffs, review findings, commit message rules) and returns plain text output. The worker doesn't parse the agent's output structurally — it only checks for specific phrases like "No issues found." in review responses.

## Issue workflow

When you signal intent to work on an issue, the worker picks it up and progresses through these stages — one step per scheduled run:

### How to signal intent

| Signal | When |
|--------|------|
| Self-assign an issue | You assigned it to yourself |
| React with 👀 on an issue | Someone else assigned you — 👀 confirms you want the bot to help |

### Stages

```
NEW ──► AWAITING_APPROVAL ──► CODING ──► SELF_REVIEWING
 │           │                                │
 │     👍 proceed                    issues? ──┤── clean?
 │     👎 retry (max 3)                │            │
 │                              FIXING_REVIEW   READY_FOR_REVIEW
 │                                    │              │
 │                                    └──────────────┤
 │                                                   │
 │                                    comments? ─────┤── approved?
 │                                        │               │
 │                                 ADDRESSING_FEEDBACK  SQUASHING
 │                                        │               │
 │                                        └──────┐   MONITORING_CI
 │                                               │        │
 │                                               │   pass?─┤─ fail?
 │                                               │    │         │
 │                                               │   DONE    FIXING_CI
 │                                               │              │
 └───────────────────────────────────────────────┴──────────────┘
```

**1. NEW** — The bot analyzes the issue and posts a comment on it summarizing its understanding and proposed approach. It mentions you (e.g., "@you Does this look right? React with 👍 to proceed or 👎 if I should reconsider.").

**2. AWAITING_APPROVAL** — The bot checks its comment for your reaction:
- 👍 — proceed to coding
- 👎 — the bot reads your follow-up comment (if any) as feedback and re-analyzes (up to 3 attempts)
- No reaction — stays here, checked again next run

**3. CODING** — The bot creates a worktree, runs Claude Code to implement the fix, pushes to its fork, and creates a **draft PR** linking to the issue.

**4. SELF_REVIEWING** — The bot reviews its own PR for correctness, test coverage, documentation, security, maintainability, and Quarkus patterns. If it finds issues, it moves to FIXING_REVIEW. If clean, it marks the PR ready and adds you as reviewer.

**5. FIXING_REVIEW** — The bot fixes all self-review findings, squashes to one commit, marks the PR ready for review, and adds you as reviewer.

**6. READY_FOR_REVIEW** — The bot monitors the PR for your response:
- **You comment** — moves to ADDRESSING_FEEDBACK
- **You approve** — moves to SQUASHING
- **Neither** — stays here

**7. ADDRESSING_FEEDBACK** — The bot processes feedback from you and other org members, makes changes, squashes, force-pushes, replies to each review comment explaining what changed, reacts 👍 on each processed comment, and re-requests review from all reviewers who gave feedback.

**8. SQUASHING** — The bot squashes all commits into a single clean commit.

**9. MONITORING_CI** — The bot checks CI status:
- All checks pass → **DONE**
- Any check fails → **FIXING_CI**
- Checks still running → stays here

**10. FIXING_CI** — The bot investigates CI failures, fixes them, pushes, and re-requests your review. Up to 3 attempts. If it can't fix CI after 3 tries, it posts a comment and gives up.

**11. DONE** — CI is green or you merged the PR. The entry stays in state for the summary email, then gets pruned after `LOOKBACK_DAYS`.

## Review workflow

When you're requested as a reviewer on a PR, the bot can do the review for you.

### How to signal intent

| Signal | When |
|--------|------|
| Self-request as reviewer | You added yourself as reviewer |
| React with 👀 on the PR | Someone else requested you — 👀 confirms you want the bot to review |

### Stages

**1. NEW** — The bot clones the PR, runs Claude Code for a comprehensive review covering:
- Correctness and completeness
- Test coverage
- Documentation updates
- Security implications
- Code quality and maintainability
- Quarkus-specific patterns and conventions

**2. REVIEW_POSTED** — The review is posted as the bot account on the PR. Each finding is tagged with severity: `[CRITICAL]`, `[SUGGESTION]`, or `[NIT]`.

**3. DONE** — One-shot workflow, no further action needed.

## Safety

- **Security triage** — every issue is analyzed for malicious content (shell injection, obfuscated payloads, social engineering) before any code is executed. Suspicious issues are flagged via email and skipped.
- **Lock file** — prevents overlapping runs if a step takes longer than the schedule interval
- **Retry limits** — max 3 attempts on understanding loops (👎) and CI fix loops
- **Bot account isolation** — all automated actions come from the bot account, never yours
- **Active hours** — the worker only runs during configured hours
- **Draft PRs** — code changes always start as draft PRs; you always have final say

## State

The worker persists its state in `~/.config/github-worker/state.json`. Each scheduled run:

1. Discovers new eligible issues and review requests
2. Advances each tracked item by exactly one step
3. Saves state atomically (write to tmp file, rename)

State entries in DONE are automatically pruned after `LOOKBACK_DAYS`.

To inspect state from the command line: `github-worker --status`

## Troubleshooting

### Check service status

```bash
# Linux
systemctl --user status github-worker.timer
systemctl --user status github-worker-ui.service

# View recent logs
journalctl --user-unit github-worker -n 50
```

### Force a run

```bash
github-worker --once
```

Or click "Trigger Now" in the dashboard.

### Reset state for an issue

Edit `~/.config/github-worker/state.json` and remove the entry, or change its `state` field.

### The worker skips an issue I expect it to pick up

- Check that the issue is assigned to your `GITHUB_USER`
- Check that you either self-assigned it or added a 👀 reaction
- Check that the issue's org is not in `EXCLUDE_ORGS`
- Check that the issue was created within `LOOKBACK_DAYS`
- Check that no `fix/{issueNumber}` PR already exists from the bot

Run `github-worker --preview` to see what the worker finds.
