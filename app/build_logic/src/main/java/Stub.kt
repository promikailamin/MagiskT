/**
 * Stub APK generation logic for Magisk's dynamic-loading architecture.
 *
 * <p>The stub APK is a minimal proxy that the Magisk app installs during initial setup.
 * It contains:
 * <ul>
 *   <li>Randomly named Java classes that extend {@code DelegateComponentFactory} and
 *       {@code StubApplication} — the names are generated from a shuffled dictionary to
 *       avoid static detection.</li>
 *   <li>Encrypted {@code resources.arsc} (AES/CBC) so external APKs cannot read resource IDs.</li>
 *   <li>A modified {@code AndroidManifest.xml} with component placeholders shuffled in a
 *       non-deterministic order (except on CI where order is reproducible).</li>
 * </ul>
 */
import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.assign
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.security.SecureRandom
import java.util.Random
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipFile
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.asKotlinRandom

private val k_r_a_n_d_o_m get() = RANDOM.asKotlinRandom()

// Shuffled dictionary pools for generating random class names (1-, 2-, and 3-character)
private val c1 = mutableListOf<String>()
private val c2 = mutableListOf<String>()
private val c3 = mutableListOf<String>()

/**
 * Initializes the shared random generator and builds a shuffled dictionary file.
 *
 * Uses {@link SecureRandom} for local builds (non-deterministic) or a seed-based
 * {@link Random} for CI builds (reproducible). Generates all possible 1/2/3 character
 * alphanumeric combinations (excluding 'a' and 'A' for single-letter names), shuffles
 * them, and writes them to {@code dict.txt} for diagnostic/reproducibility purposes.
 */
fun init_random(dict: File) {
    RANDOM = if (RAND_SEED != 0) Random(RAND_SEED.toLong()) else SecureRandom()
    c1.clear()
    c2.clear()
    c3.clear()
    for (a in chain('a'..'z', 'A'..'Z')) {
        if (a != 'a' && a != 'A') {
            c1.add("$a")
        }
        for (b in chain('a'..'z', 'A'..'Z', '0'..'9')) {
            c2.add("$a$b")
            for (c in chain('a'..'z', 'A'..'Z', '0'..'9')) {
                c3.add("$a$b$c")
            }
        }
    }
    c1.shuffle(RANDOM)
    c2.shuffle(RANDOM)
    c3.shuffle(RANDOM)
    PrintStream(dict).use {
        for (c in chain(c1, c2, c3)) {
            it.println(c)
        }
    }
}

private fun <T> chain(vararg iters: Iterable<T>) = sequence {
    iters.forEach { it.forEach { v -> yield(v) } }
}

private fun PrintStream.byteField(name: String, bytes: ByteArray) {
    println("public static byte[] $name() {")
    print("byte[] buf = {")
    print(bytes.joinToString(",") { it.toString() })
    println("};")
    println("return buf;")
    println("}")
}

@CacheableTask
private abstract class ManifestUpdater: DefaultTask() {
    @get:Input
    abstract val applicationId: Property<String>

    @get:Input
    abstract val factory_class: Property<String>

    @get:Input
    abstract val app_class: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val merged_manifest: RegularFileProperty

    @get:OutputFile
    abstract val output_manifest: RegularFileProperty

    @TaskAction
    fun task_action() {
        fun String.ind(level: Int) = replaceIndentByMargin("    ".repeat(level))

        val cmp_list = mutableListOf<String>()

        cmp_list.add("""
            |<provider
            |    android:name="x.COMPONENT_PLACEHOLDER_0"
            |    android:authorities="${'$'}{applicationId}.provider"
            |    android:directBootAware="true"
            |    android:exported="false"
            |    android:grantUriPermissions="true" />""".ind(2)
        )

        cmp_list.add("""
            |<receiver
            |    android:name="x.COMPONENT_PLACEHOLDER_1"
            |    android:exported="false">
            |    <intent-filter>
            |        <action android:name="android.intent.action.LOCALE_CHANGED" />
            |        <action android:name="android.intent.action.UID_REMOVED" />
            |        <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            |    </intent-filter>
            |    <intent-filter>
            |        <action android:name="android.intent.action.PACKAGE_REPLACED" />
            |        <action android:name="android.intent.action.PACKAGE_FULLY_REMOVED" />
            |
            |        <data android:scheme="package" />
            |    </intent-filter>
            |</receiver>""".ind(2)
        )

        cmp_list.add("""
            |<activity
            |    android:name="x.COMPONENT_PLACEHOLDER_2"
            |    android:exported="true">
            |    <intent-filter>
            |        <action android:name="android.intent.action.MAIN" />
            |        <category android:name="android.intent.category.LAUNCHER" />
            |    </intent-filter>
            |</activity>""".ind(2)
        )

        cmp_list.add("""
            |<activity
            |    android:name="x.COMPONENT_PLACEHOLDER_3"
            |    android:directBootAware="true"
            |    android:exported="false"
            |    android:taskAffinity="">
            |    <intent-filter>
            |        <action android:name="android.intent.action.VIEW"/>
            |        <category android:name="android.intent.category.DEFAULT"/>
            |    </intent-filter>
            |</activity>""".ind(2)
        )

        cmp_list.add("""
            |<service
            |    android:name="x.COMPONENT_PLACEHOLDER_4"
            |    android:exported="false"
            |    android:foregroundServiceType="dataSync" />""".ind(2)
        )

        cmp_list.add("""
            |<service
            |    android:name="x.COMPONENT_PLACEHOLDER_5"
            |    android:exported="false"
            |    android:permission="android.permission.BIND_JOB_SERVICE" />""".ind(2)
        )

        // Shuffle the order of the components
        cmp_list.shuffle(RANDOM)
        val components = cmp_list.joinToString("\n\n")
            .replace("\${applicationId}", applicationId.get())
        val manifest = merged_manifest.asFile.get().readText().replace(Regex(".*\\<application"), """
            |<application
            |    android:appComponentFactory="${factory_class.get()}"
            |    android:name="${app_class.get()}"""".ind(1)
        ).replace(Regex(".*\\<\\/application"), "$components\n    </application")
        output_manifest.get().asFile.writeText(manifest)
    }
}

