package com.dsh.miuix.cardface.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EditorDraftStateDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun sameCardSnapshotsRemainIndependent() = runBlocking {
        val red = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val blue = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val url = "https://draft-test.example/card"
        val first = EditorDraftState.saveBitmap(context.cacheDir, red, url)
        val second = EditorDraftState.saveBitmap(context.cacheDir, blue, url, first)
        assertNotNull(first)
        assertNotNull(second)
        assertNotEquals(first, second)
        assertEquals(Color.RED, EditorDraftState.restoreBitmap(first)!!.getPixel(0, 0))
        assertEquals(Color.BLUE, EditorDraftState.restoreBitmap(second)!!.getPixel(0, 0))
    }

    @Test fun bundleRestoresMatchingImageAndScalars() = runBlocking {
        val model = AppModel(context)
        model.openEditor("https://draft-test.example/restore")
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        model.editor.localBitmap = bitmap
        model.editor.localCrop = RectF(1f, 2f, 6f, 7f)
        model.editor.logoAsset = "jingjinji"
        model.editor.roundCorners = false
        model.draftBitmapPath = EditorDraftState.saveBitmap(context.cacheDir, bitmap, model.editor.url)
        model.persistedDraftBitmap = bitmap
        val bundle = Bundle()
        EditorDraftState.saveScalars(bundle, model)
        val restored = AppModel(context)
        val path = EditorDraftState.restoreScalars(bundle, restored)
        assertEquals(model.editor.url, restored.editor.url)
        assertEquals(model.editor.localCrop, restored.editor.localCrop)
        assertEquals("jingjinji", restored.editor.logoAsset)
        assertFalse(restored.editor.roundCorners)
        assertTrue(restored.editor.restoringBitmap)
        assertEquals(Color.GREEN, EditorDraftState.restoreBitmap(path)!!.getPixel(0, 0))
        model.dispose()
        restored.dispose()
    }

    @Test fun unpublishedImageDoesNotRestorePreviousImage() = runBlocking {
        val model = AppModel(context)
        model.openEditor("https://draft-test.example/pending")
        val old = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        model.draftBitmapPath = EditorDraftState.saveBitmap(context.cacheDir, old, model.editor.url)
        model.persistedDraftBitmap = old
        model.editor.localBitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val bundle = Bundle()
        EditorDraftState.saveScalars(bundle, model)
        assertNull(bundle.getString("cardface.draft.bitmapPath"))
        model.dispose()
    }
}
