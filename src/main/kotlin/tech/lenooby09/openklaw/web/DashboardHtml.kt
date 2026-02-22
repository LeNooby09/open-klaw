package tech.lenooby09.openklaw.web

object DashboardHtml {
	val INDEX: String by lazy {
		DashboardHtml::class.java.getResourceAsStream("/web/dashboard.html")?.bufferedReader()?.readText()
			?: error("Could not load /web/dashboard.html from classpath")
	}
}
