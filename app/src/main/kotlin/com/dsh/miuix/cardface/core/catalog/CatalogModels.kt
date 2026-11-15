package com.dsh.miuix.cardface.core.catalog

/** 卡面库分类：对应在线目录的三种资源。 */
enum class CatalogCategory(
    val label: String,
    val resource: String,
    val webPath: String,
    val filterKey: String,
    val filterLabel: String,
    val searchKey: String = "search",
) {
    BANK("银行卡面", "face", "face", "issuer", "银行名称（可选）"),
    TRANSIT("交通卡面", "face_ctu", "face-ctu", "city", "城市 / 卡种（可选）"),
    IP("IP 联动", "face_ctu_origin", "face-ctu-origin", "ipName", "IP 名称（可选）", searchKey = "productName");

    val webUrl: String get() = "https://hi.my-api.cn/$webPath"
}

data class CatalogItem(
    val id: String,
    val category: CatalogCategory,
    val title: String,
    val subtitle: String,
    val imageUrl: String,
    val source: String,
) {
    val key: String get() = "${category.resource}:$id"
}

data class CatalogPage(
    val items: List<CatalogItem>,
    val page: Int,
    val pages: Int,
    val total: Int,
    val cached: Boolean = false,
)
