import groovy.json.JsonSlurper
import groovy.xml.XmlParser
import org.apache.tools.ant.taskdefs.condition.Os.*
import org.gradle.api.JavaVersion.VERSION_11
import org.gradle.api.JavaVersion.VERSION_17
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform
import org.jetbrains.grammarkit.tasks.GenerateLexerTask
import org.jetbrains.grammarkit.tasks.GenerateParserTask
import org.jetbrains.intellij.tasks.PatchPluginXmlTask
import org.jetbrains.intellij.tasks.PrepareSandboxTask
import org.jetbrains.intellij.tasks.PublishPluginTask
import org.jetbrains.intellij.tasks.RunIdeTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jsoup.Jsoup
import java.io.Writer
import kotlin.concurrent.thread

// The same as `--stacktrace` param
gradle.startParameter.showStacktrace = ShowStacktrace.ALWAYS

val isCI = System.getenv("CI") != null
val isTeamcity = System.getenv("TEAMCITY_VERSION") != null

val channel = prop("publishChannel")
val platformVersion = prop("platformVersion").toInt()
val baseIDE = prop("baseIDE")
val ideaVersion = prop("ideaVersion")
val clionVersion = prop("clionVersion")
val baseVersion = when (baseIDE) {
    "idea" -> ideaVersion
    "clion" -> clionVersion
    else -> error("Unexpected IDE name: `$baseIDE`")
}

val tomlPlugin = "org.toml.lang"
val nativeDebugPlugin = "com.intellij.nativeDebug:${prop("nativeDebugPluginVersion")}"
val graziePlugin = "tanvd.grazi"
val psiViewerPlugin = "PsiViewer:${prop("psiViewerPluginVersion")}"
val intelliLangPlugin = "org.intellij.intelliLang"
val copyrightPlugin = "com.intellij.copyright"
val javaPlugin = "com.intellij.java"
val javaIdePlugin = "com.intellij.java.ide"
val javaScriptPlugin = "JavaScript"
val clionPlugins = listOf("com.intellij.cidr.base", "com.intellij.clion")
val mlCompletionPlugin = "com.intellij.completion.ml.ranking"

val compileNativeCodeTaskName = "compileNativeCode"

plugins {
    idea
    kotlin("jvm") version "1.7.20"
    id("org.jetbrains.intellij") version "1.10.0"
    id("org.jetbrains.grammarkit") version "2021.2.2"
    id("net.saliman.properties") version "1.5.2"
    id("org.gradle.test-retry") version "1.4.1"
}

idea {
    module {
        // https://github.com/gradle/kotlin-dsl/issues/537/
        excludeDirs = excludeDirs + file("testData") + file("deps") + file("bin")
    }
}

