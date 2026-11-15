package com.dsh.miuix.cardface.ui

import org.junit.Assert.*
import org.junit.Test

class ExternalEditorNavigationTest {
    @Test fun warmRequestReplacesOldEditorAndHistory() {
        val nav = NavState(listOf(Route(Page.Home), Route(Page.Editor, "https://cards.example/a"), Route(Page.Crop)))
        nav.openExternalEditor("https://cards.example/b")
        assertEquals(listOf(Route(Page.Editor, "https://cards.example/b")), nav.routes)
        assertFalse(nav.canGoBack)
        assertFalse(nav.pop())
    }

    @Test fun sameCardRequestDoesNotAccumulateRoutes() {
        val nav = NavState(listOf(Route(Page.Editor, "https://cards.example/a")))
        repeat(3) { nav.openExternalEditor("https://cards.example/a") }
        assertEquals(1, nav.routes.size)
        assertEquals(NavDirection.Tab, nav.direction)
    }

    @Test fun externalRequestFromHomeIsAnEditorRoot() {
        val nav = NavState()
        nav.openExternalEditor("https://cards.example/a")
        assertEquals(Page.Editor, nav.current.page)
        assertEquals("https://cards.example/a", nav.current.arg)
        assertFalse(nav.canGoBack)
    }
}
