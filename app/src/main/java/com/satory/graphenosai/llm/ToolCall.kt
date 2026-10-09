package com.satory.graphenosai.llm

import com.satory.graphenosai.search.SearchResult
import org.json.JSONArray
import org.json.JSONObject

data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: ToolFunctionCall
)

data class ToolFunctionCall(
    val name: String,
    val arguments: String
)

object WebSearchTool {
    fun buildDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "web_search")
                put("description", "Search the web for current, real-time information. Use this instead of saying you lack internet access whenever the user asks about news, current versions, releases, weather, prices, sports, stock data, recent events, availability, or facts you are not confident are stable. Always use the user's question verbatim as the search query.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply {
                            put("type", "string")
                            put("description", "The search query string")
                        })
                    })
                    put("required", JSONArray(listOf("query")))
                })
            })
        }
    }

    fun buildToolsArray(): JSONArray {
        return JSONArray().apply {
            put(buildDefinition())
        }
    }
}

fun parseSearchQuery(arguments: String): String {
    return try {
        JSONObject(arguments).optString("query", "").trim()
    } catch (e: Exception) {
        arguments.trim()
    }
}

fun buildToolAssistantMessage(toolCalls: List<ToolCall>): JSONObject {
    return JSONObject().apply {
        put("role", "assistant")
        put("content", JSONObject.NULL)
        val callsArray = JSONArray()
        for (tc in toolCalls) {
            callsArray.put(JSONObject().apply {
                put("id", tc.id)
                put("type", tc.type)
                put("function", JSONObject().apply {
                    put("name", tc.function.name)
                    put("arguments", tc.function.arguments)
                })
            })
        }
        put("tool_calls", callsArray)
    }
}

fun containsDsmlToolCalls(text: String): Boolean =
    text.contains("<|DSML| |tool_calls", ignoreCase = true) ||
    text.contains("DSML", ignoreCase = true) && text.contains("tool_calls", ignoreCase = true)

/**
 * Extract query value from DSML tags using simple string search.
 * Looks for name="query" or name='query' and captures the value after > up to the next <|
 */
fun extractDsmlQuery(text: String): String {
    val marker = "query\""
    val idx = text.indexOf(marker, ignoreCase = true)
    if (idx < 0) return ""

    val afterMarker = text.substring(idx + marker.length)
    // Find the closing > after string="true" etc.
    val closeBracket = afterMarker.indexOf('>')
    if (closeBracket < 0) return ""

    val valueStart = closeBracket + 1
    // Find the next <| closing tag or end of string
    val closeTag = afterMarker.indexOf("<|", valueStart)
    val endIndex = if (closeTag >= 0) closeTag else afterMarker.length

    return afterMarker.substring(valueStart, endIndex).trim()
}

fun parseDsmlToolCalls(text: String): Pair<List<ToolCall>, String> {
    val calls = mutableListOf<ToolCall>()

    if (!containsDsmlToolCalls(text)) return Pair(calls, text)

    val query = extractDsmlQuery(text)

    if (query.isNotEmpty()) {
        val params = JSONObject()
        params.put("query", query)
        calls.add(ToolCall(
            id = "dsml_0",
            type = "function",
            function = ToolFunctionCall("web_search", params.toString())
        ))
    }

    // Strip everything that looks like a tag: <|...>
    val cleaned = text.replace(Regex("<\\|[^>]*>"), "").trim()

    return Pair(calls, cleaned)
}

fun buildSearchResultsMessage(results: List<SearchResult>): String {
    if (results.isEmpty()) return "Web search returned no results."
    return results.joinToString("\n\n") {
        "Title: ${it.title}\nURL: ${it.url}\n${it.snippet}"
    }
}

fun buildToolResultMessage(toolCallId: String, content: String): JSONObject {
    return JSONObject().apply {
        put("role", "tool")
        put("tool_call_id", toolCallId)
        put("content", content)
    }
}

sealed class StreamEvent {
    data class Content(val text: String) : StreamEvent()
    data class Reasoning(val text: String) : StreamEvent()
    data class ToolCallsDetected(val assistantMessage: JSONObject, val calls: List<ToolCall>) : StreamEvent()
}