allprojects {
    apply {
        plugin("idea")
        plugin("kotlin")
        plugin("org.jetbrains.grammarkit")
        plugin("org.jetbrains.intellij")
        plugin("org.gradle.test-retry")
    }

    repositories {
        mavenCentral()
        maven("https://cache-redirector.jetbrains.com/repo.maven.apache.org/maven2")
        maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
    }

    idea {
        module {
            generatedSourceDirs.add(file("src/gen"))
        }
    }

    intellij {
        version.set(baseVersion)
        downloadSources.set(!isCI)
        updateSinceUntilBuild.set(true)
        instrumentCode.set(false)
        ideaDependencyCachePath.set(dependencyCachePath)
        sandboxDir.set("$buildDir/$baseIDE-sandbox-$platformVersion")
    }

    val javaVersion = if (platformVersion < 223) VERSION_11 else VERSION_17

    configure<JavaPluginExtension> {
        // BACKCOMPAT: 2022.2. Use VERSION_17
        sourceCompatibility = VERSION_11
        targetCompatibility = javaVersion
    }

    tasks {
        withType<KotlinCompile> {
            kotlinOptions {
                jvmTarget = javaVersion.toString()
                languageVersion = "1.7"
                // see https://plugins.jetbrains.com/docs/intellij/kotlin.html#kotlin-standard-library
                apiVersion = "1.6"
                freeCompilerArgs = listOf("-Xjvm-default=all")
            }
        }
        withType<PatchPluginXmlTask> {
            sinceBuild.set(prop("sinceBuild"))
            untilBuild.set(prop("untilBuild"))
        }

        // All these tasks don't make sense for non-root subprojects
        // Root project (i.e. `:plugin`) enables them itself if needed
        runIde { enabled = false }
        prepareSandbox { enabled = false }
        buildSearchableOptions { enabled = false }

        test {
            testLogging {
                showStandardStreams = prop("showStandardStreams").toBoolean()
                afterSuite(
                    KotlinClosure2<TestDescriptor, TestResult, Unit>({ desc, result ->
                        if (desc.parent == null) { // will match the outermost suite
                            val output = "Results: ${result.resultType} (${result.testCount} tests, ${result.successfulTestCount} passed, ${result.failedTestCount} failed, ${result.skippedTestCount} skipped)"
                            println(output)
                        }
                    })
                )
            }
            if (isCI) {
                retry {
                    maxRetries.set(3)
                    maxFailures.set(5)
                }
            }
        }

        // It makes sense to copy native binaries only for root ("intellij-rust") and "plugin" projects because:
        // - `intellij-rust` is supposed to provide all necessary functionality related to procedural macro expander.
        //   So the binaries are required for the corresponding tests.
        // - `plugin` is root project to build plugin artifact and exactly its sandbox is included into the plugin artifact
        if (project.name in listOf("intellij-rust", "plugin")) {
            task<Exec>(compileNativeCodeTaskName) {
                workingDir = rootDir.resolve("native-helper")
                executable = "cargo"
                // Hack to use unstable `--out-dir` option work for stable toolchain
                // https://doc.rust-lang.org/cargo/commands/cargo-build.html#output-options
                environment("RUSTC_BOOTSTRAP", "1")

                val hostPlatform = DefaultNativePlatform.host()
                val archName = when (val archName = hostPlatform.architecture.name) {
                    "arm-v8", "aarch64" -> "arm64"
                    else -> archName
                }
                val outDir = "${rootDir}/bin/${hostPlatform.operatingSystem.toFamilyName()}/$archName"
                args("build", "--release", "-Z", "unstable-options", "--out-dir", outDir)

                // It may be useful to disable compilation of native code.
                // For example, CI builds native code for each platform in separate tasks and puts it into `bin` dir manually
                // so there is no need to do it again.
                enabled = prop("compileNativeCode").toBoolean()
            }
        }
    }

    sourceSets {
        main {
            java.srcDirs("src/gen")
            resources.srcDirs("src/$platformVersion/main/resources")
        }
        test {
            resources.srcDirs("src/$platformVersion/test/resources")
        }
    }
    kotlin {
        sourceSets {
            main {
                kotlin.srcDirs("src/$platformVersion/main/kotlin")
            }
            test {
                kotlin.srcDirs("src/$platformVersion/test/kotlin")
            }
        }
    }

    val testOutput = configurations.create("testOutput")

    dependencies {
        compileOnly(kotlin("stdlib-jdk8"))
        testOutput(sourceSets.getByName("test").output.classesDirs)
    }

    afterEvaluate {
        tasks.withType<AbstractTestTask> {
            testLogging {
                if (hasProp("showTestStatus") && prop("showTestStatus").toBoolean()) {
                    events = setOf(TestLogEvent.STARTED, TestLogEvent.PASSED, TestLogEvent.SKIPPED, TestLogEvent.FAILED)
                }
                exceptionFormat = TestExceptionFormat.FULL
            }
        }

        tasks.withType<Test>().configureEach {
            jvmArgs = listOf("-Xmx2g", "-XX:-OmitStackTraceInFastThrow")
            // We need to prevent the platform-specific shared JNA library to loading from the system library paths,
            // because otherwise it can lead to compatibility issues.
            // Also note that IDEA does the same thing at startup, and not only for tests.
            systemProperty("jna.nosys", "true")
            if (isTeamcity) {
                // Make teamcity builds green if only muted tests fail
                // https://youtrack.jetbrains.com/issue/TW-16784
                ignoreFailures = true
            }
            if (hasProp("excludeTests")) {
                exclude(prop("excludeTests"))
            }
        }
    }
}


