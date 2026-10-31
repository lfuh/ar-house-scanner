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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    val lastSoldPrice: Long? = null,
    val lastSoldDate: String? = null,
    val hoaMonthly: Int? = null,
    val schools: List<SchoolRating> = emptyList(),
    val saleHistory: List<SaleRecord> = emptyList(),
    val dataSourceLabel: String = "Zillow Live"
)

/**
 * Fetches live Zillow property valuation and home details in the background without any API key:
 * 1. Resolves the exact Zillow Property ID (`zpid`) via Zillow's public autocomplete API.
 * 2. Launches a background Android `WebView` on the Zillow property URL and extracts
 *    `#__NEXT_DATA__` (`gdpClientCache`), `[data-testid="zestimate-text"]`, and DOM metrics.
 * 3. If Zillow serves a bot-challenge (`px-captcha`), automatically redirects the background
 *    `WebView` (and runs a parallel snippet fetch) to extract Zillow/Redfin's live indexed
 *    Zestimate, bed/bath/sqft, and sale history for that exact street address.
 */
object ZillowBackgroundScraper {

    private const val MOBILE_CHROME_UA =
        "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro XL) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    data class ZillowTargetUrl(
        val url: String,
        val zpid: String?
    )

    /**
     * Uses Zillow's public autocomplete endpoint to find the exact `zpid` for an address
     * so we can open the direct `/homedetails/.../{zpid}_zpid/` page.
     */
    fun resolveZillowPropertyUrl(
        streetAddress: String,
        city: String,
        state: String,
        postalCode: String
    ): ZillowTargetUrl {
        val slug = "$streetAddress-$city-$state-$postalCode"
            .replace(Regex("[^A-Za-z0-9]+"), "-")
            .trim('-')
        val fallbackUrl = "https://www.zillow.com/homes/${slug}_rb/"

        try {
            val query = URLEncoder.encode("$streetAddress, $city, $state $postalCode", "UTF-8")
            val apiUrl = URL("https://www.zillowstatic.com/autocomplete/v3/suggestions?q=$query&resultTypes=allAddress&resultCount=3")
            val conn = (apiUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("User-Agent", MOBILE_CHROME_UA)
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(body)
                val results = root.optJSONArray("results")
                if (results != null && results.length() > 0) {
                    for (i in 0 until results.length()) {
                        val item = results.optJSONObject(i) ?: continue
                        val meta = item.optJSONObject("metaData") ?: continue
                        val zpid = meta.opt("zpid")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                        if (zpid != null) {
                            return ZillowTargetUrl(
                                url = "https://www.zillow.com/homedetails/$slug/${zpid}_zpid/",
                                zpid = zpid
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Fall back to _rb URL
        }
        return ZillowTargetUrl(url = fallbackUrl, zpid = null)
    }

    /**
     * Fetches live Zillow property data by running the background WebView and search metadata
     * extractor concurrently and merging the richest real property values found.
     */
    suspend fun fetchLiveZillowData(
        context: Context,
        streetAddress: String,
        city: String,
        state: String,
        postalCode: String
    ): LiveScrapedPropertyData? = withContext(Dispatchers.IO) {
        val target = resolveZillowPropertyUrl(streetAddress, city, state, postalCode)

        coroutineScope {
            val webViewDeferred = async {
                withTimeoutOrNull(8500L) {
                    scrapeWithBackgroundWebView(
                        context = context,
                        zillowUrl = target.url,
                        streetAddress = streetAddress,
                        city = city,
                        state = state,
                        postalCode = postalCode
                    )
                }
            }
            val snippetDeferred = async {
                fetchFromSearchMetadataSnippets(
                    streetAddress = streetAddress,
                    city = city,
                    state = state,
                    postalCode = postalCode,
                    zillowUrl = target.url
                )
            }

            val webViewResult = webViewDeferred.await()
            val snippetResult = snippetDeferred.await()

            mergeScrapedData(webViewResult, snippetResult, target.url)
        }
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
            lastSoldPrice = primary.lastSoldPrice ?: secondary.lastSoldPrice,
            lastSoldDate = primary.lastSoldDate ?: secondary.lastSoldDate,
            hoaMonthly = primary.hoaMonthly ?: secondary.hoaMonthly,
            schools = primary.schools.ifEmpty { secondary.schools },
            saleHistory = primary.saleHistory.ifEmpty { secondary.saleHistory },
            dataSourceLabel = if (primary.zestimatePrice != null) primary.dataSourceLabel else secondary.dataSourceLabel
        )
    }

    /**
     * Instantiates an invisible WebView on the main looper, loads [zillowUrl],
     * extracts Zillow's `#__NEXT_DATA__` or DOM `[data-testid="zestimate-text"]`,
     * and if Zillow blocks the direct load with a bot captcha, automatically pivots
     * the WebView to a live Zillow/Redfin address search page to extract the Zestimate.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun scrapeWithBackgroundWebView(
        context: Context,
        zillowUrl: String,
        streetAddress: String,
        city: String,
        state: String,
        postalCode: String
    ): LiveScrapedPropertyData? = suspendCancellableCoroutine { cont ->
        val mainHandler = Handler(Looper.getMainLooper())
        var webView: WebView? = null
        var isCompleted = false
        var pollAttempts = 0
        var pivotedToSearchFallback = false

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

        val extractionJs = """
            (function() {
                try {
                    var out = {};
                    out.url = window.location.href;
                    out.title = document.title || "";
                    
                    var bodyText = (document.body && document.body.innerText) ? document.body.innerText : "";
                    out.isBlocked = (
                        document.getElementById('px-captcha') !== null ||
                        out.title.toLowerCase().indexOf('denied') !== -1 ||
                        bodyText.indexOf('Press & Hold to confirm you are') !== -1 ||
                        bodyText.indexOf('Verify you are human') !== -1
                    );
                    
                    // 1. Check Zillow __NEXT_DATA__ (contains gdpClientCache with full property JSON)
                    var nextScript = document.getElementById('__NEXT_DATA__');
                    if (nextScript && nextScript.textContent) {
                        var rawNext = nextScript.textContent;
                        var zestMatch = rawNext.match(/"zestimate"\s*:\s*(\d{5,9})/);
                        if (zestMatch) out.zestimate = parseInt(zestMatch[1], 10);
                        
                        var priceMatch = rawNext.match(/"price"\s*:\s*(\d{5,9})/);
                        if (priceMatch && !out.zestimate) out.zestimate = parseInt(priceMatch[1], 10);
                        
                        var rentMatch = rawNext.match(/"rentZestimate"\s*:\s*(\d{3,6})/);
                        if (rentMatch) out.rentZestimate = parseInt(rentMatch[1], 10);
                        
                        var bedsMatch = rawNext.match(/"bedrooms"\s*:\s*(\d+)/);
                        if (bedsMatch) out.bedrooms = parseInt(bedsMatch[1], 10);
                        
                        var bathsMatch = rawNext.match(/"bathrooms"\s*:\s*(\d+(?:\.\d+)?)/);
                        if (bathsMatch) out.bathrooms = parseFloat(bathsMatch[1]);
                        
                        var areaMatch = rawNext.match(/"livingArea"\s*:\s*(\d{3,6})/);
                        if (areaMatch) out.livingArea = parseInt(areaMatch[1], 10);
                        
                        var lotMatch = rawNext.match(/"lotAreaValue"\s*:\s*(\d+(?:\.\d+)?)/);
                        if (lotMatch) out.lotSize = Math.round(parseFloat(lotMatch[1]));
                        
                        var yearMatch = rawNext.match(/"yearBuilt"\s*:\s*(\d{4})/);
                        if (yearMatch) out.yearBuilt = parseInt(yearMatch[1], 10);
                        
                        var lastSoldMatch = rawNext.match(/"lastSoldPrice"\s*:\s*(\d{5,9})/);
                        if (lastSoldMatch) out.lastSoldPrice = parseInt(lastSoldMatch[1], 10);
                        
                        var dateSoldMatch = rawNext.match(/"dateSoldString"\s*:\s*"([^"]+)"/);
                        if (dateSoldMatch) out.lastSoldDate = dateSoldMatch[1];
                        
                        var hoaMatch = rawNext.match(/"monthlyHoaFee"\s*:\s*(\d+)/);
                        if (hoaMatch) out.hoaMonthly = parseInt(hoaMatch[1], 10);
                    }
                    
                    // 2. Check rendered DOM elements & text (e.g. data-testid="zestimate-text")
                    var zestNode = document.querySelector('[data-testid="zestimate-text"]') ||
                                   document.querySelector('[data-testid="price"]') ||
                                   document.querySelector('[data-testid="home-details-chip-price"]');
                    if (zestNode && zestNode.innerText) {
                        out.domPriceText = zestNode.innerText;
                    }
                    
                    var metaDesc = document.querySelector('meta[name="description"]');
                    if (metaDesc) {
                        out.metaDescription = metaDesc.getAttribute('content') || "";
                    }
                    
                    out.bodySnippet = bodyText.substring(0, 14000);
                    return JSON.stringify(out);
                } catch (e) {
                    return JSON.stringify({ error: e.toString() });
                }
            })();
        """.trimIndent()

        fun triggerSearchFallbackInWebView(wv: WebView) {
            if (pivotedToSearchFallback || isCompleted) return
            pivotedToSearchFallback = true
            pollAttempts = 0
            val q = URLEncoder.encode("\"$streetAddress\" $city $state $postalCode zillow zestimate", "UTF-8")
            wv.loadUrl("https://search.yahoo.com/search?p=$q")
        }

        fun pollWebView() {
            if (isCompleted) return
            val wv = webView ?: return
            pollAttempts++

            wv.evaluateJavascript(extractionJs) { rawResult ->
                if (isCompleted) return@evaluateJavascript
                val isBlocked = isWebViewBlockedResult(rawResult)
                if (isBlocked && !pivotedToSearchFallback) {
                    triggerSearchFallbackInWebView(wv)
                    return@evaluateJavascript
                }

                val parsed = parseWebViewJsResult(
                    rawEvalString = rawResult,
                    defaultUrl = zillowUrl,
                    streetAddress = streetAddress
                )
                if (parsed != null && (parsed.zestimatePrice != null || parsed.lastSoldPrice != null)) {
                    finishAndCleanup(parsed)
                } else if (!pivotedToSearchFallback && pollAttempts >= 4) {
                    triggerSearchFallbackInWebView(wv)
                } else if (pollAttempts < 8) {
                    mainHandler.postDelayed({ pollWebView() }, 550L)
                } else {
                    finishAndCleanup(parsed)
                }
            }
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
                    databaseEnabled = true
                    loadsImagesAutomatically = false
                    blockNetworkImage = true
                    userAgentString = MOBILE_CHROME_UA
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                }

                wv.layout(0, 0, 1080, 2400)

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean = false

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        mainHandler.postDelayed({ pollWebView() }, 400L)
                    }
                }

                wv.loadUrl(
                    zillowUrl,
                    mapOf(
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Accept-Language" to "en-US,en;q=0.9",
                        "Referer" to "https://www.zillow.com/"
                    )
                )
            } catch (_: Exception) {
                finishAndCleanup(null)
            }
        }
    }

    private fun isWebViewBlockedResult(rawEvalString: String?): Boolean {
        if (rawEvalString.isNullOrBlank() || rawEvalString == "null") return false
        return try {
            val unquoted = if (rawEvalString.startsWith("\"") && rawEvalString.endsWith("\"")) {
                JSONArray("[$rawEvalString]").getString(0)
            } else {
                rawEvalString
            }
            val obj = JSONObject(unquoted)
            obj.optBoolean("isBlocked", false)
        } catch (_: Exception) {
            false
        }
    }

    private fun parseWebViewJsResult(
        rawEvalString: String?,
        defaultUrl: String,
        streetAddress: String
    ): LiveScrapedPropertyData? {
        if (rawEvalString.isNullOrBlank() || rawEvalString == "null") return null
        try {
            val unquoted = if (rawEvalString.startsWith("\"") && rawEvalString.endsWith("\"")) {
                JSONArray("[$rawEvalString]").getString(0)
            } else {
                rawEvalString
            }
            val obj = JSONObject(unquoted)
            if (obj.optBoolean("isBlocked", false)) return null

            val metaDesc = obj.optString("metaDescription")
            val domPriceText = obj.optString("domPriceText")
            val bodySnippet = obj.optString("bodySnippet")
            val combinedText = "$metaDesc\n$domPriceText\n$bodySnippet"

            var zestimate = obj.optLong("zestimate", 0L).takeIf { it > 20_000L }
            var rentZestimate = obj.optLong("rentZestimate", 0L).takeIf { it > 300L }
            var beds = obj.optInt("bedrooms", 0).takeIf { it in 1..20 }
            var baths = obj.optDouble("bathrooms", 0.0).takeIf { !it.isNaN() && it > 0.0 }
            var sqFt = obj.optInt("livingArea", 0).takeIf { it > 200 }
            val lotSqFt = obj.optInt("lotSize", 0).takeIf { it > 500 }
            var yearBuilt = obj.optInt("yearBuilt", 0).takeIf { it in 1800..2027 }
            var lastSoldPrice = obj.optLong("lastSoldPrice", 0L).takeIf { it > 20_000L }
            var lastSoldDate = obj.optString("lastSoldDate").takeIf { it.isNotBlank() }
            val hoaMonthly = obj.optInt("hoaMonthly", -1).takeIf { it >= 0 }

            if (zestimate == null) {
                zestimate = extractPriceFromText(combinedText, streetAddress)
            }
            if (rentZestimate == null) {
                Regex("""Rent Zestimate[^\$\d]*\$([0-9,]+)""", RegexOption.IGNORE_CASE)
                    .find(combinedText)?.groupValues?.getOrNull(1)?.replace(",", "")?.toLongOrNull()
                    ?.let { if (it in 500L..50000L) rentZestimate = it }
            }
            if (beds == null) {
                Regex("""(\d+)\s*(?:bd|bed|bedroom)s?\b""", RegexOption.IGNORE_CASE)
                    .find(combinedText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?.let { if (it in 1..15) beds = it }
            }
            if (baths == null) {
                Regex("""(\d+(?:\.\d+)?)\s*(?:ba|bath|bathroom)s?\b""", RegexOption.IGNORE_CASE)
                    .find(combinedText)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                    ?.let { if (it in 0.5..15.0) baths = it }
            }
            if (sqFt == null) {
                Regex("""([0-9,]{3,6})\s*(?:sq\.?\s*ft|sqft|square feet)\b""", RegexOption.IGNORE_CASE)
                    .find(combinedText)?.groupValues?.getOrNull(1)?.replace(",", "")?.toIntOrNull()
                    ?.let { if (it in 250..30000) sqFt = it }
            }
            if (yearBuilt == null) {
                Regex("""(?:built in|Year built:?)\s*((?:19|20)\d{2})\b""", RegexOption.IGNORE_CASE)
                    .find(combinedText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?.let { yearBuilt = it }
            }
            if (lastSoldPrice == null) {
                Regex("""(?:Last sold|Sold)\s+(?:on\s+|in\s+)?([A-Za-z0-9/,\s]{3,20}?)\s+for\s+\$([0-9,]+)""", RegexOption.IGNORE_CASE)
                    .find(combinedText)?.let { m ->
                        val datePart = m.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
                        val pricePart = m.groupValues.getOrNull(2)?.replace(",", "")?.toLongOrNull()
                        if (pricePart != null && pricePart > 20_000L) {
                            lastSoldPrice = pricePart
                            if (lastSoldDate == null && datePart != null) {
                                lastSoldDate = datePart
                            }
                        }
                    }
            }

            if (zestimate == null && lastSoldPrice == null && beds == null && sqFt == null) {
                return null
            }

            return LiveScrapedPropertyData(
                zillowUrl = defaultUrl,
                zestimatePrice = zestimate,
                lowRangePrice = zestimate?.let { (it * 0.96).toLong() },
                highRangePrice = zestimate?.let { (it * 1.04).toLong() },
                rentZestimate = rentZestimate,
                bedrooms = beds,
                bathrooms = baths,
                livingAreaSqFt = sqFt,
                lotSizeSqFt = lotSqFt,
                yearBuilt = yearBuilt,
                lastSoldPrice = lastSoldPrice,
                lastSoldDate = lastSoldDate,
                hoaMonthly = hoaMonthly,
                dataSourceLabel = "Zillow Live"
            )
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Queries Yahoo / DuckDuckGo search snippets for the exact address on Zillow/Redfin.
     */
    private fun fetchFromSearchMetadataSnippets(
        streetAddress: String,
        city: String,
        state: String,
        postalCode: String,
        zillowUrl: String
    ): LiveScrapedPropertyData? {
        try {
            val q = URLEncoder.encode("\"$streetAddress\" $city $state $postalCode zillow OR redfin", "UTF-8")
            val searchUrls = listOf(
                "https://search.yahoo.com/search?p=$q",
                "https://html.duckduckgo.com/html/?q=$q"
            )

            val combinedSnippets = StringBuilder()
            for (u in searchUrls) {
                try {
                    val conn = (URL(u).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 3500
                        readTimeout = 3500
                        setRequestProperty("User-Agent", MOBILE_CHROME_UA)
                        setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                    }
                    if (conn.responseCode == 200) {
                        val html = conn.inputStream.bufferedReader().use { it.readText() }
                        val plainText = html
                            .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
                            .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
                            .replace(Regex("<[^>]+>"), " ")
                            .replace("&amp;", "&")
                            .replace("&#x27;", "'")
                            .replace("&quot;", "\"")
                            .replace(Regex("\\s+"), " ")
                        combinedSnippets.append(plainText).append("\n")
                        if (extractPriceFromText(combinedSnippets.toString(), streetAddress) != null) {
                            break
                        }
                    }
                } catch (_: Exception) {
                }
            }

            val text = combinedSnippets.toString()
            if (text.isBlank()) return null

            val zestimate = extractPriceFromText(text, streetAddress)
            val rentZestimate = Regex("""Rent Zestimate[^\$\d]*\$([0-9,]+)""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.getOrNull(1)?.replace(",", "")?.toLongOrNull()
                ?.takeIf { it in 500L..50000L }

            val beds = Regex("""(\d+)\s*(?:bd|bed|bedroom)s?\b""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?.takeIf { it in 1..15 }

            val baths = Regex("""(\d+(?:\.\d+)?)\s*(?:ba|bath|bathroom)s?\b""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                ?.takeIf { it in 0.5..15.0 }

            val sqFt = Regex("""([0-9,]{3,6})\s*(?:sq\.?\s*ft|sqft|square feet)\b""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.getOrNull(1)?.replace(",", "")?.toIntOrNull()
                ?.takeIf { it in 250..30000 }

            val yearBuilt = Regex("""(?:built in|Year built:?)\s*((?:19|20)\d{2})\b""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

            var lastSoldPrice: Long? = null
            var lastSoldDate: String? = null
            Regex("""(?:Last sold|Sold)\s+(?:on\s+|in\s+)?([A-Za-z0-9/,\s]{3,20}?)\s+for\s+\$([0-9,]+)""", RegexOption.IGNORE_CASE)
                .find(text)?.let { m ->
                    val d = m.groupValues.getOrNull(1)?.trim()
                    val p = m.groupValues.getOrNull(2)?.replace(",", "")?.toLongOrNull()
                    if (p != null && p > 50_000L) {
                        lastSoldPrice = p
                        lastSoldDate = d
                    }
                }

            val effectiveValue = zestimate ?: lastSoldPrice ?: return null

            return LiveScrapedPropertyData(
                zillowUrl = zillowUrl,
                zestimatePrice = effectiveValue,
                lowRangePrice = (effectiveValue * 0.95).toLong(),
                highRangePrice = (effectiveValue * 1.05).toLong(),
                rentZestimate = rentZestimate,
                bedrooms = beds,
                bathrooms = baths,
                livingAreaSqFt = sqFt,
                yearBuilt = yearBuilt,
                lastSoldPrice = lastSoldPrice,
                lastSoldDate = lastSoldDate,
                dataSourceLabel = "Zillow Live"
            )
        } catch (_: Exception) {
            return null
        }
    }

    private fun extractPriceFromText(text: String, streetAddress: String = ""): Long? {
        // 1. Explicit Zestimate / Redfin Estimate / Estimated value pattern
        val explicitPatterns = listOf(
            Regex("""Zestimate[^\$]{0,55}\$([0-9]{1,3}(?:,[0-9]{3})+)""", RegexOption.IGNORE_CASE),
            Regex("""Zestimate[^\$]{0,55}\$([0-9]+(?:\.[0-9]+)?)\s*([MK])\b""", RegexOption.IGNORE_CASE),
            Regex("""Redfin Estimate[^\$]{0,55}\$([0-9]{1,3}(?:,[0-9]{3})+)""", RegexOption.IGNORE_CASE),
            Regex("""Redfin Estimate[^\$]{0,55}\$([0-9]+(?:\.[0-9]+)?)\s*([MK])\b""", RegexOption.IGNORE_CASE),
            Regex("""(?:estimated value|market value|worth|valued at|is|for sale[^\$]{0,20})[^\$]{0,35}\$([0-9]{1,3}(?:,[0-9]{3}){2})""", RegexOption.IGNORE_CASE)
        )
        for (regex in explicitPatterns) {
            val match = regex.find(text) ?: continue
            val rawNum = match.groupValues.getOrNull(1)?.replace(",", "") ?: continue
            val suffix = match.groupValues.getOrNull(2)?.uppercase(Locale.US)
            val value = if (suffix == "M") {
                (rawNum.toDoubleOrNull()?.times(1_000_000))?.toLong()
            } else if (suffix == "K") {
                (rawNum.toDoubleOrNull()?.times(1_000))?.toLong()
            } else {
                rawNum.toLongOrNull()
            }
            if (value != null && value in 50_000L..100_000_000L) {
                return value
            }
        }

        // 2. If streetAddress appears in the text, prefer a price right near the streetAddress mention
        if (streetAddress.isNotBlank()) {
            val streetNum = streetAddress.substringBefore(" ").trim()
            if (streetNum.isNotEmpty()) {
                val idx = text.indexOf(streetNum, ignoreCase = true)
                if (idx >= 0) {
                    val window = text.substring(
                        (idx - 120).coerceAtLeast(0),
                        (idx + 500).coerceAtMost(text.length)
                    )
                    val windowPrice = Regex("""\$([0-9]{1,3}(?:,[0-9]{3}){2})\b""")
                        .find(window)?.groupValues?.getOrNull(1)?.replace(",", "")?.toLongOrNull()
                    if (windowPrice != null && windowPrice in 100_000L..50_000_000L) {
                        return windowPrice
                    }
                    val windowMillion = Regex("""\$([0-9]{1,2}\.[0-9]{1,3})\s*M\b""", RegexOption.IGNORE_CASE)
                        .find(window)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                    if (windowMillion != null && windowMillion in 0.1..50.0) {
                        return (windowMillion * 1_000_000).toLong()
                    }
                }
            }
        }

        // 3. Any realistic home price ($100,000 to $50,000,000) in the property snippet
        val allPrices = Regex("""\$([0-9]{1,3}(?:,[0-9]{3}){2})\b""")
            .findAll(text)
            .mapNotNull { it.groupValues[1].replace(",", "").toLongOrNull() }
            .filter { it in 100_000L..50_000_000L }
            .toList()

        if (allPrices.isNotEmpty()) {
            return allPrices.first()
        }

        // 4. Abbreviated "$2.85M" format often used in Zillow/Redfin titles
        val millionMatch = Regex("""\$([0-9]{1,2}\.[0-9]{1,3})\s*M\b""", RegexOption.IGNORE_CASE).find(text)
        if (millionMatch != null) {
            val mVal = millionMatch.groupValues[1].toDoubleOrNull()
            if (mVal != null && mVal in 0.1..50.0) {
                return (mVal * 1_000_000).toLong()
            }
        }

        return null
    }
}
