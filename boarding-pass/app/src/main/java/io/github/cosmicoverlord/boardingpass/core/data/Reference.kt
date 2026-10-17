package io.github.cosmicoverlord.boardingpass.core.data

import java.io.InputStream
import java.time.ZoneId
import java.util.IllformedLocaleException
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class Airport(
    val iata: String,
    val name: String,
    val city: String,
    val country: String,
    val lat: Double,
    val lon: Double,
    val tz: String?,
) {
    val zone: ZoneId? get() = tz?.let { runCatching { ZoneId.of(it) }.getOrNull() }
    val displayCity: String get() = city.ifBlank { name }
}

/** Airports by IATA code, from OurAirports (public domain) with time zones from mwgg/Airports. */
class AirportDb(private val byCode: Map<String, Airport>) {
    operator fun get(code: String?): Airport? = code?.let { byCode[it.trim().uppercase()] }
    val size: Int get() = byCode.size

    companion object {
        fun parse(input: InputStream): AirportDb {
            val map = HashMap<String, Airport>(10_000)
            input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val p = line.split('\t')
                    if (p.size >= 7) {
                        map[p[0]] = Airport(
                            iata = p[0],
                            name = p[1],
                            city = p[2],
                            country = p[3],
                            lat = p[4].toDoubleOrNull() ?: 0.0,
                            lon = p[5].toDoubleOrNull() ?: 0.0,
                            tz = p[6].ifBlank { null },
                        )
                    }
                }
            }
            return AirportDb(map)
        }
    }
}

/** Airlines by IATA designator, from OpenFlights (ODbL) plus fresher overrides. */
class AirlineDb(private val byCode: Map<String, String>) {
    fun name(code: String?): String? = code?.let { byCode[it.trim().uppercase()] }

    companion object {
        private val overrides = mapOf(
            "SU" to "Аэрофлот",
            "FV" to "Россия",
            "DP" to "Победа",
            "S7" to "S7 Airlines",
            "U6" to "Уральские авиалинии",
            "UT" to "ЮТэйр",
            "5N" to "Smartavia",
            "N4" to "Nordwind",
            "WZ" to "Red Wings",
            "ZF" to "Azur Air",
            "A4" to "Азимут",
            "IO" to "ИрАэро",
            "R3" to "Якутия",
            "Y7" to "NordStar",
            "6R" to "Алроса",
            "7R" to "РусЛайн",
            "EO" to "Ikar (Pegas Fly)",
            "HY" to "Uzbekistan Airways",
            "KC" to "Air Astana",
            "DV" to "SCAT",
            "J2" to "Azerbaijan Airlines",
            "B2" to "Belavia",
            "FZ" to "flydubai",
            "G9" to "Air Arabia",
            "PC" to "Pegasus",
            "XQ" to "SunExpress",
            "W6" to "Wizz Air",
            "FR" to "Ryanair",
            "U2" to "easyJet",
            "VY" to "Vueling",
            "QR" to "Qatar Airways",
            "EY" to "Etihad Airways",
            "TK" to "Turkish Airlines",
            "AJ" to "AJet",
            "MU" to "China Eastern",
            "CZ" to "China Southern",
            "CA" to "Air China",
            "HU" to "Hainan Airlines",
            "QS" to "Smartwings",
            "LO" to "LOT",
            "AY" to "Finnair",
            "KL" to "KLM",
            "AF" to "Air France",
            "LH" to "Lufthansa",
            "BA" to "British Airways",
            "EK" to "Emirates",
            "6E" to "IndiGo",
            "AI" to "Air India",
        )

        fun parse(input: InputStream): AirlineDb {
            val map = HashMap<String, String>(1500)
            input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val p = line.split('\t')
                    if (p.size >= 2) map[p[0]] = p[1]
                }
            }
            map.putAll(overrides)
            return AirlineDb(map)
        }
    }
}

object Geo {
    /** Great-circle distance in km. */
    fun distanceKm(a: Airport, b: Airport): Int {
        val r = 6371.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return (2 * r * asin(sqrt(h))).roundToInt()
    }