val Project.dependencyCachePath
    get(): String {
        val cachePath = file("${rootProject.projectDir}/deps")
        // If cache path doesn't exist, we need to create it manually
        // because otherwise gradle-intellij-plugin will ignore it
        if (!cachePath.exists()) {
            cachePath.mkdirs()
        }
        return cachePath.absolutePath
    }

val channelSuffix = if (channel.isBlank() || channel == "stable") "" else "-$channel"
val versionSuffix = "-$platformVersion$channelSuffix"
val majorVersion = "0.4"
val patchVersion = prop("patchVersion").toInt()

// Special module with run, build and publish tasks
project(":plugin") {
    version = "$majorVersion.$patchVersion.${prop("buildNumber")}$versionSuffix"
    intellij {
        pluginName.set("intellij-rust")
        val pluginList = mutableListOf(
            tomlPlugin,
            intelliLangPlugin,
            graziePlugin,
            psiViewerPlugin,
            javaScriptPlugin,
            mlCompletionPlugin
        )
        if (baseIDE == "idea") {
            pluginList += listOf(
                copyrightPlugin,
                javaPlugin,
                nativeDebugPlugin
            )
        }
        plugins.set(pluginList)
    }

    dependencies {
        implementation(project(":"))
        implementation(project(":idea"))
        implementation(project(":clion"))
        implementation(project(":debugger"))
        implementation(project(":profiler"))
        implementation(project(":toml"))
        implementation(project(":copyright"))
        implementation(project(":coverage"))
        implementation(project(":intelliLang"))
        implementation(project(":duplicates"))
        implementation(project(":grazie"))
        implementation(project(":js"))
        implementation(project(":ml-completion"))
    }

    // Collects all jars produced by compilation of project modules and merges them into singe one.
    // We need to put all plugin manifest files into single jar to make new plugin model work
    val mergePluginJarTask = task<Jar>("mergePluginJars") {
        duplicatesStrategy = DuplicatesStrategy.FAIL
        archiveBaseName.set("intellij-rust")

        exclude("META-INF/MANIFEST.MF")
        exclude("**/classpath.index")

        val pluginLibDir by lazy {
            val sandboxTask = tasks.prepareSandbox.get()
            sandboxTask.destinationDir.resolve("${sandboxTask.pluginName.get()}/lib")
        }

        val pluginJars by lazy {
            pluginLibDir.listFiles().orEmpty().filter { it.isPluginJar() }
        }

        destinationDirectory.set(project.layout.dir(provider { pluginLibDir }))

        doFirst {
            for (file in pluginJars) {
                from(zipTree(file))
            }
        }

        doLast {
            delete(pluginJars)
        }
    }

    tasks {
        buildPlugin {
            // Set proper name for final plugin zip.
            // Otherwise, base name is the same as gradle module name
            archiveBaseName.set("intellij-rust")
        }
        runIde { enabled = true }
        prepareSandbox {
            finalizedBy(mergePluginJarTask)
            enabled = true
        }
        buildSearchableOptions {
            // Force `mergePluginJarTask` be executed before `buildSearchableOptions`
            // Otherwise, `buildSearchableOptions` task can't load the plugin and searchable options are not built.
            // Should be dropped when jar merging is implemented in `gradle-intellij-plugin` itself
            dependsOn(mergePluginJarTask)
            enabled = prop("enableBuildSearchableOptions").toBoolean()
        }
        withType<PrepareSandboxTask> {
            dependsOn(named(compileNativeCodeTaskName))

            // Copy native binaries
            from("${rootDir}/bin") {
                into("${pluginName.get()}/bin")
                include("**")
            }
            // Copy pretty printers
            from("$rootDir/prettyPrinters") {
                into("${pluginName.get()}/prettyPrinters")
                include("*.py")
            }
        }

        withType<RunIdeTask> {
            // Default args for IDEA installation
            jvmArgs("-Xmx768m", "-XX:+UseG1GC", "-XX:SoftRefLRUPolicyMSPerMB=50")
            // Disable plugin auto reloading. See `com.intellij.ide.plugins.DynamicPluginVfsListener`
            jvmArgs("-Didea.auto.reload.plugins=false")
            // Don't show "Tip of the Day" at startup
            jvmArgs("-Dide.show.tips.on.startup.default.value=false")
            // uncomment if `unexpected exception ProcessCanceledException` prevents you from debugging a running IDE
            // jvmArgs("-Didea.ProcessCanceledException=disabled")

            // Uncomment to enable FUS testing mode
            // jvmArgs("-Dfus.internal.test.mode=true")

            // Uncomment to enable localization testing mode
            // jvmArgs("-Didea.l10n=true")
        }

        withType<PatchPluginXmlTask> {
            pluginDescription.set(provider { file("description.html").readText() })
        }

        withType<PublishPluginTask> {
            token.set(prop("publishToken"))
            channels.set(listOf(channel))
        }
    }

    // Generates event scheme for Rust plugin FUS events to `plugin/build/eventScheme.json`
    task<RunIdeTask>("buildEventsScheme") {
        dependsOn(tasks.prepareSandbox)
        args("buildEventsScheme", "--outputFile=${buildDir.resolve("eventScheme.json").absolutePath}", "--pluginId=org.rust.lang")
        // BACKCOMPAT: 2022.2. Update value to 223 and this comment
        // `IDEA_BUILD_NUMBER` variable is used by `buildEventsScheme` task to write `buildNumber` to output json.
        // It will be used by TeamCity automation to set minimal IDE version for new events
        environment("IDEA_BUILD_NUMBER", "222")
    }
}

