---
name: split-mode-backend-classloader
description: IntelliJ split-mode backend runs in a separate JVM process — third-party deps must be fat-jarred into the backend module JAR, not placed in lib/
metadata:
  type: project
---

In split-mode the backend and frontend run in **separate JVM processes**. The plugin's `lib/` directory is only on the frontend (client IDE) classpath. The backend process only loads `lib/modules/intellij-quanta-ai-plugin.backend.jar`.

**Why:** Any third-party dep (openai, jgit, mcp-sdk, jackson-module-kotlin) referenced by backend code must be bundled inside the backend module JAR as a fat JAR — placing them in `lib/` makes them invisible to the backend process and causes `NoClassDefFoundError` at RPC registration time (symptom: "Sync" button never turns to "Send", error `com/openai/models/ChatModel [Plugin: com.intellij]`).

**How to apply:** Use a `backendRuntime` configuration in `backend/build.gradle.kts` with IDE-provided transitives excluded (kotlin, kotlinx-coroutines, kotlinx-serialization, slf4j, logback, ktor, netty). Unpack those JARs into the backend module JAR via a `named<Jar>("jar")` task with `from(backendRuntime.map { zipTree(it) })` and `DuplicatesStrategy.EXCLUDE`. The frontend module does NOT need a fat JAR — its deps go in `lib/` normally via `implementation` on the root project or the frontend subproject.
