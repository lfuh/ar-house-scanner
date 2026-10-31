package com.house.arscanner.model

data class SchoolRating(
    val name: String,
    val grades: String,
    val ratingOutOf10: Int,
    val distanceMiles: String
)

data class SaleRecord(
    val date: String,
    val event: String,
    val price: String,
    val priceChangePercent: String
)

data class PropertyValuation(
    val streetAddress: String,
    val cityStateZip: String,
    val latitude: Double,
    val longitude: Double,
    val headingDegrees: Int,
    val cardinalDirection: String,
    val estimatedDistanceMeters: Int,
    val estimatedMarketValue: String,
    val valuationRange: String,
    val pricePerSqFt: String,
    val lastSoldPrice: String,
    val lastSoldDate: String,
    val appreciationSinceLastSale: String,
    val estimatedMonthlyRent: String,
    val estimatedMortgageMonthly: String,
    val propertyTaxAnnual: String,
    val hoaFeeMonthly: String,
    val bedrooms: Int,
    val bathrooms: Double,
    val livingAreaSqFt: Int,
    val lotSizeSqFt: Int,
    val yearBuilt: Int,
    val propertyType: String,
    val overallSchoolScore: String,
    val schools: List<SchoolRating>,
    val saleHistory: List<SaleRecord>,
    val zillowSearchUrl: String,
    val redfinSearchUrl: String,
    val realtorSearchUrl: String,
    val valuationSource: String = "Zillow Live"
)
