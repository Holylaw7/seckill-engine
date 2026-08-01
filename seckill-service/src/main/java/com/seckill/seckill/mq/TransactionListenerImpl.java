package com.seckill.seckill.mq;

import com.seckill.common.util.JsonUtils;
import com.seckill.seckill.constant.SeckillConstants;
import com.seckill.seckill.dto.SeckillOrderMessage;
import com.seckill.seckill.redis.StockService;
import com.seckill.seckill.service.PreDeductService;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

/**
 * 事务消息监听：本地事务（pre_deduct INIT→SUCCESS/FAIL）与回查。
 */
@Component
@RequiredArgsConstructor
public class TransactionListenerImpl implements RocketMQLocalTransactionListener {

    private final PreDeductService preDeductService;
    private final StockService stockService;

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        SeckillOrderMessage message = parse(msg);
        try {
            preDeductService.createInitial(message);
            preDeductService.markTxSuccess(message.getMessageId());
            return RocketMQLocalTransactionState.COMMIT;
        } catch (DuplicateKeyException e) {
            String status = preDeductService.findTxStatus(message.getMessageId());
            if (SeckillConstants.TX_STATUS_FAIL.equals(status)) {
                return RocketMQLocalTransactionState.ROLLBACK;
            }
            return RocketMQLocalTransactionState.COMMIT;
        } catch (Exception e) {
            try {
                preDeductService.markTxFail(message.getMessageId());
                preDeductService.markRecovered(message.getMessageId());
            } catch (Exception ignored) {
                // 补偿动作失败由对账兜底
            }
            stockService.recover(String.valueOf(message.getSkuId()),
                    String.valueOf(message.getUserId()), message.getQuantity(), true);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        SeckillOrderMessage message = parse(msg);
        String status = preDeductService.findTxStatus(message.getMessageId());
        if (SeckillConstants.TX_STATUS_SUCCESS.equals(status)) {
            return RocketMQLocalTransactionState.COMMIT;
        }
        if (SeckillConstants.TX_STATUS_FAIL.equals(status)) {
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        return RocketMQLocalTransactionState.UNKNOWN;
    }

    private static SeckillOrderMessage parse(Message<?> msg) {
        Object payload = msg.getPayload();
        String json = payload instanceof String s ? s : JsonUtils.toJson(payload);
        return JsonUtils.fromJson(json, SeckillOrderMessage.class);
    }
}
