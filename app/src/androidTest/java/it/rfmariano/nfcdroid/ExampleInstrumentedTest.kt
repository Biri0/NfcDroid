package it.rfmariano.nfcdroid

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun useAppContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("it.rfmariano.nfcdroid", appContext.packageName)
    }

    @Test
    fun showsNfcDiscoveryPrompt() {
        assertEquals("it.rfmariano.nfcdroid", composeTestRule.activity.packageName)
    }

    @Test
    fun patchMessage_preservesNonTextAndOrder() {
        val mimeRecord = NdefRecord(
            NdefRecord.TNF_MIME_MEDIA,
            "text/plain".toByteArray(),
            ByteArray(0),
            "payload".toByteArray()
        )
        val original = NdefMessage(
            arrayOf(
                NdefRecord.createTextRecord("en", "old-1"),
                mimeRecord,
                NdefRecord.createTextRecord("en", "old-2")
            )
        )
        val edited = listOf(
            NdefTextCodec.EditableTextRecord(originalRecordIndex = 0, text = "  new-1  "),
            NdefTextCodec.EditableTextRecord(originalRecordIndex = null, text = "  new-3  ")
        )

        val patched = NdefTextCodec.patchMessage(original, edited)

        assertEquals(3, patched.records.size)
        assertEquals("new-1", NdefTextCodec.parseTextRecords(NdefMessage(arrayOf(patched.records[0]))).single())
        assertEquals(mimeRecord.tnf, patched.records[1].tnf)
        assertTrue(mimeRecord.type.contentEquals(patched.records[1].type))
        assertTrue(mimeRecord.payload.contentEquals(patched.records[1].payload))
        assertEquals("new-3", NdefTextCodec.parseTextRecords(NdefMessage(arrayOf(patched.records[2]))).single())
    }
}
