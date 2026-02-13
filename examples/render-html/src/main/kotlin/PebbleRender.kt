import io.github.cymoo.colleen.Context
import io.github.cymoo.colleen.Middleware
import io.github.cymoo.colleen.Next
import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.extension.AbstractExtension
import io.pebbletemplates.pebble.loader.ClasspathLoader
import io.pebbletemplates.pebble.loader.DelegatingLoader
import io.pebbletemplates.pebble.loader.FileLoader
import io.pebbletemplates.pebble.loader.Loader
import java.io.File
import java.io.StringWriter

/**
 * Pebble Template Rendering Middleware
 *
 * Provides template rendering capabilities using the Pebble template engine.
 * Adds a convenient render() method to the Context for rendering templates with data.
 *
 * The middleware automatically determines the best template loading strategy:
 * 1. If templateDir exists as a file system directory, use FileLoader (supports external templates)
 * 2. Otherwise, use ClasspathLoader (for templates bundled in jar)
 * 3. If both are possible, use DelegatingLoader to try FileLoader first, then ClasspathLoader
 *
 * @param templateDir The directory where templates are located (default: "templates")
 * @param globals Global variables available to template
 * @param cache Whether to enable template caching (default: true)
 * @param configure Optional configuration for PebbleEngine
 *
 */
class PebbleRender(
    templateDir: String = "templates",
    globals: Map<String, Any?> = emptyMap(),
    cache: Boolean = true,
    configure: PebbleEngine.Builder.() -> Unit = {}
) : Middleware {

    companion object {
        internal const val RENDER_KEY = "io.github.cymoo.colleen.middleware.PebbleRender.engine"
    }

    private val engine: PebbleEngine

    init {
        val loader = createLoader(templateDir)

        engine = PebbleEngine.Builder()
            .loader(loader)
            .cacheActive(cache)
            .apply {
                if (globals.isNotEmpty()) {
                    extension(GlobalVariablesExtension(globals))
                }
            }
            .apply(configure)
            .build()
    }

    /**
     * Creates the appropriate loader based on template directory availability
     */
    private fun createLoader(templateDir: String): Loader<String> {
        val fileDir = File(templateDir)
        val fileLoaderExists = fileDir.exists() && fileDir.isDirectory

        // Check if templates exist in classpath
        val classpathResource = javaClass.classLoader.getResource(templateDir)
        val classpathLoaderExists = classpathResource != null

        @Suppress("UNCHECKED_CAST")
        return when {
            // Both exist: try file system first, fall back to classpath
            fileLoaderExists && classpathLoaderExists -> {
                DelegatingLoader(
                    listOf<Loader<*>>(
                        FileLoader(fileDir.absolutePath),
                        createClasspathLoader(templateDir)
                    )
                ) as Loader<String>
            }
            // Only file system exists
            fileLoaderExists -> FileLoader(fileDir.absolutePath)
            // Only classpath exists (or neither, will fail later with clear error)
            else -> createClasspathLoader(templateDir)
        }
    }

    private fun createClasspathLoader(dir: String): ClasspathLoader {
        return ClasspathLoader().apply {
            prefix = if (dir.endsWith("/")) dir else "$dir/"
        }
    }

    override fun invoke(ctx: Context, next: Next) {
        ctx.setState(RENDER_KEY, engine)
        next()
    }
}

/**
 * Render a template with the given data model
 *
 * @param template The template name
 * @param model The data model to pass to the template
 */
fun Context.render(template: String, model: Map<String, Any?> = emptyMap()) {
    val engine = this.getStateOrNull<PebbleEngine>(PebbleRender.RENDER_KEY) ?: throw IllegalStateException(
        "PebbleRender middleware not configured. " +
                "Please add PebbleRender() to your middleware chain."
    )

    val writer = StringWriter()
    engine.getTemplate(template).evaluate(writer, model)

    this.html(writer.toString())
}

private class GlobalVariablesExtension(
    private val globals: Map<String, Any?>
) : AbstractExtension() {

    override fun getGlobalVariables(): Map<String, Any?> {
        return globals
    }
}