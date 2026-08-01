-- seckill_deduct.lua v1.0（冻结：seckill-service 设计 B.1）
-- KEYS[1] = seckill:stock:{skuId}
-- KEYS[2] = seckill:user:{skuId}:{userId}
-- ARGV[1] = quantity
-- ARGV[2] = userKeyTtlSeconds
-- return: 1=SUCCESS -1=STOCK_EMPTY -2=REPEAT_BUY -3=NOT_READY

local userMarked = redis.call('EXISTS', KEYS[2])
if userMarked == 1 then
    return -2
end

local stock = redis.call('GET', KEYS[1])
if not stock then
    return -3
end

if tonumber(stock) < tonumber(ARGV[1]) then
    return -1
end

redis.call('DECRBY', KEYS[1], ARGV[1])
redis.call('SET', KEYS[2], '1', 'EX', ARGV[2])
return 1
