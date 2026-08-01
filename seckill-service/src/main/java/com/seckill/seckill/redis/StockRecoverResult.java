package com.seckill.seckill.redis;

/**
 * Lua 回补返回码映射（冻结：1/-3/-4）。
 */
public enum StockRecoverResult {
    SUCCESS, NOT_READY, OVER_TOTAL
}