project(":") {
    sourceSets {
        main {
            if (channel == "nightly" || channel == "dev") {
                resources.srcDirs("src/main/resources-nightly")
                resources.srcDirs("src/$platformVersion/main/resources-nightly")
            } else {
                resources.srcDirs("src/main/resources-stable")
                resources.srcDirs("src/$platformVersion/main/resources-stable")
            }
        }
    }

    dependencies {
        implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-toml:2.14.1") {
            exclude(module = "jackson-core")
            exclude(module = "jackson-databind")
            exclude(module = "jackson-annotations")
        }
        api("io.github.z4kn4fein:semver:1.4.0") {
            excludeKotlinDeps()
        }
        testImplementation("com.squareup.okhttp3:mockwebserver:4.10.0")
    }

    val generateRustLexer = task<GenerateLexerTask>("generateRustLexer") {
        source.set("src/main/grammars/RustLexer.flex")
        targetDir.set("src/gen/org/rust/lang/core/lexer")
        targetClass.set("_RustLexer")
        purgeOldFiles.set(true)
    }

    // Previously, we had `GenerateLexer` task that generate a lexer for rustdoc highlighting.
    // Now we don't have this task, but previously generated lexer may have remained on the file system.
    // We have to remove it in order to prevent a compilation failure
    val deleteOldRustDocHighlightingLexer = task<Delete>("deleteOldRustDocHighlightingLexer") {
        delete("src/gen/org/rust/lang/doc")
    }

    val generateRustParser = task<GenerateParserTask>("generateRustParser") {
        source.set("src/main/grammars/RustParser.bnf")
        targetRoot.set("src/gen")
        pathToParser.set("org/rust/lang/core/parser/RustParser.java")
        pathToPsiRoot.set("org/rust/lang/core/psi")
        purgeOldFiles.set(true)
    }

    tasks {
        withType<KotlinCompile> {
            dependsOn(
                generateRustLexer, deleteOldRustDocHighlightingLexer,
                generateRustParser
            )
        }

        // In tests `resources` directory is used instead of `sandbox`
        processTestResources {
            dependsOn(named(compileNativeCodeTaskName))
            from("${rootDir}/bin") {
                into("bin")
                include("**")
            }
        }
    }

    task("resolveDependencies") {
        doLast {
            rootProject.allprojects
                .map { it.configurations }
                .flatMap { it.filter { c -> c.isCanBeResolved } }
                .forEach { it.resolve() }
        }
    }
}

