import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.jvm.tasks.Jar
import java.io.File

val pluginSourceSets = extensions.getByType<SourceSetContainer>()
val pluginRuntimeClasspath = configurations.named("runtimeClasspath")

tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Builds a dynamic-bot plugin fat jar without host-provided dependencies."
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Multi-Release"] = "true"
    }

    from(pluginSourceSets.named("main").map { it.output })
    dependsOn(pluginRuntimeClasspath)
    from({
        pluginRuntimeClasspath.get()
            .filter { it.exists() && !it.isDynamicBotHostProvided() }
            .map { file -> if (file.isDirectory) file else zipTree(file) }
    })
}

fun File.isDynamicBotHostProvided(): Boolean {
    val normalizedPath = path.replace('\\', '/')
    return normalizedPath.contains("/dynamic-bot-core/build/") ||
        name.startsWith("dynamic-bot-core-") ||
        name.startsWith("kotlin-logging-jvm-") ||
        name.startsWith("log4j-api-") ||
        name.startsWith("log4j-core-") ||
        name.startsWith("log4j-slf4j") ||
        name.startsWith("log4j-to-slf4j-") ||
        name.startsWith("logback-") ||
        name.startsWith("jul-to-slf4j-") ||
        name.startsWith("slf4j-")
}
