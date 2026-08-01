package com.seckill.seckill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.common.id.SnowflakeIdGenerator;
import com.seckill.seckill.constant.SeckillConstants;
import com.seckill.seckill.dto.SeckillOrderMessage;
import com.seckill.seckill.entity.SeckillPreDeduct;
import com.seckill.seckill.mapper.SeckillPreDeductMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PreDeductService {

    private final SeckillPreDeductMapper preDeductMapper;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    @Transactional
    public void createInitial(SeckillOrderMessage message) {
        SeckillPreDeduct row = new SeckillPreDeduct();
        row.setId(snowflakeIdGenerator.nextId());
        row.setMessageId(message.getMessageId());
        row.setOrderId(message.getOrderId());
        row.setUserId(message.getUserId());
        row.setSessionId(message.getSessionId());
        row.setSkuId(message.getSkuId());
        row.setQuantity(message.getQuantity());
        row.setTxStatus(SeckillConstants.TX_STATUS_INIT);
        row.setDeductStatus(SeckillConstants.DEDUCT_STATUS_DEDUCTED);
        preDeductMapper.insert(row);
    }

    public void markTxSuccess(String messageId) {
        updateTxStatus(messageId, SeckillConstants.TX_STATUS_SUCCESS);
    }

    public void markTxFail(String messageId) {
        updateTxStatus(messageId, SeckillConstants.TX_STATUS_FAIL);
    }

    public void markRecovered(String messageId) {
        preDeductMapper.update(null, new LambdaUpdateWrapper<SeckillPreDeduct>()
                .eq(SeckillPreDeduct::getMessageId, messageId)
                .eq(SeckillPreDeduct::getDeductStatus, SeckillConstants.DEDUCT_STATUS_DEDUCTED)
                .set(SeckillPreDeduct::getDeductStatus, SeckillConstants.DEDUCT_STATUS_RECOVERED));
    }

    /**
     * order-service 建单后回填确认（幂等：已确认直接成功，已回补拒绝）。
     */
    @Transactional
    public boolean confirm(String messageId, String orderId) {
        SeckillPreDeduct row = preDeductMapper.selectOne(new LambdaQueryWrapper<SeckillPreDeduct>()
                .eq(SeckillPreDeduct::getMessageId, messageId));
        if (row == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "预扣流水不存在");
        }
        if (SeckillConstants.DEDUCT_STATUS_CONFIRMED.equals(row.getDeductStatus())) {
            return true;
        }
        if (!SeckillConstants.DEDUCT_STATUS_DEDUCTED.equals(row.getDeductStatus())) {
            return false;
        }
        preDeductMapper.update(null, new LambdaUpdateWrapper<SeckillPreDeduct>()
                .eq(SeckillPreDeduct::getMessageId, messageId)
                .eq(SeckillPreDeduct::getDeductStatus, SeckillConstants.DEDUCT_STATUS_DEDUCTED)
                .set(SeckillPreDeduct::getOrderId, orderId)
                .set(SeckillPreDeduct::getDeductStatus, SeckillConstants.DEDUCT_STATUS_CONFIRMED));
        return true;
    }

    public String findTxStatus(String messageId) {
        SeckillPreDeduct row = preDeductMapper.selectOne(new LambdaQueryWrapper<SeckillPreDeduct>()
                .eq(SeckillPreDeduct::getMessageId, messageId));
        return row == null ? null : row.getTxStatus();
    }

    public SeckillPreDeduct findLatestByUserSessionSku(Long userId, Long sessionId, Long skuId) {
        return preDeductMapper.selectOne(new LambdaQueryWrapper<SeckillPreDeduct>()
                .eq(SeckillPreDeduct::getUserId, userId)
                .eq(SeckillPreDeduct::getSessionId, sessionId)
                .eq(SeckillPreDeduct::getSkuId, skuId)
                .orderByDesc(SeckillPreDeduct::getId)
                .last("LIMIT 1"));
    }

    private void updateTxStatus(String messageId, String targetStatus) {
        preDeductMapper.update(null, new LambdaUpdateWrapper<SeckillPreDeduct>()
                .eq(SeckillPreDeduct::getMessageId, messageId)
                .eq(SeckillPreDeduct::getTxStatus, SeckillConstants.TX_STATUS_INIT)
                .set(SeckillPreDeduct::getTxStatus, targetStatus));
    }
}
