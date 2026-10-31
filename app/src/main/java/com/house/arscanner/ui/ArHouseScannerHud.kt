package com.house.arscanner.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.house.arscanner.data.PropertyValuationEngine
import com.house.arscanner.model.PropertyValuation
import kotlin.math.roundToInt

private val EmeraldAccent = Color(0xFF00E676)
private val CyanAccent = Color(0xFF00E5FF)
private val GoldAccent = Color(0xFFFFCA28)
private val DarkGlass = Color(0xCC0A121E)
private val CardGlass = Color(0xE6101C2E)
private val SurfaceSlate = Color(0xFF17263C)
private val TextWhite = Color(0xFFF3F8FF)
private val TextMuted = Color(0xFF9AB0CC)

/**
 * Top telemetry HUD bar displaying live GPS coordinates, compass heading,
 * target distance selector, and stabilization status.
 */
@Composable
fun TopTelemetryHudBar(
    lat: Double,
    lon: Double,
    hasRealGps: Boolean,
    headingDeg: Float,
    targetDistanceMeters: Int,
    isLocked: Boolean,
    isSteady: Boolean,
    stabilityProgress: Float,
    onCycleDistance: () -> Unit,
    onRescanClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardinal = PropertyValuationEngine.headingToCardinal(headingDeg.roundToInt())

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(DarkGlass)
                .border(1.dp, Color(0x4D00E5FF), RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: GPS Status & Coordinates
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(if (hasRealGps) EmeraldAccent else GoldAccent)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = if (hasRealGps) "LIVE GPS + COMPASS" else "GPS DEMO / INDOOR MODE",
                        color = if (hasRealGps) EmeraldAccent else GoldAccent,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.6.sp
                    )
                    Text(
                        text = String.format("%.5f, %.5f", lat, lon),
                        color = TextWhite,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Middle: Compass Heading & Target Distance pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceSlate)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "🧭 ${headingDeg.roundToInt()}° $cardinal",
                        color = CyanAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceSlate)
                        .border(1.dp, CyanAccent.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .clickable { onCycleDistance() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "🎯 ${targetDistanceMeters}m",
                        color = TextWhite,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(CyanAccent.copy(alpha = 0.2f))
                        .border(1.dp, CyanAccent, RoundedCornerShape(8.dp))
                        .clickable { onRescanClick() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Scan Now",
                        color = CyanAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Preset Property Switcher (helpful when testing indoors or comparing houses)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Test Locations:",
                color = TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(DarkGlass)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
            PropertyValuationEngine.presetLocations.forEach { (label, pLat, pLon) ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(DarkGlass)
                        .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(50))
                        .clickable {
                            onPresetSelected(label, pLat, pLon)
                        }
                        .padding(horizontal = 9.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = label,
                        color = TextWhite,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

// Callback holder to keep TopTelemetryHudBar signature clean
private var onPresetSelected: (String, Double, Double) -> Unit = { _, _, _ -> }

fun bindPresetLocationCallback(callback: (String, Double, Double) -> Unit) {
    onPresetSelected = callback
}

/**
 * Center AR Viewfinder Reticle & Floating Price Callout Pin.
 * Shows the circular stabilization progress ring while locking onto the house,
 * and transforms into a floating AR Price Callout Badge anchored to the house once stabilized!
 */
@Composable
fun ArHouseTargetOverlay(
    stabilityProgress: Float,
    isSteady: Boolean,
    isLocked: Boolean,
    isLoadingLookup: Boolean,
    property: PropertyValuation?,
    onOpenDetailsSheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = stabilityProgress,
        animationSpec = tween(durationMillis = 120),
        label = "stability_ring"
    )

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // 1. Viewfinder Reticle & Stabilization Ring
        Box(
            modifier = Modifier.size(190.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeW = 3.dp.toPx()
                val ringColor = when {
                    isLocked -> EmeraldAccent
                    isSteady -> CyanAccent
                    else -> GoldAccent
                }

                // Corner framing brackets
                val bracketLen = 26.dp.toPx()
                val pad = 10.dp.toPx()
                val w = size.width
                val h = size.height

                // Top-left bracket
                drawLine(ringColor, Offset(pad, pad), Offset(pad + bracketLen, pad), strokeW)
                drawLine(ringColor, Offset(pad, pad), Offset(pad, pad + bracketLen), strokeW)
                // Top-right bracket
                drawLine(ringColor, Offset(w - pad, pad), Offset(w - pad - bracketLen, pad), strokeW)
                drawLine(ringColor, Offset(w - pad, pad), Offset(w - pad, pad + bracketLen), strokeW)
                // Bottom-left bracket
                drawLine(ringColor, Offset(pad, h - pad), Offset(pad + bracketLen, h - pad), strokeW)
                drawLine(ringColor, Offset(pad, h - pad), Offset(pad, h - pad - bracketLen), strokeW)
                // Bottom-right bracket
                drawLine(ringColor, Offset(w - pad, h - pad), Offset(w - pad - bracketLen, h - pad), strokeW)
                drawLine(ringColor, Offset(w - pad, h - pad), Offset(w - pad, h - pad - bracketLen), strokeW)

                // Inner circular stabilization progress arc
                val arcRadius = (size.minDimension / 2f) - 24.dp.toPx()
                drawCircle(
                    color = Color.White.copy(alpha = 0.15f),
                    radius = arcRadius,
                    style = Stroke(width = 4.dp.toPx())
                )
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * animatedProgress,
                    useCenter = false,
                    topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                    size = Size(arcRadius * 2, arcRadius * 2),
                    style = Stroke(width = 4.5.dp.toPx(), cap = StrokeCap.Round)
                )

                // Center target dot
                drawCircle(
                    color = ringColor,
                    radius = if (isLocked) 5.dp.toPx() else 3.5.dp.toPx(),
                    center = center
                )
            }

            // Stabilization status prompt when not yet locked
            if (!isLocked || isLoadingLookup) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                        .clip(RoundedCornerShape(50))
                        .background(DarkGlass)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    val statusText = when {
                        isLoadingLookup -> "Fetching live Zillow Zestimate..."
                        !isSteady -> "Point at house & hold steady..."
                        else -> "Stabilizing ${(animatedProgress * 100).roundToInt()}%..."
                    }
                    Text(
                        text = statusText,
                        color = if (isSteady) CyanAccent else GoldAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 2. Floating AR House Valuation Callout Pin (Appears over the house once stabilized!)
        AnimatedVisibility(
            visible = isLocked && property != null && !isLoadingLookup,
            enter = fadeIn() + scaleIn(initialScale = 0.88f),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 140.dp)
        ) {
            property?.let { prop ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { onOpenDetailsSheet() }
                ) {
                    Surface(
                        color = CardGlass,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .shadow(16.dp, RoundedCornerShape(16.dp))
                            .border(
                                width = 1.5.dp,
                                brush = Brush.horizontalGradient(listOf(EmeraldAccent, CyanAccent)),
                                shape = RoundedCornerShape(16.dp)
                            )
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Address pill + Live Source Indicator
                            Text(
                                text = "📍 ${prop.streetAddress} • ${prop.cityStateZip}",
                                color = CyanAccent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "⚡ ${prop.valuationSource}",
                                color = EmeraldAccent,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(2.dp))

                            // Prominent Market Value
                            Text(
                                text = prop.estimatedMarketValue,
                                color = TextWhite,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.ExtraBold
                            )

                            // Bed / Bath / SqFt & Price/SqFt
                            val bathText = if (prop.bathrooms % 1.0 == 0.0) {
                                prop.bathrooms.toInt().toString()
                            } else {
                                prop.bathrooms.toString()
                            }
                            Text(
                                text = "${prop.bedrooms} bd • $bathText ba • ${prop.livingAreaSqFt} sqft (${prop.pricePerSqFt})",
                                color = TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            // Quick 3-metric badges inside floating AR tag: Last Sold, Est Rent, School Score
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ArFloatingMetricPill(
                                    label = "Last Sold",
                                    value = "${prop.lastSoldPrice} (${prop.lastSoldDate})",
                                    accent = GoldAccent
                                )
                                ArFloatingMetricPill(
                                    label = "Est. Rent",
                                    value = prop.estimatedMonthlyRent,
                                    accent = EmeraldAccent
                                )
                                ArFloatingMetricPill(
                                    label = "Schools",
                                    value = "🎓 ${prop.overallSchoolScore}",
                                    accent = CyanAccent
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Tap badge or card below for full property report ▾",
                                color = EmeraldAccent,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Downward pointer triangle connecting the AR badge to the house reticle
                    Canvas(modifier = Modifier.size(width = 20.dp, height = 10.dp)) {
                        val path = Path().apply {
                            moveTo(0f, 0f)
                            lineTo(size.width, 0f)
                            lineTo(size.width / 2f, size.height)
                            close()
                        }
                        drawPath(path, color = EmeraldAccent)
                    }
                }
            }
        }
    }
}

@Composable
private fun ArFloatingMetricPill(
    label: String,
    value: String,
    accent: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceSlate)
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label.uppercase(),
            color = TextMuted,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = value,
            color = accent,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

/**
 * Bottom Property Valuation Card & Expandable Full Report Dialog.
 * Automatically slides up when the camera locks onto a house.
 */
@Composable
fun BottomPropertyInsightSheet(
    property: PropertyValuation?,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onLockNewHouse: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    AnimatedVisibility(
        visible = property != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        property?.let { prop ->
            Surface(
                color = CardGlass,
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.2.dp,
                        brush = Brush.horizontalGradient(listOf(CyanAccent, EmeraldAccent)),
                        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // Drag handle pill
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .width(42.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(TextMuted.copy(alpha = 0.5f))
                            .clickable { onToggleExpand() }
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Header row: Address + Valuation + Expand/Collapse toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleExpand() },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(EmeraldAccent.copy(alpha = 0.2f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "LOCKED • ${prop.estimatedDistanceMeters}m ${prop.cardinalDirection}",
                                        color = EmeraldAccent,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Built ${prop.yearBuilt}",
                                    color = TextMuted,
                                    fontSize = 10.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = prop.streetAddress,
                                color = TextWhite,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = prop.cityStateZip,
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = prop.valuationSource.uppercase(),
                                color = CyanAccent,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = prop.estimatedMarketValue,
                                color = EmeraldAccent,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "Range: ${prop.valuationRange}",
                                color = TextMuted,
                                fontSize = 9.5.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Key Metrics Grid (Always visible in compact card)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PropertyStatBox(
                            title = "Last Sold Price",
                            primaryValue = prop.lastSoldPrice,
                            subValue = "${prop.lastSoldDate} (${prop.appreciationSinceLastSale})",
                            accentColor = GoldAccent,
                            modifier = Modifier.weight(1f)
                        )
                        PropertyStatBox(
                            title = "Est. Monthly Rent",
                            primaryValue = prop.estimatedMonthlyRent,
                            subValue = "Mortgage: ${prop.estimatedMortgageMonthly}",
                            accentColor = EmeraldAccent,
                            modifier = Modifier.weight(1f)
                        )
                        PropertyStatBox(
                            title = "School Score",
                            primaryValue = prop.overallSchoolScore,
                            subValue = "${prop.schools.size} Assigned Schools",
                            accentColor = CyanAccent,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Expand / Collapse button row
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val bathStr = if (prop.bathrooms % 1.0 == 0.0) {
                            prop.bathrooms.toInt().toString()
                        } else {
                            prop.bathrooms.toString()
                        }
                        Text(
                            text = "${prop.bedrooms} Beds • $bathStr Baths • ${prop.livingAreaSqFt} SqFt • Lot ${prop.lotSizeSqFt} SqFt",
                            color = TextWhite,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (isExpanded) "Hide Details ▴" else "Full Details ▾",
                            color = CyanAccent,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onToggleExpand() }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    // Expanded Detailed Sections: Schools, Sale History, Taxes, and Live Zillow/Redfin Links
                    AnimatedVisibility(visible = isExpanded) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Spacer(modifier = Modifier.height(10.dp))

                            // Assigned Schools Breakdown
                            Text(
                                text = "ASSIGNED SCHOOLS & RATINGS",
                                color = CyanAccent,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.6.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            prop.schools.forEach { school ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(SurfaceSlate)
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (school.ratingOutOf10 >= 8) EmeraldAccent.copy(alpha = 0.2f)
                                                    else GoldAccent.copy(alpha = 0.2f)
                                                )
                                                .border(
                                                    1.dp,
                                                    if (school.ratingOutOf10 >= 8) EmeraldAccent else GoldAccent,
                                                    CircleShape
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "${school.ratingOutOf10}/10",
                                                color = if (school.ratingOutOf10 >= 8) EmeraldAccent else GoldAccent,
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = school.name,
                                                color = TextWhite,
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "Grades ${school.grades}",
                                                color = TextMuted,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                    Text(
                                        text = school.distanceMiles,
                                        color = TextMuted,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Sale & Tax History
                            Text(
                                text = "PRIOR SALE HISTORY & OWNERSHIP COSTS",
                                color = GoldAccent,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.6.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            prop.saleHistory.forEach { sale ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(SurfaceSlate)
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = sale.event,
                                            color = TextWhite,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = sale.date,
                                            color = TextMuted,
                                            fontSize = 10.sp
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = sale.price,
                                            color = GoldAccent,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = sale.priceChangePercent,
                                            color = EmeraldAccent,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Est. Property Tax: ${prop.propertyTaxAnnual}  •  HOA: ${prop.hoaFeeMonthly}  •  Price/SqFt: ${prop.pricePerSqFt}",
                                color = TextMuted,
                                fontSize = 10.5.sp
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // Direct Live Listing Lookup Buttons (Zillow / Redfin / Realtor)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(prop.zillowSearchUrl))
                                        )
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = CyanAccent,
                                        contentColor = Color(0xFF06101E)
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Open on Zillow", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                                }

                                OutlinedButton(
                                    onClick = {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(prop.redfinSearchUrl))
                                        )
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Open on Redfin", color = TextWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                OutlinedButton(
                                    onClick = onLockNewHouse,
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Rescan", color = EmeraldAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PropertyStatBox(
    title: String,
    primaryValue: String,
    subValue: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceSlate)
            .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp)
    ) {
        Text(
            text = title,
            color = TextMuted,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = primaryValue,
            color = accentColor,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1
        )
        Text(
            text = subValue,
            color = TextWhite.copy(alpha = 0.85f),
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
