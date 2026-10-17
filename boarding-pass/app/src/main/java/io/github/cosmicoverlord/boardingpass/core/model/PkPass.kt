package io.github.cosmicoverlord.boardingpass.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Apple Wallet (.pkpass) support: the barcode payload lives in pass.json inside the zip. */
object PkPass {
    fun isZip(bytes: ByteArray) = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    fun extract(bytes: ByteArray): List<DecodedCode> {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "pass.json") return fromPassJson(zip.readBytes().toString(Charsets.UTF_8))
            }
        }
        return emptyList()
    }

    fun fromPassJson(text: String): List<DecodedCode> {
        val root = AppJson.parseToJsonElement(text.trimStart('﻿')) as? JsonObject ?: return emptyList()
        val codes = mutableListOf<JsonElement>()
        (root["barcodes"] as? JsonArray)?.let { codes.addAll(it) }
        root["barcode"]?.let { codes.add(it) }
        return codes.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val message = (o["message"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val format = when ((o["format"] as? JsonPrimitive)?.contentOrNull) {
                "PKBarcodeFormatQR" -> CodeFormat.QR_CODE
                "PKBarcodeFormatPDF417" -> CodeFormat.PDF_417
                "PKBarcodeFormatAztec" -> CodeFormat.AZTEC
                "PKBarcodeFormatCode128" -> CodeFormat.CODE_128
                else -> CodeFormat.QR_CODE
            }
            DecodedCode(message, format)
        }.distinctBy { it.raw }
    }
}
