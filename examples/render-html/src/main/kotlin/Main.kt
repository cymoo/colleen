import io.github.cymoo.colleen.Colleen

data class User(val id: Int, val name: String, val email: String)

/**
 * Pebble Template Engine Demo
 *
 * Features demonstrated:
 * - Integration of Pebble template engine with Colleen
 * - Automatic template loader detection
 * - Rendering dynamic HTML pages
 * - Passing data models to templates
 * - Support for both development and production modes
 */
fun main() {
    val app = Colleen()

    // Register Pebble template renderer.
    //
    // PebbleRender automatically selects the most appropriate loader:
    // 1. If a local "templates/" directory exists -> use FileLoader (external templates)
    // 2. Otherwise, if "templates/" exists in classpath -> use ClasspathLoader (templates inside jar)
    // 3. If both exist -> FileLoader has higher priority
    app.use(PebbleRender())

    // Render a simple welcome page
    app.get("/") { ctx ->
        ctx.render(
            "index.html",
            mapOf(
                "title" to "Welcome",
                "message" to "Hello from Pebble!"
            )
        )
    }

    // Render a user list page with dynamic data
    app.get("/users") { ctx ->
        val users = listOf(
            User(1, "Alice", "alice@example.com"),
            User(2, "Bob", "bob@example.com")
        )

        ctx.render(
            "users.html",
            mapOf("users" to users)
        )
    }

    // Start the HTTP server
    app.listen()
    println("Server running at http://localhost:8000")
}

// ============================================
// Usage Guide
// ============================================

/*
Mode 1: Development Mode (External Templates with Hot Reload)

Project structure:

  project/
  ├── pom.xml
  ├── src/main/kotlin/Main.kt
  ├── templates/              <- external template directory
  │   ├── index.html
  │   └── users.html
  └── target/

Command to run:

  mvn compile exec:java

In this mode, templates are loaded directly from the "templates/" folder.
Any changes to template files will take effect immediately after refreshing the browser
(if template caching is disabled).

------------------------------------------------------------

Mode 2: Production Mode (Templates Packaged Inside JAR)

Project structure:

  project/
  ├── pom.xml
  ├── src/
  │   └── main/
  │       ├── kotlin/Main.kt
  │       └── resources/
  │           └── templates/   <- templates packaged inside jar
  │               ├── index.html
  │               └── users.html
  └── target/
      └── app.jar

Command to run:

  java -jar target/app.jar

In this mode, all templates are loaded from inside the application JAR file.

------------------------------------------------------------

Mode 3: Hybrid Mode (JAR + External Template Override)

Deployment structure:

  /opt/myapp/
  ├── app.jar                 <- contains default templates
  └── templates/              <- external template overrides
      └── index.html          <- override specific templates only

Command to run:

  java -jar app.jar

Behavior:

- External templates in "./templates/" take priority.
- If a template does not exist externally, the version inside the JAR will be used as fallback.
- This allows customizing specific templates without modifying or rebuilding the application.
*/
