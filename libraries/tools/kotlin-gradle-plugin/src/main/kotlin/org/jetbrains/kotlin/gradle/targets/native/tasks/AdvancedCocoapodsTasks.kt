/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.native.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.api.tasks.Optional
import org.jetbrains.kotlin.gradle.plugin.cocoapods.CocoapodsExtension.*
import org.jetbrains.kotlin.gradle.plugin.cocoapods.CocoapodsExtension.CocoapodsDependency.PodspecLocation.*
import org.jetbrains.kotlin.gradle.plugin.cocoapods.cocoapodsBuildDirs
import org.jetbrains.kotlin.gradle.plugin.cocoapods.splitQuotedArgs
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URI
import java.util.*

internal val KotlinNativeTarget.toBuildSettingsFileName: String
    get() = "build-settings-$disambiguationClassifier.properties"

internal val KotlinNativeTarget.toValidSDK: String
    get() = when (konanTarget) {
        KonanTarget.IOS_X64 -> "iphonesimulator"
        KonanTarget.IOS_ARM32, KonanTarget.IOS_ARM64 -> "iphoneos"
        KonanTarget.WATCHOS_X86, KonanTarget.WATCHOS_X64 -> "watchsimulator"
        KonanTarget.WATCHOS_ARM32, KonanTarget.WATCHOS_ARM64 -> "watchos"
        KonanTarget.TVOS_X64 -> "appletvsimulator"
        KonanTarget.TVOS_ARM64 -> "appletvos"
        KonanTarget.MACOS_X64 -> "macosx"
        else -> throw IllegalArgumentException("Bad target ${konanTarget.name}.")
    }

internal val KotlinNativeTarget.platformLiteral: String
    get() = when (konanTarget.family) {
        Family.OSX -> "macos"
        Family.IOS -> "ios"
        Family.TVOS -> "tvos"
        Family.WATCHOS -> "watchos"
        else -> throw IllegalArgumentException("Unsupported native target '${konanTarget.name}'")
    }

val CocoapodsDependency.schemeName: String
    get() = name.split("/")[0]

/**
 * The task takes the path to the Podfile and calls `pod install`
 * to obtain sources or artifacts for the declared dependencies.
 * This task is a part of CocoaPods integration infrastructure.
 */
open class PodInstallTask : DefaultTask() {
    init {
        onlyIf { podfile.orNull != null }
    }

    @get:Optional
    @get:Input
    internal val podfile = project.objects.property(File::class.java)

    @get:Optional
    @get:OutputDirectory
    internal val podsXcodeProjDirProvider: Provider<File>?
        get() = podfile.orNull?.let {
            project.provider { it.parentFile.resolve("Pods").resolve("Pods.xcodeproj") }
        }


    @TaskAction
    fun doPodInstall() {
        podfile.orNull?.parentFile?.also { podfileDir ->
            val podInstallProcess = ProcessBuilder("pod", "install").apply {
                directory(podfileDir)
            }.start()
            val podInstallRetCode = podInstallProcess.waitFor()
            val podInstallOutput = podInstallProcess.inputStream.use { it.reader().readText() }

            check(podInstallRetCode == 0) {
                listOf(
                    "Executing of 'pod install' failed with code $podInstallRetCode.",
                    "Error message:",
                    podInstallOutput
                ).joinToString("\n")
            }
            with(podsXcodeProjDirProvider) {
                check(this != null && get().exists() && get().isDirectory) {
                    "The directory 'Pods/Pods.xcodeproj' was not created as a result of the `pod install` call."
                }
            }
        }
    }
}

abstract class DownloadCocoapodsTask : DefaultTask() {
    @get:Input
    internal lateinit var podName: Provider<String>
}

open class PodDownloadUrlTask : DownloadCocoapodsTask() {

    @get:Nested
    internal lateinit var podspecLocation: Provider<Url>

    @get:Internal
    internal val urlDir = project.provider {
        project.cocoapodsBuildDirs.synthetic("url")
    }

    @get:OutputFile
    internal val podspecFile = project.provider {
        urlDir.get().resolve("${podName.get()}.podspec")
    }

    @TaskAction
    fun download() {
        val curlCommand = listOf(
            "curl",
            "${podspecLocation.get().url}",
            "-f",
            "-L",
            "-o", podspecFile.get().name,
            "--create-dirs",
            "--netrc-optional",
            "--retry", "2"
        )
        val configProcess: ProcessBuilder.() -> Unit = { directory(urlDir.get()) }
        runCommand(curlCommand, configProcess)
    }
}


