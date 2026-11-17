package com.dsh.miuix.cardface.core

/** Pure policy for the OnePlus wallet's real, account-owned NFC cards. */
object OwnedCardPolicy {
    private val blockedStatuses = setOf(
        "", "RECOMMEND", "WAIT_TO_RECEIVE", "ALLOW_OPEN", "NOT_BINDED", "DELETED", "DELETING",
        "CANCELED", "DK_CANCELED", "TERMINATE", "TERMINATED_IN_VEHICLE",
        "TERMINATED_ON_DEVICE", "IN_TERMINATION_ON_VEHICLE_FIRST", "NO_PIN",
    )
    private val blockedTypes = setOf("", "0", "4", "19")

    data class Candidate(
        val type: String?,
        val status: String?,
        val aid: String?,
        val uniqueCardId: String?,
        val imageUrl: String?,
        val displayName: String?,
    )

    fun accept(candidate: Candidate): Boolean {
        val type = candidate.type?.trim().orEmpty()
        val status = candidate.status?.trim()?.uppercase().orEmpty()
        val aid = candidate.aid?.trim().orEmpty()
        val unique = candidate.uniqueCardId?.trim().orEmpty()
        val url = candidate.imageUrl?.trim().orEmpty()
        // Only called for records returned by the current-account NfcCard list.
        // NFC AIDs are absent on provisioned QR/payment cards. Such records need
        // a real card instance ID and an explicitly provisioned lifecycle state.
        // Templates may also have instance IDs, so an ID alone is never enough.
        val provisionedWithoutAid = status in setOf(
            "ACTIVE", "SUC", "SUSPEND", "SUSPENDED", "RESUME", "WAIT_ACTIVATE",
            "PUSH_RESUME", "PUSH_RESUME_REQUEST", "PUSH_SUSPEND",
            "WAIT_DEVICE_RESUME", "WAIT_VEHICLE_RESUME", "WAIT_VEHICLE_SUSPEND",
        ) && unique.isNotEmpty() && !unique.equals("null", true)
        val validAid = aid.isNotEmpty() && !aid.equals("null", true)
        return type !in blockedTypes && status !in blockedStatuses &&
            (validAid || provisionedWithoutAid) && url.isNotEmpty()
    }

    fun normalizeUrl(url: String): String = url.trim()

    fun snapshotJson(at: Long, cards: Collection<Pair<String, String?>>): String {
        val array = org.json.JSONArray()
        cards.asSequence()
            .map { normalizeUrl(it.first) to it.second?.trim() }
            .filter { it.first.isNotEmpty() }
            .distinctBy { it.first }
            .sortedBy { it.first }
            .forEach { (url, name) ->
                array.put(org.json.JSONObject().put("url", url).apply {
                    if (!name.isNullOrBlank()) put("displayName", name)
                })
            }
        return org.json.JSONObject().put("schema", 1).put("at", at).put("cards", array).toString()
    }

    fun snapshotUrls(json: String?): Set<String> {
        if (json.isNullOrBlank()) return emptySet()
        return runCatching {
            val cards = org.json.JSONObject(json).optJSONArray("cards") ?: return@runCatching emptySet()
            buildSet {
                for (i in 0 until cards.length()) {
                    val url = cards.optJSONObject(i)?.optString("url").orEmpty().trim()
                    if (url.isNotEmpty()) add(url)
                }
            }
        }.getOrDefault(emptySet())
    }

    fun snapshotNames(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val cards = org.json.JSONObject(json).optJSONArray("cards") ?: return@runCatching emptyMap()
            buildMap {
                for (i in 0 until cards.length()) {
                    val card = cards.optJSONObject(i) ?: continue
                    val url = card.optString("url").trim()
                    val name = card.optString("displayName").trim()
                    if (url.isNotEmpty() && name.isNotEmpty()) put(url, name)
                }
            }
        }.getOrDefault(emptyMap())
    }
}
