/**
 * Gradle task that post-processes a signed APK produced by the Android build system.
 *
 * <p>For each APK artifact:
 * <ol>
 *   <li>Copies the APK to the output directory</li>
 *   <li>Re-signs it using V1 + V2 signing schemes via {@code apkzlib}</li>
 *   <li>Cleans up build-artifact metadata entries
 *       ({@code APP_METADATA}, {@code VERSION_CONTROL_INFO}, {@code MANIFEST.MF},
 *        {@code PublicSuffixDatabase.list})</li>
 *   <li>Applies a configurable list of transformations (e.g. embedding version info
 *       into the ZIP End of Central Directory comment)</li>
 * </ol>
 */
import com.android.build.api.artifact.ArtifactTransformationRequest
import com.android.build.api.dsl.ApkSigningConfig
import com.android.builder.internal.packaging.IncrementalPackager
import com.android.ide.common.signing.KeystoreHelper
import com.android.tools.build.apkzlib.sign.SigningExtension
import com.android.tools.build.apkzlib.sign.SigningOptions
import com.android.tools.build.apkzlib.zfile.ZFiles
import com.android.tools.build.apkzlib.zip.ZFile
import com.android.tools.build.apkzlib.zip.ZFileOptions
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.jar.JarFile

abstract class TransformApkTask : DefaultTask() {
    @get:Input
    abstract val signingConfig: Property<ApkSigningConfig>

    @get:InputFiles
    abstract val apk_folder: DirectoryProperty

    @get:OutputDirectory
    abstract val out_folder: DirectoryProperty

    @get:Internal
    abstract val transformations: ListProperty<(ZFile) -> Unit>

    @get:Internal
    abstract val transformation_request: Property<ArtifactTransformationRequest<TransformApkTask>>

    /** Re-signs and post-processes the APK. */
    @TaskAction
    fun task_action() = transformation_request.get().submit(this) { artifact ->
        val in_file = File(artifact.outputFile)
        val out_file = out_folder.file(in_file.name).get().asFile

        val config = signingConfig.get()
        val info = KeystoreHelper.getCertificateInfo(
            config.storeType,
            config.storeFile,
            config.storePassword,
            config.keyPassword,
            config.keyAlias
        )

        val signing_options = SigningOptions.builder()
            .setMinSdkVersion(0)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setKey(info.key)
            .setCertificates(info.certificate)
            .setValidation(SigningOptions.Validation.ASSUME_INVALID)
            .build()
        val options = ZFileOptions().apply {
            noTimestamps = true
            autoSortFiles = true
        }
        out_file.parentFile?.mkdirs()
        in_file.copyTo(out_file, overwrite = true)
        ZFiles.apk(out_file, options).use {
            SigningExtension(signing_options).register(it)
            it.get(IncrementalPackager.APP_METADATA_ENTRY_PATH)?.delete()
            it.get(IncrementalPackager.VERSION_CONTROL_INFO_ENTRY_PATH)?.delete()
            it.get(JarFile.MANIFEST_NAME)?.delete()
            it.get("assets/PublicSuffixDatabase.list")?.delete()
            transformations.get().forEach { transform -> transform(it) }
        }

        out_file
    }
}
