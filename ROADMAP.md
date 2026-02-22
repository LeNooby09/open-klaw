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
- [x] **Security Hardening** — One-time signup token for first admin registration (no hardcoded credentials), localhost-only CORS with credentials support, login rate limiting with exponential backoff (atomic/thread-safe), two-cookie CSRF protection (HttpOnly session cookie + readable CSRF cookie for double-submit pattern via `X-CSRF-Token` header), security headers (`X-Content-Type-Options`, `X-Frame-Options`, `X-XSS-Protection`, `Referrer-Policy`, `CSP`, `HSTS` for non-localhost), HTTPS enforcement for non-localhost binds, bounded request body reading (hard byte limit regardless of Content-Length header) with configurable max payload size, username format validation (`^[a-zA-Z0-9_-]{3,32}$`), conversation session ownership verification with authorization checks on delete, periodic expired session cleanup with pluggable cleanup callbacks (rate limiter + idle conversation flushing), idle conversation archival to JSONL on disk, API keys loaded from environment variables (`apiKeyEnv`), user management APIs (create/delete users, change password, list users), DOM XSS prevention via `addEventListener` (no inline event handlers), path traversal protection via canonicalization in memory files and UUID validation for session/conversation IDs, prompt injection mitigation with structured DATA-ONLY boundary markers in system prompts, sensitive data redaction (API keys, tokens, private keys) in conversation logs, restrictive POSIX file permissions (owner-only read/write) on all memory and log files, per-user configurable storage budgets with admin dashboard, capped semantic search index (configurable max documents), and dual-layer sandbox detection (env var + container indicators).

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

- [ ] **Skill Definition Format (`SKILL.md`)** — Structured Markdown files that teach the agent new capabilities.
- [ ] **Bundled Skills** — Ship with a set of default skills (web research, file management, coding assistance).
- [ ] **Workspace Skills** — User-defined skills scoped to a specific project or workspace.
- [ ] **Skill Install Gating & UI** — Review and approve skills before they become active.
- [ ] **Skill Registry (ClawHub equivalent)** — Community repository where skills can be searched, installed, and published.
- [ ] **Self-Improving Agent** — Allow the agent to write and install new skills autonomously when it encounters unfamiliar tasks.

---

## Phase 7 — Safety, Security & Operations

Production-grade reliability and safety guardrails.

- [ ] **Lane Queue System** — Serial task execution per session to prevent race conditions and state drift.
- [x] **Permission Controls** — Fine-grained tool permissions for scheduler-initiated agent calls. Configurable `schedulerRestrictedTools` (default: shell, filesystem) and `schedulerSafeTools` lists. Admin-created tasks get unrestricted access; non-admin automated tasks are limited to safe tools only. Enforced at both prompt-level (tool descriptions filtered) and execution-level (blocked calls return rejection message to LLM).
- [ ] **Fine-Grained User Permissions** — Per-user tool access controls for interactive sessions.
- [ ] **Retry Policy** — Configurable retry logic for failed LLM calls and tool executions.
- [ ] **Health Checks & Doctor Diagnostics** — Self-diagnosis tools to detect misconfiguration or degraded state.
- [ ] **Logging & Observability** — Structured logging, usage tracking, and presence/typing indicators.
- [x] **Auth & Access Control** — Username/password, token-based auth for the gateway and UI. One-time signup token, CSRF protection, rate limiting, and user management.
- [x] **Sandboxed Execution** — Docker-first execution model: the application defaults to running inside a Docker container where shell/filesystem tools are confined. Bare-metal mode requires explicit opt-in via `--bare-metal` flag or `OPENKLAW_BARE_METAL=true` env var. Container runs as non-root user with all capabilities dropped, resource limits enforced, and workspace/data volumes isolated. Startup blocked without sandbox detection unless bare-metal is explicitly acknowledged.

---

## Phase 8 — Multi-Platform & Distribution

Run everywhere: desktop, mobile, containers.

- [x] **Docker Deployment** — One-command `docker compose up` with all services configured. Multi-stage Dockerfile (build + minimal JRE runtime) with pinned image tags (`eclipse-temurin:21.0.6_7`), non-root user, dropped capabilities, `no-new-privileges`, enforced resource limits via `mem_limit`/`cpus` (2GB RAM, 2 CPUs), persistent volumes for workspace and data. Launch script (`run.sh`) defaults to Docker with compose file validation, with `--bare-metal` escape hatch.
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
