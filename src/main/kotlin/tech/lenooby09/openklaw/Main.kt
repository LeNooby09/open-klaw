package tech.lenooby09.openklaw

import org.slf4j.LoggerFactory
import tech.lenooby09.openklaw.agent.AgentLoop
import tech.lenooby09.openklaw.config.AppConfig
import tech.lenooby09.openklaw.config.GatewayConfig
import tech.lenooby09.openklaw.config.LlmConfig
import tech.lenooby09.openklaw.config.SecurityConfig
import tech.lenooby09.openklaw.gateway.GatewayServer
import tech.lenooby09.openklaw.llm.LlmOrchestrator
import tech.lenooby09.openklaw.session.SessionManager

fun main() {
	val logger = LoggerFactory.getLogger("open-klaw")
	val startTime = System.currentTimeMillis()

	logger.info("Starting Open-Klaw...")

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
		security = SecurityConfig()
	)

	val sessionManager = SessionManager(config.auth, config.security)
	sessionManager.startCleanupScheduler()

	val orchestrator = LlmOrchestrator(config.llm)
	orchestrator.initialize()

	val agentLoop = AgentLoop(orchestrator)

	val gateway = GatewayServer(config.gateway, config.security, sessionManager, agentLoop, startTime)

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
