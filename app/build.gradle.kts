@file:Suppress("UnstableApiUsage")

import com.itsaky.androidide.build.config.BuildConfig
import com.itsaky.androidide.desugaring.utils.JavaIOReplacements.applyJavaIOReplacements
import com.itsaky.androidide.plugins.AndroidIDEAssetsPlugin
import com.itsaky.androidide.plugins.tasks.AddFileToAssetsTask
import org.adfa.constants.GRADLE_API_NAME_JAR_BR
import org.adfa.constants.GRADLE_API_NAME_JAR_ZIP
import org.adfa.constants.GRADLE_DISTRIBUTION_ARCHIVE_NAME
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("kotlin-kapt")
    id("kotlin-parcelize")
    id("androidx.navigation.safeargs.kotlin")
    id("com.itsaky.androidide.desugaring")
    alias(libs.plugins.sentry)
    alias(libs.plugins.google.services)
    alias(libs.plugins.kotlin.compose)
}

buildscript {
    dependencies {
        classpath(libs.composite.desugaringCore)
        classpath(libs.org.json)
    }
}

evaluationDependsOn(":subprojects:tooling-api-impl")
evaluationDependsOn(":logsender")

fun propOrEnv(name: String): String =
    project.findProperty(name) as String?
        ?: System.getenv(name)
        ?: ""

val props =
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) load(file.inputStream())
    }

val glitchtipDsn = props.getProperty("glitchtipDsn") ?: propOrEnv("GLITCHTIP_DSN")

apply {
    plugin(AndroidIDEAssetsPlugin::class.java)
}

// --- Task Hoisting: Registering createPluginArtifactsZip early to avoid TaskNotFoundException ---
tasks.register<Zip>("createPluginArtifactsZip") {
    // Ensure the dependency task exists or is handled lazily
    if (tasks.findByName("copyPluginApiJarToAssets") != null) {
        dependsOn("copyPluginApiJarToAssets")
    }
    dependsOn(gradle.includedBuild("plugin-builder").task(":jar"))

    from(rootProject.file("assets/plugin-api.jar"))
    from(rootProject.file("plugin-api/plugin-builder/build/libs/plugin-builder-1.0.0.jar")) {
        rename { "gradle-plugin.jar" }
    }

    archiveFileName.set("plugin-artifacts.zip")
    destinationDirectory.set(rootProject.file("assets"))
}

android {
    namespace = BuildConfig.PACKAGE_NAME

    defaultConfig {
        applicationId = BuildConfig.PACKAGE_NAME
        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunnerArguments["class"] = "com.itsaky.androidide.OrderedTestSuite"
    }
}

// --- Safe Lazy Provider References for Variant Tasks ---
androidComponents {
    onVariants {
        val variantName = it.name.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase() else char.toString() }
        
        val copyArtifactsTask = tasks.register("copy${variantName}PluginArtifacts") {
            // Use tasks.named to ensure the provider is resolved during the execution/task-graph phase
            dependsOn(tasks.named("createPluginArtifactsZip"))
            
            val zipFile = tasks.named<Zip>("createPluginArtifactsZip").flatMap { z -> z.archiveFile }
            inputs.file(zipFile)
            // Logic to ensure assets are updated for the specific variant
        }

        // Hook into the asset merging process safely
        tasks.named("merge${variantName}Assets").configure {
            dependsOn(copyArtifactsTask)
        }
    }
}
