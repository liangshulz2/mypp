package com.example.a4trendtrader

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.abs
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private var latestRows: List<DailyRow> = emptyList()

    private val csvPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val tvCsvResult = findViewById<TextView>(R.id.tvCsvResult)
        if (uri == null) {
            tvCsvResult.text = "未选择CSV文件"
            return@registerForActivityResult
        }
        val symbol = findViewById<EditText>(R.id.etSymbol).text.toString().trim()
        if (symbol.isEmpty()) {
            tvCsvResult.text = "请先输入品种代码（RB/M/MA）"
            return@registerForActivityResult
        }
        tvCsvResult.text = buildCsvSummary(uri, symbol)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val etSymbol = findViewById<EditText>(R.id.etSymbol)
        val btnEvaluate = findViewById<Button>(R.id.btnEvaluate)
        val btnImportCsv = findViewById<Button>(R.id.btnImportCsv)
        val tvResult = findViewById<TextView>(R.id.tvResult)

        btnEvaluate.setOnClickListener {
            val symbol = etSymbol.text.toString().trim()
            if (symbol.isEmpty()) {
                tvResult.text = "请输入品种代码（RB/M/MA）"
                return@setOnClickListener
            }
            if (latestRows.isEmpty()) {
                tvResult.text = "请先导入CSV数据"
                return@setOnClickListener
            }
            val latest = latestRows.last()
            val input = MarketInput(
                symbol = symbol,
                closePrice = latest.close,
                atr = latest.atr ?: 0.0,
                ma20Slope = latest.ma20Slope,
                breakoutHigh = latest.breakoutHigh,
                breakoutLow = latest.breakoutLow,
                gapAtrMultiple = 0.0,
                consecutiveStopLosses = 0
            )

            val decision = TradingRuleEngine.evaluateEntry(input)
            val detail = buildString {
                appendLine(decision.message)
                if (decision.canTrade) {
                    appendLine("方向: ${decision.direction}")
                    appendLine("建议手数: ${decision.suggestedLots}")
                    appendLine("ATR止损距离: ${decision.initialStopByAtr}")
                    appendLine("单笔风险预算: ${decision.hardStopLossAmount}")
                }
                appendLine()
                append(TradingRuleEngine.holdRulesSummary(symbol))
            }
            tvResult.text = detail
        }

        btnImportCsv.setOnClickListener {
            csvPicker.launch(arrayOf("text/csv", "text/comma-separated-values", "application/vnd.ms-excel"))
        }
    }

    private fun buildCsvSummary(uri: Uri, symbol: String): String {
        val config = TradingRuleEngine.getSymbolConfig(symbol)
            ?: return "品种不在固定池（仅允许 RB / M / MA）"
        val rows = readCsv(uri)
        if (rows.isEmpty()) {
            return "CSV为空或无法识别字段（需包含 date/high/low/close，atr可选）"
        }
        val withAtr = calculateAtr(rows, config.atrPeriod, config.breakoutPeriod)
        latestRows = withAtr
        val last10 = withAtr.takeLast(10)
        val lines = buildString {
            appendLine("最近10日：")
            last10.forEach { row ->
                val suggestion = when {
                    row.close > row.breakoutHigh -> "做多"
                    row.close < row.breakoutLow -> "做空"
                    else -> "观望"
                }
                appendLine(
                    "${row.date} | 建议: $suggestion | ATR: ${formatNumber(row.atr)}" +
                        " | 20日最高: ${formatNumber(row.high20)} | 20日最低: ${formatNumber(row.low20)}"
                )
            }
        }
        return lines
    }

    private fun readCsv(uri: Uri): List<DailyRow> {
        return contentResolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input)).use { reader ->
                val lines = reader.readLines().filter { it.isNotBlank() }
                if (lines.isEmpty()) return emptyList()
                val header = lines.first().split(",").map { it.trim().lowercase() }
                val idxDate = header.indexOfFirst { it == "date" || it == "日期" }
                val idxHigh = header.indexOfFirst { it == "high" || it == "最高" }
                val idxLow = header.indexOfFirst { it == "low" || it == "最低" }
                val idxClose = header.indexOfFirst { it == "close" || it == "收盘" }
                val idxAtr = header.indexOfFirst { it == "atr" }

                if (idxDate == -1 || idxHigh == -1 || idxLow == -1 || idxClose == -1) {
                    return emptyList()
                }

                lines.drop(1).mapNotNull { line ->
                    val parts = line.split(",")
                    val date = parts.getOrNull(idxDate)?.trim().orEmpty()
                    if (date.isEmpty()) return@mapNotNull null
                    val high = parts.getOrNull(idxHigh)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
                    val low = parts.getOrNull(idxLow)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
                    val close = parts.getOrNull(idxClose)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
                    val atr = parts.getOrNull(idxAtr)?.trim()?.toDoubleOrNull()
                    DailyRow(date = date, high = high, low = low, close = close, atr = atr)
                }
            }
        } ?: emptyList()
    }

    private fun calculateAtr(
        rows: List<DailyRow>,
        atrPeriod: Int,
        breakoutPeriod: Int
    ): List<DailyRow> {
        if (rows.isEmpty()) return rows
        val result = mutableListOf<DailyRow>()
        var prevClose = rows.first().close
        val trValues = mutableListOf<Double>()
        val closeValues = mutableListOf<Double>()
        var prevMa20: Double? = null
        rows.forEachIndexed { index, row ->
            val tr = max(row.high - row.low, max(abs(row.high - prevClose), abs(row.low - prevClose)))
            trValues.add(tr)
            val atr = row.atr ?: if (index + 1 >= atrPeriod) {
                trValues.takeLast(atrPeriod).average()
            } else {
                trValues.average()
            }
            closeValues.add(row.close)
            val recent20 = rows.subList(0, index + 1).takeLast(20)
            val high20 = recent20.maxOf { it.high }
            val low20 = recent20.minOf { it.low }
            val recentBreakout = rows.subList(0, index + 1).takeLast(breakoutPeriod)
            val breakoutHigh = recentBreakout.maxOf { it.high }
            val breakoutLow = recentBreakout.minOf { it.low }
            val ma20 = if (closeValues.size >= 20) {
                closeValues.takeLast(20).average()
            } else {
                closeValues.average()
            }
            val ma20Slope = if (prevMa20 == null) 0.0 else ma20 - (prevMa20 ?: ma20)
            prevMa20 = ma20
            result.add(
                row.copy(
                    atr = atr,
                    high20 = high20,
                    low20 = low20,
                    breakoutHigh = breakoutHigh,
                    breakoutLow = breakoutLow,
                    ma20 = ma20,
                    ma20Slope = ma20Slope
                )
            )
            prevClose = row.close
        }
        return result
    }

    private fun formatNumber(value: Double?): String {
        if (value == null) return "--"
        return String.format("%.2f", value)
    }

    data class DailyRow(
        val date: String,
        val high: Double,
        val low: Double,
        val close: Double,
        val atr: Double? = null,
        val high20: Double = high,
        val low20: Double = low,
        val breakoutHigh: Double = high,
        val breakoutLow: Double = low,
        val ma20: Double = close,
        val ma20Slope: Double = 0.0
    )
}
