package com.example.a4trendtrader

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var csvComputed: CsvComputed? = null

    private val openCsvLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            handleCsvSelected(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val etSymbol = findViewById<EditText>(R.id.etSymbol)
        val btnImportCsv = findViewById<Button>(R.id.btnImportCsv)
        val btnEvaluate = findViewById<Button>(R.id.btnEvaluate)
        val tvCsvStatus = findViewById<TextView>(R.id.tvCsvStatus)
        val tvResult = findViewById<TextView>(R.id.tvResult)

        btnImportCsv.setOnClickListener {
            openCsvLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain"))
        }

        btnEvaluate.setOnClickListener {
            val symbol = etSymbol.text.toString().trim()
            val computed = csvComputed
            if (symbol.isBlank()) {
                tvResult.text = "请先填写品种代码。"
                return@setOnClickListener
            }
            if (computed == null) {
                tvResult.text = "请先导入CSV行情数据。"
                return@setOnClickListener
            }
            if (!computed.symbolMatches(symbol)) {
                tvResult.text = "CSV中的品种与输入不一致，请检查品种代码。"
                return@setOnClickListener
            }

            val input = MarketInput(
                symbol = symbol,
                closePrice = computed.closePrice,
                atr = computed.atr,
                ma20Slope = computed.ma20Slope,
                high20 = computed.high20,
                low20 = computed.low20,
                gapAtrMultiple = computed.gapAtrMultiple,
                consecutiveStopLosses = computed.consecutiveStopLosses
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

        csvComputed?.let { computed ->
            tvCsvStatus.text = computed.statusText()
        } ?: run {
            tvCsvStatus.text = "未导入CSV"
        }
    }

    private fun handleCsvSelected(uri: Uri) {
        val tvCsvStatus = findViewById<TextView>(R.id.tvCsvStatus)
        val tvResult = findViewById<TextView>(R.id.tvResult)
        val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        if (content.isNullOrBlank()) {
            tvCsvStatus.text = "CSV读取失败"
            tvResult.text = "无法读取CSV内容，请确认文件格式。"
            csvComputed = null
            return
        }

        val parseResult = CsvParser.parse(content)
        if (parseResult.error != null) {
            tvCsvStatus.text = "CSV解析失败"
            tvResult.text = parseResult.error
            csvComputed = null
            return
        }

        csvComputed = parseResult.computed
        tvCsvStatus.text = parseResult.computed.statusText()
        tvResult.text = "CSV已导入：${parseResult.computed.summaryText()}"
    }
}
