package pro.magisk.core.download

import pro.magisk.core.ktx.copyAndClose
import pro.magisk.core.utils.MediaStoreUtils.outputStream
import java.io.InputStream

class DownloadProcessor(notifier: DownloadNotifier) : DownloadNotifier by notifier {

    suspend fun handle(stream: InputStream, subject: Subject) {
        stream.copyAndClose(subject.file.outputStream())
    }
}
