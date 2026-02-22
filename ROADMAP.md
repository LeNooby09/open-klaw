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
- [x] **Security Hardening** — One-time signup token for first admin registration (no hardcoded credentials), localhost-only CORS, login rate limiting with exponential backoff, CSRF token protection (via `X-CSRF-Token` header + `SameSite=Strict` cookie), security headers (`X-Content-Type-Options`, `X-Frame-Options`, `X-XSS-Protection`, `Referrer-Policy`, `CSP`, `HSTS` for non-localhost), HTTPS enforcement for non-localhost binds, input sanitization with configurable max payload size, conversation session ownership verification, periodic expired session cleanup, API keys loaded from environment variables (`apiKeyEnv`), and user management APIs (create/delete users, change password, list users).

---

## Phase 2 — Tool Execution Engine

Give the agent hands: the ability to interact with the real world.

- [ ] **Shell Command Execution** — Run arbitrary shell commands in a sandboxed environment.
- [ ] **File System Access** — Read, write, search, and manage files and directories.
- [ ] **Browser Control** — Headless/headed browser automation (navigate, click, fill forms, take snapshots).
- [ ] **Canvas / UI Surface** — Push rich content (HTML, images, interactive elements) to a display surface.
- [ ] **Tool Registry** — Pluggable tool interface so new tools can be registered at runtime.

---

## Phase 3 — Persistent Memory System

Let the agent remember across sessions and grow smarter over time.

- [ ] **Conversation Logging (JSONL)** — Store raw interaction transcripts for every session.
- [ ] **Curated Long-Term Memory (`MEMORY.md`)** — Distill key facts into a human-readable, editable Markdown file.
- [ ] **Identity & Personality (`SOUL.md`)** — Define the agent's persona, tone, and behavioral guidelines.
- [ ] **User Profile (`USER.md`)** — Store user preferences, coding style, and personal context.
- [ ] **Semantic Memory Search** — Vector-based retrieval of relevant past conversations per turn.
- [ ] **Three-Tier Memory Architecture** — Daily logs → curated memory → deep semantic search.

---

## Phase 4 — Messaging & Transport Integrations

Meet users where they are: chat apps, email, and beyond.

- [ ] **Discord Integration** — Bi-directional messaging with Discord channels/DMs.
- [ ] **Telegram Integration** — Full Telegram bot support with inline buttons, reactions, and streaming.
- [ ] **WhatsApp Integration** — QR/pairing code auth, media support.
- [ ] **Slack Integration** — Channel messages, reactions, pins, and bot coexistence.
- [ ] **Email (Gmail Pub/Sub)** — Monitor inbox, send replies, and trigger workflows from email events.
- [ ] **WebChat UI** — Built-in web interface served directly from the gateway.
- [ ] **Channel Routing** — Route messages to the right handler based on source channel and context.

---

## Phase 5 — Proactive Automation & Scheduling

Move from reactive assistant to proactive agent.

- [ ] **Heartbeat System (`HEARTBEAT.md`)** — Periodic wake-up schedule so the agent can act without being prompted.
- [ ] **Cron Jobs** — Schedule recurring tasks (e.g., daily summaries, build monitoring).
- [ ] **Webhook Triggers** — Expose HTTP endpoints that trigger agent workflows from external events.
- [ ] **Git/CI Monitoring** — Watch repositories, trigger builds, parse error logs, propose fixes.
- [ ] **Proactive Notifications** — Alert users via their preferred channel when something needs attention.

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
- [ ] **Permission Controls** — Fine-grained permissions for tools (file access, shell, network, etc.).
- [ ] **Retry Policy** — Configurable retry logic for failed LLM calls and tool executions.
- [ ] **Health Checks & Doctor Diagnostics** — Self-diagnosis tools to detect misconfiguration or degraded state.
- [ ] **Logging & Observability** — Structured logging, usage tracking, and presence/typing indicators.
- [x] **Auth & Access Control** — Username/password, token-based auth for the gateway and UI. One-time signup token, CSRF protection, rate limiting, and user management.
- [ ] **Sandboxed Execution** — Isolate tool execution to prevent unintended side effects.

---

## Phase 8 — Multi-Platform & Distribution

Run everywhere: desktop, mobile, containers.

- [ ] **Docker Deployment** — One-command `docker compose up` with all services configured.
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
