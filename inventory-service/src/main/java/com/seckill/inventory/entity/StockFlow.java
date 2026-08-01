package com.seckill.inventory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("stock_flow")
public class StockFlow {

    @TableId(type = IdType.INPUT)
    private Long id;

    /** 唯一流水号（= Redis 回补接口 requestId） */
    private String flowNo;

    private Long skuId;

    /** INIT / DEDUCT / RECOVER / REPAIR / ADJUST / CONFIRM */
    private String changeType;

    /** 扣减为负，回补为正 */
    private Integer changeQty;

    private Integer beforeQty;

    private Integer afterQty;

    /** ORDER / CANCEL / TIMEOUT / REFUND / MANUAL */
    private String bizType;

    private String bizId;

    private Long operatorId;

    private String remark;

    private LocalDateTime createdAt;
}
