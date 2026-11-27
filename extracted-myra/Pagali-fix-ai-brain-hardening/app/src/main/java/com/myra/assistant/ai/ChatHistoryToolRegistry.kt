package com.myra.assistant.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Validates bounded query expansion for MYRA's own locally stored chat history.
 * The database search is read-only; every phrase is an exact substring candidate.
 */
object ChatHistoryToolRegistry {
    fun declaration(): JSONObject = JSONObject().apply {
        put("name", "search_myra_history")
        put(
            "description",
            "Search the user's complete saved MYRA conversation history when they ask what they previously said, requested, instructed, decided, or discussed. Provide 2 to 5 short alternative search phrases: include the most likely exact words, Hindi/Hinglish transliterations, and close wording variants for the same person, topic, or action. Do not invent facts or unrelated synonyms. Each phrase is searched as an exact substring; results are ranked by how many phrases match, then by recency. This searches MYRA's own local history only, not ChatGPT or other apps. Do not guess if no matching messages are found.",
        )
        put(
            "parameters",
            JSONObject().apply {
                put("type", "OBJECT")
                put(
                    "properties",
                    JSONObject().apply {
                        put(
                            "queries",
                            JSONObject().apply {
                                put("type", "ARRAY")
                                put("items", JSONObject().put("type", "STRING"))
                                put("description", "2 to 5 concise alternative phrases for the same recall topic, including Hindi/Hinglish variants when useful.")
                            },
                        )
                        put(
                            "limit",
                            JSONObject().put("type", "INTEGER").put(
                                "description",
                                "Maximum number of matching messages to return (1 to 12).",
                            ),
                        )
                        put("after_timestamp", JSONObject().put("type", "INTEGER").put("description", "Optional inclusive Unix timestamp in milliseconds; use when the user gives a start date/time."))
                        put("before_timestamp", JSONObject().put("type", "INTEGER").put("description", "Optional inclusive Unix timestamp in milliseconds; use when the user gives an end date/time."))
                    },
                )
                put("required", JSONArray(listOf("queries", "limit")))
            },
        )
    }

    fun validate(args: JSONObject): SearchRequest? {
        val keys = mutableSetOf<String>()
        val iterator = args.keys()
        while (iterator.hasNext()) keys += iterator.next()
        if (!setOf("queries", "limit").all { it in keys } ||
            keys.any { it !in setOf("queries", "limit", "after_timestamp", "before_timestamp") }
        ) return null

        val rawQueries = args.optJSONArray("queries") ?: return null
        if (rawQueries.length() !in 2..5) return null
        val queries = mutableListOf<String>()
        for (index in 0 until rawQueries.length()) {
            val query = rawQueries.optString(index).trim()
            if (query.length !in 2..80 || query.any { it == '\n' || it == '\r' }) return null
            if (queries.none { it.equals(query, ignoreCase = true) }) queries += query
        }
        if (queries.size !in 2..5) return null

        val limit = args.optInt("limit", -1)
        if (limit !in 1..12) return null
        val afterTimestamp = if (args.has("after_timestamp")) args.optLong("after_timestamp", -1L) else 0L
        val beforeTimestamp = if (args.has("before_timestamp")) args.optLong("before_timestamp", -1L) else Long.MAX_VALUE
        if (afterTimestamp < 0L || beforeTimestamp < 0L || afterTimestamp > beforeTimestamp) return null
        return SearchRequest(queries, limit, afterTimestamp, beforeTimestamp)
    }

    data class SearchRequest(
        val queries: List<String>,
        val limit: Int,
        val afterTimestamp: Long,
        val beforeTimestamp: Long,
    )
}
