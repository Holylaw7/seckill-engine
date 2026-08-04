package com.seckill.inventory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存事实分桶（Phase 6.2）：单 SKU 拆 N 桶，行锁按桶并行。
 */
@Data
@TableName("inventory_bucket")
public class InventoryBucket {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long skuId;

    private Integer bucketNo;

    private Integer totalStock;

    private Integer lockedStock;

    private Integer availableStock;

    /** 乐观锁版本（CAS 更新） */
    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
