package com.dsh.miuix.cardface.core

import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

enum class OnlineCategory(val label: String, val resource: String, val webPath: String, val filterKey: String, val filterLabel: String) {
    BANK("银行卡面", "face", "face", "native_name", "银行名称（可选）"),
    TRANSIT("交通卡面", "face_ctu", "face-ctu", "city", "城市 / 卡种（可选）"),
    IP("IP 联动", "face_ctu_origin", "face-ctu-origin", "ip", "IP 名称（可选）");
    val webUrl get() = "https://hi.my-api.cn/$webPath"
}

data class OnlineCard(val id: String, val category: OnlineCategory, val title: String,
    val subtitle: String, val imageUrl: String, val source: String) {
    val key get() = "${category.resource}:$id"
}
data class OnlinePage(val cards: List<OnlineCard>, val page: Int, val pages: Int, val total: Int, val cached: Boolean = false)

object OnlineCatalog {
    const val PAGE_SIZE = 24
    fun query(category: OnlineCategory, page: Int, search: String, filter: String): String {
        require(page in 1..10000 && search.length <= 120 && filter.length <= 120)
        val params = linkedMapOf("resource" to category.resource, "page" to page.toString(), "page_size" to PAGE_SIZE.toString())
        if (search.isNotBlank()) params["search"] = search.trim()
        if (filter.isNotBlank()) params[category.filterKey] = filter.trim()
        return "https://hi.my-api.cn/api/?" + params.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
    }
    fun normalizeImageUrl(url: String): String? = runCatching {
        require(url.length <= 4096 && url.none { it.code < 32 })
        val uri = URI(url.replace(" ", "%20"))
        require(uri.scheme == "https" && uri.host.equals("storage.my-api.cn", true) &&
            uri.rawUserInfo == null && uri.port in setOf(-1,443) && uri.path.startsWith("/static/images/") &&
            !uri.path.split('/').contains("..") && uri.fragment == null)
        uri.toASCIIString()
    }.getOrNull()

    fun validImageUrl(url: String): Boolean = normalizeImageUrl(url) != null

    fun parse(text: String, category: OnlineCategory): OnlinePage {
        val root = JSONObject(text)
        require(root.optInt("code") == 200) { root.optString("msg", "在线目录加载失败").take(120) }
        val data = root.optJSONObject("data") ?: error("在线目录格式已变化，请打开原网站")
        val list = data.optJSONArray("list") ?: error("在线目录缺少图片列表")
        require(list.length() <= 100) { "单页条目异常" }
        val cards = (0 until list.length()).mapNotNull { i ->
            val item = list.optJSONObject(i) ?: return@mapNotNull null
            val image = normalizeImageUrl(item.optString(when(category) { OnlineCategory.BANK -> "image"; OnlineCategory.TRANSIT -> "filename"; OnlineCategory.IP -> "productImagePath" }))
                ?: return@mapNotNull null
            val title = item.optString(if (category == OnlineCategory.BANK) "description" else if (category == OnlineCategory.TRANSIT) "title" else "productName").trim().take(160).ifBlank { "未命名卡面" }
            val subtitle = when(category) {
                OnlineCategory.BANK -> item.optJSONObject("issuer")?.optString("native_name").orEmpty()
                OnlineCategory.TRANSIT -> item.optString("city")
                OnlineCategory.IP -> item.optString("ipName")
            }.trim().take(120)
            val id = item.optString(if (category == OnlineCategory.IP) "productId" else "id").takeIf { it.isNotBlank() && it != "null" }
                ?: CardRuntime.fingerprint(image)
            OnlineCard(id, category, title, subtitle, image, if (category == OnlineCategory.IP) "IP 联动" else item.optString("source").take(100))
        }.distinctBy { it.key }
        val page = data.optInt("page",1).coerceAtLeast(1)
        val total = data.optInt("total", cards.size).coerceIn(0,1000000)
        val pages = data.optInt("total_pages", maxOf(1, (total + PAGE_SIZE - 1) / PAGE_SIZE)).coerceIn(1,10000)
        return OnlinePage(cards,page,pages,total)
    }
}
