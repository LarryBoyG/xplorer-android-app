package com.example.xirolite

import org.json.JSONArray
import org.json.JSONObject

object RelayPayloadParser {
    data class CurrentAirWifiInfo(
        val ssid: String? = null,
        val mac: String? = null,
        val channel: String? = null,
        val signal: String? = null,
        val password: String? = null
    )

    data class RepeaterStatusInfo(
        val repeaterStatus: String? = null,
        val lastError: String? = null,
        val boundSsid: String? = null,
        val statusCode: String? = null,
        val signal: String? = null
    )

    fun extractLikelyBoundSsid(text: String): String? {
        parseRepeaterStatus(text)?.boundSsid?.let { ssid ->
            if (isPlausibleBoundSsid(ssid)) return ssid
        }

        parseCurrentAirWifi(text)?.ssid?.let { ssid ->
            if (isPlausibleBoundSsid(ssid)) return ssid
        }

        extractQuotedValue(text, "SSID")?.let { ssid ->
            if (isPlausibleBoundSsid(ssid)) return ssid
        }

        return text.lineSequence()
            .map { normalizeMixedRelayLine(it) }
            .firstOrNull { isPlausibleBoundSsid(it) }
    }

    fun looksLikeStructuredWifiListPayload(text: String): Boolean {
        if (!text.contains("wifi_list", ignoreCase = true) &&
            !text.contains("\"ssid\"", ignoreCase = true) &&
            !text.contains("\"APs\"", ignoreCase = true) &&
            !text.contains("wifiscan", ignoreCase = true)
        ) {
            return false
        }
        return parseStructuredWifiCandidates(text).isNotEmpty()
    }

    fun parseWifiCandidates(text: String): List<RelayWifiCandidate> {
        val structured = parseStructuredWifiCandidates(text)
        if (structured.isNotEmpty()) return structured

        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { isPlainWifiCandidate(it) }
            .distinct()
            .take(40)
            .map { RelayWifiCandidate(ssid = it) }
            .toList()
    }

    fun parseCurrentAirWifi(text: String): CurrentAirWifiInfo? {
        parseJsonObject(extractJsonPayload(text))?.let { root ->
            val infoObject = when {
                looksLikeWifiInfoObject(root) -> root
                root.optJSONObjectIgnoreCase("wifi") != null -> root.optJSONObjectIgnoreCase("wifi")
                root.optJSONObjectIgnoreCase("current_air_wifi_info") != null -> root.optJSONObjectIgnoreCase("current_air_wifi_info")
                else -> findFirstWifiInfoObject(root)
            }

            if (infoObject != null) {
                return CurrentAirWifiInfo(
                    ssid = infoObject.optStringOrNullIgnoreCase("ssid"),
                    mac = infoObject.optStringOrNullIgnoreCase("mac", "bssid"),
                    channel = infoObject.optFlexibleStringIgnoreCase("channel"),
                    signal = infoObject.optFlexibleStringIgnoreCase("signal"),
                    password = infoObject.optStringOrNullIgnoreCase("password")
                )
            }
        }

        val ssid = extractQuotedValue(text, "ssid")
        val mac = extractQuotedValue(text, "mac")
        val channel = extractFlexibleValue(text, "channel")
        val signal = extractFlexibleValue(text, "signal")
        val password = extractQuotedValue(text, "password")

        if (ssid == null && mac == null && channel == null && signal == null && password == null) {
            return null
        }

        return CurrentAirWifiInfo(
            ssid = ssid,
            mac = mac,
            channel = channel,
            signal = signal,
            password = password
        )
    }

    fun parseRepeaterStatus(text: String): RepeaterStatusInfo? {
        parseJsonObject(extractJsonPayload(text))?.let { root ->
            val tag = root.optStringOrNullIgnoreCase("tag")
            val statusCode = root.optFlexibleStringIgnoreCase("status")
            val signal = root.optFlexibleStringIgnoreCase("signal")
            val boundSsid = root.optStringOrNullIgnoreCase("ssid")
            val repeaterStatus = when {
                root.optFlexibleStringIgnoreCase("repeater_status") != null -> root.optFlexibleStringIgnoreCase("repeater_status")
                tag.equals("repeaterstatus", ignoreCase = true) && statusCode == "0" -> "Connected"
                else -> null
            }
            val lastError = root.optFlexibleStringIgnoreCase("last_error")
            if (repeaterStatus != null || lastError != null || boundSsid != null || statusCode != null || signal != null) {
                return RepeaterStatusInfo(
                    repeaterStatus = repeaterStatus,
                    lastError = lastError,
                    boundSsid = boundSsid,
                    statusCode = statusCode,
                    signal = signal
                )
            }
        }

        val repeaterStatus = extractFlexibleValue(text, "repeater_status")
        val lastError = extractFlexibleValue(text, "last_error")
        val boundSsid = extractQuotedValue(text, "SSID") ?: extractQuotedValue(text, "ssid")
        val statusCode = extractFlexibleValue(text, "status")
        val signal = extractFlexibleValue(text, "signal")
        if (repeaterStatus == null && lastError == null && boundSsid == null && statusCode == null && signal == null) return null

        return RepeaterStatusInfo(
            repeaterStatus = repeaterStatus,
            lastError = lastError,
            boundSsid = boundSsid,
            statusCode = statusCode,
            signal = signal
        )
    }

