package com.seckill.inventory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.seckill.common.id.SnowflakeIdGenerator;
import com.seckill.inventory.entity.StockFlow;
import com.seckill.inventory.mapper.StockFlowMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StockFlowService {

    private final StockFlowMapper stockFlowMapper;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    public StockFlow findByBiz(String bizType, String bizId) {
        return stockFlowMapper.selectOne(new LambdaQueryWrapper<StockFlow>()
                .eq(StockFlow::getBizType, bizType)
                .eq(StockFlow::getBizId, bizId));
    }

    public boolean existsByBiz(String bizType, String bizId) {
        return findByBiz(bizType, bizId) != null;
    }

    /**
     * 创建流水（uk_biz 幂等：重复返回既有 flowNo）。
     */
    public String createFlow(String changeType, String bizType, String bizId, Long skuId,
                             int changeQty, int beforeQty, int afterQty, Long operatorId, String remark) {
        return createFlow(changeType, bizType, bizId, skuId, changeQty, beforeQty, afterQty,
                operatorId, remark, null);
    }

    /**
     * 创建流水（uk_biz 幂等：重复返回既有 flowNo；bucketNo 仅定位）。
     */
    public String createFlow(String changeType, String bizType, String bizId, Long skuId,
                             int changeQty, int beforeQty, int afterQty, Long operatorId,
                             String remark, Integer bucketNo) {
        StockFlow existing = findByBiz(bizType, bizId);
        if (existing != null) {
            return existing.getFlowNo();
        }
        long id = snowflakeIdGenerator.nextId();
        StockFlow flow = new StockFlow();
        flow.setId(id);
        flow.setFlowNo("SF" + id);
        flow.setSkuId(skuId);
        flow.setChangeType(changeType);
        flow.setChangeQty(changeQty);
        flow.setBeforeQty(beforeQty);
        flow.setAfterQty(afterQty);
        flow.setBizType(bizType);
        flow.setBizId(bizId);
        flow.setOperatorId(operatorId);
        flow.setRemark(remark);
        flow.setBucketNo(bucketNo);
        try {
            stockFlowMapper.insert(flow);
        } catch (DuplicateKeyException e) {
            StockFlow duplicate = findByBiz(bizType, bizId);
            if (duplicate != null) {
                return duplicate.getFlowNo();
            }
            throw e;
        }
        return flow.getFlowNo();
    }
}
