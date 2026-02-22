package tech.lenooby09.openklaw

import org.slf4j.LoggerFactory
import tech.lenooby09.openklaw.agent.AgentLoop
import tech.lenooby09.openklaw.config.AppConfig
import tech.lenooby09.openklaw.config.GatewayConfig
import tech.lenooby09.openklaw.config.LlmConfig
import tech.lenooby09.openklaw.config.SecurityConfig
import tech.lenooby09.openklaw.config.ToolsConfig
import tech.lenooby09.openklaw.gateway.GatewayServer
import tech.lenooby09.openklaw.llm.LlmOrchestrator
import tech.lenooby09.openklaw.memory.MemoryManager
import tech.lenooby09.openklaw.session.SessionManager
import tech.lenooby09.openklaw.tools.*

fun main(args: Array<String>) {
	val logger = LoggerFactory.getLogger("open-klaw")
	val startTime = System.currentTimeMillis()

	logger.info("Starting Open-Klaw...")

	// Sandbox detection: require either Docker container or explicit bare-metal opt-in
	// Check both the env var AND actual container indicators for defense-in-depth
	val envClaimsSandboxed = System.getenv("OPENKLAW_SANDBOXED")?.toBoolean() == true
	val hasContainerIndicators = java.io.File("/.dockerenv").exists() ||
		(java.io.File("/proc/1/cgroup").let { it.exists() && it.readText().contains("docker|containerd|lxc|kubepods".toRegex()) })
	val isSandboxed = envClaimsSandboxed && hasContainerIndicators
	val isBareMetal = System.getenv("OPENKLAW_BARE_METAL")?.toBoolean() == true || args.contains("--bare-metal")

	if (envClaimsSandboxed && !hasContainerIndicators) {
		logger.warn("OPENKLAW_SANDBOXED=true is set but no container indicators found. Treating as bare-metal.")
	}

	if (!isSandboxed && !isBareMetal) {
		logger.error("╔══════════════════════════════════════════════════════════════╗")
		logger.error("║  STARTUP BLOCKED: No sandbox detected.                      ║")
		logger.error("║                                                              ║")
		logger.error("║  Open-Klaw's shell and filesystem tools grant the LLM        ║")
		logger.error("║  direct access to the host system. Running without a sandbox ║")
		logger.error("║  (e.g., Docker) is a serious security risk.                  ║")
		logger.error("║                                                              ║")
		logger.error("║  To start safely:    ./run.sh                (uses Docker)   ║")
		logger.error("║  To force bare-metal: ./run.sh --bare-metal                  ║")
		logger.error("║    or set env:  OPENKLAW_BARE_METAL=true                     ║")
		logger.error("╚══════════════════════════════════════════════════════════════╝")
		System.exit(1)
		return
	}

	if (isBareMetal && !isSandboxed) {
		logger.warn("╔══════════════════════════════════════════════════════════╗")
		logger.warn("║  ⚠  WARNING: Running in bare-metal mode.               ║")
		logger.warn("║  Shell and filesystem tools have UNRESTRICTED access    ║")
		logger.warn("║  to the host system. Use Docker for safer execution.    ║")
		logger.warn("╚══════════════════════════════════════════════════════════╝")
	} else {
		logger.info("Running in sandboxed container mode.")
	}

	val config = AppConfig(
		gateway = GatewayConfig(
			enabled = true,
			port = System.getenv("OPENKLAW_PORT")?.toIntOrNull() ?: 8080,
			bindAddress = System.getenv("OPENKLAW_BIND") ?: "127.0.0.1"
		),
		llm = LlmConfig(
			providers = emptyList(),
			failoverEnabled = true
		),
		security = SecurityConfig(),
		tools = ToolsConfig(
			shellEnabled = System.getenv("OPENKLAW_TOOL_SHELL")?.toBoolean() ?: true,
			fileSystemEnabled = System.getenv("OPENKLAW_TOOL_FILE")?.toBoolean() ?: true,
			browserEnabled = System.getenv("OPENKLAW_TOOL_BROWSER")?.toBoolean() ?: true,
			canvasEnabled = System.getenv("OPENKLAW_TOOL_CANVAS")?.toBoolean() ?: true,
			fileSystemBaseDir = System.getenv("OPENKLAW_FILE_BASE_DIR") ?: "."
		)
	)

	val sessionManager = SessionManager(config.auth, config.security)
	sessionManager.startCleanupScheduler()

	val orchestrator = LlmOrchestrator(config.llm)
	orchestrator.initialize()

	// Initialize Tool Registry and register built-in tools
	val toolRegistry = ToolRegistry()
	val canvasTool = CanvasTool(config.tools)

	val shellTool = ShellTool(config.tools)
	val fileSystemTool = FileSystemTool(config.tools)
	val browserTool = BrowserTool(config.tools)

	toolRegistry.register(shellTool)
	toolRegistry.register(fileSystemTool)
	toolRegistry.register(browserTool)
	toolRegistry.register(canvasTool)

	logger.info("Tool execution engine initialized — ${toolRegistry.getToolCount()} tools registered")

	// Initialize Memory System
	val memoryManager = MemoryManager(config.memory)
	memoryManager.initialize()
	logger.info("Persistent memory system initialized (dataDir=${config.memory.dataDir})")

	val agentLoop = AgentLoop(orchestrator, toolRegistry, memoryManager)

	val gateway = GatewayServer(config.gateway, config.security, sessionManager, agentLoop, startTime, toolRegistry, canvasTool, memoryManager)

	// Register periodic cleanup callbacks
	sessionManager.onCleanup { gateway.cleanupRateLimiter() }
	sessionManager.onCleanup { agentLoop.flushIdleConversations(config.security.conversationIdleTimeoutMinutes) }

	Runtime.getRuntime().addShutdownHook(Thread {
		logger.info("Shutting down Open-Klaw...")
		sessionManager.stopCleanupScheduler()
		gateway.stop()
	})

	gateway.start()
	logger.info("Open-Klaw is ready — http://${config.gateway.bindAddress}:${config.gateway.port}")

	val signupToken = sessionManager.signupToken
	if (signupToken != null) {
		logger.info("╔══════════════════════════════════════════════════════╗")
		logger.info("║  FIRST-TIME SETUP: Use the signup token below to     ║")
		logger.info("║  register the initial admin account.                 ║")
		logger.info("║  Signup Token: $signupToken                            ║")
		logger.info("╚══════════════════════════════════════════════════════╝")
	}

	Thread.currentThread().join()
}
