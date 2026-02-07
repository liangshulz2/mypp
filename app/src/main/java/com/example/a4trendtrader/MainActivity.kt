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

    private val csvPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val tvCsvResult = findViewById<TextView>(R.id.tvCsvResult)
        if (uri == null) {
            tvCsvResult.text = "未选择CSV文件"
            return@registerForActivityResult
        }
        tvCsvResult.text = buildCsvSummary(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val etSymbol = findViewById<EditText>(R.id.etSymbol)
        val etPrice = findViewById<EditText>(R.id.etPrice)
        val etAtr = findViewById<EditText>(R.id.etAtr)
        val etMa20Slope = findViewById<EditText>(R.id.etMa20Slope)
        val etHigh20 = findViewById<EditText>(R.id.etHigh20)
        val etLow20 = findViewById<EditText>(R.id.etLow20)
        val etGapAtr = findViewById<EditText>(R.id.etGapAtr)
        val etConsecutiveLosses = findViewById<EditText>(R.id.etConsecutiveLosses)

        val btnEvaluate = findViewById<Button>(R.id.btnEvaluate)
        val btnImportCsv = findViewById<Button>(R.id.btnImportCsv)
        val tvResult = findViewById<TextView>(R.id.tvResult)

        btnEvaluate.setOnClickListener {
            val input = MarketInput(
                symbol = etSymbol.text.toString().trim(),
                closePrice = etPrice.text.toString().toDoubleOrNull() ?: 0.0,
                atr = etAtr.text.toString().toDoubleOrNull() ?: 0.0,
                ma20Slope = etMa20Slope.text.toString().toDoubleOrNull() ?: 0.0,
                high20 = etHigh20.text.toString().toDoubleOrNull() ?: 0.0,
                low20 = etLow20.text.toString().toDoubleOrNull() ?: 0.0,
                gapAtrMultiple = etGapAtr.text.toString().toDoubleOrNull() ?: 0.0,
                consecutiveStopLosses = etConsecutiveLosses.text.toString().toIntOrNull() ?: 0
            )

            val decision = TradingRuleEngine.evaluateEntry(input)
            val detail = buildString {
                appendLine(decision.message)
                if (decision.canTrade) {
                    appendLine("方向: ${decision.direction}")
                    appendLine("建议手数: ${decision.suggestedLots}")
                    appendLine("ATR止损距离: ${decision.initialStopByAtr}")
                    appendLine("金额硬止损(单手): ${decision.hardStopLossAmount}")
                }
                appendLine()
                append(TradingRuleEngine.holdRulesSummary())
            }
            tvResult.text = detail
        }

        btnImportCsv.setOnClickListener {
            csvPicker.launch(arrayOf("text/csv", "text/comma-separated-values", "application/vnd.ms-excel"))
        }
    }

    private fun buildCsvSummary(uri: Uri): String {
        val rows = readCsv(uri)
        if (rows.isEmpty()) {
            return "CSV为空或无法识别字段（需包含 date/high/low/close，atr可选）"
        }
        val withAtr = calculateAtr(rows)
        val last10 = withAtr.takeLast(10)
        val lines = buildString {
            appendLine("最近10日：")
            last10.forEach { row ->
                val suggestion = when {
                    row.close > row.high20 -> "做多"
                    row.close < row.low20 -> "做空"
                    else -> "观望"
                }
                appendLine("${row.date} | 建议: $suggestion | ATR: ${formatNumber(row.atr)}")
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

    private fun calculateAtr(rows: List<DailyRow>, period: Int = 14): List<DailyRow> {
        if (rows.isEmpty()) return rows
        val result = mutableListOf<DailyRow>()
        var prevClose = rows.first().close
        val trValues = mutableListOf<Double>()
        rows.forEachIndexed { index, row ->
            val tr = max(row.high - row.low, max(abs(row.high - prevClose), abs(row.low - prevClose)))
            trValues.add(tr)
            val atr = row.atr ?: if (index + 1 >= period) {
                trValues.takeLast(period).average()
            } else {
                trValues.average()
            }
            val high20 = rows.subList(0, index + 1).takeLast(20).maxOf { it.high }
            val low20 = rows.subList(0, index + 1).takeLast(20).minOf { it.low }
            result.add(row.copy(atr = atr, high20 = high20, low20 = low20))
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
        val low20: Double = low
    )
}
