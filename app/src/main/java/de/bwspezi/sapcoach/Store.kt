package de.bwspezi.sapcoach

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Ein angelernter Tap in der Claude-App: Beschriftung der Schaltfläche + Position als Rückfalloption. */
data class Step(val label: String?, val viewId: String?, val x: Float, val y: Float) {
    fun describe(): String = label ?: "Tap auf Position (${x.toInt()}, ${y.toInt()})"
}

object Store {
    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("sap_coach_starter", Context.MODE_PRIVATE)

    fun steps(ctx: Context): List<Step> {
        val arr = JSONArray(sp(ctx).getString("steps", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Step(
                o.optString("label").ifBlank { null },
                o.optString("viewId").ifBlank { null },
                o.getDouble("x").toFloat(),
                o.getDouble("y").toFloat()
            )
        }
    }

    fun setSteps(ctx: Context, steps: List<Step>) {
        val arr = JSONArray()
        steps.forEach {
            arr.put(
                JSONObject()
                    .put("label", it.label ?: "")
                    .put("viewId", it.viewId ?: "")
                    .put("x", it.x.toDouble())
                    .put("y", it.y.toDouble())
            )
        }
        sp(ctx).edit().putString("steps", arr.toString()).apply()
    }

    fun autoStart(ctx: Context) = sp(ctx).getBoolean("autoStart", false)
    fun setAutoStart(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("autoStart", v).apply()

    fun btAddress(ctx: Context) = sp(ctx).getString("btAddress", null)
    fun btName(ctx: Context) = sp(ctx).getString("btName", null)
    fun setBt(ctx: Context, address: String, name: String) =
        sp(ctx).edit().putString("btAddress", address).putString("btName", name).apply()
}
