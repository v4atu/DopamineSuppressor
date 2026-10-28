package com.v4atu.dopaminesuppressor.data

data class AppLimit(
    val packageName: String,
    val dailyLimitMinutes: Int,
    val enabled: Boolean,
) {
    val limitMillis: Long get() = dailyLimitMinutes * 60_000L
}