project(":idea") {
    intellij {
        version.set(ideaVersion)
        plugins.set(listOf(
            javaPlugin,
            // this plugin registers `com.intellij.ide.projectView.impl.ProjectViewPane` for IDEA that we use in tests
            javaIdePlugin
        ))
    }
    dependencies {
        implementation(project(":"))
        testImplementation(project(":", "testOutput"))
    }
}

project(":clion") {
    intellij {
        version.set(clionVersion)
        plugins.set(clionPlugins)
    }
    dependencies {
        implementation(project(":"))
        implementation(project(":debugger"))
        testImplementation(project(":", "testOutput"))
    }
}

project(":debugger") {
    apply {
        plugin("antlr")
    }
    intellij {
        if (baseIDE == "idea") {
            plugins.set(listOf(nativeDebugPlugin))
        } else {
            version.set(clionVersion)
            plugins.set(clionPlugins)
        }
    }

    // Kotlin Gradle support doesn't generate proper extensions if the plugin is not declared in `plugin` block.
    // But if we do it, `antlr` plugin will be applied to root project as well that we want to avoid.
    // So, let's define all necessary things manually
    val antlr by configurations
    val generateGrammarSource: AntlrTask by tasks
    val generateTestGrammarSource: AntlrTask by tasks

    dependencies {
        implementation(project(":"))
        antlr("org.antlr:antlr4:4.11.1")
        implementation("org.antlr:antlr4-runtime:4.11.1")
        testImplementation(project(":", "testOutput"))
    }
    tasks {
        compileKotlin {
            dependsOn(generateGrammarSource)
        }
        compileTestKotlin {
            dependsOn(generateTestGrammarSource)
        }

        generateGrammarSource {
            arguments.add("-no-listener")
            arguments.add("-visitor")
            outputDirectory = file("src/gen/org/rust/debugger/lang")
        }
    }
    // Exclude antlr4 from transitive dependencies of `:debugger:api` configuration (https://github.com/gradle/gradle/issues/820)
    configurations.api {
        setExtendsFrom(extendsFrom.filter { it.name != "antlr" })
    }
}

project(":profiler") {
    intellij {
        version.set(clionVersion)
        plugins.set(clionPlugins)
    }
    dependencies {
        implementation(project(":"))
        testImplementation(project(":", "testOutput"))
    }
}

project(":toml") {
    intellij {
        plugins.set(listOf(tomlPlugin))
    }
    dependencies {
        implementation("org.eclipse.jgit:org.eclipse.jgit:6.3.0.202209071007-r") { exclude("org.slf4j") }

        implementation(project(":"))
        testImplementation(project(":", "testOutput"))
    }
    tasks {
        // Set custom plugin directory name.
        // Otherwise, `prepareSandbox`/`prepareTestingSandbox` tasks merge directories
        // of `toml` plugin and `toml` module because of the same name into single one that's not expected
        withType<PrepareSandboxTask> {
            pluginName.set("rust-toml")
        }
    }
}

project(":intelliLang") {
    intellij {
        plugins.set(listOf(intelliLangPlugin))
    }
    dependencies {
        implementation(project(":"))
        testImplementation(project(":", "testOutput"))
    }
}

