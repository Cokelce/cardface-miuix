package com.dsh.miuix.cardface.core.catalog

import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Hibank 在线卡面目录（https://hi.my-api.cn）的只读客户端协议：
 *  - 目录 JSON：/api/?resource=face|face_ctu|face_ctu_origin&page=&page_size=&search=&filter
 *  - 图片只接受 storage.my-api.cn/static/images/ 下的 https 地址
 *
 * 只做纯数据解析与 URL 校验，网络/缓存/下载在 CatalogRepository。
 */
object HibankCatalogSource {

    const val PAGE_SIZE = 24
    private const val API_HOST = "hi.my-api.cn"
    private const val IMAGE_HOST = "storage.my-api.cn"

    fun queryUrl(
        category: CatalogCategory,
        page: Int,
        search: String,
        filter: String,
        searchKeyOverride: String? = null,
    ): String {
        require(page in 1..10_000) { "页码异常" }
        require(search.length <= 120 && filter.length <= 120) { "搜索内容过长" }
        val params = linkedMapOf(
            "resource" to category.resource,
            "page" to page.toString(),
            "page_size" to PAGE_SIZE.toString(),
        )
        if (search.isNotBlank()) params[searchKeyOverride ?: category.searchKey] = search.trim()
        if (filter.isNotBlank()) params[category.filterKey] = filter.trim()
        return "https://$API_HOST/api/?" + params.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
    }

    /** 只接受 https + storage.my-api.cn + /static/images/ 路径，拒绝用户信息/非标准端口/`..`/片段。 */
    fun normalizeImageUrl(url: String): String? = runCatching {
        require(url.length in 1..4096 && url.none { it.code < 32 })
        val uri = URI(url.replace(" ", "%20"))
        require(uri.scheme.equals("https", true))
        require(uri.host.equals(IMAGE_HOST, true))
        require(uri.rawUserInfo == null)
        require(uri.port == -1 || uri.port == 443)
        require(uri.path.startsWith("/static/images/"))
        require(!uri.path.split('/').contains(".."))
        require(uri.fragment == null)
        uri.toASCIIString()
    }.getOrNull()

    fun validImageUrl(url: String): Boolean = normalizeImageUrl(url) != null

    /**
     * 列表缩略图：原站不提供缩略图，走 wsrv.nl 图片代理压成小尺寸 WebP（约 15–25KB/张），
     * 流量大减；失败时调用方回退原图。原图地址仍必须通过来源白名单校验。
     */
    fun thumbnailUrl(imageUrl: String, width: Int = 400): String {
        val normalized = normalizeImageUrl(imageUrl) ?: return imageUrl
        return "https://wsrv.nl/?url=" + URLEncoder.encode(normalized, "UTF-8") +
            "&w=$width&output=webp&q=72&we"
    }

    /** 详情预览：比列表大但仍压缩，点「使用此卡面」才下载原图。 */
    fun previewUrl(imageUrl: String, width: Int = 1080): String {
        val normalized = normalizeImageUrl(imageUrl) ?: return imageUrl
        return "https://wsrv.nl/?url=" + URLEncoder.encode(normalized, "UTF-8") +
            "&w=$width&output=webp&q=82&we"
    }

    fun parse(text: String, category: CatalogCategory): CatalogPage {
        val root = JSONObject(text)
        require(root.optInt("code") == 200) {
            root.optString("msg", "在线目录加载失败").take(120)
        }
        val data = root.optJSONObject("data") ?: error("在线目录格式已变化")
        val list = data.optJSONArray("list") ?: error("在线目录缺少图片列表")
        require(list.length() <= 100) { "单页条目异常" }

        val items = (0 until list.length()).mapNotNull { i ->
            val item = list.optJSONObject(i) ?: return@mapNotNull null
            val rawImage = item.optString(
                when (category) {
                    CatalogCategory.BANK -> "image"
                    CatalogCategory.TRANSIT -> "filename"
                    CatalogCategory.IP, CatalogCategory.ANIME -> "productImagePath"
                },
            )
            val image = normalizeImageUrl(rawImage) ?: return@mapNotNull null
            val title = item.optString(
                when (category) {
                    CatalogCategory.BANK -> "description"
                    CatalogCategory.TRANSIT -> "title"
                    CatalogCategory.IP, CatalogCategory.ANIME -> "productName"
                },
            ).trim().take(160).ifBlank { "未命名卡面" }
            val subtitle = when (category) {
                CatalogCategory.BANK -> item.optJSONObject("issuer")?.optString("native_name").orEmpty()
                CatalogCategory.TRANSIT -> item.optString("city")
                CatalogCategory.IP, CatalogCategory.ANIME -> item.optString("ipName")
            }.trim().take(120)
            val id = item.optString(
                if (category == CatalogCategory.IP || category == CatalogCategory.ANIME) "productId" else "id",
            ).takeIf { it.isNotBlank() && it != "null" } ?: sha1(image).take(16)
            CatalogItem(
                id = id,
                category = category,
                title = title,
                subtitle = subtitle,
                imageUrl = image,
                source = if (category == CatalogCategory.IP || category == CatalogCategory.ANIME) {
                    "IP 联动"
                } else {
                    item.optString("source").take(100)
                },
            )
        }.distinctBy { it.key }

        val page = data.optInt("page", 1).coerceAtLeast(1)
        val total = data.optInt("total", items.size).coerceIn(0, 1_000_000)
        val pages = data.optInt(
            "total_pages",
            maxOf(1, (total + PAGE_SIZE - 1) / PAGE_SIZE),
        ).coerceIn(1, 10_000)
        return CatalogPage(items, page, pages, total)
    }

    fun sha1(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