open class PodDownloadGitTask : DownloadCocoapodsTask() {

    @get:Nested
    internal lateinit var podspecLocation: Provider<Git>

    @get:Internal
    internal val gitDir = project.provider {
        project.cocoapodsBuildDirs.synthetic("git")
    }

    @get:OutputDirectory
    internal val gitPodDir = project.provider {
        project.cocoapodsBuildDirs.synthetic("git").resolve(podName.get())
    }

    @TaskAction
    fun download() {
        gitDir.get().resolve(podName.get()).deleteRecursively()
        val git = podspecLocation.get()
        val branch = git.tag ?: git.branch
        val commit = git.commit
        val url = git.url
        try {
            when {
                commit != null -> {
                    retrieveCommit(url, commit)
                }
                branch != null -> {
                    cloneShallow(url, branch)
                }
                else -> {
                    cloneHead(git)
                }
            }
        } catch (e: IllegalStateException) {
            fallback(git)
        }
    }

    private fun retrieveCommit(url: URI, commit: String) {
        val initCommand = listOf(
            "git",
            "init"
        )
        val repo = gitDir.get().resolve(podName.get())
        repo.mkdir()
        val configProcess: ProcessBuilder.() -> Unit = { directory(repo) }
        runCommand(initCommand, configProcess)

        val fetchCommand = listOf(
            "git",
            "fetch",
            "--depth", "1",
            "$url",
            commit
        )
        runCommand(fetchCommand, configProcess)

        val checkoutCommand = listOf(
            "git",
            "checkout",
            "FETCH_HEAD"
        )
        runCommand(checkoutCommand, configProcess)
    }

    private fun cloneShallow(url: URI, branch: String) {
        val shallowCloneCommand = listOf(
            "git",
            "clone",
            "$url",
            podName.get(),
            "--branch", branch,
            "--depth", "1"
        )
        val configProcess: ProcessBuilder.() -> Unit = { directory(gitDir.get()) }
        runCommand(shallowCloneCommand, configProcess)
    }

    private fun cloneHead(podspecLocation: Git) {
        val cloneHeadCommand = listOf(
            "git",
            "clone",
            "${podspecLocation.url}",
            podName.get(),
            "--depth", "1"
        )
        val configProcess: ProcessBuilder.() -> Unit = { directory(gitDir.get()) }
        runCommand(cloneHeadCommand, configProcess)
    }

    private fun fallback(podspecLocation: Git) {
        // removing any traces of other commands
        gitDir.get().resolve(podName.get()).deleteRecursively()
        val cloneAllCommand = listOf(
            "git",
            "clone",
            "${podspecLocation.url}",
            podName.get()
        )
        val configProcess: ProcessBuilder.() -> Unit = { directory(gitDir.get()) }
        runCommand(cloneAllCommand, configProcess)
    }
}

private fun runCommand(
    command: List<String>,
    processConfiguration: ((ProcessBuilder.() -> Unit))? = null,
    errorHandler: ((retCode: Int, process: Process) -> Unit)? = null
): String {
    val process = ProcessBuilder(command)
        .apply {
            if (processConfiguration != null) {
                this.processConfiguration()
            }
        }.start()

    val retCode = process.waitFor()
    if (retCode != 0) {
        errorHandler?.invoke(retCode, process) ?: throwStandardException(command, retCode, process)
    }
    return process.inputStream.use {
        it.reader().readText()
    }
}

private fun throwStandardException(command: List<String>, retCode: Int, process: Process) {
    val errorText = process.errorStream.use {
        it.reader().readText()
    }
    throw IllegalStateException(
        "Executing of '${command.joinToString(" ")}' failed with code $retCode and message: $errorText"
    )
}

abstract class CocoapodsWithSyntheticTask : DefaultTask() {
    @get:Nested
    val pods = project.objects.listProperty(CocoapodsDependency::class.java)

    @get:Internal
    internal lateinit var kotlinNativeTarget: Provider<KotlinNativeTarget>

    init {
        onlyIf {
            pods.get().isNotEmpty()
        }
    }
}

/**
 * The task takes the path to the .podspec file and calls `pod gen`
 * to create synthetic xcode project and workspace.
 */
open class PodGenTask : CocoapodsWithSyntheticTask() {

    @get:InputFile
    internal lateinit var podspec: Provider<File>

    @get:Nested
    internal lateinit var sources: Provider<Sources>

