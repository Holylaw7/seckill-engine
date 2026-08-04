-- seckill_deduct_v2.lua v1.0（Phase 6.2 分桶准入，冻结语义扩展）
-- KEYS[1]   = seckill:stock:{skuId}（全局库存）
-- KEYS[2]   = seckill:user:{skuId}:{userId}（防重标记）
-- KEYS[3]   = seckill:stock:rr:{skuId}（轮询计数器）
-- KEYS[4..] = seckill:stock:bucket:{skuId}:{bucketNo}（分桶库存）
-- ARGV[1]   = quantity
-- ARGV[2]   = userKeyTtlSeconds
-- ARGV[3]   = bucketCount
-- ARGV[4]   = counterTtlSeconds
-- return: SUCCESS:{bucketNo} / STOCK_EMPTY / REPEAT_BUY / NOT_READY

local userMarked = redis.call('EXISTS', KEYS[2])
if userMarked == 1 then
    return 'REPEAT_BUY'
end

local stock = redis.call('GET', KEYS[1])
if not stock then
    return 'NOT_READY'
end

if tonumber(stock) < tonumber(ARGV[1]) then
    return 'STOCK_EMPTY'
end

local bucketCount = tonumber(ARGV[3])
if bucketCount < 1 then
    bucketCount = 1
end

local counter = redis.call('INCR', KEYS[3])
redis.call('EXPIRE', KEYS[3], ARGV[4])
local startIdx = counter % bucketCount

for offset = 0, bucketCount - 1 do
    local idx = (startIdx + offset) % bucketCount
    local bucketStock = redis.call('GET', KEYS[4 + idx])
    if bucketStock and tonumber(bucketStock) >= tonumber(ARGV[1]) then
        redis.call('DECRBY', KEYS[1], ARGV[1])
        redis.call('DECRBY', KEYS[4 + idx], ARGV[1])
        redis.call('SET', KEYS[2], '1', 'EX', ARGV[2])
        return 'SUCCESS:' .. idx
    end
end

return 'STOCK_EMPTY'
