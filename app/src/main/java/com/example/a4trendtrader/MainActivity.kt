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
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
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
        val btnFetchOnline = findViewById<Button>(R.id.btnFetchOnline)
        val tvResult = findViewById<TextView>(R.id.tvResult)
        val tvOnlineResult = findViewById<TextView>(R.id.tvOnlineResult)

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

        btnFetchOnline.setOnClickListener {
            val symbol = etSymbol.text.toString().trim()
            if (symbol.isEmpty()) {
                tvOnlineResult.text = "请输入品种代码（RB/M/MA）"
                return@setOnClickListener
            }
            tvOnlineResult.text = "正在获取线上行情..."
            fetchOnlineQuote(symbol) { result ->
                tvOnlineResult.text = result
            }
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

    private fun fetchOnlineQuote(symbol: String, onResult: (String) -> Unit) {
        val contract = symbolToContract(symbol)
        if (contract == null) {
            onResult("品种不在固定池（仅允许 RB / M / MA）")
            return
        }
        thread {
            val result = runCatching {
                val url = URL("https://hq.sinajs.cn/list=nf_$contract")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Referer", "https://vip.stock.finance.sina.com.cn/")
                connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                parseSinaQuote(contract, body)
            }.getOrElse { ex ->
                "获取失败：${ex.message ?: "未知错误"}"
            }
            runOnUiThread {
                onResult(result)
            }
        }
    }

    private fun symbolToContract(symbol: String): String? {
        return when (symbol.uppercase()) {
            "RB" -> "RB0"
            "M" -> "M0"
            "MA" -> "MA0"
            else -> null
        }
    }

    private fun parseSinaQuote(contract: String, body: String): String {
        val payload = body.substringAfter("=\"", "")
            .substringBeforeLast("\"", "")
        if (payload.isBlank()) {
            return "未获取到行情数据（可能被限流或合约无效）"
        }
        val fields = payload.split(",")
        val quote = FuturesQuote(
            name = fields.getOrNull(0).orEmpty(),
            time = fields.getOrNull(1).orEmpty(),
            open = fields.getOrNull(2)?.toDoubleOrNull(),
            high = fields.getOrNull(3)?.toDoubleOrNull(),
            low = fields.getOrNull(4)?.toDoubleOrNull(),
            lastClose = fields.getOrNull(5)?.toDoubleOrNull(),
            bid = fields.getOrNull(6)?.toDoubleOrNull(),
            ask = fields.getOrNull(7)?.toDoubleOrNull(),
            price = fields.getOrNull(8)?.toDoubleOrNull(),
            avgPrice = fields.getOrNull(9)?.toDoubleOrNull(),
            settle = fields.getOrNull(10)?.toDoubleOrNull(),
            buyVol = fields.getOrNull(11)?.toLongOrNull(),
            sellVol = fields.getOrNull(12)?.toLongOrNull(),
            hold = fields.getOrNull(13)?.toLongOrNull(),
            volume = fields.getOrNull(14)?.toLongOrNull()
        )
        return buildString {
            appendLine("在线行情（$contract）：${quote.name}")
            appendLine("时间: ${quote.time}")
            appendLine(
                "开/高/低/现: ${formatNumber(quote.open)} / ${formatNumber(quote.high)} / " +
                    "${formatNumber(quote.low)} / ${formatNumber(quote.price)}"
            )
            appendLine(
                "昨收: ${formatNumber(quote.lastClose)} | 结算: ${formatNumber(quote.settle)}" +
                    " | 均价: ${formatNumber(quote.avgPrice)}"
            )
            appendLine(
                "买一/卖一: ${formatNumber(quote.bid)} / ${formatNumber(quote.ask)}"
            )
            appendLine(
                "成交量: ${quote.volume ?: "--"} | 持仓量: ${quote.hold ?: "--"}"
            )
            appendLine("数据源: 新浪财经 (hq.sinajs.cn)")
        }
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

    data class FuturesQuote(
        val name: String,
        val time: String,
        val open: Double?,
        val high: Double?,
        val low: Double?,
        val lastClose: Double?,
        val bid: Double?,
        val ask: Double?,
        val price: Double?,
        val avgPrice: Double?,
        val settle: Double?,
        val buyVol: Long?,
        val sellVol: Long?,
        val hold: Long?,
        val volume: Long?
    )
}
