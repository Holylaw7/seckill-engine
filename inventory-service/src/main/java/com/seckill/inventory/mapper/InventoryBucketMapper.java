package com.seckill.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.seckill.inventory.entity.InventoryBucket;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 库存分桶 Mapper：分桶行锁查询与 SKU 聚合。
 */
public interface InventoryBucketMapper extends BaseMapper<InventoryBucket> {

    /** 按桶加行锁（DEDUCT/RECOVER 唯一串行点） */
    @Select("SELECT * FROM inventory_bucket "
            + "WHERE sku_id = #{skuId} AND bucket_no = #{bucketNo} FOR UPDATE")
    InventoryBucket selectBySkuAndBucketForUpdate(@Param("skuId") Long skuId,
                                                  @Param("bucketNo") Integer bucketNo);

    /** 按 SKU 取全部分桶（对账/迁移） */
    @Select("SELECT * FROM inventory_bucket WHERE sku_id = #{skuId} ORDER BY bucket_no")
    List<InventoryBucket> selectBySku(@Param("skuId") Long skuId);
}
