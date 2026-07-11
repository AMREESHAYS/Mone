package com.example.mone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persists the list of past downloads in SharedPreferences as a JSON array. */
object HistoryStore {
    private const val PREFS = "mone_history"
    private const val KEY = "items"
    private const val MAX = 200

    data class Entry(val name: String, val time: Long, val uri: String?)

    fun add(context: Context, entry: Entry) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        val obj = JSONObject()
            .put("name", entry.name)
            .put("time", entry.time)
            .put("uri", entry.uri ?: JSONObject.NULL)
        val out = JSONArray().put(obj)
        for (i in 0 until minOf(arr.length(), MAX - 1)) out.put(arr.get(i))
        prefs.edit().putString(KEY, out.toString()).apply()
    }

    fun all(context: Context): List<Entry> {
        val arr = JSONArray(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"),
        )
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(o.getString("name"), o.getLong("time"), o.optString("uri").ifBlank { null })
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
