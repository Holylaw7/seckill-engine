package com.seckill.seckill.redis;

/**
 * Lua 预扣返回码映射（冻结：1/-1/-2/-3）。
 */
public enum StockDeductResult {
    SUCCESS, STOCK_EMPTY, REPEAT_BUY, NOT_READY
}
