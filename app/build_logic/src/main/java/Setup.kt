/**
 * Gradle build configuration helpers for all Magisk Android modules.
 *
 * <p>Provides shared setup for compile SDK, NDK, packaging, and variant-specific tasks
 * such as JNI lib syncing, resource staging, asset bundling, and APK post-processing
 * (signing and comment embedding).
 *
 * <p>Key entry points:
 * <ul>
 *   <li>{@link #setupCommon} — shared Android SDK/NDK configuration</li>
 *   <li>{@link #setupCoreLib} — config for the {@code :core} library module</li>
 *   <li>{@link #setupAppCommon} — config shared by the APK-producing modules</li>
 *   <li>{@link #setupMainApk} — config for the main Magisk APK ({@code :apk})</li>
 * </ul>
 */
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.instrumentation.FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.apache.tools.ant.filters.FixCrLfFilter
import org.gradle.api.Action
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.StopExecutionException
import org.gradle.api.tasks.Sync
import org.gradle.kotlin.dsl.assign
import org.gradle.kotlin.dsl.filter
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.register
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.HexFormat

private fun Project.android(configure: Action<CommonExtension>) =
    extensions.configure("android", configure)

private fun Project.androidApp(configure: Action<ApplicationExtension>) =
    extensions.configure("android", configure)

internal val Project.androidApp: ApplicationExtension
    get() = extensions["android"] as ApplicationExtension

private fun Project.androidComponents(configure: Action<AndroidComponentsExtension<*, *, *>>) =
    extensions.configure(AndroidComponentsExtension::class.java, configure)

private val Project.androidComponents: AndroidComponentsExtension<*, *, *>
    get() = extensions["androidComponents"] as AndroidComponentsExtension<*, *, *>

internal fun Project.androidAppComponents(configure: Action<ApplicationAndroidComponentsExtension>) =
    extensions.configure(ApplicationAndroidComponentsExtension::class.java, configure)

/**
 * Configures the common Android SDK/NDK settings for all Magisk modules.
 * - compileSdk 37, buildTools 37.0.0
 * - NDK r30 (custom path)
 * - minSdk 23
 * - Java 21 source/target compatibility
 * - Aggressive META-INF and resource exclusion packaging rules
 */
fun Project.setupCommon() {
    android {
        compileSdk {
            version = release(37)
        }
        buildToolsVersion = "37.0.0"
        ndkPath = "${androidComponents.sdkComponents.sdkDirectory.get().asFile}/ndk/magisk"
        ndkVersion = "30.0.14904198"

        defaultConfig.apply {
            minSdk = 23
        }

        compileOptions.apply {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }

        packaging.apply {
            resources {
                excludes += arrayOf(
                    "/META-INF/*",
                    "/META-INF/androidx/**",
                    "/META-INF/versions/**",
                    "/META-INF/native-image/**",
                    "/org/bouncycastle/**",
                    "/org/apache/commons/**",
                    "/kotlin/**",
                    "/*.txt",
                    "/*.json",
                    "**/*.bin",
                    "**/*.proto",
                )
            }
        }
    }
}

private fun Project.downloadFile(url: String, checksum: String): File {
    val file = layout.buildDirectory.file(checksum).get().asFile
    if (file.exists()) {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { md.update(it.readAllBytes()) }
        val hash = HexFormat.of().formatHex(md.digest())
        if (hash != checksum) {
            file.delete()
        }
    }
    if (!file.exists()) {
        file.parentFile.mkdirs()
        URI(url).toURL().openStream().use { dl ->
            file.outputStream().use {
                dl.copyTo(it)
            }
        }
    }
    return file
}

const val BUSYBOX_DOWNLOAD_URL =
    "https://github.com/topjohnwu/magisk-files/releases/download/files/busybox-1.36.1.1.zip"
const val BUSYBOX_ZIP_CHECKSUM =
    "b4d0551feabaf314e53c79316c980e8f66432e9fb91a69dbbf10a93564b40951"

private abstract class SyncWithDir : Sync() {
    @get:OutputDirectory
    abstract val output_folder: DirectoryProperty
}

/**
 * Configures the {@code :core} library module. In addition to common settings:
 * <ul>
 *   <li>Syncs native binaries (magiskboot, magiskinit, magiskpolicy, magisk, libinit-ld.so)
 *       from {@code native/out/$abi} for each ABI</li>
 *   <li>Downloads and extracts BusyBox</li>
 *   <li>Copies flash scripts as META-INF resources</li>
 *   <li>Stubs version constants into {@code util_functions.sh}</li>
 *   <li>Includes the built stub APK as an asset</li>
 * </ul>
 */