    @get:OutputDirectory
    internal val podsXcodeProjDir: Provider<File>
        get() = project.provider {
            project.cocoapodsBuildDirs.synthetic(kotlinNativeTarget.get())
                .resolve(podspec.get().nameWithoutExtension)
                .resolve("Pods")
                .resolve("Pods.xcodeproj")
        }

    @TaskAction
    fun generate() {
        val podspecDir = podspec.get().parentFile
        val localPodspecPaths = pods.get()
            .mapNotNull { (it.podspec as? Path)?.dir?.absolutePath }
            .toMutableList()
        localPodspecPaths += pods.get()
            .filter { it.podspec is Git }
            .map { project.cocoapodsBuildDirs.synthetic("git").resolve(it.name).absolutePath }
        localPodspecPaths += project.cocoapodsBuildDirs.synthetic("url").absolutePath

        val sources = sources.get().getAll().toMutableList()
        sources += URI("https://cdn.cocoapods.org")

        val podGenProcessArgs = listOfNotNull(
            "pod", "gen",
            "--platforms=${kotlinNativeTarget.get().platformLiteral}",
            "--gen-directory=${project.cocoapodsBuildDirs.synthetic(kotlinNativeTarget.get()).absolutePath}",
            localPodspecPaths.takeIf { it.isNotEmpty() }?.joinToString(separator = ",")?.let { "--local-sources=$it" },
            sources.takeIf { it.isNotEmpty() }?.joinToString(separator = ",")?.let { "--sources=$it" },
            podspec.get().name
        )

        val podGenProcess = ProcessBuilder(podGenProcessArgs).apply {
            directory(podspecDir)
        }.start()
        val podGenRetCode = podGenProcess.waitFor()
        val outputText = podGenProcess.inputStream.use { it.reader().readText() }

        check(podGenRetCode == 0) {
            listOfNotNull(
                "Executing of '${podGenProcessArgs.joinToString(" ")}' failed with code $podGenRetCode and message:",
                outputText,
                outputText.takeIf {
                    it.contains("deployment target")
                            || it.contains("requested platforms: [\"${kotlinNativeTarget.get().platformLiteral}\"]")
                }?.let {
                    """
                        Tip: try to configure deployment_target for ALL targets as follows:
                        cocoapods {
                            ...
                            ${kotlinNativeTarget.get().konanTarget.family.name.toLowerCase()}.deploymentTarget = "..."
                            ...
                        }
                    """.trimIndent()
                }
            ).joinToString("\n")
        }

        val podsXcprojFile = podsXcodeProjDir.get()
        check(podsXcprojFile.exists() && podsXcprojFile.isDirectory) {
            "The directory '${podsXcprojFile.path}' was not created as a result of the `pod gen` call."
        }
    }
}


open class PodSetupBuildTask : CocoapodsWithSyntheticTask() {

    @get:InputDirectory
    internal lateinit var podsXcodeProjDir: Provider<File>

    @get:Input
    lateinit var frameworkName: Provider<String>

    @get:OutputFile
    internal val buildSettingsFile: Provider<File> = project.provider {
        project.cocoapodsBuildDirs
            .buildSettings
            .resolve(kotlinNativeTarget.get().toBuildSettingsFileName)
    }

    @get:OutputDirectory
    val buildSettingsDir = project.provider { project.cocoapodsBuildDirs.buildSettings }

    @TaskAction
    fun setupBuild() {
        val podsXcodeProjDir = podsXcodeProjDir.get()

        val buildSettingsReceivingCommand = listOf(
            "xcodebuild", "-showBuildSettings",
            "-project", podsXcodeProjDir.name,
            "-scheme", frameworkName.get(),
            "-sdk", kotlinNativeTarget.get().toValidSDK
        )

        val buildSettingsProcess = ProcessBuilder(buildSettingsReceivingCommand)
            .apply {
                directory(podsXcodeProjDir.parentFile)
            }.start()

        val buildSettingsRetCode = buildSettingsProcess.waitFor()
        check(buildSettingsRetCode == 0) {
            listOf(
                "Executing of '${buildSettingsReceivingCommand.joinToString(" ")}' failed with code $buildSettingsRetCode and message:",
                buildSettingsProcess.errorStream.use { it.reader().readText() }
            ).joinToString("\n")
        }

        val stdOut = buildSettingsProcess.inputStream

        val buildSettingsProperties = PodBuildSettingsProperties.readSettingsFromStream(stdOut)
        buildSettingsFile.get().let { buildSettingsProperties.writeSettings(it, pods.get()) }
    }
}

