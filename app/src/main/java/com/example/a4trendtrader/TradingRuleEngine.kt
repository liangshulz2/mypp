package com.example.a4trendtrader

import kotlin.math.floor

private const val INITIAL_CAPITAL = 100_000.0
private const val MAX_DRAWDOWN_LIMIT = 85_000.0
private const val MAX_MARGIN = 50_000.0
private const val MAX_SYMBOLS = 3

enum class Direction {
    LONG,
    SHORT,
    NONE
}

data class SymbolConfig(
    val code: String,
    val multiplier: Double,
    val atrPeriod: Int,
    val breakoutPeriod: Int,
    val exitPeriod: Int,
    val initialStopAtrMultiplier: Double,
    val riskPerTrade: Double,
    val maxLots: Int,
    val allowAddOn: Boolean,
    val addOnAtrMultiple: Double,
    val maxConsecutiveLosses: Int
)

data class MarketInput(
    val symbol: String,
    val closePrice: Double,
    val atr: Double,
    val ma20Slope: Double,
    val breakoutHigh: Double,
    val breakoutLow: Double,
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
        "RB" to SymbolConfig(
            code = "RB",
            multiplier = 10.0,
            atrPeriod = 20,
            breakoutPeriod = 25,
            exitPeriod = 12,
            initialStopAtrMultiplier = 2.2,
            riskPerTrade = 1_400.0,
            maxLots = 2,
            allowAddOn = true,
            addOnAtrMultiple = 1.5,
            maxConsecutiveLosses = 3
        ),
        "M" to SymbolConfig(
            code = "M",
            multiplier = 10.0,
            atrPeriod = 14,
            breakoutPeriod = 20,
            exitPeriod = 10,
            initialStopAtrMultiplier = 1.8,
            riskPerTrade = 1_200.0,
            maxLots = 2,
            allowAddOn = false,
            addOnAtrMultiple = 0.0,
            maxConsecutiveLosses = 3
        ),
        "MA" to SymbolConfig(
            code = "MA",
            multiplier = 10.0,
            atrPeriod = 10,
            breakoutPeriod = 15,
            exitPeriod = 7,
            initialStopAtrMultiplier = 2.5,
            riskPerTrade = 1_000.0,
            maxLots = 1,
            allowAddOn = false,
            addOnAtrMultiple = 0.0,
            maxConsecutiveLosses = 2
        )
    )

    fun getSymbolConfig(symbol: String): SymbolConfig? {
        return allowedSymbols[symbol.uppercase()]
    }

    fun evaluateEntry(input: MarketInput): TradeDecision {
        val config = getSymbolConfig(input.symbol)
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

        if (input.consecutiveStopLosses > config.maxConsecutiveLosses) {
            return reject("已超过允许的连续止损次数：暂停新开仓")
        }

        if (input.gapAtrMultiple > 2.0) {
            return reject("当日跳空 > 2ATR：当天禁止入场")
        }

        val atrRatio = input.atr / input.closePrice
        if (input.ma20Slope == 0.0 || atrRatio < 0.01) {
            return reject("趋势过滤不通过（20日均线斜率=0 或 ATR/价格 <1%）")
        }

        val direction = when {
            input.closePrice > input.breakoutHigh -> Direction.LONG
            input.closePrice < input.breakoutLow -> Direction.SHORT
            else -> Direction.NONE
        }

        if (direction == Direction.NONE) {
            return reject("未发生20日突破，不入场")
        }

        val stopPriceDistance = config.initialStopAtrMultiplier * input.atr
        val riskPerLot = stopPriceDistance * config.multiplier
        val maxLotsByRisk = floor(config.riskPerTrade / riskPerLot).toInt()

        if (maxLotsByRisk < 1) {
            return reject("按风险预算可开手数 < 1，放弃交易")
        }

        val firstEntryLots = 1.coerceAtMost(config.maxLots)

        val directionText = if (direction == Direction.LONG) "做多" else "做空"
        return TradeDecision(
            canTrade = true,
            direction = direction,
            suggestedLots = firstEntryLots,
            initialStopByAtr = stopPriceDistance,
            hardStopLossAmount = config.riskPerTrade,
            message = "信号通过：$directionText，首笔1手。止损=反向${config.initialStopAtrMultiplier}ATR，" +
                "单笔风险不超${config.riskPerTrade}元。"
        )
    }

    fun holdRulesSummary(symbol: String): String {
        val config = getSymbolConfig(symbol) ?: return "持仓规则：未知品种"
        val addOnRule = if (config.allowAddOn) {
            "3) 浮盈>=${config.addOnAtrMultiple}ATR可加1手，单品种最多${config.maxLots}手，加仓后统一止损。"
        } else {
            "3) 禁止加仓。"
        }
        return "持仓规则：\n" +
            "1) 开仓后10个交易日内，若浮盈<0.8ATR，立即平仓。\n" +
            "2) 趋势止盈：多单跌破${config.exitPeriod}日最低，空单突破${config.exitPeriod}日最高。\n" +
            addOnRule
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
