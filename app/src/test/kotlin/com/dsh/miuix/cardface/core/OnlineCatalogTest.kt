package com.dsh.miuix.cardface.core

import org.junit.Assert.*
import org.junit.Test

class OnlineCatalogTest {
    private fun page(row:String)="""{"code":200,"data":{"list":[$row],"total":48,"page":1,"page_size":24,"total_pages":2}}"""
    private val image="https://storage.my-api.cn/static/images/test.png"
    @Test fun bankFields(){val c=OnlineCatalog.parse(page("""{"id":2,"description":"主题卡","image":"$image","issuer":{"native_name":"某银行"},"source":"云闪付"}"""),OnlineCategory.BANK).cards.single();assertEquals("主题卡",c.title);assertEquals("某银行",c.subtitle);assertEquals("face:2",c.key)}
    @Test fun transitFields(){val c=OnlineCatalog.parse(page("""{"id":3,"title":"交通卡","filename":"$image","city":"上海","source":"Mi Pay"}"""),OnlineCategory.TRANSIT).cards.single();assertEquals("上海",c.subtitle);assertEquals("交通卡",c.title)}
    @Test fun ipFields(){val c=OnlineCatalog.parse(page("""{"productId":5,"productName":"野营","productImagePath":"$image","ipName":"小兔"}"""),OnlineCategory.IP).cards.single();assertEquals("小兔",c.subtitle);assertEquals("face_ctu_origin:5",c.key)}
    @Test fun correctPagination(){val p=OnlineCatalog.parse(page("""{"id":1,"image":"$image"}"""),OnlineCategory.BANK);assertEquals(48,p.total);assertEquals(2,p.pages)}
    @Test fun queryEscapesChineseAndAmpersand(){val q=OnlineCatalog.query(OnlineCategory.BANK,2,"中国&卡","银行");assertTrue(q.contains("page=2"));assertTrue(q.contains("page_size=24"));assertTrue(q.contains("%26"));assertTrue(q.contains("native_name="))}
    @Test fun categoryFilterKeys(){assertTrue(OnlineCatalog.query(OnlineCategory.TRANSIT,1,"","上海").contains("city="));assertTrue(OnlineCatalog.query(OnlineCategory.IP,1,"","小兔").contains("ip="))}
    @Test fun rejectsUnexpectedHost(){assertFalse(OnlineCatalog.validImageUrl("https://example.com/static/images/x.png"))}
    @Test fun rejectsUserInfo(){assertFalse(OnlineCatalog.validImageUrl("https://user@storage.my-api.cn/static/images/x.png"))}
    @Test fun rejectsHttp(){assertFalse(OnlineCatalog.validImageUrl("http://storage.my-api.cn/static/images/x.png"))}
    @Test fun rejectsTraversal(){assertFalse(OnlineCatalog.validImageUrl("https://storage.my-api.cn/static/images/../x.png"))}
    @Test fun rejectsEncodedTraversal(){assertFalse(OnlineCatalog.validImageUrl("https://storage.my-api.cn/static/images/%2e%2e/x.png"))}
    @Test fun supportsChineseAndSpaces(){assertEquals("https://storage.my-api.cn/static/images/%E5%B0%8F%E5%85%94/%E9%87%8E%20%E8%90%A5.png",OnlineCatalog.normalizeImageUrl("https://storage.my-api.cn/static/images/小兔/野 营.png"))}
    @Test fun keepsExistingEscape(){assertEquals("https://storage.my-api.cn/static/images/a%20b.png",OnlineCatalog.normalizeImageUrl("https://storage.my-api.cn/static/images/a%20b.png"))}
    @Test fun skipsInvalidImages(){assertTrue(OnlineCatalog.parse(page("""{"id":1,"image":"https://other.example/x.png"}"""),OnlineCategory.BANK).cards.isEmpty())}
    @Test(expected=IllegalArgumentException::class) fun apiErrorIsNotEmptySuccess(){OnlineCatalog.parse("""{"code":500,"msg":"maintenance"}""",OnlineCategory.BANK)}
    @Test(expected=IllegalStateException::class) fun changedResponseIsExplicitError(){OnlineCatalog.parse("""{"code":200,"data":"changed"}""",OnlineCategory.BANK)}
    @Test fun duplicatesAreDeduplicated(){val row="""{"id":1,"image":"$image"}""";assertEquals(1,OnlineCatalog.parse(page("$row,$row"),OnlineCategory.BANK).cards.size)}
    @Test fun missingIdUsesContentStableKey(){val row="""{"image":"$image"}""";val a=OnlineCatalog.parse(page(row),OnlineCategory.BANK).cards.single();assertEquals(64,a.id.length)}
    @Test fun sameIdInDifferentCategoriesDoesNotCollide(){assertNotEquals(OnlineCard("1",OnlineCategory.BANK,"","",image,"").key,OnlineCard("1",OnlineCategory.IP,"","",image,"").key)}
    @Test(expected=IllegalArgumentException::class) fun oversizedSearchIsRejected(){OnlineCatalog.query(OnlineCategory.BANK,1,"x".repeat(121),"")}
}