/**
 * The task compiles external cocoa pods sources.
 */
open class PodBuildTask : CocoapodsWithSyntheticTask() {

    @get:InputDirectory
    internal lateinit var podsXcodeProjDir: Provider<File>

    @get:InputFile
    internal lateinit var buildSettingsFile: Provider<File>

    @get:OutputDirectory
    internal var buildDirProvider: Provider<File>? = project.provider {
        project.file(
            PodBuildSettingsProperties.readSettingsFromStream(
                FileInputStream(buildSettingsFile.get())
            ).buildDir
        )
    }

    @TaskAction
    fun buildDependencies() {
        val podBuildSettings = PodBuildSettingsProperties.readSettingsFromStream(
            FileInputStream(buildSettingsFile.get())
        )

        val podsXcodeProjDir = podsXcodeProjDir.get()
        pods.get().forEach {

            val podXcodeBuildCommand = listOf(
                "xcodebuild",
                "-project", podsXcodeProjDir.name,
                "-scheme", it.schemeName,
                "-sdk", kotlinNativeTarget.get().toValidSDK,
                "-configuration", podBuildSettings.configuration
            )

            val podBuildProcess = ProcessBuilder(podXcodeBuildCommand)
                .apply {
                    directory(podsXcodeProjDir.parentFile)
                    inheritIO()
                }.start()

            val podBuildRetCode = podBuildProcess.waitFor()
            check(podBuildRetCode == 0) {
                listOf(
                    "Executing of '${podXcodeBuildCommand.joinToString(" ")}' failed with code $podBuildRetCode and message:",
                    podBuildProcess.errorStream.use { it.reader().readText() }
                ).joinToString("\n")
            }
        }
    }
}

internal data class PodBuildSettingsProperties(
    internal val buildDir: String,
    internal val configuration: String,
    internal val cflags: String? = null,
    internal val headerPaths: String? = null,
    internal val frameworkPaths: String? = null
) {

    fun writeSettings(
        buildSettingsFile: File,
        pods: MutableList<CocoapodsDependency>
    ) {
        buildSettingsFile.parentFile.mkdirs()
        buildSettingsFile.delete()
        buildSettingsFile.createNewFile()

        check(buildSettingsFile.exists()) { "Unable to create file ${buildSettingsFile.path}!" }

        with(buildSettingsFile) {
            appendText("$BUILD_DIR=$buildDir\n")
            appendText("$CONFIGURATION=$configuration\n")
            cflags?.let { appendText("$OTHER_CFLAGS=$it\n") }
            headerPaths?.let { appendText("$HEADER_SEARCH_PATHS=$it") }
        }

        if (frameworkPaths != null) {
            val frameworkPathsCollection = frameworkPaths.splitQuotedArgs()
            val podsSchemeNames = mutableSetOf<String>()
            for (pod in pods) {
                if (pod.schemeName in podsSchemeNames) {
                    continue
                }
                podsSchemeNames.add(pod.schemeName)

                val buildSettingsName = buildSettingsFile.nameWithoutExtension
                val podSettings = buildSettingsFile.resolveSibling("$buildSettingsName-${pod.schemeName}.settings")
                podSettings.delete()
                podSettings.createNewFile()

                val frameworkPath = frameworkPathsCollection.find { it.contains(pod.schemeName) }
                frameworkPath?.let { podSettings.appendText(it) }
            }
        }
    }

//    fun writeFrame

    companion object {
        const val BUILD_DIR: String = "BUILD_DIR"
        const val CONFIGURATION: String = "CONFIGURATION"
        const val OTHER_CFLAGS: String = "OTHER_CFLAGS"
        const val HEADER_SEARCH_PATHS: String = "HEADER_SEARCH_PATHS"
        const val FRAMEWORK_SEARCH_PATHS: String = "FRAMEWORK_SEARCH_PATHS"

        fun readSettingsFromStream(inputStream: InputStream): PodBuildSettingsProperties {
            with(Properties()) {
                load(inputStream)
                return PodBuildSettingsProperties(
                    getProperty(BUILD_DIR),
                    getProperty(CONFIGURATION),
                    getProperty(OTHER_CFLAGS),
                    getProperty(HEADER_SEARCH_PATHS),
                    getProperty(FRAMEWORK_SEARCH_PATHS)
                )
            }
        }
    }
}
