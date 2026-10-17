package io.github.cosmicoverlord.boardingpass.importer

import android.content.Context
import android.net.Uri
import io.github.cosmicoverlord.boardingpass.core.bcbp.BcbpParser
import io.github.cosmicoverlord.boardingpass.core.model.BoardingPass
import io.github.cosmicoverlord.boardingpass.core.model.DecodedCode
import io.github.cosmicoverlord.boardingpass.core.model.PassFactory
import io.github.cosmicoverlord.boardingpass.core.model.PkPass
import io.github.cosmicoverlord.boardingpass.data.PassRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.cosmicoverlord.boardingpass.core.tr

sealed interface ImportOutcome {
    /** [ids] of the passes that were added or already existed, in barcode order. */
    data class Added(val ids: List<String>, val newCount: Int) : ImportOutcome

    /** A barcode was found but it is not IATA BCBP data. */
    data class NotBoardingPass(val code: DecodedCode) : ImportOutcome

    data class Failed(val message: String) : ImportOutcome
}

class PassImporter(
    private val context: Context,
    private val repo: PassRepository,
    private val decoder: BarcodeDecoder,
) {
    suspend fun importUri(uri: Uri): ImportOutcome = try {
        importCodes(decodeUri(uri))
    } catch (e: ImportException) {
        ImportOutcome.Failed(e.message ?: tr("Не удалось прочитать файл", "Couldn't read the file"))
    } catch (e: CancellationException) {
        throw e
    } catch (e: SecurityException) {
        ImportOutcome.Failed(tr("Нет доступа к файлу", "No access to the file"))
    } catch (e: OutOfMemoryError) {
        ImportOutcome.Failed(tr("Файл слишком большой", "The file is too large"))
    } catch (e: Exception) {
        // FileNotFoundException for expired grants, IOExceptions from PdfRenderer, etc.
        ImportOutcome.Failed(tr("Не удалось прочитать файл", "Couldn't read the file"))
    }

    suspend fun importCodes(codes: List<DecodedCode>): ImportOutcome {
        if (codes.isEmpty()) {
            return ImportOutcome.Failed(tr("Штрихкод не найден. Попробуйте скриншот покрупнее или сканирование камерой", "No barcode found. Try a larger screenshot or the camera scanner"))
        }
        val parsed = codes.mapNotNull { code -> BcbpParser.parseOrNull(code.raw)?.let { code to it } }
        if (parsed.isEmpty()) return ImportOutcome.NotBoardingPass(codes.first())

        val ids = mutableListOf<String>()
        var newCount = 0
        repo.update { list ->
            val result = list.toMutableList()
            parsed.forEach { (code, bcbp) ->
                PassFactory.fromBcbp(bcbp, code.format).forEach { candidate ->
                    val existing = result.firstOrNull { it.barcode == candidate.barcode && it.legIndex == candidate.legIndex }
                    if (existing != null) {
                        ids += existing.id
                    } else {
                        result += candidate
                        ids += candidate.id
                        newCount++
                    }
                }
            }
            result
        }
        return ImportOutcome.Added(ids, newCount)
    }

    suspend fun saveUnparsed(code: DecodedCode): BoardingPass {
        val pass = PassFactory.manual(code)
        repo.upsert(pass)
        return pass
    }

    private suspend fun decodeUri(uri: Uri): List<DecodedCode> {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri).orEmpty()
        val head = withContext(Dispatchers.IO) {
            resolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(8)
                val n = input.read(buf)
                if (n > 0) buf.copyOf(n) else ByteArray(0)
            }
        } ?: throw ImportException(tr("Не удалось открыть файл", "Couldn't open the file"))

        val isPdf = head.size >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "%PDF"
        return when {
            isPdf || mime == "application/pdf" -> decoder.decodePdf(uri)
            PkPass.isZip(head) || mime.contains("pkpass") -> {
                val bytes = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: throw ImportException(tr("Не удалось открыть файл", "Couldn't open the file"))
                try {
                    PkPass.extract(bytes)
                } catch (e: Exception) {
                    throw ImportException(tr("Не удалось прочитать .pkpass", "Couldn't read the .pkpass"))
                }
            }
            else -> decoder.decodeImage(uri)
        }
    }
}
