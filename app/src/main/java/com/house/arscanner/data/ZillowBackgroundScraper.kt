package com.house.arscanner.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.house.arscanner.model.SaleRecord
import com.house.arscanner.model.SchoolRating
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

data class LiveScrapedPropertyData(
    val zillowUrl: String,
    val zestimatePrice: Long? = null,
    val lowRangePrice: Long? = null,
    val highRangePrice: Long? = null,
    val rentZestimate: Long? = null,
    val bedrooms: Int? = null,
    val bathrooms: Double? = null,
    val livingAreaSqFt: Int? = null,
    val lotSizeSqFt: Int? = null,
    val yearBuilt: Int? = null,
    val propertyType: String? = null,
    val lastSoldPrice: Long? = null,
    val lastSoldDate: String? = null,
    val taxAssessedValue: Long? = null,
    val hoaMonthly: Int? = null,
    val schools: List<SchoolRating> = emptyList(),
    val saleHistory: List<SaleRecord> = emptyList(),
    val dataSourceLabel: String = "Zillow Live Zestimate"
)

/**
 * Fetches live Zillow property valuation and exact home facts without requiring any API key:
 * 1. Queries Zillow's public autocomplete endpoint (`zillowstatic.com/autocomplete/v3/suggestions`)
 *    to resolve the exact Zillow Property ID (`zpid`) and canonical address slug.
 * 2. Fetches the Zillow Group property data payload (`__NEXT_DATA__` via Trulia's Zillow-backed
 *    `/home/{slug}-{zpid}` endpoint, which serves Zillow's exact Zestimate, beds, baths, sqft,
 *    year built, tax assessment, and MLS sale history without PerimeterX captcha blocks).
 * 3. Falls back to DuckDuckGo Lite search snippets + background WebView if a parcel has no
 *    direct `zpid` match.
 */
object ZillowBackgroundScraper {