fun Project.setupCoreLib() {
    setupCommon()

    val abi_list = Config.abi_list

    androidComponents {
        onVariants { variant ->
            val variant_name = variant.name
            val variant_capped = variant_name.replaceFirstChar { it.uppercase() }

            val sync_libs = tasks.register("sync${variant_capped}JniLibs", SyncWithDir::class) {
                output_folder.set(layout.buildDirectory.dir("$variant_name/jniLibs"))
                into(output_folder)

                for (abi in abi_list) {
                    into(abi) {
                        from(rootFile("native/out/$abi")) {
                            include("magiskboot", "magiskinit", "magiskpolicy", "magisk", "libinit-ld.so")
                            rename { if (it.endsWith(".so")) it else "lib$it.so" }
                        }
                    }
                }
                from(zipTree(downloadFile(BUSYBOX_DOWNLOAD_URL, BUSYBOX_ZIP_CHECKSUM)))
                include(abi_list.map { "$it/libbusybox.so" })
                onlyIf {
                    if (inputs.sourceFiles.files.size != abi_list.size * 6)
                        throw StopExecutionException("Please build binaries first! (./build.py binary)")
                    true
                }
            }
            variant.sources.jniLibs
                ?.addGeneratedSourceDirectory(sync_libs, SyncWithDir::output_folder)

            val sync_resources = tasks.register("sync${variant_capped}Resources", SyncWithDir::class) {
                output_folder.set(layout.buildDirectory.dir("$variant_name/resources"))
                into(output_folder)

                into("META-INF/com/google/android") {
                    from(rootFile("scripts/update_binary.sh")) {
                        rename { "update-binary" }
                    }
                    from(rootFile("scripts/flash_script.sh")) {
                        rename { "updater-script" }
                    }
                }
            }
            variant.sources.resources
                ?.addGeneratedSourceDirectory(sync_resources, SyncWithDir::output_folder)

            val stub_task = tasks.getByPath(":stub:transform${variant_capped}Apk")
            val sync_assets = tasks.register("sync${variant_capped}Assets", SyncWithDir::class) {
                output_folder.set(layout.buildDirectory.dir("$variant_name/assets"))
                into(output_folder)

                inputs.property("version", Config.version)
                inputs.property("versionCode", Config.versionCode)
                from(rootFile("scripts")) {
                    include("util_functions.sh", "boot_patch.sh", "addon.d.sh",
                        "app_functions.sh", "uninstaller.sh", "module_installer.sh")
                }
                from(rootFile("tools/bootctl"))
                into("chromeos") {
                    from(rootFile("tools/futility"))
                    from(rootFile("tools/keys")) {
                        include("kernel_data_key.vbprivk", "kernel.keyblock")
                    }
                }
                from(stub_task) {
                    include { it.name.endsWith(".apk") }
                    rename { "stub.apk" }
                }
                filesMatching("**/util_functions.sh") {
                    filter {
                        it.replace(
                            "#MAGISK_VERSION_STUB",
                            "MAGISK_VER='${Config.version}'\nMAGISK_VER_CODE=${Config.versionCode}"
                        )
                    }
                    filter<FixCrLfFilter>("eol" to FixCrLfFilter.CrLf.newInstance("lf"))
                }
            }
            variant.sources.assets
                ?.addGeneratedSourceDirectory(sync_assets, SyncWithDir::output_folder)
        }
    }
}

/**
 * Configures settings common to all APK-producing modules ({@code :apk}, {@code :stub}).
 * Includes signing config, targetSdk, ProGuard, lint, dependency info suppression,
 * legacy JNI lib packaging, and a post-processing APK transformation that embeds the
 * version metadata in the ZIP End of Central Directory comment.
 */
fun Project.setupAppCommon() {
    setupCommon()

    androidApp {
        signingConfigs {
            Config["keyStore"]?.also {
                create("config") {
                    storeFile = rootFile(it)
                    storePassword = Config["keyStorePass"]
                    keyAlias = Config["keyAlias"]
                    keyPassword = Config["keyPass"]
                    storeType = "PKCS12"
                }
            }
        }

        defaultConfig {
            targetSdk = 37
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt")
            )
        }

        buildTypes {
            val config = signingConfigs.findByName("config") ?: signingConfigs["debug"]
            debug {
                signingConfig = config
            }
            release {
                signingConfig = config
            }
        }

        lint {
            abortOnError = false
            checkReleaseBuilds = false
            disable += "MissingTranslation"
        }

        dependenciesInfo {
            includeInApk = false
        }

        packaging {
            jniLibs {
                useLegacyPackaging = true
            }
        }
    }

    androidAppComponents {
        onVariants { variant ->
            val comment_task = tasks.register(
                "transform${variant.name.replaceFirstChar { it.uppercase() }}Apk",
                TransformApkTask::class.java
            )
            val transformation_request = variant.artifacts.use(comment_task)
                .wiredWithDirectories(TransformApkTask::apk_folder, TransformApkTask::out_folder)
                .toTransformMany(SingleArtifact.APK)
            val signingConfig = androidApp.buildTypes.getByName(variant.buildType!!).signingConfig
            comment_task.configure {
                this.transformation_request = transformation_request
                this.signingConfig = signingConfig
                this.out_folder.set(layout.buildDirectory.dir("outputs/apk/${variant.name}"))
                // Always add a transformation to set comments on the APK
                this.transformations.add {
                    it.eocdComment = ("version=${Config.version}\n" +
                            "versionCode=${Config.versionCode}\n" +
                            "stubVersion=${Config.stub_version}\n").toByteArray()
                }
            }

        }
    }
}

/**
 * Configures the main Magisk APK ({@code :apk}).
 * Adds app-specific defaults (namespace, applicationId, version, ABI filters)
 * and registers the {@link DesugarClassVisitorFactory} ASM instrumentation.
 */
fun Project.setupMainApk() {
    setupAppCommon()

    androidApp {
        namespace = "pro.magisk"

        defaultConfig {
            applicationId = "pro.magisk"
            vectorDrawables.useSupportLibrary = true
            versionName = Config.version
            versionCode = Config.versionCode
            ndk {
                abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64", "riscv64")
                debugSymbolLevel = "FULL"
            }
        }
    }

    androidComponents {
        onVariants { variant ->
            variant.instrumentation.apply {
                setAsmFramesComputationMode(COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS)
                transformClassesWith(
                    DesugarClassVisitorFactory::class.java, InstrumentationScope.ALL) {}
            }
        }
    }
}