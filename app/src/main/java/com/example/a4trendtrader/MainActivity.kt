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
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

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

        val btnImportCsv = findViewById<Button>(R.id.btnImportCsv)
        val tvImportStatus = findViewById<TextView>(R.id.tvImportStatus)
        val btnEvaluate = findViewById<Button>(R.id.btnEvaluate)
        val tvResult = findViewById<TextView>(R.id.tvResult)

        val importLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri == null) {
                tvImportStatus.text = "未选择文件"
                return@registerForActivityResult
            }

            val result = runCatching { readFuturesCsv(uri) }
            result.onSuccess { parseResult ->
                tvImportStatus.text = parseResult.summary
                parseResult.latestClose?.let { etPrice.setText(it) }
                parseResult.high20?.let { etHigh20.setText(it) }
                parseResult.low20?.let { etLow20.setText(it) }
            }.onFailure { error ->
                tvImportStatus.text = "导入失败：${error.message}"
            }
        }

        btnImportCsv.setOnClickListener {
            importLauncher.launch(arrayOf("text/csv", "text/*", "application/vnd.ms-excel"))
        }

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
    }

    private fun readFuturesCsv(uri: Uri): CsvParseResult {
        contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                val lines = reader.readLines()
                if (lines.isEmpty()) {
                    return CsvParseResult(summary = "文件为空")
                }

                val rows = lines.dropWhile { it.isBlank() }.drop(1)
                    .mapNotNull { parseCsvLine(it) }

                if (rows.isEmpty()) {
                    return CsvParseResult(summary = "未解析到有效数据行")
                }

                val latest = rows.last()
                val last20 = rows.takeLast(20)
                val high20 = last20.fold(Double.MIN_VALUE) { acc, bar -> max(acc, bar.high) }
                val low20 = last20.fold(Double.MAX_VALUE) { acc, bar -> min(acc, bar.low) }

                return CsvParseResult(
                    summary = buildString {
                        append("已导入 ${rows.size} 行，最新日期 ${latest.date}。")
                        append("20日最高 ${formatNumber(high20)}，20日最低 ${formatNumber(low20)}。")
                    },
                    latestClose = formatNumber(latest.close),
                    high20 = formatNumber(high20),
                    low20 = formatNumber(low20)
                )
            }
        }

        return CsvParseResult(summary = "无法读取文件内容")
    }

    private fun parseCsvLine(line: String): FuturesBar? {
        val parts = line.split(",").map { it.trim() }
        if (parts.size < 8 || parts[0].equals("date", ignoreCase = true)) {
            return null
        }

        val date = parts[0]
        val open = parts[1].toDoubleOrNull() ?: return null
        val high = parts[2].toDoubleOrNull() ?: return null
        val low = parts[3].toDoubleOrNull() ?: return null
        val close = parts[4].toDoubleOrNull() ?: return null
        val volume = parts[5].toLongOrNull() ?: 0L
        val hold = parts[6].toLongOrNull() ?: 0L
        val settle = parts[7].toDoubleOrNull() ?: 0.0

        return FuturesBar(
            date = date,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
            hold = hold,
            settle = settle
        )
    }

    private fun formatNumber(value: Double): String {
        return if (value % 1.0 == 0.0) {
            value.toInt().toString()
        } else {
            String.format("%.2f", value)
        }
    }
}

private data class FuturesBar(
    val date: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
    val hold: Long,
    val settle: Double
)

private data class CsvParseResult(
    val summary: String,
    val latestClose: String? = null,
    val high20: String? = null,
    val low20: String? = null
)
