package com.termux.app.plugins

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Strict JSON helpers over org.json. Every helper rejects missing keys, wrong types and
 * (where relevant) control characters, so a malformed document fails closed with a clear
 * [PluginException] instead of a silent default.
 */
object PluginJson {

    fun parseObject(text: String, what: String): JSONObject {
        try {
            return JSONObject(text)
        } catch (e: JSONException) {
            throw PluginException("$what is not valid JSON: ${e.message}", e)
        }
    }

    fun rejectUnknownKeys(obj: JSONObject, allowed: Set<String>, what: String) {
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next() as String
            if (key !in allowed) throw PluginException("$what has unknown field \"$key\"")
        }
    }

    fun requireObject(obj: JSONObject, key: String, what: String): JSONObject {
        val value = obj.opt(key) ?: throw PluginException("$what is missing required field \"$key\"")
        return value as? JSONObject
            ?: throw PluginException("$what field \"$key\" must be a JSON object")
    }

    fun optionalObject(obj: JSONObject, key: String, what: String): JSONObject? {
        val value = obj.opt(key) ?: return null
        return value as? JSONObject
            ?: throw PluginException("$what field \"$key\" must be a JSON object")
    }

    fun requireArray(obj: JSONObject, key: String, what: String): JSONArray {
        val value = obj.opt(key) ?: throw PluginException("$what is missing required field \"$key\"")
        return value as? JSONArray
            ?: throw PluginException("$what field \"$key\" must be a JSON array")
    }

    fun optionalArray(obj: JSONObject, key: String, what: String): JSONArray? {
        val value = obj.opt(key) ?: return null
        return value as? JSONArray
            ?: throw PluginException("$what field \"$key\" must be a JSON array")
    }

    fun requireString(obj: JSONObject, key: String, what: String, maxLength: Int, allowControl: Boolean = false): String {
        val value = obj.opt(key) ?: throw PluginException("$what is missing required field \"$key\"")
        if (value !is String) throw PluginException("$what field \"$key\" must be a string")
        if (value.isEmpty()) throw PluginException("$what field \"$key\" must not be empty")
        if (value.length > maxLength) throw PluginException("$what field \"$key\" exceeds $maxLength characters")
        if (!allowControl && ZenApi.hasControlChars(value))
            throw PluginException("$what field \"$key\" contains control characters")
        return value
    }

    fun optionalString(obj: JSONObject, key: String, what: String, maxLength: Int, allowControl: Boolean = false): String? {
        val value = obj.opt(key) ?: return null
        if (value !is String) throw PluginException("$what field \"$key\" must be a string")
        if (value.length > maxLength) throw PluginException("$what field \"$key\" exceeds $maxLength characters")
        if (!allowControl && ZenApi.hasControlChars(value))
            throw PluginException("$what field \"$key\" contains control characters")
        return value
    }

    /** Requires a JSON integer (rejects decimals, strings and booleans). */
    fun requireInt(obj: JSONObject, key: String, what: String): Int {
        val value = obj.opt(key) ?: throw PluginException("$what is missing required field \"$key\"")
        return intFrom(value, "$what field \"$key\"")
    }

    fun optionalInt(obj: JSONObject, key: String, what: String): Int? {
        val value = obj.opt(key) ?: return null
        return intFrom(value, "$what field \"$key\"")
    }

    private fun intFrom(value: Any, what: String): Int {
        val long = when (value) {
            is Int -> value.toLong()
            is Long -> value
            else -> throw PluginException("$what must be an integer")
        }
        if (long < Int.MIN_VALUE || long > Int.MAX_VALUE) throw PluginException("$what is out of range")
        return long.toInt()
    }

    fun requireBool(obj: JSONObject, key: String, what: String): Boolean {
        val value = obj.opt(key) ?: throw PluginException("$what is missing required field \"$key\"")
        return value as? Boolean ?: throw PluginException("$what field \"$key\" must be a boolean")
    }

    fun optionalBool(obj: JSONObject, key: String, what: String): Boolean? {
        val value = obj.opt(key) ?: return null
        return value as? Boolean ?: throw PluginException("$what field \"$key\" must be a boolean")
    }

    fun stringAt(array: JSONArray, index: Int, what: String, maxLength: Int): String {
        val value = array.opt(index) ?: throw PluginException("$what[$index] is missing")
        if (value !is String) throw PluginException("$what[$index] must be a string")
        if (value.isEmpty()) throw PluginException("$what[$index] must not be empty")
        if (value.length > maxLength) throw PluginException("$what[$index] exceeds $maxLength characters")
        if (ZenApi.hasControlChars(value)) throw PluginException("$what[$index] contains control characters")
        return value
    }

    fun objectAt(array: JSONArray, index: Int, what: String): JSONObject {
        val value = array.opt(index) ?: throw PluginException("$what[$index] is missing")
        return value as? JSONObject ?: throw PluginException("$what[$index] must be a JSON object")
    }

    fun arrayAt(array: JSONArray, index: Int, what: String): JSONArray {
        val value = array.opt(index) ?: throw PluginException("$what[$index] is missing")
        return value as? JSONArray ?: throw PluginException("$what[$index] must be a JSON array")
    }
}
