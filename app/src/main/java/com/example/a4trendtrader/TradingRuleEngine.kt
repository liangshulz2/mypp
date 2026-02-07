package com.example.a4trendtrader

import kotlin.math.floor

private const val INITIAL_CAPITAL = 100_000.0
private const val MAX_DRAWDOWN_LIMIT = 85_000.0
private const val RISK_PER_TRADE = 1_500.0
private const val MAX_MARGIN = 50_000.0
private const val MAX_SYMBOLS = 3
private const val MAX_LOTS_PER_SYMBOL = 2

enum class Direction {
    LONG,
    SHORT,
    NONE
}

data class SymbolConfig(
    val code: String,
    val multiplier: Double
)

data class MarketInput(
    val symbol: String,
    val closePrice: Double,
    val atr: Double,
    val ma20Slope: Double,
    val high20: Double,
    val low20: Double,
    val gapAtrMultiple: Double,
    val consecutiveStopLosses: Int,
    val accountNetValue: Double = INITIAL_CAPITAL,
    val currentMarginUsed: Double = 0.0,
    val currentSymbolsHeld: Int = 0
)

data class TradeDecision(
    val canTrade: Boolean,
    val direction: Direction,
    val suggestedLots: Int,
    val initialStopByAtr: Double,
    val hardStopLossAmount: Double,
    val message: String
)

object TradingRuleEngine {
    private val allowedSymbols = mapOf(
        "RB" to SymbolConfig("RB", multiplier = 10.0),
        "M" to SymbolConfig("M", multiplier = 10.0),
        "MA" to SymbolConfig("MA", multiplier = 10.0)
    )

    fun evaluateEntry(input: MarketInput): TradeDecision {
        val symbolKey = input.symbol.uppercase()
        val config = allowedSymbols[symbolKey]
            ?: return reject("品种不在固定池（仅允许 RB / M / MA）")

        if (input.accountNetValue <= MAX_DRAWDOWN_LIMIT) {
            return reject("净值 <= 85,000，必须停手复盘")
        }

        if (input.currentMarginUsed > MAX_MARGIN) {
            return reject("保证金占用超限（>50,000）")
        }

        if (input.currentSymbolsHeld >= MAX_SYMBOLS) {
            return reject("已达到最大同时持仓品种数（3个）")
        }

        if (input.consecutiveStopLosses >= 3) {
            return reject("已连续3笔止损：暂停新开仓5个交易日")
        }

        if (input.gapAtrMultiple > 2.0) {
            return reject("当日跳空 > 2ATR：当天禁止入场")
        }

        val atrRatio = input.atr / input.closePrice
        if (input.ma20Slope == 0.0 || atrRatio < 0.01) {
            return reject("趋势过滤不通过（20日均线斜率=0 或 ATR/价格 <1%）")
        }

        val direction = when {
            input.closePrice > input.high20 -> Direction.LONG
            input.closePrice < input.low20 -> Direction.SHORT
            else -> Direction.NONE
        }

        if (direction == Direction.NONE) {
            return reject("未发生20日突破，不入场")
        }

        val riskPerLot = input.atr * config.multiplier
        val maxLotsByRisk = floor(RISK_PER_TRADE / riskPerLot).toInt()

        if (maxLotsByRisk < 1) {
            return reject("按风险预算可开手数 < 1，放弃交易")
        }

        val firstEntryLots = 1.coerceAtMost(MAX_LOTS_PER_SYMBOL)
        val stopPriceDistance = 2 * input.atr
        val hardStopAmountPerLot = 2_000.0

        val directionText = if (direction == Direction.LONG) "做多" else "做空"
        return TradeDecision(
            canTrade = true,
            direction = direction,
            suggestedLots = firstEntryLots,
            initialStopByAtr = stopPriceDistance,
            hardStopLossAmount = hardStopAmountPerLot,
            message = "信号通过：$directionText，首笔1手。止损=反向2ATR，且单手亏损不超2000元。"
        )
    }

    fun holdRulesSummary(): String {
        return "持仓规则：\n" +
            "1) 开仓后10个交易日内，若浮盈<0.8ATR，立即平仓。\n" +
            "2) 趋势止盈：多单跌破10日最低，空单突破10日最高。\n" +
            "3) 浮盈>=1.5ATR可加1手，单品种最多2手，加仓后统一止损。"
    }

    private fun reject(reason: String): TradeDecision {
        return TradeDecision(
            canTrade = false,
            direction = Direction.NONE,
            suggestedLots = 0,
            initialStopByAtr = 0.0,
            hardStopLossAmount = 0.0,
            message = "拒绝开仓：$reason"
        )
    }
}
