-- seckill_recover.lua v1.0（冻结：seckill-service 设计 B.1）
-- KEYS[1] = seckill:stock:{skuId}
-- KEYS[2] = seckill:stock:total:{skuId}
-- KEYS[3] = seckill:user:{skuId}:{userId}
-- ARGV[1] = quantity
-- ARGV[2] = removeUserMark ('1'/'0')
-- return: 1=SUCCESS -3=NOT_READY -4=OVER_TOTAL

local stock = redis.call('GET', KEYS[1])
if not stock then
    return -3
end

local totalStr = redis.call('GET', KEYS[2])
local total = tonumber(totalStr or '0')
if tonumber(stock) + tonumber(ARGV[1]) > total then
    return -4
end

redis.call('INCRBY', KEYS[1], ARGV[1])
if ARGV[2] == '1' then
    redis.call('DEL', KEYS[3])
end
return 1
