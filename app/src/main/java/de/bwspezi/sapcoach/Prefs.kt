package de.bwspezi.sapcoach

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class SourceDoc(val fileId: String, val name: String)
data class Exchange(val question: String, val answer: String, val timestamp: Long)

/** Lokale Ablage: API-Schlüssel, Modell, Quell-PDFs (Datei-IDs) und Gesprächsverlauf. */
object Prefs {
    private const val NAME = "sap_coach"
    private const val KEY_API = "api_key"
    private const val KEY_MODEL = "model"
    private const val KEY_DOCS = "docs"
    private const val KEY_HISTORY = "history"
    private const val MAX_HISTORY = 30

    const val DEFAULT_MODEL = "claude-sonnet-5-5"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun apiKey(ctx: Context): String = sp(ctx).getString(KEY_API, "") ?: ""
    fun setApiKey(ctx: Context, v: String) = sp(ctx).edit().putString(KEY_API, v.trim()).apply()

    fun model(ctx: Context): String =
        (sp(ctx).getString(KEY_MODEL, "") ?: "").ifBlank { DEFAULT_MODEL }
    fun setModel(ctx: Context, v: String) = sp(ctx).edit().putString(KEY_MODEL, v.trim()).apply()

    fun docs(ctx: Context): List<SourceDoc> {
        val arr = JSONArray(sp(ctx).getString(KEY_DOCS, "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            SourceDoc(o.getString("id"), o.getString("name"))
        }
    }

    fun addDoc(ctx: Context, doc: SourceDoc) {
        val list = docs(ctx).toMutableList()
        list.add(doc)
        saveDocs(ctx, list)
    }

    fun clearDocs(ctx: Context) = saveDocs(ctx, emptyList())

    private fun saveDocs(ctx: Context, list: List<SourceDoc>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.fileId).put("name", it.name)) }
        sp(ctx).edit().putString(KEY_DOCS, arr.toString()).apply()
    }

    /** Verlauf, älteste zuerst. */
    fun history(ctx: Context): List<Exchange> {
        val arr = JSONArray(sp(ctx).getString(KEY_HISTORY, "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Exchange(o.getString("q"), o.getString("a"), o.optLong("t"))
        }
    }

    fun addExchange(ctx: Context, e: Exchange) {
        val list = (history(ctx) + e).takeLast(MAX_HISTORY)
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("q", it.question).put("a", it.answer).put("t", it.timestamp)) }
        sp(ctx).edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun clearHistory(ctx: Context) = sp(ctx).edit().putString(KEY_HISTORY, "[]").apply()
}
