package it.rfmariano.nfcdroid

import android.nfc.NdefMessage
import android.nfc.Tag
import android.nfc.tech.NfcA
import java.io.IOException
import kotlin.math.min

object Ntag215Manager {
    private const val GET_VERSION_COMMAND: Byte = 0x60
    private const val READ_COMMAND: Byte = 0x30
    private const val WRITE_COMMAND: Byte = 0xA2.toByte()
    private const val PASSWORD_AUTH_COMMAND: Byte = 0x1B

    private const val VERSION_RESPONSE_LENGTH = 8
    private const val NXP_VENDOR_ID = 0x04
    private const val NTAG21X_PRODUCT_TYPE = 0x04
    private const val NTAG215_STORAGE_SIZE = 0x11

    private const val FIRST_USER_PAGE = 4
    private const val USER_MEMORY_BYTES = 504
    private const val CONFIG_PAGE = 131
    private const val ACCESS_PAGE = 132
    private const val PASSWORD_PAGE = 133
    private const val PACK_PAGE = 134
    private const val PROTECTION_DISABLED = 0xFF
    private const val PROT_BIT = 0x80

    data class TagSecurityInfo(
        val isNtag215: Boolean,
        val isWriteProtected: Boolean,
        val auth0Page: Int,
        val protectRead: Boolean,
        val pack: ByteArray
    )

    fun inspect(tag: Tag): TagSecurityInfo? {
        val nfcA = NfcA.get(tag) ?: return null
        return use(nfcA) {
            if (!isNtag215(it)) return@use null
            readSecurityInfo(it)
        }
    }

    fun protect(tag: Tag, passwordInput: String, protectRead: Boolean = false): TagSecurityInfo {
        val nfcA = NfcA.get(tag) ?: throw IOException("NFC-A is unavailable for this tag.")
        val password = parsePassword(passwordInput)
        return use(nfcA) {
            ensureNtag215(it)
            val security = readSecurityInfo(it)
            val configBytes = readConfigurationBytes(it)
            val cfg0 = configBytes.copyOfRange(0, 4)
            val cfg1 = configBytes.copyOfRange(4, 8)
            val packPage = configBytes.copyOfRange(12, 16)

            cfg1[0] = if (protectRead) {
                (cfg1[0].toInt() or PROT_BIT).toByte()
            } else {
                (cfg1[0].toInt() and PROT_BIT.inv()).toByte()
            }
            cfg0[3] = FIRST_USER_PAGE.toByte()

            writePage(it, PASSWORD_PAGE, password)
            val derivedPack = derivePack(password)
            packPage[0] = derivedPack[0]
            packPage[1] = derivedPack[1]
            writePage(it, PACK_PAGE, packPage)
            writePage(it, ACCESS_PAGE, cfg1)
            writePage(it, CONFIG_PAGE, cfg0)

            readSecurityInfo(it).copy(pack = derivedPack)
        }
    }

    fun unprotect(tag: Tag, passwordInput: String): TagSecurityInfo {
        val nfcA = NfcA.get(tag) ?: throw IOException("NFC-A is unavailable for this tag.")
        val password = parsePassword(passwordInput)
        return use(nfcA) {
            ensureNtag215(it)
            val configBytes = readConfigurationBytes(it)
            authenticate(it, password)

            val cfg0 = configBytes.copyOfRange(0, 4)
            val cfg1 = configBytes.copyOfRange(4, 8)
            val packPage = configBytes.copyOfRange(12, 16)

            cfg0[3] = PROTECTION_DISABLED.toByte()
            cfg1[0] = (cfg1[0].toInt() and PROT_BIT.inv()).toByte()
            packPage[0] = 0x00
            packPage[1] = 0x00

            writePage(it, CONFIG_PAGE, cfg0)
            writePage(it, ACCESS_PAGE, cfg1)
            writePage(it, PASSWORD_PAGE, byteArrayOf(0x00, 0x00, 0x00, 0x00))
            writePage(it, PACK_PAGE, packPage)

            readSecurityInfo(it)
        }
    }

    fun writeProtectedNdef(tag: Tag, message: NdefMessage, passwordInput: String): TagSecurityInfo {
        val nfcA = NfcA.get(tag) ?: throw IOException("NFC-A is unavailable for this tag.")
        val password = parsePassword(passwordInput)
        return use(nfcA) {
            ensureNtag215(it)
            val security = readSecurityInfo(it)
            if (!security.isWriteProtected) {
                throw IOException("Tag is not password protected.")
            }
            authenticate(it, password)
            val encoded = encodeNdefTlv(message.toByteArray())
            require(encoded.size <= USER_MEMORY_BYTES) { "NDEF message is too large for NTAG215." }
            writeUserMemory(it, encoded)
            readSecurityInfo(it)
        }
    }

