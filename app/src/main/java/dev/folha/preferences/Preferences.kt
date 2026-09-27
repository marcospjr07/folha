package dev.folha.preferences

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

enum class ThemeMode { SYSTEM, LIGHT, DARK }
data class RecentFile(val uri: String, val name: String)
fun updateRecents(recents: List<RecentFile>, item: RecentFile): List<RecentFile> =
    (listOf(item) + recents.filterNot { it.uri == item.uri }).take(12)

class Preferences(context: Context) {
    private val prefs = context.getSharedPreferences("folha", Context.MODE_PRIVATE)
    var theme: ThemeMode
        get() = runCatching { ThemeMode.valueOf(prefs.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM)
        set(value) { prefs.edit { putString("theme", value.name) } }
    fun recents(): List<RecentFile> = runCatching {
        val array = JSONArray(prefs.getString("recents", "[]"))
        (0 until array.length()).map { array.getJSONObject(it).let { obj -> RecentFile(obj.getString("uri"), obj.getString("name")) } }
    }.getOrDefault(emptyList())
    fun saveRecents(items: List<RecentFile>) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("uri", it.uri).put("name", it.name)) }
        prefs.edit { putString("recents", array.toString()) }
    }
}
