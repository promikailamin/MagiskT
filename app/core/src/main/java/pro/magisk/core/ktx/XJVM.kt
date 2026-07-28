/**
 * JVM / coroutine / IO extension functions used across the app.
 *
 * Provides utilities for stream copying (with coroutine awareness),
 * synchronized collection wrappers, reflection helpers, and date
 * formatters.
 */
package pro.magisk.core.ktx

import androidx.collection.SparseArrayCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Field
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Collections

/** Open two [Closeable]s, invoke [withBoth], and close both safely. */
inline fun <In : Closeable, Out : Closeable> withInOut(
    input: In,
    output: Out,
    withBoth: (In, Out) -> Unit
) {
    input.use { reader ->
        output.use { writer ->
            withBoth(reader, writer)
        }
    }
}

/**
 * Copy all bytes from an [InputStream] to an [OutputStream] with
 * coroutine-aware cancellation.
 */
@Throws(IOException::class)
suspend fun InputStream.copyAll(
    out: OutputStream,
    bufferSize: Int = DEFAULT_BUFFER_SIZE,
    dispatcher: CoroutineDispatcher = Dispatchers.IO
): Long {
    return withContext(dispatcher) {
        var bytesCopied: Long = 0
        val buffer = ByteArray(bufferSize)
        var bytes = read(buffer)
        while (isActive && bytes >= 0) {
            out.write(buffer, 0, bytes)
            bytesCopied += bytes
            bytes = read(buffer)
        }
        bytesCopied
    }
}

/** Copy and close both streams. */
@Throws(IOException::class)
suspend inline fun InputStream.copyAndClose(
    out: OutputStream,
    bufferSize: Int = DEFAULT_BUFFER_SIZE,
    dispatcher: CoroutineDispatcher = Dispatchers.IO
) = withInOut(this, out) { i, o -> i.copyAll(o, bufferSize, dispatcher) }

/** Copy this stream to a [File] and close both. */
@Throws(IOException::class)
suspend inline fun InputStream.writeTo(
    file: File,
    bufferSize: Int = DEFAULT_BUFFER_SIZE,
    dispatcher: CoroutineDispatcher = Dispatchers.IO
) = copyAndClose(file.outputStream(), bufferSize, dispatcher)

/** Operator overload to allow `sparse[key] = value`. */
operator fun <E> SparseArrayCompat<E>.set(key: Int, value: E) {
    put(key, value)
}

/** Return a thread-safe (synchronized) view of the list. */
fun <T> MutableList<T>.synchronized(): MutableList<T> = Collections.synchronizedList(this)

/** Return a thread-safe (synchronized) view of the set. */
fun <T> MutableSet<T>.synchronized(): MutableSet<T> = Collections.synchronizedSet(this)

/** Return a thread-safe (synchronized) view of the map. */
fun <K, V> MutableMap<K, V>.synchronized(): MutableMap<K, V> = Collections.synchronizedMap(this)

/** Access a declared field reflectively, making it accessible. */
fun Class<*>.reflectField(name: String): Field =
    getDeclaredField(name).apply { isAccessible = true }

/** Concurrent map operator on a [Flow] (flatMapMerge). */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
inline fun <T, R> Flow<T>.concurrentMap(crossinline transform: suspend (T) -> R): Flow<R> {
    return flatMapMerge { value ->
        flow { emit(transform(value)) }
    }
}

/** Format a millis timestamp to a human-readable string. */
fun Long.toTime(format: DateTimeFormatter): String = format.format(Instant.ofEpochMilli(this))

val timeFormatStandard: DateTimeFormatter by lazy {
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH.mm.ss").withZone(ZoneId.systemDefault())
}
val timeDateFormat: DateTimeFormatter by lazy {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())
}
val dateFormat: DateTimeFormatter by lazy {
    DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withZone(ZoneId.systemDefault())
}