project(":copyright") {
    intellij {
        version.set(ideaVersion)
        plugins.set(listOf(copyrightPlugin))
    }
    dependencies {
        implementation(project(":"))
        testImplementation(project(":", "testOutput"))
    }
}

project(":duplicates") {
    dependencies {
        implementation(project(":"))
        testImplementation(project(":", "testOutput"))
    }
}

project(":coverage") {
    dependencies {
        implementation(project(":"))
        testImplementation(project(":", "testOutput"))
    }
}

project(":grazie") {
    intellij {
        plugins.set(listOf(graziePlugin))
    }
    dependencies {
        implementation(project(":"))
        testImplementation(project(":", "testOutput"))
    }
}

project(":js") {
    intellij {
        plugins.set(listOf(javaScriptPlugin))
    }
    dependencies {
        implementation(project(":"))
        testImplementation(project(":", "testOutput"))
    }
}

project(":ml-completion") {
    intellij {
        plugins.set(listOf(mlCompletionPlugin))
    }
    dependencies {
        implementation("org.jetbrains.intellij.deps.completion:completion-ranking-rust:0.4.1")
        implementation(project(":"))
        testImplementation(project(":", "testOutput"))
    }
}

task("runPrettyPrintersTests") {
    doLast {
        val lldbPath = when {
            // TODO: Use `lldb` Python module from CLion distribution
            isFamily(FAMILY_MAC) -> "/Applications/Xcode.app/Contents/SharedFrameworks/LLDB.framework/Resources/Python"
            isFamily(FAMILY_UNIX) -> "$projectDir/deps/${clionVersion.replaceFirst("CL", "clion")}/bin/lldb/linux/lib/python3.8/site-packages"
            isFamily(FAMILY_WINDOWS) -> "" // `python36._pth` is used below instead
            else -> error("Unsupported OS")
        }
        val runCommand = "cargo run --package pretty_printers_test --bin pretty_printers_test -- lldb $lldbPath"
        if (isFamily(FAMILY_WINDOWS)) {
            val lldbBundlePath = "$projectDir\\deps\\${clionVersion.replaceFirst("CL", "clion")}\\bin\\lldb\\win\\x64"
            // Add path to bundled `lldb` Python module to `._pth` file (which overrides `sys.path`)
            // TODO: Drop when this is implemented on CLion side
            "cmd /C echo ../lib/site-packages>> bin/python36._pth".execute(lldbBundlePath)
            // Create symlink to allow `lldb` Python module perform `import _lldb` inside
            // TODO: Drop when this is implemented on CLion side
            "cmd /C mklink $lldbBundlePath\\lib\\site-packages\\lldb\\_lldb.pyd $lldbBundlePath\\bin\\liblldb.dll".execute()

            // Add path to bundled Python 3 to `Settings_windows.toml` (it is not added statically since it requires $projectDir)
            "cmd /C echo python = \"${lldbBundlePath.replace("\\", "/")}/bin/python.exe\">> Settings_windows.toml".execute("pretty_printers_tests")
            // Use UTF-8 to properly decode test output in `lldb_batchmode.py`
            "cmd /C set PYTHONIOENCODING=utf8 & $runCommand".execute("pretty_printers_tests")
        } else {
            // TODO: Remove after CLion snapshot builds provide these files with required permissions
            if (isFamily(FAMILY_UNIX)) {
                val lldbLinuxBinDir = File("$projectDir/deps/${clionVersion.replaceFirst("CL", "clion")}/bin/lldb/linux/bin")
                lldbLinuxBinDir.resolve("lldb").setExecutable(true)
                lldbLinuxBinDir.resolve("LLDBFrontend").setExecutable(true)
                lldbLinuxBinDir.resolve("lldb-argdumper").setExecutable(true)
                lldbLinuxBinDir.resolve("lldb-server").setExecutable(true)
            } else if (isFamily(FAMILY_MAC)) {
                val lldbMacBinDir = File("$projectDir/deps/${clionVersion.replaceFirst("CL", "clion")}/bin/lldb/mac")
                lldbMacBinDir.resolve("lldb").setExecutable(true)
                lldbMacBinDir.resolve("LLDBFrontend").setExecutable(true)
                lldbMacBinDir.resolve("LLDB.framework").resolve("LLDB").setExecutable(true)
            }

            runCommand.execute("pretty_printers_tests")
        }

        val gdbBinary = when {
            isFamily(FAMILY_MAC) -> "$projectDir/deps/${clionVersion.replaceFirst("CL", "clion")}/bin/gdb/mac/bin/gdb"
            isFamily(FAMILY_UNIX) -> "$projectDir/deps/${clionVersion.replaceFirst("CL", "clion")}/bin/gdb/linux/bin/gdb"
            isFamily(FAMILY_WINDOWS) -> {
                println("GDB pretty-printers tests are not supported yet for Windows")
                return@doLast
            }
            else -> error("Unsupported OS")
        }
        // TODO: Remove after CLion snapshot builds provide this file with required permissions
        File(gdbBinary).setExecutable(true)

        "cargo run --package pretty_printers_test --bin pretty_printers_test -- gdb $gdbBinary".execute("pretty_printers_tests")
    }
}