    fun flag(countryCode: String?): String {
        val cc = countryCode?.uppercase() ?: return ""
        if (cc.length != 2 || !cc.all { it in 'A'..'Z' }) return ""
        val sb = StringBuilder()
        cc.forEach { sb.appendCodePoint(0x1F1E6 + (it - 'A')) }
        return sb.toString()
    }

    fun countryName(countryCode: String?, locale: Locale = Locale.getDefault()): String {
        val cc = countryCode?.uppercase() ?: return ""
        return try {
            Locale.Builder().setRegion(cc).build().getDisplayCountry(locale).ifBlank { cc }
        } catch (e: IllformedLocaleException) {
            cc
        }
    }
}

/** ICAO / IATA aircraft type designators -> readable model names. */
object AircraftTypes {
    private val names = mapOf(
        "A318" to "Airbus A318", "A319" to "Airbus A319", "A320" to "Airbus A320", "A321" to "Airbus A321",
        "A19N" to "Airbus A319neo", "A20N" to "Airbus A320neo", "A21N" to "Airbus A321neo",
        "319" to "Airbus A319", "320" to "Airbus A320", "321" to "Airbus A321", "32N" to "Airbus A320neo",
        "32Q" to "Airbus A321neo", "32A" to "Airbus A320", "32B" to "Airbus A321",
        "A332" to "Airbus A330-200", "A333" to "Airbus A330-300", "A338" to "Airbus A330-800neo",
        "A339" to "Airbus A330-900neo", "332" to "Airbus A330-200", "333" to "Airbus A330-300",
        "A359" to "Airbus A350-900", "A35K" to "Airbus A350-1000", "359" to "Airbus A350-900",
        "351" to "Airbus A350-1000", "A388" to "Airbus A380", "388" to "Airbus A380",
        "BCS1" to "Airbus A220-100", "BCS3" to "Airbus A220-300", "223" to "Airbus A220-300",
        "B737" to "Boeing 737-700", "B738" to "Boeing 737-800", "B739" to "Boeing 737-900",
        "B38M" to "Boeing 737 MAX 8", "B39M" to "Boeing 737 MAX 9", "7M8" to "Boeing 737 MAX 8",
        "73H" to "Boeing 737-800", "738" to "Boeing 737-800", "73G" to "Boeing 737-700",
        "B752" to "Boeing 757-200", "B763" to "Boeing 767-300", "B772" to "Boeing 777-200",
        "B77W" to "Boeing 777-300ER", "B77L" to "Boeing 777-200LR", "77W" to "Boeing 777-300ER",
        "B788" to "Boeing 787-8", "B789" to "Boeing 787-9", "B78X" to "Boeing 787-10",
        "788" to "Boeing 787-8", "789" to "Boeing 787-9", "B744" to "Boeing 747-400", "B748" to "Boeing 747-8",
        "SU95" to "Sukhoi Superjet 100", "SU9" to "Sukhoi Superjet 100", "E170" to "Embraer 170",
        "E175" to "Embraer 175", "E190" to "Embraer 190", "E195" to "Embraer 195", "E290" to "Embraer E190-E2",
        "E295" to "Embraer E195-E2", "CRJ2" to "Bombardier CRJ200", "CRJ7" to "Bombardier CRJ700",
        "CRJ9" to "Bombardier CRJ900", "DH8D" to "De Havilland Dash 8-400", "AT72" to "ATR 72",
        "AT76" to "ATR 72-600", "AT75" to "ATR 72-500", "AT45" to "ATR 42-500",
        "A306" to "Airbus A300-600", "IL96" to "Ильюшин Ил-96", "T204" to "Ту-204", "T214" to "Ту-214",
        "MC21" to "Яковлев МС-21", "AN24" to "Ан-24", "AN26" to "Ан-26", "L410" to "L-410",
        "C919" to "COMAC C919", "AJ27" to "COMAC ARJ21",
    )

    fun name(code: String?): String? {
        val c = code?.trim()?.uppercase()?.ifEmpty { null } ?: return null
        return names[c] ?: c
    }
}
