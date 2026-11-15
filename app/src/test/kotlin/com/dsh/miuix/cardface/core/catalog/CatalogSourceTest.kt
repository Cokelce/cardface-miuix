package com.dsh.miuix.cardface.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSourceTest {

    @Test
    fun `bank catalog parses pagination and fields`() {
        val json = """
            {"code":200,"msg":"ok","data":{"list":[
              {"id":700,"description":"杏子","source":"Other",
               "image":"https://storage.my-api.cn/static/images/new_upload/a.png",
               "issuer":{"native_name":"中国建设银行"}}
            ],"page":2,"total":695,"total_pages":232}}
        """.trimIndent()
        val page = HibankCatalogSource.parse(json, CatalogCategory.BANK)
        assertEquals(1, page.items.size)
        assertEquals("杏子", page.items[0].title)
        assertEquals("中国建设银行", page.items[0].subtitle)
        assertEquals(2, page.page)
        assertEquals(232, page.pages)
        assertEquals(695, page.total)
    }

    @Test
    fun `transit and ip use different image fields`() {
        val transit = """{"code":200,"data":{"list":[{"id":31,"city":"上海一卡通","title":"碧蓝档案",
            "filename":"https://storage.my-api.cn/static/images/pass/a.png","source":"Mi Pay"}],
            "page":1,"total":1,"total_pages":1}}"""
        val t = HibankCatalogSource.parse(transit, CatalogCategory.TRANSIT)
        assertEquals("上海一卡通", t.items[0].subtitle)
        assertEquals("碧蓝档案", t.items[0].title)

        val ip = """{"code":200,"data":{"list":[{"productId":15351,"productName":"晒背",
            "productImagePath":"https://storage.my-api.cn/static/images/pass2/ip/a.png",
            "ipName":"艾丝乐小兔"}],"page":1,"total":1,"total_pages":1}}"""
        val i = HibankCatalogSource.parse(ip, CatalogCategory.IP)
        assertEquals("艾丝乐小兔", i.items[0].subtitle)
    }

    @Test
    fun `rejects non-allowlisted or unsafe image urls`() {
        assertNull(HibankCatalogSource.normalizeImageUrl("http://storage.my-api.cn/static/images/a.png"))
        assertNull(HibankCatalogSource.normalizeImageUrl("https://evil.example/static/images/a.png"))
        assertNull(HibankCatalogSource.normalizeImageUrl("https://storage.my-api.cn/other/a.png"))
        assertNull(HibankCatalogSource.normalizeImageUrl("https://storage.my-api.cn/static/images/../a.png"))
        assertTrue(HibankCatalogSource.validImageUrl("https://storage.my-api.cn/static/images/a.png"))
        assertFalse(HibankCatalogSource.validImageUrl(""))
    }

    @Test
    fun `bad response surfaces error`() {
        val error = runCatching {
            HibankCatalogSource.parse("""{"code":403,"msg":"forbidden"}""", CatalogCategory.BANK)
        }.exceptionOrNull()
        assertTrue(error != null && error.message!!.contains("forbidden"))
    }
}
