-- seckill_recover_v2.lua v1.0（Phase 6.2 分桶回补）
-- KEYS[1] = seckill:stock:{skuId}（全局库存）
-- KEYS[2] = seckill:stock:total:{skuId}（全局总库存）
-- KEYS[3] = seckill:stock:bucket:{skuId}:{bucketNo}（分桶库存，可能缺失）
-- KEYS[4] = seckill:user:{skuId}:{userId}（防重标记）
-- ARGV[1] = quantity
-- ARGV[2] = removeUserMark ('1'/'0')
-- return: SUCCESS / NOT_READY / OVER_TOTAL

local stock = redis.call('GET', KEYS[1])
if not stock then
    return 'NOT_READY'
end

local totalStr = redis.call('GET', KEYS[2])
local total = tonumber(totalStr or '0')
if tonumber(stock) + tonumber(ARGV[1]) > total then
    return 'OVER_TOTAL'
end

redis.call('INCRBY', KEYS[1], ARGV[1])

local bucketStock = redis.call('GET', KEYS[3])
if bucketStock then
    redis.call('INCRBY', KEYS[3], ARGV[1])
end

if ARGV[2] == '1' then
    redis.call('DEL', KEYS[4])
end

return 'SUCCESS'