task("updateCargoOptions") {
    doLast {
        val file = File("src/main/kotlin/org/rust/cargo/util/CargoOptions.kt")
        file.bufferedWriter().use {
            it.writeln("""
                /*
                 * Use of this source code is governed by the MIT license that can be
                 * found in the LICENSE file.
                 */

                package org.rust.cargo.util

                data class CargoOption(val name: String, val description: String) {
                    val longName: String get() = "--${'$'}name"
                }

            """.trimIndent())
            it.writeCargoOptions("https://doc.rust-lang.org/cargo/commands")
        }
    }
}

task("updateLints") {
    doLast {
        val lints = JsonSlurper().parseText("python3 fetch_lints.py".execute("scripts", print = false)) as List<Map<String, *>>

        fun Map<String, *>.isGroup(): Boolean = get("group") as Boolean
        fun Map<String, *>.isRustcLint(): Boolean = get("rustc") as Boolean
        fun Map<String, *>.getName(): String = get("name") as String

        fun writeLints(path: String, lints: List<Map<String, *>>, variableName: String) {
            val file = File(path)
            val items = lints.sortedWith(compareBy({ !it.isGroup() }, { it.getName() })).joinToString(
                separator = ",\n    "
            ) {
                val name = it.getName()
                val isGroup = it.isGroup()
                "Lint(\"$name\", $isGroup)"
            }
            file.bufferedWriter().use {
                it.writeln("""
/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.completion.lint

val $variableName: List<Lint> = listOf(
    $items
)
""".trim())
            }
        }

        writeLints(
            "src/main/kotlin/org/rust/lang/core/completion/lint/RustcLints.kt",
            lints.filter { it.isRustcLint() },
            "RUSTC_LINTS"
        )
        writeLints(
            "src/main/kotlin/org/rust/lang/core/completion/lint/ClippyLints.kt",
            lints.filter { !it.isRustcLint() },
            "CLIPPY_LINTS"
        )
    }
}