    private fun parseStructuredWifiCandidates(text: String): List<RelayWifiCandidate> {
        val jsonPayload = extractJsonPayload(text)
        if (jsonPayload != null) {
            val fromJson = parseWifiCandidatesFromJson(jsonPayload)
            if (fromJson.isNotEmpty()) return fromJson
        }

        val objectRegex = Regex("\\{[^{}]*\"(?:ssid|SSID)\"\\s*:\\s*\"([^\"]+)\"[^{}]*\\}", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return objectRegex.findAll(text)
            .mapNotNull { match ->
                val fragment = match.value
                val ssid = (extractQuotedValue(fragment, "SSID") ?: extractQuotedValue(fragment, "ssid"))?.trim().orEmpty()
                if (!isPlainWifiCandidate(ssid)) return@mapNotNull null
                RelayWifiCandidate(
                    ssid = ssid,
                    mac = extractQuotedValue(fragment, "BSSID") ?: extractQuotedValue(fragment, "mac"),
                    channel = extractFlexibleValue(fragment, "channel"),
                    signal = extractFlexibleValue(fragment, "signal"),
                    encrypt = extractQuotedValue(fragment, "Encrypt") ?: extractQuotedValue(fragment, "encrypt"),
                    mode = extractQuotedValue(fragment, "Mode") ?: extractQuotedValue(fragment, "mode"),
                    channelBond = extractFlexibleValue(fragment, "ChannelBond"),
                    sideBand = extractFlexibleValue(fragment, "SideBand")
                )
            }
            .distinctBy { it.ssid to (it.mac ?: "") to (it.channel ?: "") }
            .toList()
    }

    private fun parseWifiCandidatesFromJson(jsonPayload: String): List<RelayWifiCandidate> {
        val array = when {
            jsonPayload.trimStart().startsWith("{") -> findArrayByKeys(JSONObject(jsonPayload), "wifi_list", "APs")
            jsonPayload.trimStart().startsWith("[") -> JSONArray(jsonPayload)
            else -> null
        } ?: return emptyList()

        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val ssid = item.optStringOrNullIgnoreCase("ssid")?.trim().orEmpty()
                if (!isPlainWifiCandidate(ssid)) continue
                add(
                    RelayWifiCandidate(
                        ssid = ssid,
                        mac = item.optStringOrNullIgnoreCase("mac", "bssid"),
                        channel = item.optFlexibleStringIgnoreCase("channel"),
                        signal = item.optFlexibleStringIgnoreCase("signal"),
                        encrypt = item.optStringOrNullIgnoreCase("encrypt"),
                        mode = item.optStringOrNullIgnoreCase("mode"),
                        channelBond = item.optFlexibleStringIgnoreCase("channelbond"),
                        sideBand = item.optFlexibleStringIgnoreCase("sideband")
                    )
                )
            }
        }.distinctBy { it.ssid to (it.mac ?: "") to (it.channel ?: "") }
    }

    private fun findArrayByKeys(node: JSONObject, vararg keys: String): JSONArray? {
        node.optJSONArrayIgnoreCase(*keys)?.let { return it }

        val iterator = node.keys()
        while (iterator.hasNext()) {
            val childKey = iterator.next()
            when (val child = node.opt(childKey)) {
                is JSONObject -> findArrayByKeys(child, *keys)?.let { return it }
                is JSONArray -> {
                    for (index in 0 until child.length()) {
                        val item = child.opt(index)
                        if (item is JSONObject) {
                            findArrayByKeys(item, *keys)?.let { return it }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun findFirstWifiInfoObject(node: JSONObject): JSONObject? {
        val keys = node.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val child = node.opt(key)) {
                is JSONObject -> {
                    if (looksLikeWifiInfoObject(child)) return child
                    findFirstWifiInfoObject(child)?.let { return it }
                }
                is JSONArray -> {
                    for (index in 0 until child.length()) {
                        val item = child.opt(index)
                        if (item is JSONObject) {
                            if (looksLikeWifiInfoObject(item)) return item
                            findFirstWifiInfoObject(item)?.let { return it }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun looksLikeWifiInfoObject(node: JSONObject): Boolean {
        return node.hasAnyKeyIgnoreCase("ssid", "mac", "bssid", "password", "channel")
    }

    private fun parseJsonObject(payload: String?): JSONObject? {
        if (payload == null || !payload.trimStart().startsWith("{")) return null
        return runCatching { JSONObject(payload) }.getOrNull()
    }

    private fun extractJsonPayload(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val objectStart = trimmed.indexOf('{')
        val arrayStart = trimmed.indexOf('[')
        val start = listOf(objectStart, arrayStart)
            .filter { it >= 0 }
            .minOrNull() ?: return null

        val candidate = trimmed.substring(start)
        return when {
            candidate.startsWith("{") -> {
                val end = candidate.lastIndexOf('}')
                if (end >= 0) candidate.substring(0, end + 1) else null
            }
            candidate.startsWith("[") -> {
                val end = candidate.lastIndexOf(']')
                if (end >= 0) candidate.substring(0, end + 1) else null
            }
            else -> null
        }
    }

    private fun extractQuotedValue(text: String, key: String): String? {
        val regex = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
        return regex.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun extractFlexibleValue(text: String, key: String): String? {
        return extractQuotedValue(text, key)
            ?: Regex("\"$key\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
    }

    private fun JSONObject.optStringOrNullIgnoreCase(vararg keys: String): String? {
        val key = resolveKey(*keys) ?: return null
        val value = optString(key).trim()
        return value.takeIf { it.isNotEmpty() }
    }

    private fun JSONObject.optFlexibleStringIgnoreCase(vararg keys: String): String? {
        val key = resolveKey(*keys) ?: return null
        val raw = opt(key) ?: return null
        return when (raw) {
            is Number -> raw.toString()
            is String -> raw.trim().takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    private fun JSONObject.optJSONObjectIgnoreCase(vararg keys: String): JSONObject? {
        val key = resolveKey(*keys) ?: return null
        return optJSONObject(key)
    }

    private fun JSONObject.optJSONArrayIgnoreCase(vararg keys: String): JSONArray? {
        val key = resolveKey(*keys) ?: return null
        return optJSONArray(key)
    }

    private fun JSONObject.hasAnyKeyIgnoreCase(vararg keys: String): Boolean {
        return resolveKey(*keys) != null
    }

    private fun JSONObject.resolveKey(vararg keys: String): String? {
        val available = keys().asSequence().toList()
        return keys.firstNotNullOfOrNull { wanted ->
            available.firstOrNull { it.equals(wanted, ignoreCase = true) }
        }
    }

    private fun isPlainWifiCandidate(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isBlank()) return false
        if (trimmed == "<empty body>") return false
        if (trimmed.startsWith("<") || trimmed.startsWith("{") || trimmed.startsWith("[")) return false
        if (trimmed.startsWith("failed to connect", ignoreCase = true)) return false
        if (trimmed.contains("connection refused", ignoreCase = true)) return false
        if (trimmed.contains("exception", ignoreCase = true)) return false
        if (trimmed.contains("timeout", ignoreCase = true)) return false
        if (trimmed.contains("socket ok", ignoreCase = true)) return false
        if (trimmed.contains("bad request", ignoreCase = true)) return false
        if (trimmed.equals("ssid", ignoreCase = true)) return false
        if (trimmed.equals("mac", ignoreCase = true)) return false
        if (trimmed.equals("channel", ignoreCase = true)) return false
        if (trimmed.equals("signal", ignoreCase = true)) return false
        if (trimmed.equals("wifi_list", ignoreCase = true)) return false
        if (trimmed.equals("repeater_status", ignoreCase = true)) return false
        if (trimmed.equals("last_error", ignoreCase = true)) return false
        if (trimmed.contains("enum_", ignoreCase = true)) return false
        if (!trimmed.any { it.isLetterOrDigit() }) return false
        if (!trimmed.any { it.isLetter() }) return false
        return trimmed.length in 1..64
    }

    private fun normalizeMixedRelayLine(raw: String): String {
        val trimmed = raw.trim()
            .trim('"', '\'')
            .trimEnd { it == '\u0000' || it.isWhitespace() }

        val withoutTrailingStatus = Regex("^(.+?)(\\d{1,2})$")
            .matchEntire(trimmed)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.any(Char::isLetter) }
            ?: trimmed

        return withoutTrailingStatus
            .trim('"', '\'')
            .trim()
    }

    private fun isPlausibleBoundSsid(value: String?): Boolean {
        val normalized = value?.trim().orEmpty()
        if (normalized.isBlank()) return false
        if (normalized.length !in 2..64) return false
        if (!normalized.any(Char::isLetter)) return false
        if (normalized.equals("<empty body>", ignoreCase = true)) return false
        if (normalized.startsWith("failed to connect", ignoreCase = true)) return false
        if (normalized.contains("socket ok", ignoreCase = true)) return false
        if (normalized.contains("exception", ignoreCase = true)) return false
        if (normalized.contains("timeout", ignoreCase = true)) return false
        if (normalized.contains("connection refused", ignoreCase = true)) return false
        if (normalized.equals("connected", ignoreCase = true)) return false
        if (normalized.equals("connecting", ignoreCase = true)) return false
        if (normalized.equals("disconnected", ignoreCase = true)) return false
        if (normalized.equals("repeater_status", ignoreCase = true)) return false
        return true
    }
}