private fun gen_stub_classes(out_dir: File): Pair<String, String> {
    val class_name_generator = sequence {
        fun not_java_keyword(name: String) = when (name) {
            "do", "if", "for", "int", "new", "try" -> false
            else -> true
        }

        fun List<String>.process() = asSequence()
            .filter(::not_java_keyword)
            // Distinct by lower case to support case insensitive file systems
            .distinctBy { it.lowercase() }

        val names = mutableListOf<String>()
        names.addAll(c1)
        names.addAll(c2.process().take(30))
        names.addAll(c3.process().take(30))
        names.shuffle(RANDOM)

        while (true) {
            val cls = StringBuilder()
            cls.append(names.random(k_r_a_n_d_o_m))
            cls.append('.')
            cls.append(names.random(k_r_a_n_d_o_m))
            // Old Android does not support capitalized package names
            // Check Android 7.0.0 PackageParser#buildClassName
            yield(cls.toString().replaceFirstChar { it.lowercase() })
        }
    }.distinct().iterator()

    fun gen_class(type: String, out_dir: File): String {
        val clz_name = class_name_generator.next()
        val (pkg, name) = clz_name.split('.')
        val pkg_dir = File(out_dir, pkg)
        pkg_dir.mkdirs()
        PrintStream(File(pkg_dir, "$name.java")).use {
            it.println("package $pkg;")
            it.println("public class $name extends pro.magisk.$type {}")
        }
        return clz_name
    }

    val factory = gen_class("DelegateComponentFactory", out_dir)
    val app = gen_class("StubApplication", out_dir)
    return Pair(factory, app)
}

private fun gen_encrypted_resources(res: ByteArray, out_dir: File) {
    val main_pkg_dir = File(out_dir, "pro.magisk")
    main_pkg_dir.mkdirs()

    // Generate iv and key
    val iv = ByteArray(16)
    val key = ByteArray(32)
    RANDOM.nextBytes(iv)
    RANDOM.nextBytes(key)

    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    val bos = ByteArrayOutputStream()

    ByteArrayInputStream(res).use {
        CipherOutputStream(bos, cipher).use { os ->
            it.transferTo(os)
        }
    }

    PrintStream(File(main_pkg_dir, "Bytes.java")).use {
        it.println("package pro.magisk;")
        it.println("public final class Bytes {")

        it.byteField("key", key)
        it.byteField("iv", iv)
        it.byteField("res", bos.toByteArray())

        it.println("}")
    }
}

private abstract class TaskWithDir : DefaultTask() {
    @get:OutputDirectory
    abstract val output_folder: DirectoryProperty
}

/**
 * Configures the {@code :stub} module build. For each variant:
 * <ol>
 *   <li>Generates random stub class files (DelegateComponentFactory + StubApplication)</li>
 *   <li>Rewrites the merged manifest to inject shuffled component declarations</li>
 *   <li>Encrypts and bundles {@code resources.arsc} from the {@code :stub-res} APK</li>
 *   <li>Deletes {@code resources.arsc} from the final APK so that the host app provides resources</li>
 * </ol>
 */
fun Project.setupStubApk() {
    setupAppCommon()

    androidAppComponents {
        onVariants { variant ->
            val variant_name = variant.name
            val variant_capped = variant_name.replaceFirstChar { it.uppercase() }
            val variant_lowered = variant_name.lowercase()

            val component_java_out_dir = layout.buildDirectory
                .dir("generated/${variant_lowered}/components").get().asFile
            component_java_out_dir.deleteRecursively()

            val (factory, app) = gen_stub_classes(component_java_out_dir)

            val manifest_updater =
                project.tasks.register("${variant_name}ManifestProducer", ManifestUpdater::class.java) {
                    applicationId = variant.applicationId
                    factory_class.set(factory)
                    app_class.set(app)
                }
            variant.artifacts.use(manifest_updater)
                .wiredWithFiles(
                    ManifestUpdater::merged_manifest,
                    ManifestUpdater::output_manifest)
                .toTransform(SingleArtifact.MERGED_MANIFEST)

            val res_task = tasks.getByPath(":stub-res:package$variant_capped")
            val gen_resources_task = tasks.register("generate${variant_capped}BundledResources", TaskWithDir::class) {
                dependsOn(res_task)
                output_folder.set(layout.buildDirectory.dir("generated/${variant_lowered}/resources"))

                doLast {
                    val apk = res_task.outputs.files.asFileTree
                        .filter { it.name.endsWith(".apk") }.files.first()

                    val bos = ByteArrayOutputStream()
                    ZipFile(apk).use { src ->
                        DeflaterOutputStream(bos, Deflater(Deflater.BEST_COMPRESSION)).use {
                            src.getInputStream(src.getEntry("resources.arsc")).transferTo(it)
                        }
                    }
                    gen_encrypted_resources(bos.toByteArray(), output_folder.get().asFile)
                }
            }

            tasks.withType(TransformApkTask::class) {
                transformations.add {
                    // Always delete resources.arsc from the APK
                    // to ensure that external resources can be loaded
                    it.get("resources.arsc")?.delete()
                }
            }

            variant.sources.java?.let {
                it.addStaticSourceDirectory(component_java_out_dir.path)
                it.addGeneratedSourceDirectory(gen_resources_task, TaskWithDir::output_folder)
            }
        }
    }
}
