# Open-Klaw Roadmap

> Inspired by [OpenClaw](https://github.com/openclaw/openclaw) — an open-source personal AI agent framework.  
> This roadmap maps OpenClaw's feature set into phased milestones for the **open-klaw** project (Kotlin/JVM, powered by [koog-agents](https://github.com/JetBrains/koog)).

---

## Phase 1 — Core Agent Loop & Gateway

The foundation: a running agent that can receive a message, think, act, and respond.

- [x] **Local Gateway Server** — Ktor CIO HTTP server with REST API (auth, stats, chat, conversations) and Bearer token auth. Serves a web dashboard SPA from an external HTML resource file (`/web/dashboard.html`) loaded at runtime.
- [x] **Agent Loop** — Core think → act → observe cycle with conversation session management and context windowing (last 50 messages).
- [x] **Model-Agnostic LLM Orchestrator** — Unified `LlmProvider` interface with 4 implementations: OpenAI, Anthropic, Ollama, and OpenRouter.
- [x] **Model Failover** — Automatic priority-based fallback to secondary models when the primary is unavailable.
- [x] **Streaming / Chunked Responses** — Stream partial responses back to the user in real time via the agent loop.
- [x] **Session Management** — BCrypt password auth, 256-bit token generation, 24h session expiry in ConcurrentHashMap.
- [x] **Security Hardening** — One-time signup token for first admin registration (no hardcoded credentials),
  localhost-only CORS with credentials support, login rate limiting with exponential backoff (atomic/thread-safe),
  two-cookie CSRF protection (HttpOnly session cookie + readable CSRF cookie for double-submit pattern via
  `X-CSRF-Token` header), security headers (`X-Content-Type-Options`, `X-Frame-Options`, `X-XSS-Protection`,
  `Referrer-Policy`, `CSP`, `HSTS` for non-localhost), bounded request body reading via `receiveText()` with
  configurable max payload size, username format validation (`^[a-zA-Z0-9_-]{3,32}$`),
  conversation session ownership verification with authorization checks on delete, periodic expired session cleanup with
  pluggable cleanup callbacks (rate limiter + idle conversation flushing), idle conversation archival to JSONL on disk,
  API keys loaded from environment variables (`apiKeyEnv`), user management APIs (create/delete users, change password,
  list users), DOM XSS prevention via `addEventListener` (no inline event handlers), path traversal protection via
  canonicalization in memory files and UUID validation for session/conversation IDs, prompt injection mitigation with
  structured DATA-ONLY boundary markers in system prompts, sensitive data redaction (API keys, tokens, private keys) in
  conversation logs, restrictive POSIX file permissions (owner-only read/write) on all memory and log files, per-user
  configurable storage budgets with admin dashboard, capped semantic search index (configurable max documents), and
  dual-layer sandbox detection (env var + container indicators).

---

## Phase 2 — Tool Execution Engine

Give the agent hands: the ability to interact with the real world.

- [x] **Shell Command Execution** — Run arbitrary shell commands in a sandboxed environment. Configurable timeouts, blocked command patterns, and working directory support.
- [x] **File System Access** — Read, write, append, list, search, delete, mkdir, exists, and info operations. Path traversal protection via base directory scoping.
- [x] **Browser Control** — HTTP-based navigation, text/link extraction, and page snapshots. Form fill, click, and JS execution stubbed for future Playwright/Selenium integration.
- [x] **Canvas / UI Surface** — Push, update, remove, clear, and list rich content items (HTML, Markdown, images, code) on a display surface accessible via REST API.
- [x] **Tool Registry** — Pluggable `Tool` interface with runtime registration/unregistration. Automatic parameter validation, execution timing, and LLM system prompt generation for tool descriptions.

---

## Phase 3 — Persistent Memory System

Let the agent remember across sessions and grow smarter over time.

- [x] **Conversation Logging (JSONL)** — Real-time append-only JSONL logging of every message per session, organized in daily directories (`data/logs/{date}/{sessionId}.jsonl`). Supports reading individual sessions, daily aggregates, and full log enumeration.
- [x] **Curated Long-Term Memory (`MEMORY.md`)** — Distill key facts into a human-readable, editable Markdown file. Automatic topic extraction and distillation from conversations exceeding a configurable message threshold. Max file size enforcement prevents unbounded growth.
- [x] **Identity & Personality (`SOUL.md`)** — Define the agent's persona, tone, and behavioral guidelines. Ships with sensible defaults; auto-created on first run. Injected into every system prompt.
- [x] **User Profile (`USER.md`)** — Per-user `USER_{username}.md` files store preferences, coding style, and personal context. Automatically included in system prompts when present.
- [x] **Semantic Memory Search** — TF-IDF vector-based retrieval of relevant past conversations per turn. In-memory index with automatic periodic reindexing (5-minute interval). Configurable result count and minimum relevance score.
- [x] **Three-Tier Memory Architecture** — Tier 1: Daily JSONL logs (raw transcripts). Tier 2: Curated Markdown files (SOUL.md, MEMORY.md, USER.md). Tier 3: Semantic search over all historical logs. All tiers integrated into the agent's system prompt via `MemoryManager`.

---

## Phase 4 — Messaging & Transport Integrations

Meet users where they are: chat apps, email, and beyond.

- [x] **Discord Integration** — Bi-directional messaging with Discord channels/DMs via the Discord Bot REST API. HTTP polling for inbound messages with configurable interval, bot mention filtering, automatic message splitting (2000 char limit), and DM support.
- [x] **Telegram Integration** — Full Telegram bot support with long polling (getUpdates), inline keyboard buttons (sendMessage with reply_markup), emoji reactions (setMessageReaction), and streaming via progressive message editing (editMessageText). Group chat mention filtering and callback query handling.
- [x] **WhatsApp Integration** — WhatsApp Cloud API (Meta Business) integration with webhook-based inbound message reception (hub verification + POST notifications), text message sending, read receipts, contact name resolution, interactive button/list reply support, and message deduplication.
- [x] **Slack Integration** — Slack Web API + Events API integration with channel messages (chat.postMessage with mrkdwn blocks), reactions (reactions.add), pins (pins.add), bot coexistence (configurable bot message filtering), HMAC-SHA256 request signature verification, URL verification challenge handling, and automatic channel joining.
- [x] **Email (Gmail Pub/Sub)** — IMAP polling for inbound emails with SMTP sending. Supports Gmail (App Passwords), Outlook, and standard IMAP/SMTP providers. MIME multipart text extraction, HTML stripping, sender allowlisting, email threading (In-Reply-To/References headers), and on-demand inbox check for Gmail Pub/Sub webhook integration.
- [x] **WhatsApp Integration** *(updated)* — Added HMAC-SHA256 webhook signature verification (`X-Hub-Signature-256`) with constant-time comparison via `MessageDigest.isEqual`. App secret loaded from `WHATSAPP_APP_SECRET` env var.
- [x] **Slack Integration** *(updated)* — Signing secret now **required** to start the channel (refuses to start without it). Signature verification uses constant-time comparison. LRU dedup with atomic eviction replaces the old ConcurrentHashMap set.
- [x] **WebChat UI** — Built-in WebSocket-based chat interface served directly from the gateway at `/ws/chat`. **Requires session token authentication** via `?token=` query parameter — validates against `SessionManager` before accepting connections. Username derived exclusively from the authenticated session, never from client-supplied data. Real-time bi-directional JSON messaging, ping/pong keepalive, and graceful disconnect handling.
- [x] **Channel Routing** — Pluggable `MessageChannel` interface with `ChannelRouter` that routes inbound messages from any registered channel to the AgentLoop and dispatches responses back to the originating channel. **Account linking required**: channel users must link their messaging identity to a registered Open-Klaw user via the dashboard before messages are processed; unlinked users receive a prompt to link. LRU-evicting session map with configurable max size (`channelSessionMapMaxSize`), input length validation (`maxChannelMessageLength`), periodic cleanup of unlinked sessions, and `/api/channels` status endpoint.
- [x] **Account Linking API** — REST API for linking/unlinking messaging channel identities to Open-Klaw user accounts: `GET/POST /api/channel-links`, `DELETE /api/channel-links/{type}/{id}`, admin `GET /api/admin/channel-links`. Supports all channel types (Discord, Telegram, WhatsApp, Slack, Email, WebChat).
- [x] **Channel Security Hardening** — Discord snowflake ID validation (`^\d{1,20}$`) prevents SSRF via crafted channel IDs. All channels use LRU dedup with atomic eviction (synchronized deque+set) replacing the old race-prone ConcurrentHashMap eviction. Email multipart parsing has a recursion depth limit (10 levels) to prevent stack overflow from malicious MIME structures. Webhook rate limiting via dedicated `RateLimiter` instance with periodic cleanup. Jakarta Mail 2.0.3 replaces EOL javax.mail 1.6.2.

---

## Phase 5 — Proactive Automation & Scheduling

Move from reactive assistant to proactive agent.

- [x] **Heartbeat System (`HEARTBEAT.md`)** — Persistent Markdown file (`HEARTBEAT.md`) defining periodic wake-up rules with a human-readable DSL (`every Nm`, `hourly`, `daily HH:mm`, `weekday HH:mm`, `weekend HH:mm`). Parsed at startup with hot-reload support. Dedicated bounded `ThreadPoolExecutor` for task execution (prevents ForkJoinPool starvation). Auto-creates default file with owner-only POSIX permissions on first run. SHA-256 collision-resistant rule IDs. Day-of-week filtering for weekday/weekend rules with configurable check interval.
- [x] **Cron Jobs** — Simplified 5-field cron expression engine (`minute hour dayOfMonth month dayOfWeek`) supporting wildcards (`*`), exact values, step intervals (`start/step`), and comma-separated lists with **range validation** (e.g., minutes 0–59, hours 0–23, step > 0). Runtime job CRUD with configurable max job limit, job ID format validation (`^[a-zA-Z0-9_-]{1,64}$`), and username validation (`^(system|[a-zA-Z0-9_-]{3,32})$`). `createdByAdmin` flag determines tool access policy. Dedicated bounded executor with per-minute scheduler and double-fire prevention.
- [x] **Webhook Triggers** — REST endpoints at `/api/webhooks/{triggerId}` that accept POST requests from external services. HMAC-SHA256 signature verification via `X-Webhook-Signature` header is **mandatory** — routes refuse to install without a configured secret (`OPENKLAW_WEBHOOK_SECRET`). Bounded body reading (hard byte limit), per-IP rate limiting with exponential backoff, and dedicated bounded executor. `GET /api/webhooks` listing moved behind admin authentication. External payloads wrapped in `[BEGIN_DATA]/[END_DATA]` markers for prompt injection mitigation. `createdByAdmin` flag controls tool access.
- [x] **Git/CI Monitoring** — Background polling of configured Git repositories via `git` CLI with **configurable process timeout** (`waitFor` with forced kill on timeout). Repo paths validated against allowed base directories (`gitAllowedBaseDirs`) to prevent arbitrary filesystem access. Bounded file reads (`gitMaxFileReadBytes`) prevent memory exhaustion from large log files. Dedicated bounded executor. Detects new commits via `git rev-parse HEAD` comparison and build status via `build.log`/`error.log` scanning with Gradle test report parsing. Git event details wrapped in `[BEGIN_DATA]/[END_DATA]` markers.
- [x] **Proactive Notifications** — Multi-channel notification delivery with per-user channel preferences, automatic fallback through linked channel accounts, and configurable default channel. Notification formatting with priority-based icons (ℹ️/🔔/⚠️/🚨). Broadcast to all registered users. Thread-safe bounded notification history (1000 entries, `ReentrantLock`-guarded `ArrayList` replacing race-prone `CopyOnWriteArrayList`) with username filtering and timestamp-descending retrieval.
- [x] **Scheduler Security Hardening** — Admin-authorized tool access: heartbeat tasks (from admin-managed `HEARTBEAT.md`) and admin-created cron/webhook jobs get full tool access; non-admin automated tasks are restricted to safe tools only (configurable `schedulerRestrictedTools`/`schedulerSafeTools`). `AgentLoop.chat()` accepts optional `allowedTools` parameter that filters both tool descriptions in system prompts and blocks disallowed tool calls at execution time. All scheduler components use dedicated bounded `ThreadPoolExecutor` instead of `ForkJoinPool.commonPool()` to prevent JVM-wide thread starvation.

---

## Phase 6 — Skills Platform & Extensibility

Make the agent infinitely extensible through a skill/plugin ecosystem.

- [x] **Skill Definition Format (`SKILL.md`)** — Structured Markdown files (`# Name`, `**id:**`, `**version:**`, `**author:**`, `**tags:**`, `## Description`, `## Instructions`, `## Examples`, `## Context`) parsed into `SkillDefinition` data model. ID validation (`^[a-zA-Z0-9_-]{1,64}$`), auto-generated IDs from skill name, section extraction from `##` headings, and source/status tracking. Active skills injected into agent system prompts via `buildPromptSection()`.
- [x] **Bundled Skills** — Three default skills shipped with Open-Klaw: Web Research (structured research methodology with source attribution), File Management (safe file organization with destructive operation confirmation), and Coding Assistance (code writing, review, and debugging workflow). Bundled skills are auto-approved (`ACTIVE` status) and cannot be overwritten or removed.
- [x] **Workspace Skills** — User-defined `SKILL.md` files loaded from a configurable workspace skills directory (`workspaceSkillsDir`). New workspace skills enter `PENDING` status by default, with optional `autoApproveWorkspaceSkills` config flag for trusted environments. Max file size enforcement (256 KB) prevents oversized skill files.
- [x] **Skill Install Gating & UI** — Full approval workflow: skills enter `PENDING` → admin can `approve` (→ `ACTIVE`), `reject` (→ `REJECTED`), `disable` (→ `DISABLED`), or `enable` (→ `ACTIVE`). REST API endpoints for all gating operations (`POST /api/skills/approve|reject|disable|enable`). Status persisted in JSON manifest file (`data/skills/manifest.json`) with POSIX owner-only permissions. Admin-only access enforced on all mutation endpoints (including publish). Atomic state transitions via `ConcurrentHashMap.computeIfPresent()` prevent race conditions.
- [x] **Skill Registry (ClawHub equivalent)** — `SkillRegistryClient` with search, fetch, install, and publish operations against a configurable remote registry URL (`registryUrl`). REST API endpoints: `POST /api/skills/registry/search`, `POST /api/skills/registry/install`, `POST /api/skills/registry/publish`. Registry-installed skills stored in `data/skills/registry/` directory. Graceful degradation when registry is unavailable — operations return empty results without blocking. SSRF prevention: HTTPS-only enforcement, configurable host allowlist (`registryAllowedHosts`), and URL validation at startup.
- [x] **Self-Improving Agent** — `SkillWriterTool` registered in the tool registry (name: `skill_writer`) allows the agent to autonomously create new skills when it encounters unfamiliar task patterns. Generates valid `SKILL.md` content with all sections and installs via `SkillManager`. Self-created skills saved to `data/skills/self-created/` and enter `PENDING` status requiring admin approval. Configurable via `selfImprovementEnabled` flag (default: `false`, opt-in). Duplicate ID and bundled skill overwrite protection.
- [x] **Skills Security Hardening** — Prompt injection detection: suspicious pattern logging on skill install (e.g., "ignore previous instructions", "override system prompt"). Total skill context size cap (`maxSkillContextChars`, default 50K) prevents context window flooding. Path traversal protection in skill file deletion via canonical path validation against allowed directories. Admin-only enforcement on all mutation endpoints including registry publish.

---

## Phase 7 — Safety, Security & Operations

Production-grade reliability and safety guardrails.

- [x] **Lane Queue System** — Per-session serial task execution via coroutine `Mutex`-based lane queues. Concurrent requests for the same session are queued and executed one at a time, preventing race conditions and state drift. Different sessions execute independently in parallel. Lane cleanup integrated into conversation deletion and idle flush. `LaneQueue` class with `withLane()`, `isLaneOccupied()`, `removeLane()`, and `cleanupStaleLanes()` methods. Safe lane removal: `removeLane()` and `cleanupStaleLanes()` use `computeIfPresent()` to skip removal of locked mutexes, preventing serialization guarantee bypass.
- [x] **Permission Controls** — Fine-grained tool permissions for scheduler-initiated agent calls. Configurable `schedulerRestrictedTools` (default: shell, filesystem) and `schedulerSafeTools` lists. Admin-created tasks get unrestricted access; non-admin automated tasks are limited to safe tools only. Enforced at both prompt-level (tool descriptions filtered) and execution-level (blocked calls return rejection message to LLM).
- [x] **Fine-Grained User Permissions** — Per-user tool access controls for interactive sessions via `UserPermissionManager`. Admins can configure which tools each user may use via REST API (`GET/POST/DELETE /api/permissions/{username}`). Users with no explicit entry get unrestricted access (default). Admins always bypass restrictions. Permissions enforced at the `AgentLoop.chat()` level via `allowedTools` parameter (both prompt-level and execution-level filtering). Permissions persisted to `data/permissions.json` with POSIX owner-only file permissions — survives application restarts.
- [x] **Retry Policy** — Configurable retry logic for failed LLM calls and tool executions via `RetryPolicy` data class. Exponential backoff with jitter, configurable max attempts, initial/max delay, backoff multiplier, and optional `shouldRetry` predicate. Pre-built policies: `LLM_DEFAULT` (3 attempts, 1s initial delay), `TOOL_DEFAULT` (2 attempts, 500ms initial delay), `NONE` (single attempt). Integrated into `LlmOrchestrator` (per-provider retry for non-streaming; streaming uses single-attempt to prevent partial output duplication) and `ToolRegistry.execute()` (retry only for idempotent tools via `Tool.idempotent` property — non-idempotent tools like shell/filesystem use `NONE` to prevent re-execution after partial completion).
- [x] **Health Checks & Doctor Diagnostics** — `HealthCheckManager` with pluggable `HealthCheck` interface (thread-safe `CopyOnWriteArrayList` for check registration) and 5 built-in checks: LLM providers (availability), tool registry (registration/enablement), memory system (data directory, SOUL.md, MEMORY.md), messaging channels (connection status), and system resources (JVM memory usage with 75%/90% thresholds). Three-state model: `HEALTHY`/`DEGRADED`/`UNHEALTHY`. REST endpoints: `GET /api/health` (public, returns aggregate status only — no component details), `GET /api/health/diagnostics` (admin, full report with per-component details and recommendations), `GET /api/health/{name}` (admin, individual check). Doctor diagnostics generates actionable recommendations per component.
- [x] **Logging & Observability** — `UsageTracker` with thread-safe atomic counters for per-user and aggregate metrics: message counts, tool invocations (per-tool breakdown), LLM calls (with average latency), and error counts. Bounded per-user metrics map (`maxTrackedUsers`, default 10K) with LRU eviction of stalest entries to prevent unbounded memory growth. Presence tracking with configurable timeout (default 5 min) and periodic cleanup. REST endpoints: `GET /api/usage` (admin, global stats), `GET /api/usage/users` (admin, per-user stats), `GET /api/presence` (authenticated, online users), `POST /api/presence/heartbeat` (update user presence). Usage recorded automatically on chat API calls.
- [x] **Auth & Access Control** — Username/password, token-based auth for the gateway and UI. One-time signup token, CSRF protection, rate limiting, and user management.
- [x] **Sandboxed Execution** — Docker-first execution model: the application defaults to running inside a Docker container where shell/filesystem tools are confined. Bare-metal mode requires explicit opt-in via `--bare-metal` flag or `OPENKLAW_BARE_METAL=true` env var. Container runs as non-root user with all capabilities dropped, resource limits enforced, and workspace/data volumes isolated. Startup blocked without sandbox detection unless bare-metal is explicitly acknowledged.
- [x] **Phase 7 Security Hardening** — Streaming tool restrictions: `chatStream()` enforces `allowedTools` parameter matching `chat()` behavior, preventing tool permission bypass via streaming endpoint. Public health endpoint info disclosure fix: `/api/health` returns only aggregate status string, detailed component info moved to admin-only `/api/health/diagnostics`. Idempotent tool retry safety: `Tool` interface gains `idempotent` property (default `false`); `ToolRegistry` skips retry for non-idempotent tools to prevent re-execution of side-effecting operations. LLM streaming retry removed: `completeStream()` no longer retries within a provider to prevent partial output duplication (failover to next provider still supported).

---

## Phase 8 — Multi-Platform & Distribution

Run everywhere: desktop, mobile, containers.

- [x] **Docker Deployment** — One-command `docker compose up` with all services configured. Multi-stage Dockerfile (build + minimal JRE runtime) with pinned image tags (`eclipse-temurin:21.0.6_7`), non-root user, dropped capabilities, `no-new-privileges`, enforced resource limits via `mem_limit`/`cpus` (2GB RAM, 2 CPUs), persistent volumes for workspace and data. Launch script (`run.sh`) defaults to Docker with compose file validation, with `--bare-metal` escape hatch.
- [x] **Reverse Proxy Support** — `trustProxy` gateway config option enables `X-Forwarded-Proto` header inspection,
  allowing HTTPS termination at a reverse proxy (e.g., nginx) while the app listens on plain HTTP internally. Disabled
  by default for security. HTTPS enforcement for non-localhost connections has been removed to avoid blocking legitimate
  requests when behind a reverse proxy (e.g., nginx terminating TLS); HSTS header is still sent for non-localhost binds.
- [x] **External YAML Configuration** — All application settings loaded from `config.yaml` at startup via
  `ConfigLoader` (kaml/kotlinx.serialization). Supports partial configs (omitted fields use defaults), environment
  variable overrides for key deployment settings (`OPENKLAW_PORT`, `OPENKLAW_BIND`, tool toggles), custom config path
  via `--config=<path>` flag or `OPENKLAW_CONFIG` env var, and `--generate-config` to produce a fully-commented default
  template. Unknown keys ignored for forward compatibility (`strictMode = false`).
- [ ] **Tailscale / SSH Tunnels** — Secure remote access without exposing the gateway to the public internet.
- [ ] **Desktop App (macOS/Linux/Windows)** — System tray/menu bar control, voice wake, push-to-talk.
- [ ] **Mobile Nodes (iOS/Android)** — Pair mobile devices as agent nodes with voice trigger and canvas support.
- [ ] **Bonjour/mDNS Discovery** — Auto-discover gateway instances on the local network.
- [ ] **Nix Mode** — Declarative configuration for reproducible setups.

---

## Phase 9 — Advanced Capabilities

Power-user features and cutting-edge integrations.

- [ ] **Smart Home Integration** — Control Philips Hue, Elgato, Home Assistant devices.
- [ ] **Automated QA & Testing** — Run headless test builds, simulate inputs, generate structured bug reports.
- [ ] **Content Repurposing** — Transform long-form content into platform-specific formats (LinkedIn, Twitter, email).
- [ ] **Personal Threat Monitoring** — Scheduled security checks for exposed credentials, open ports, DNS anomalies.
- [ ] **Workflow Automation from Behavior** — Detect repeated patterns in user behavior and auto-generate CLI tools.
- [ ] **Multi-Agent Orchestration** — Coordinate multiple specialized agents working on related tasks.

---

## Summary

| Phase | Theme | Key Deliverable |
|-------|-------|----------------|
| 1 | Core Agent Loop & Gateway | A running agent that thinks and responds |
| 2 | Tool Execution Engine | Shell, files, browser — the agent can act |
| 3 | Persistent Memory | The agent remembers and learns |
| 4 | Messaging Integrations | Discord, Telegram, WhatsApp, Slack, email |
| 5 | Proactive Automation | Heartbeat, cron, webhooks — acts on its own |
| 6 | Skills Platform | Extensible, community-driven skill ecosystem |
| 7 | Safety & Operations | Production-grade reliability and security |
| 8 | Multi-Platform | Desktop, mobile, Docker, remote access |
| 9 | Advanced Capabilities | Smart home, QA, multi-agent, and more |
