package tech.lenooby09.openklaw.security

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class UserPermissionManagerTest {

	@TempDir
	lateinit var tempDir: File

	@Test
	fun `default is unrestricted`() {
		val mgr = UserPermissionManager(tempDir.absolutePath)
		assertNull(mgr.getUserTools("alice"))
		assertNull(mgr.resolveAllowedTools("alice", isAdmin = false))
	}

	@Test
	fun `set and get user tools`() {
		val mgr = UserPermissionManager(tempDir.absolutePath)
		mgr.setUserTools("alice", setOf("shell", "filesystem"))

		assertEquals(setOf("shell", "filesystem"), mgr.getUserTools("alice"))
	}

	@Test
	fun `resolveAllowedTools returns tools for restricted user`() {
		val mgr = UserPermissionManager(tempDir.absolutePath)
		mgr.setUserTools("alice", setOf("browser"))

		assertEquals(setOf("browser"), mgr.resolveAllowedTools("alice", isAdmin = false))
	}

	@Test
	fun `admins always get unrestricted access`() {
		val mgr = UserPermissionManager(tempDir.absolutePath)
		mgr.setUserTools("admin_user", setOf("browser"))

		assertNull(mgr.resolveAllowedTools("admin_user", isAdmin = true))
	}

	@Test
	fun `isToolAllowed checks correctly`() {
		val mgr = UserPermissionManager(tempDir.absolutePath)
		mgr.setUserTools("alice", setOf("browser", "canvas"))

		assertTrue(mgr.isToolAllowed("alice", isAdmin = false, "browser"))
		assertTrue(mgr.isToolAllowed("alice", isAdmin = false, "canvas"))
		assertFalse(mgr.isToolAllowed("alice", isAdmin = false, "shell"))

		// Admins bypass
		assertTrue(mgr.isToolAllowed("alice", isAdmin = true, "shell"))
	}

	@Test
	fun `isToolAllowed allows all when no restrictions set`() {
		val mgr = UserPermissionManager(tempDir.absolutePath)

		assertTrue(mgr.isToolAllowed("bob", isAdmin = false, "shell"))
		assertTrue(mgr.isToolAllowed("bob", isAdmin = false, "filesystem"))
	}

	@Test
	fun `clear restrictions with null`() {
		val mgr = UserPermissionManager(tempDir.absolutePath)
		mgr.setUserTools("alice", setOf("browser"))
		assertNotNull(mgr.getUserTools("alice"))

		mgr.setUserTools("alice", null)
		assertNull(mgr.getUserTools("alice"))
	}

	@Test
	fun `removeUserPermissions returns correct status`() {
		val mgr = UserPermissionManager(tempDir.absolutePath)
		mgr.setUserTools("alice", setOf("browser"))

		assertTrue(mgr.removeUserPermissions("alice"))
		assertFalse(mgr.removeUserPermissions("alice")) // Already removed
	}

	@Test
	fun `listPermissions returns sorted`() {
		val mgr = UserPermissionManager(tempDir.absolutePath)
		mgr.setUserTools("charlie", setOf("shell"))
		mgr.setUserTools("alice", setOf("browser"))

		val list = mgr.listPermissions()
		assertEquals(2, list.size)
		assertEquals("alice", list[0].username)
		assertEquals("charlie", list[1].username)
	}

	@Test
	fun `getPermissionCount tracks entries`() {
		val mgr = UserPermissionManager(tempDir.absolutePath)
		assertEquals(0, mgr.getPermissionCount())

		mgr.setUserTools("alice", setOf("browser"))
		assertEquals(1, mgr.getPermissionCount())

		mgr.setUserTools("bob", setOf("shell"))
		assertEquals(2, mgr.getPermissionCount())

		mgr.removeUserPermissions("alice")
		assertEquals(1, mgr.getPermissionCount())
	}

	@Test
	fun `permissions persist across instances`() {
		val mgr1 = UserPermissionManager(tempDir.absolutePath)
		mgr1.setUserTools("alice", setOf("browser", "canvas"))
		mgr1.setUserTools("bob", setOf("shell"))

		// Create a new instance pointing at the same directory — should load persisted data
		val mgr2 = UserPermissionManager(tempDir.absolutePath)
		assertEquals(setOf("browser", "canvas"), mgr2.getUserTools("alice"))
		assertEquals(setOf("shell"), mgr2.getUserTools("bob"))
		assertEquals(2, mgr2.getPermissionCount())
	}

	@Test
	fun `permissions file has correct content`() {
		val mgr = UserPermissionManager(tempDir.absolutePath)
		mgr.setUserTools("alice", setOf("browser"))

		val file = File(tempDir, "permissions.json")
		assertTrue(file.exists())
		val content = file.readText()
		assertTrue(content.contains("alice"))
		assertTrue(content.contains("browser"))
	}
}
