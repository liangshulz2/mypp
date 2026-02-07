package com.example.a4trendtrader

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

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

        val btnEvaluate = findViewById<Button>(R.id.btnEvaluate)
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
    }
}
