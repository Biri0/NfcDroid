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
    fun editableRecordsFromMessage_supportsTextLinkPhoneAndEmail() {
        val message = NdefMessage(
            arrayOf(
                NdefRecord.createTextRecord("en", "hello"),
                NdefRecord.createUri("https://example.com"),
                NdefRecord.createUri("tel:+123456789"),
                NdefRecord.createUri("mailto:test@example.com")
            )
        )

        val parsed = NdefTextCodec.editableRecordsFromMessage(message)

        assertEquals(
            listOf(
                NdefTextCodec.EditableRecord(
                    originalRecordIndex = 0,
                    type = NdefTextCodec.EditableRecordType.TEXT,
                    value = "hello"
                ),
                NdefTextCodec.EditableRecord(
                    originalRecordIndex = 1,
                    type = NdefTextCodec.EditableRecordType.LINK,
                    value = "https://example.com"
                ),
                NdefTextCodec.EditableRecord(
                    originalRecordIndex = 2,
                    type = NdefTextCodec.EditableRecordType.PHONE,
                    value = "+123456789"
                ),
                NdefTextCodec.EditableRecord(
                    originalRecordIndex = 3,
                    type = NdefTextCodec.EditableRecordType.EMAIL,
                    value = "test@example.com"
                )
            ),
            parsed
        )
    }

    @Test
    fun patchMessage_preservesUnsupportedRecordsAndAppendsNewMixedRecords() {
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
                NdefRecord.createUri("tel:+111")
            )
        )
        val edited = listOf(
            NdefTextCodec.EditableRecord(
                originalRecordIndex = 0,
                type = NdefTextCodec.EditableRecordType.TEXT,
                value = "  new-1  "
            ),
            NdefTextCodec.EditableRecord(
                originalRecordIndex = 2,
                type = NdefTextCodec.EditableRecordType.PHONE,
                value = "  +222  "
            ),
            NdefTextCodec.EditableRecord(
                originalRecordIndex = null,
                type = NdefTextCodec.EditableRecordType.EMAIL,
                value = "  new@example.com  "
            ),
            NdefTextCodec.EditableRecord(
                originalRecordIndex = null,
                type = NdefTextCodec.EditableRecordType.LINK,
                value = "  https://github.com  "
            )
        )

        val patched = NdefTextCodec.patchMessage(original, edited)
        val parsedPatchedRecords = NdefTextCodec.editableRecordsFromMessage(patched)

        assertEquals(5, patched.records.size)
        assertEquals(mimeRecord.tnf, patched.records[1].tnf)
        assertTrue(mimeRecord.type.contentEquals(patched.records[1].type))
        assertTrue(mimeRecord.payload.contentEquals(patched.records[1].payload))
        assertEquals(
            listOf(
                NdefTextCodec.EditableRecord(
                    originalRecordIndex = 0,
                    type = NdefTextCodec.EditableRecordType.TEXT,
                    value = "new-1"
                ),
                NdefTextCodec.EditableRecord(
                    originalRecordIndex = 2,
                    type = NdefTextCodec.EditableRecordType.PHONE,
                    value = "+222"
                ),
                NdefTextCodec.EditableRecord(
                    originalRecordIndex = 3,
                    type = NdefTextCodec.EditableRecordType.EMAIL,
                    value = "new@example.com"
                ),
                NdefTextCodec.EditableRecord(
                    originalRecordIndex = 4,
                    type = NdefTextCodec.EditableRecordType.LINK,
                    value = "https://github.com"
                )
            ),
            parsedPatchedRecords
        )
    }
}
