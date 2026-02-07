package com.example.a4trendtrader

import kotlin.math.abs
import kotlin.math.max

private const val ATR_PERIOD = 14
private const val MA_PERIOD = 20

data class CsvComputed(
    val symbolFromCsv: String?,
    val closePrice: Double,
    val atr: Double,
    val ma20Slope: Double,
    val high20: Double,
    val low20: Double,
    val gapAtrMultiple: Double,
    val consecutiveStopLosses: Int
) {
    fun symbolMatches(input: String): Boolean {
        return symbolFromCsv == null || symbolFromCsv.equals(input, ignoreCase = true)
    }

    fun statusText(): String {
        return symbolFromCsv?.let { "已导入CSV（品种：$it）" } ?: "已导入CSV"
    }

    fun summaryText(): String {
        return "收盘价=$closePrice，ATR=$atr，20日高/低=$high20/$low20，" +
            "MA20斜率=$ma20Slope，跳空ATR=$gapAtrMultiple"
    }
}

data class CsvParseResult(
    val computed: CsvComputed? = null,
    val error: String? = null
)

private data class CsvRow(
    val symbol: String?,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val consecutiveStopLosses: Int?
)

object CsvParser {
    fun parse(content: String): CsvParseResult {
        val lines = content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        if (lines.isEmpty()) {
            return CsvParseResult(error = "CSV为空或无有效内容。")
        }

        val headerTokens = lines.first().split(",").map { it.trim() }
        val hasHeader = headerTokens.any { token ->
            token.equals("open", true) ||
                token.equals("high", true) ||
                token.equals("low", true) ||
                token.equals("close", true) ||
                token.equals("symbol", true) ||
                token.equals("code", true)
        }

        val startIndex = if (hasHeader) 1 else 0
        val indices = if (hasHeader) {
            headerTokens.mapIndexed { index, token -> token.lowercase() to index }.toMap()
        } else {
            emptyMap()
        }

        val rows = mutableListOf<CsvRow>()
        for (i in startIndex until lines.size) {
            val values = lines[i].split(",").map { it.trim() }
            if (values.size < 5) {
                continue
            }

            val symbol = valueAt(values, indices, listOf("symbol", "code", "ticker"))
            val open = valueAt(values, indices, listOf("open"))?.toDoubleOrNull()
                ?: valueAt(values, indices, listOf("开盘", "o"))?.toDoubleOrNull()
                ?: fallbackNumeric(values, 1)
            val high = valueAt(values, indices, listOf("high"))?.toDoubleOrNull()
                ?: valueAt(values, indices, listOf("最高", "h"))?.toDoubleOrNull()
                ?: fallbackNumeric(values, 2)
            val low = valueAt(values, indices, listOf("low"))?.toDoubleOrNull()
                ?: valueAt(values, indices, listOf("最低", "l"))?.toDoubleOrNull()
                ?: fallbackNumeric(values, 3)
            val close = valueAt(values, indices, listOf("close"))?.toDoubleOrNull()
                ?: valueAt(values, indices, listOf("收盘", "c"))?.toDoubleOrNull()
                ?: fallbackNumeric(values, 4)

            val consecutiveStopLosses = valueAt(values, indices, listOf("consecutive_stop_losses", "consecutivelosses"))
                ?.toIntOrNull()

            if (open == null || high == null || low == null || close == null) {
                continue
            }

            rows.add(
                CsvRow(
                    symbol = symbol,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    consecutiveStopLosses = consecutiveStopLosses
                )
            )
        }

        if (rows.size < MA_PERIOD + 1) {
            return CsvParseResult(error = "CSV行数不足，至少需要${MA_PERIOD + 1}行行情数据。")
        }

        val symbols = rows.mapNotNull { it.symbol?.ifBlank { null } }.toSet()
        if (symbols.size > 1) {
            return CsvParseResult(error = "CSV包含多个品种，请只保留单一品种数据。")
        }
        val symbolFromCsv = symbols.firstOrNull()

        val lastRow = rows.last()
        val closePrice = lastRow.close
        val high20 = rows.takeLast(MA_PERIOD).maxOf { it.high }
        val low20 = rows.takeLast(MA_PERIOD).minOf { it.low }

        val ma20 = rows.takeLast(MA_PERIOD).map { it.close }.average()
        val prevMa20 = rows.dropLast(1).takeLast(MA_PERIOD).map { it.close }.average()
        val ma20Slope = ma20 - prevMa20

        val atrStartIndex = rows.size - ATR_PERIOD
        if (atrStartIndex < 1) {
            return CsvParseResult(error = "CSV行数不足，无法计算ATR。")
        }

        val trueRanges = mutableListOf<Double>()
        for (i in atrStartIndex until rows.size) {
            val prevClose = rows[i - 1].close
            val high = rows[i].high
            val low = rows[i].low
            val tr = max(high - low, max(abs(high - prevClose), abs(low - prevClose)))
            trueRanges.add(tr)
        }
        val atr = trueRanges.average()

        val prevClose = rows[rows.size - 2].close
        val gap = abs(lastRow.open - prevClose)
        val gapAtrMultiple = if (atr == 0.0) 0.0 else gap / atr

        val consecutiveStopLosses = lastRow.consecutiveStopLosses ?: 0

        return CsvParseResult(
            computed = CsvComputed(
                symbolFromCsv = symbolFromCsv,
                closePrice = closePrice,
                atr = atr,
                ma20Slope = ma20Slope,
                high20 = high20,
                low20 = low20,
                gapAtrMultiple = gapAtrMultiple,
                consecutiveStopLosses = consecutiveStopLosses
            )
        )
    }

    private fun valueAt(values: List<String>, indices: Map<String, Int>, keys: List<String>): String? {
        for (key in keys) {
            val index = indices[key.lowercase()] ?: continue
            if (index in values.indices) {
                return values[index]
            }
        }
        return null
    }

    private fun fallbackNumeric(values: List<String>, index: Int): Double? {
        return if (index in values.indices) values[index].toDoubleOrNull() else null
    }
}
