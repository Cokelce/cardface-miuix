package com.dsh.miuix.cardface.core

import org.junit.Assert.*
import org.junit.Test

class CardNameResolverTest {
    class WalletCard { fun getDisplayName() = "  北京交通卡  " }
    class DetailCard { fun getCardName() = "门禁卡" }
    class SchoolCard { fun getSchoolName() = "校园卡" }
    class EmptyDisplay { fun getDisplayName() = " "; fun getCardName() = "公交卡" }
    class ThrowingDisplay { fun getDisplayName(): String = error("unavailable"); fun getCardName() = "交通卡" }
    class NoName { fun getCardNo() = "123456789" }
    @Test fun displayNameGetter() { assertEquals("北京交通卡", CardNameResolver.fromCard(WalletCard())) }
    @Test fun detailNameGetter() { assertEquals("门禁卡", CardNameResolver.fromCard(DetailCard())) }
    @Test fun schoolNameGetter() { assertEquals("校园卡", CardNameResolver.fromCard(SchoolCard())) }
    @Test fun blankFallsBack() { assertEquals("公交卡", CardNameResolver.fromCard(EmptyDisplay())) }
    @Test fun getterFailureDoesNotBreakCard() { assertEquals("交通卡", CardNameResolver.fromCard(ThrowingDisplay())) }
    @Test fun doesNotUsePrivateCardNumber() { assertNull(CardNameResolver.fromCard(NoName())) }
    @Test fun nullCard() { assertNull(CardNameResolver.fromCard(null)) }
    @Test fun manualNameWins() { assertEquals("我的卡", CardNameResolver.displayName("我的卡", "钱包名称", "https://x/uuid.png")) }
    @Test fun detectedNameWinsOverFileName() { assertEquals("交通卡", CardNameResolver.displayName(null, "交通卡", "https://x/uuid.png")) }
    @Test fun clearManualReturnsDetected() { assertEquals("门卡", CardNameResolver.displayName(" ", "门卡", "https://x/id.png")) }
    @Test fun fallsBackToFileName() { assertEquals("face.png", CardNameResolver.displayName(null, null, "https://x/face.png?q=1")) }
    @Test fun rejectsInvalidNames() { assertNull(CardNameResolver.clean("null")); assertNull(CardNameResolver.clean("https://x")); assertNull(CardNameResolver.clean("a".repeat(121))) }
    @Test fun removesControlCharacters() { assertEquals("公交 卡", CardNameResolver.clean("公交\n卡")) }
    @Test fun modelUsesDetectedName() { assertEquals("公交卡", CardFaceItem("https://x/id.png", null, detectedName = "公交卡").displayName) }
}