    internal fun parsePassword(input: String): ByteArray {
        val trimmed = input.trim()
        if (trimmed.length == 4) {
            return trimmed.encodeToByteArray()
        }

        val compactHex = trimmed.replace(" ", "")
        if (compactHex.length == 8 && compactHex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return ByteArray(4) { index ->
                compactHex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }

        throw IllegalArgumentException("Use a 4-character password or 8 hex digits.")
    }

    internal fun derivePack(password: ByteArray): ByteArray {
        require(password.size == 4) { "Password must be 4 bytes." }
        return byteArrayOf(
            (password[0].toInt() xor password[2].toInt()).toByte(),
            (password[1].toInt() xor password[3].toInt()).toByte()
        )
    }

    internal fun encodeNdefTlv(messageBytes: ByteArray): ByteArray {
        val header = if (messageBytes.size < 0xFF) {
            byteArrayOf(0x03, messageBytes.size.toByte())
        } else {
            byteArrayOf(
                0x03,
                0xFF.toByte(),
                ((messageBytes.size shr 8) and 0xFF).toByte(),
                (messageBytes.size and 0xFF).toByte()
            )
        }
        return header + messageBytes + byteArrayOf(0xFE.toByte())
    }

    internal fun decodeSecurityInfo(configurationBytes: ByteArray): TagSecurityInfo {
        require(configurationBytes.size >= 16) { "Configuration payload must include pages 131-134." }
        val auth0 = configurationBytes[3].toInt() and 0xFF
        val access = configurationBytes[4].toInt() and 0xFF
        return TagSecurityInfo(
            isNtag215 = true,
            isWriteProtected = auth0 != PROTECTION_DISABLED,
            auth0Page = auth0,
            protectRead = access and PROT_BIT != 0,
            pack = byteArrayOf(configurationBytes[12], configurationBytes[13])
        )
    }

    private fun readSecurityInfo(nfcA: NfcA): TagSecurityInfo = decodeSecurityInfo(readConfigurationBytes(nfcA))

    private fun readConfigurationBytes(nfcA: NfcA): ByteArray = readPageWindow(nfcA, CONFIG_PAGE)

    private fun authenticate(nfcA: NfcA, password: ByteArray): ByteArray {
        val response = nfcA.transceive(byteArrayOf(PASSWORD_AUTH_COMMAND) + password)
        if (response.size < 2) {
            throw IOException("Password authentication failed.")
        }
        return response.copyOfRange(0, min(2, response.size))
    }

    private fun writeUserMemory(nfcA: NfcA, data: ByteArray) {
        val padded = data.copyOf(((data.size + 3) / 4) * 4)
        padded.asList().chunked(4).forEachIndexed { index, chunk ->
            writePage(nfcA, FIRST_USER_PAGE + index, chunk.toByteArray())
        }
    }

    private fun readPageWindow(nfcA: NfcA, page: Int): ByteArray {
        return nfcA.transceive(byteArrayOf(READ_COMMAND, page.toByte()))
    }

    private fun writePage(nfcA: NfcA, page: Int, bytes: ByteArray) {
        require(bytes.size == 4) { "Exactly 4 bytes must be written per page." }
        nfcA.transceive(byteArrayOf(WRITE_COMMAND, page.toByte()) + bytes)
    }

    private fun ensureNtag215(nfcA: NfcA) {
        if (!isNtag215(nfcA)) {
            throw IOException("This tag is not an NXP NTAG215.")
        }
    }

    private fun isNtag215(nfcA: NfcA): Boolean {
        val version = nfcA.transceive(byteArrayOf(GET_VERSION_COMMAND))
        return version.size >= VERSION_RESPONSE_LENGTH &&
            (version[1].toInt() and 0xFF) == NXP_VENDOR_ID &&
            (version[2].toInt() and 0xFF) == NTAG21X_PRODUCT_TYPE &&
            (version[6].toInt() and 0xFF) == NTAG215_STORAGE_SIZE
    }

    private inline fun <T> use(nfcA: NfcA, block: (NfcA) -> T): T {
        try {
            nfcA.connect()
            return block(nfcA)
        } finally {
            try {
                nfcA.close()
            } catch (_: IOException) {
            }
        }
    }
}