    private const val MOBILE_CHROME_UA =
        "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro XL) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    data class ZillowTargetUrl(
        val zillowUrl: String,
        val truliaUrl: String?,
        val zpid: String?,
        val displayAddress: String
    )

    /**
     * Uses Zillow's public autocomplete endpoint to find the exact `zpid` for an address
     * and constructs both the direct Zillow `/homedetails/.../{zpid}_zpid/` URL and the
     * Zillow-Group `/home/{slug}-{zpid}` data URL.
     */
    fun resolveZillowPropertyUrl(
        streetAddress: String,
        city: String,
        state: String,
        postalCode: String
    ): ZillowTargetUrl {
        val rawFull = "$streetAddress $city $state $postalCode"
        val defaultSlug = "$streetAddress-$city-$state-$postalCode"
            .replace(Regex("[^A-Za-z0-9]+"), "-")
            .trim('-')
        val fallbackZillowUrl = "https://www.zillow.com/homes/${defaultSlug}_rb/"

        try {
            val query = URLEncoder.encode("$streetAddress, $city, $state $postalCode", "UTF-8")
            val apiUrl = URL(
                "https://www.zillowstatic.com/autocomplete/v3/suggestions?q=$query&resultTypes=allAddress&resultCount=3"
            )
            val conn = (apiUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3500
                readTimeout = 3500
                setRequestProperty("User-Agent", MOBILE_CHROME_UA)
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(body)
                val results = root.optJSONArray("results")
                if (results != null && results.length() > 0) {
                    val wantedNumber = streetAddress.substringBefore(" ").trim()
                    val wantedStreetWord = streetAddress.substringAfter(" ", "")
                        .trim().substringBefore(" ").lowercase(Locale.US)
                    for (i in 0 until results.length()) {
                        val item = results.optJSONObject(i) ?: continue
                        val display = item.optString("display").ifBlank { rawFull }
                        // Only accept the suggestion whose house number AND street match; otherwise
                        // we would open / scrape a neighboring property (e.g. 3484 instead of 3488).
                        val displayNumber = display.substringBefore(" ").trim()
                        if (wantedNumber.isNotEmpty() && !displayNumber.equals(wantedNumber, ignoreCase = true)) continue
                        if (wantedStreetWord.isNotEmpty() &&
                            !display.lowercase(Locale.US).contains(wantedStreetWord)
                        ) continue
                        val meta = item.optJSONObject("metaData") ?: continue
                        val zpid = meta.opt("zpid")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                        if (zpid != null) {
                            val zillowSlug = display
                                .replace(Regex("[^A-Za-z0-9]+"), "-")
                                .trim('-')
                            val truliaSlug = display
                                .lowercase(Locale.US)
                                .replace(Regex("[^a-z0-9]+"), "-")
                                .trim('-')
                            return ZillowTargetUrl(
                                zillowUrl = "https://www.zillow.com/homedetails/$zillowSlug/${zpid}_zpid/",
                                truliaUrl = "https://www.trulia.com/home/$truliaSlug-$zpid",
                                zpid = zpid,
                                displayAddress = display
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Fall back to standard _rb URL
        }
        return ZillowTargetUrl(
            zillowUrl = fallbackZillowUrl,
            truliaUrl = null,
            zpid = null,
            displayAddress = rawFull
        )
    }

    /**
     * Fetches live Zillow property data:
     * 1. Primary: Zillow Group `__NEXT_DATA__` JSON via `zpid` (fast ~600ms, exact Zestimate,
     *    beds, baths, sqft, year built, tax assessment, and full sale history).
     * 2. Secondary: DuckDuckGo Lite Zillow/Redfin MLS snippet + Background WebView.
     */
    suspend fun fetchLiveZillowData(
        context: Context,
        streetAddress: String,
        city: String,
        state: String,
        postalCode: String
    ): LiveScrapedPropertyData? = withContext(Dispatchers.IO) {
        val target = resolveZillowPropertyUrl(streetAddress, city, state, postalCode)

        // 1. Fetch direct Zillow Group __NEXT_DATA__ payload if zpid was resolved
        val truliaNextDataResult = if (target.truliaUrl != null) {
            fetchZillowGroupNextData(target.truliaUrl, target.zillowUrl)
        } else {
            null
        }

        if (truliaNextDataResult != null &&
            truliaNextDataResult.zestimatePrice != null &&
            truliaNextDataResult.bedrooms != null &&
            truliaNextDataResult.livingAreaSqFt != null
        ) {
            return@withContext truliaNextDataResult
        }

        // 2. Supplement or fallback with DuckDuckGo Lite MLS/Zillow/Redfin snippets
        val snippetResult = fetchFromDuckDuckGoLiteSnippets(
            streetAddress = streetAddress,
            city = city,
            state = state,
            postalCode = postalCode,
            zillowUrl = target.zillowUrl
        )

        val combined = mergeScrapedData(truliaNextDataResult, snippetResult, target.zillowUrl)
        if (combined != null && (combined.zestimatePrice != null || combined.lastSoldPrice != null)) {
            return@withContext combined
        }

        // 3. Final fallback: Background WebView on Zillow URL
        val webViewResult = withTimeoutOrNull(6500L) {
            scrapeWithBackgroundWebView(context, target.zillowUrl, streetAddress)
        }
        mergeScrapedData(combined, webViewResult, target.zillowUrl)
    }

    /**
     * Fetches and parses the Zillow Group `__NEXT_DATA__` JSON from `https://www.trulia.com/home/{slug}-{zpid}`.
     * Because Trulia is part of Zillow Group and keyed by the exact same `zpid`, its `__NEXT_DATA__`
     * payload contains the official Zillow Zestimate ("The Zestimate, from Zillow, is an estimate of a
     * home's value"), exact bedroom/bathroom counts, square footage, year built, county tax assessment,
     * and full MLS sale history without PerimeterX bot challenges.
     */
    private fun fetchZillowGroupNextData(
        truliaUrl: String,
        zillowUrl: String
    ): LiveScrapedPropertyData? {
        try {
            val conn = (URL(truliaUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", MOBILE_CHROME_UA)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            }
            if (conn.responseCode != 200) return null
            val html = conn.inputStream.bufferedReader().use { it.readText() }

            val nextMatch = Regex(
                """<script id="__NEXT_DATA__" type="application/json">([\s\S]*?)</script>"""
            ).find(html)
            val rawJson = nextMatch?.groupValues?.getOrNull(1) ?: html

            // 1. Zestimate / Valuation Price
            var zestimate: Long? = Regex(
                """"__typename"\s*:\s*"HOME_ValuationPrice"\s*,\s*"formattedPrice"\s*:\s*"[^"]+"\s*,\s*"price"\s*:\s*(\d+)"""
            ).find(rawJson)?.groupValues?.getOrNull(1)?.toLongOrNull()

            if (zestimate == null) {
                zestimate = Regex(
                    """"estimatedValue"\s*:\s*\{\s*"formattedPrice"\s*:\s*"[^"]+"\s*,\s*"price"\s*:\s*(\d+)"""
                ).find(rawJson)?.groupValues?.getOrNull(1)?.toLongOrNull()
            }
            if (zestimate == null) {
                // Active listing price if currently for sale
                zestimate = Regex(
                    """"activeListing"\s*:\s*\{[\s\S]{0,400}?"price"\s*:\s*(\d{5,9})"""
                ).find(rawJson)?.groupValues?.getOrNull(1)?.toLongOrNull()
            }

            // 2. Bedrooms & Bathrooms
            val bedrooms: Int? = Regex(
                """"bedrooms"\s*:\s*\{[^}]*?"value"\s*:\s*(\d+)"""
            ).find(rawJson)?.groupValues?.getOrNull(1)?.toIntOrNull()

            val bathrooms: Double? = Regex(
                """"bathrooms"\s*:\s*\{[^}]*?"value"\s*:\s*([0-9.]+)"""
            ).find(rawJson)?.groupValues?.getOrNull(1)?.toDoubleOrNull()

            // 3. Living Area SqFt
            var livingAreaSqFt: Int? = Regex(
                """"floorSpace"\s*:\s*\{\s*"formattedDimension"\s*:\s*"([0-9,]+)\s*sqft""""
            ).find(rawJson)?.groupValues?.getOrNull(1)?.replace(",", "")?.toIntOrNull()

            if (livingAreaSqFt == null) {
                livingAreaSqFt = Regex(
                    """"formattedName"\s*:\s*"Living Area"\s*,\s*"formattedValue"\s*:\s*"([0-9,]+)\s*sqft""""
                ).find(rawJson)?.groupValues?.getOrNull(1)?.replace(",", "")?.toIntOrNull()
            }

            // 4. Lot Size SqFt (handles "6,500 sqft" or "0.18 acres")
            var lotSizeSqFt: Int? = null
            Regex(
                """"formattedName"\s*:\s*"Lot Size"\s*,\s*"formattedValue"\s*:\s*"([^"]+)""""
            ).find(rawJson)?.groupValues?.getOrNull(1)?.let { lotStr ->
                lotSizeSqFt = parseLotSizeToSqFt(lotStr)
            }
            if (lotSizeSqFt == null) {
                Regex("""Lot Size[^0-9]{0,20}([0-9,]+(?:\.[0-9]+)?)\s*(sqft|sq\s*ft|acres?)""", RegexOption.IGNORE_CASE)
                    .find(rawJson)?.let { m ->
                        lotSizeSqFt = parseLotSizeToSqFt("${m.groupValues[1]} ${m.groupValues[2]}")
                    }
            }

            // 5. Year Built & Property Type
            val yearBuilt: Int? = Regex(
                """"formattedName"\s*:\s*"Year Built"\s*,\s*"formattedValue"\s*:\s*"(\d{4})""""
            ).find(rawJson)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""Built in (\d{4})""").find(rawJson)?.groupValues?.getOrNull(1)?.toIntOrNull()

            val propertyType: String? = Regex(
                """"formattedName"\s*:\s*"Property Type"\s*,\s*"formattedValue"\s*:\s*"([^"]+)""""
            ).find(rawJson)?.groupValues?.getOrNull(1)

            // 6. Last Sold Price & Date
            var lastSoldPrice: Long? = null
            var lastSoldDate: String? = null
            Regex(
                """"lastSold"\s*:\s*\{\s*"soldPrice"\s*:\s*\{\s*"formattedPrice"\s*:\s*"[^"]+"\s*,\s*"price"\s*:\s*(\d+)[^}]*\}\s*,\s*"formattedSoldDate"\s*:\s*"([^"]+)""""
            ).find(rawJson)?.let { m ->
                val p = m.groupValues.getOrNull(1)?.toLongOrNull()
                if (p != null && p >= 50_000L) {
                    lastSoldPrice = p
                    lastSoldDate = m.groupValues.getOrNull(2)
                }
            }

            // 7. Full Price / Sale History events
            val historyEvents = mutableListOf< Pair<String, Pair<String, Long>> >()
            Regex(
                """"formattedDate"\s*:\s*"([^"]+)"\s*,\s*"event"\s*:\s*"([^"]+)"[\s\S]{0,140}?"price"\s*:\s*\{\s*"price"\s*:\s*(\d+)\s*,\s*"formattedPrice"\s*:\s*"([^"]+)""""
            ).findAll(rawJson).forEach { m ->
                val dateStr = m.groupValues[1]
                val eventName = m.groupValues[2]
                val rawEventPrice = m.groupValues[3].toLongOrNull() ?: 0L
                if (rawEventPrice >= 50_000L && historyEvents.size < 5) {
                    historyEvents.add(dateStr to (eventName to rawEventPrice))
                }
            }

            if (lastSoldPrice == null) {
                historyEvents.firstOrNull { it.second.first.contains("Sold", ignoreCase = true) }?.let {
                    lastSoldDate = it.first
                    lastSoldPrice = it.second.second
                }
            }

            val formattedSaleHistory = historyEvents.mapIndexed { idx, (dateStr, evPair) ->
                val (evName, evPrice) = evPair
                val nextOlderPrice = historyEvents.getOrNull(idx + 1)?.second?.second
                val changePctStr = if (nextOlderPrice != null && nextOlderPrice > 0L) {
                    val pct = (((evPrice - nextOlderPrice).toDouble() / nextOlderPrice) * 100).roundToInt()
                    val sign = if (pct >= 0) "+" else ""
                    "$sign$pct%"
                } else {
                    "—"
                }
                val fmtPrice = java.text.NumberFormat.getCurrencyInstance(Locale.US).apply {
                    maximumFractionDigits = 0
                }.format(evPrice)
                SaleRecord(
                    date = dateStr,
                    event = evName,
                    price = fmtPrice,
                    priceChangePercent = changePctStr
                )
            }

            // 8. County Tax Assessment
            val taxAssessedValue: Long? = Regex(
                """"totalAssessment"\s*:\s*\{\s*"formattedPrice"\s*:\s*"[^"]+"\s*,\s*"price"\s*:\s*(\d+)"""
            ).find(rawJson)?.groupValues?.getOrNull(1)?.toLongOrNull()

            // 9. HOA Fee (if any)
            val hoaMonthly: Int? = Regex(
                """"formattedName"\s*:\s*"HOA Fee"\s*,\s*"formattedValue"\s*:\s*"\$([0-9,]+)""""
            ).find(rawJson)?.groupValues?.getOrNull(1)?.replace(",", "")?.toIntOrNull()

            if (zestimate == null && lastSoldPrice == null && bedrooms == null && livingAreaSqFt == null) {
                return null
            }

            return LiveScrapedPropertyData(
                zillowUrl = zillowUrl,
                zestimatePrice = zestimate,
                lowRangePrice = zestimate?.let { (it * 0.95).toLong() },
                highRangePrice = zestimate?.let { (it * 1.05).toLong() },
                bedrooms = bedrooms,
                bathrooms = bathrooms,
                livingAreaSqFt = livingAreaSqFt,
                lotSizeSqFt = lotSizeSqFt,
                yearBuilt = yearBuilt,
                propertyType = propertyType,
                lastSoldPrice = lastSoldPrice,
                lastSoldDate = lastSoldDate,
                taxAssessedValue = taxAssessedValue,
                hoaMonthly = hoaMonthly,
                saleHistory = formattedSaleHistory,
                dataSourceLabel = "Zillow Live Zestimate"
            )
        } catch (_: Exception) {
            return null
        }
    }

    private fun parseLotSizeToSqFt(raw: String): Int? {
        val lower = raw.lowercase(Locale.US)
        val num = Regex("""([0-9,]+(?:\.[0-9]+)?)""").find(lower)?.groupValues?.getOrNull(1)
            ?.replace(",", "")?.toDoubleOrNull() ?: return null
        return if (lower.contains("acre")) {
            (num * 43560.0).roundToInt()
        } else {
            num.roundToInt()
        }.takeIf { it > 200 }
    }

    /**
     * Queries DuckDuckGo Lite (`https://lite.duckduckgo.com/lite/`) for the address on Zillow/Redfin
     * and extracts MLS beds/baths/sqft, year built, last sold price, and any Zestimate/Redfin Estimate.
     */
    private fun fetchFromDuckDuckGoLiteSnippets(
        streetAddress: String,
        city: String,
        state: String,
        postalCode: String,
        zillowUrl: String
    ): LiveScrapedPropertyData? {
        try {
            val q = URLEncoder.encode("$streetAddress $city $state $postalCode zillow redfin", "UTF-8")
            val url = URL("https://lite.duckduckgo.com/lite/?q=$q")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("User-Agent", MOBILE_CHROME_UA)
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            }
            if (conn.responseCode != 200) return null
            val html = conn.inputStream.bufferedReader().use { it.readText() }

            val clean = html
                .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<[^>]+>"), " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&#x27;", "'")
                .replace("&quot;", "\"")
                .replace(Regex("\\s+"), " ")

            // Focus on snippets that explicitly mention this street number & street name
            val streetNumber = streetAddress.substringBefore(" ").trim()
            val relevantWindows = mutableListOf<String>()
            if (streetNumber.isNotEmpty()) {
                Regex(Regex.escape(streetNumber), RegexOption.IGNORE_CASE).findAll(clean).forEach { m ->
                    val start = (m.range.first - 60).coerceAtLeast(0)
                    val end = (m.range.last + 360).coerceAtMost(clean.length)
                    relevantWindows.add(clean.substring(start, end))
                }
            }
            val targetText = if (relevantWindows.isNotEmpty()) {
                relevantWindows.joinToString("\n")
            } else {
                clean
            }

            val beds = Regex("""(\d+)\s*(?:bd|bed|bedroom)s?\b""", RegexOption.IGNORE_CASE)
                .find(targetText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?.takeIf { it in 1..15 }

            val baths = Regex("""(\d+(?:\.\d+)?)\s*(?:ba|bath|bathroom)s?\b""", RegexOption.IGNORE_CASE)
                .find(targetText)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                ?.takeIf { it in 0.5..15.0 }

            val sqFt = Regex("""([0-9,]{3,6})\s*(?:sq\.?\s*ft|sqft|square feet)\b""", RegexOption.IGNORE_CASE)
                .find(targetText)?.groupValues?.getOrNull(1)?.replace(",", "")?.toIntOrNull()
                ?.takeIf { it in 250..30000 }

            val yearBuilt = Regex("""(?:built in|Year built:?)\s*((?:19|20)\d{2})\b""", RegexOption.IGNORE_CASE)
                .find(targetText)?.groupValues?.getOrNull(1)?.toIntOrNull()

            var lastSoldPrice: Long? = null
            var lastSoldDate: String? = null
            Regex(
                """(?:last sold|sold)\s+(?:on\s+([A-Za-z0-9/,\-\s]{4,18}?)\s+for\s+\$([0-9,]+)|for\s+\$([0-9,]+)\s+on\s+([A-Za-z0-9/,\-\s]{4,18}))""",
                RegexOption.IGNORE_CASE
            ).find(targetText)?.let { m ->
                val date1 = m.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
                val price1 = m.groupValues.getOrNull(2)?.replace(",", "")?.toLongOrNull()
                val price2 = m.groupValues.getOrNull(3)?.replace(",", "")?.toLongOrNull()
                val date2 = m.groupValues.getOrNull(4)?.trim()?.takeIf { it.isNotBlank() }
                val candidateSold = (price1 ?: price2)?.takeIf { it >= 50_000L }
                if (candidateSold != null) {
                    lastSoldPrice = candidateSold
                    lastSoldDate = date1 ?: date2
                }
            }

            val zestimate = extractExplicitZestimateFromText(targetText)

            if (zestimate == null && lastSoldPrice == null && beds == null && sqFt == null) {
                return null
            }

            return LiveScrapedPropertyData(
                zillowUrl = zillowUrl,
                zestimatePrice = zestimate,
                lowRangePrice = zestimate?.let { (it * 0.95).toLong() },
                highRangePrice = zestimate?.let { (it * 1.05).toLong() },
                bedrooms = beds,
                bathrooms = baths,
                livingAreaSqFt = sqFt,
                yearBuilt = yearBuilt,
                lastSoldPrice = lastSoldPrice,
                lastSoldDate = lastSoldDate,
                dataSourceLabel = if (zestimate != null) "Zillow / Redfin Live" else "MLS Public Record"
            )
        } catch (_: Exception) {
            return null
        }
    }

    private fun extractExplicitZestimateFromText(text: String): Long? {
        val explicitPatterns = listOf(
            Regex("""(?:Zestimate|Trulia Estimate|Redfin Estimate)[^\$]{0,55}\$([0-9]{1,3}(?:,[0-9]{3})+)""", RegexOption.IGNORE_CASE),
            Regex("""(?:Zestimate|Trulia Estimate|Redfin Estimate)[^\$]{0,55}\$([0-9]+(?:\.[0-9]+)?)\s*([MK])\b""", RegexOption.IGNORE_CASE)
        )
        for (regex in explicitPatterns) {
            val match = regex.find(text) ?: continue
            val rawNum = match.groupValues.getOrNull(1)?.replace(",", "") ?: continue
            val suffix = match.groupValues.getOrNull(2)?.uppercase(Locale.US)
            val value = when (suffix) {
                "M" -> (rawNum.toDoubleOrNull()?.times(1_000_000))?.toLong()
                "K" -> (rawNum.toDoubleOrNull()?.times(1_000))?.toLong()
                else -> rawNum.toLongOrNull()
            }
            if (value != null && value in 50_000L..100_000_000L) {
                return value
            }
        }
        return null
    }

    private fun mergeScrapedData(
        primary: LiveScrapedPropertyData?,
        secondary: LiveScrapedPropertyData?,
        defaultUrl: String
    ): LiveScrapedPropertyData? {
        if (primary == null) return secondary
        if (secondary == null) return primary
        val mergedValue = primary.zestimatePrice ?: secondary.zestimatePrice
        return LiveScrapedPropertyData(
            zillowUrl = primary.zillowUrl.ifBlank { defaultUrl },
            zestimatePrice = mergedValue,
            lowRangePrice = primary.lowRangePrice ?: secondary.lowRangePrice ?: mergedValue?.let { (it * 0.95).toLong() },
            highRangePrice = primary.highRangePrice ?: secondary.highRangePrice ?: mergedValue?.let { (it * 1.05).toLong() },
            rentZestimate = primary.rentZestimate ?: secondary.rentZestimate,
            bedrooms = primary.bedrooms ?: secondary.bedrooms,
            bathrooms = primary.bathrooms ?: secondary.bathrooms,
            livingAreaSqFt = primary.livingAreaSqFt ?: secondary.livingAreaSqFt,
            lotSizeSqFt = primary.lotSizeSqFt ?: secondary.lotSizeSqFt,
            yearBuilt = primary.yearBuilt ?: secondary.yearBuilt,
            propertyType = primary.propertyType ?: secondary.propertyType,
            lastSoldPrice = primary.lastSoldPrice ?: secondary.lastSoldPrice,
            lastSoldDate = primary.lastSoldDate ?: secondary.lastSoldDate,
            taxAssessedValue = primary.taxAssessedValue ?: secondary.taxAssessedValue,
            hoaMonthly = primary.hoaMonthly ?: secondary.hoaMonthly,
            schools = primary.schools.ifEmpty { secondary.schools },
            saleHistory = primary.saleHistory.ifEmpty { secondary.saleHistory },
            dataSourceLabel = if (primary.zestimatePrice != null) primary.dataSourceLabel else secondary.dataSourceLabel
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun scrapeWithBackgroundWebView(
        context: Context,
        zillowUrl: String,
        streetAddress: String
    ): LiveScrapedPropertyData? = suspendCancellableCoroutine { cont ->
        val mainHandler = Handler(Looper.getMainLooper())
        var webView: WebView? = null
        var isCompleted = false

        fun finishAndCleanup(result: LiveScrapedPropertyData?) {
            if (isCompleted) return
            isCompleted = true
            mainHandler.post {
                try {
                    webView?.stopLoading()
                    webView?.destroy()
                } catch (_: Exception) {
                }
                webView = null
            }
            if (cont.isActive) {
                cont.resume(result)
            }
        }

        cont.invokeOnCancellation {
            finishAndCleanup(null)
        }

        mainHandler.post {
            try {
                val wv = WebView(context.applicationContext)
                webView = wv

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadsImagesAutomatically = false
                    blockNetworkImage = true
                    userAgentString = MOBILE_CHROME_UA
                    cacheMode = WebSettings.LOAD_DEFAULT
                }

                wv.layout(0, 0, 1080, 2400)

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean = false

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        mainHandler.postDelayed({
                            if (isCompleted) return@postDelayed
                            wv.evaluateJavascript(
                                "(function(){ return document.body ? document.body.innerText.substring(0, 10000) : ''; })();"
                            ) { rawText ->
                                val zestimate = rawText?.let { extractExplicitZestimateFromText(it) }
                                if (zestimate != null) {
                                    finishAndCleanup(
                                        LiveScrapedPropertyData(
                                            zillowUrl = zillowUrl,
                                            zestimatePrice = zestimate,
                                            lowRangePrice = (zestimate * 0.95).toLong(),
                                            highRangePrice = (zestimate * 1.05).toLong(),
                                            dataSourceLabel = "Zillow Live Zestimate"
                                        )
                                    )
                                } else {
                                    finishAndCleanup(null)
                                }
                            }
                        }, 600L)
                    }
                }

                wv.loadUrl(zillowUrl)
            } catch (_: Exception) {
                finishAndCleanup(null)
            }
        }
    }
}
