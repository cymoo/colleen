# Auto Reload (Development Mode)

Colleen does **not** provide a built-in hot reload mechanism.

Implementing true hot reload on the JVM (reloading classes without restarting the process) usually requires complex
classloader tricks and tight coupling with specific build tools or IDEs.

Instead, we can use a **process-level reload** approach:

* Watch source files
* Recompile the project
* Restart the JVM automatically

This keeps the framework simple and build-tool agnostic.

---

## One Simple Solution: `air`

[`air`](https://github.com/air-verse/air) is a lightweight file-watching tool that can restart any long-running process
when files change. Although it is written in Go, it works for JVM-based projects.

### 1. Install `air`

```bash
go install github.com/air-verse/air@latest
```

Make sure the Go bin directory is in your `PATH`.

---

### 2. Create `.air.toml`

Place the following file at the project root:

```toml
root = "."

tmp_dir = "target/air"

[build]
# Compile project
cmd = "mvn -T 1C -q -DskipTests compile"

bin = "mvn"

# Run application
full_bin = "mvn -q exec:java"

include_ext = ["kt", "java", "xml", "yml", "yaml", "properties"]

exclude_dir = ["target", ".git", ".idea", ".vscode"]

# debounce time (ms)
delay = 400

stop_on_error = true

# graceful shutdown
kill_signal = "SIGTERM"
kill_delay = 1

[log]
level = "info"

[misc]
clean_on_exit = true
```

---

### 3. Run

```bash
air
```

When any watched file changes:

1. The project is recompiled
2. The running JVM process is stopped
3. A new JVM process is started automatically

---

## Restart Time

Colleen is designed to be lightweight.

On most machines, a full restart (including JVM startup) typically takes **3–5 seconds**, which is acceptable for
iterative development.

---

## Alternatives

`air` is only one option. You can use any tool that:

* Watches files
* Restarts a process on change

Examples include:

* `entr`
* `watchexec`
* Custom shell scripts
