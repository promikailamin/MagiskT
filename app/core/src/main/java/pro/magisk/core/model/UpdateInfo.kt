package pro.magisk.core.model

import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonClass
import com.squareup.moshi.ToJson
import java.time.Instant

@JsonClass(generateAdapter = true)
data class ModuleJson(
    val version: String,
    val versionCode: Int,
    val zipUrl: String,
    val changelog: String,
)

class DateTimeAdapter {
    @ToJson
    fun toJson(date: Instant): String {
        return date.toString()
    }

    @FromJson
    fun fromJson(date: String): Instant {
        return Instant.parse(date)
    }
}