fun Writer.writeCargoOptions(baseUrl: String) {

    data class CargoOption(
        val name: String,
        val description: String
    )

    data class CargoCommand(
        val name: String,
        val description: String,
        val options: List<CargoOption>
    )

    fun fetchCommand(commandUrl: String): CargoCommand {
        val document = Jsoup.connect("$baseUrl/$commandUrl").get()

        val fullCommandDesc = document.select("div[class=sectionbody] > p").text()
        val parts = fullCommandDesc.split(" - ", limit = 2)
        check(parts.size == 2) { "Invalid page format: $baseUrl/$commandUrl$" }
        val commandName = parts.first().removePrefix("cargo-")
        val commandDesc = parts.last()

        val options = document
            .select("dt > strong:matches(^--)")
            .map { option ->
                val optionName = option.text().removePrefix("--")
                val nextSiblings = generateSequence(option.parent()) { it.nextElementSibling() }
                val descElement = nextSiblings.first { it.tagName() == "dd" }
                val fullOptionDesc = descElement.select("p").text()
                val optionDesc = fullOptionDesc.substringBefore(". ").removeSuffix(".")
                CargoOption(optionName, optionDesc)
            }

        return CargoCommand(commandName, commandDesc, options)
    }

    fun fetchCommands(): List<CargoCommand> {
        val document = Jsoup.connect("$baseUrl/cargo.html").get()
        val urls = document.select("dt > a[href]").map { it.attr("href") }
        return urls.map { fetchCommand(it) }
    }

    fun writeEnumVariant(command: CargoCommand, isLast: Boolean) {
        val variantName = command.name.toUpperCase().replace('-', '_')
        val renderedOptions = command.options.joinToString(
            separator = ",\n            ",
            prefix = "\n            ",
            postfix = "\n        "
        ) { "CargoOption(\"${it.name}\", \"\"\"${it.description}\"\"\")" }

        writeln("""
        |    $variantName(
        |        description = "${command.description}",
        |        options = ${if (command.options.isEmpty()) "emptyList()" else "listOf($renderedOptions)"}
        |    )${if (isLast) ";" else ","}
        """.trimMargin())
        writeln()
    }

    val commands = fetchCommands()
    writeln("enum class CargoCommands(val description: String, val options: List<CargoOption>) {")
    for ((index, command) in commands.withIndex()) {
        writeEnumVariant(command, isLast = index == commands.size - 1)
    }
    writeln("    val presentableName: String get() = name.toLowerCase().replace('_', '-')")
    writeln("}")
}

fun Writer.writeln(str: String = "") {
    write(str)
    write("\n")
}

fun hasProp(name: String): Boolean = extra.has(name)

fun prop(name: String): String =
    extra.properties[name] as? String
        ?: error("Property `$name` is not defined in gradle.properties")


inline operator fun <T : Task> T.invoke(a: T.() -> Unit): T = apply(a)

fun String.execute(wd: String? = null, ignoreExitCode: Boolean = false, print: Boolean = true): String =
    split(" ").execute(wd, ignoreExitCode, print)

fun List<String>.execute(wd: String? = null, ignoreExitCode: Boolean = false, print: Boolean = true): String {
    val process = ProcessBuilder(this)
        .also { pb -> wd?.let { pb.directory(File(it)) } }
        .start()
    var result = ""
    val errReader = thread { process.errorStream.bufferedReader().forEachLine { println(it) } }
    val outReader = thread {
        process.inputStream.bufferedReader().forEachLine { line ->
            if (print) {
                println(line)
            }
            result += line
        }
    }
    process.waitFor()
    outReader.join()
    errReader.join()
    if (process.exitValue() != 0 && !ignoreExitCode) error("Non-zero exit status for `$this`")
    return result
}

fun File.isPluginJar(): Boolean {
    if (!isFile) return false
    if (extension != "jar") return false
    return zipTree(this).files.any { it.isManifestFile() }
}

fun File.isManifestFile(): Boolean {
    if (extension != "xml") return false
    val rootNode = try {
        val parser = XmlParser()
        parser.parse(this)
    } catch (e: Exception) {
        logger.error("Failed to parse $path", e)
        return false
    }
    return rootNode.name() == "idea-plugin"
}

fun <T : ModuleDependency> T.excludeKotlinDeps() {
    exclude(module = "kotlin-reflect")
    exclude(module = "kotlin-runtime")
    exclude(module = "kotlin-stdlib")
    exclude(module = "kotlin-stdlib-common")
    exclude(module = "kotlin-stdlib-jdk8")
    exclude(module = "kotlinx-serialization-core")
}
