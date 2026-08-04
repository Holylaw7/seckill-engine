package com.seckill.common.result;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

@org.junit.jupiter.api.Tag("unit")
class PageResultTest {

    @Test
    void ofShouldBuildPageResult() {
        PageResult<String> page = PageResult.of(List.of("a", "b"), 100L, 1, 10);
        assertEquals(2, page.getList().size());
        assertEquals(100L, page.getTotal());
        assertEquals(1, page.getPageNum());
        assertEquals(10, page.getPageSize());
    }

    @Test
    void should_contain_all_frozen_fields_when_serialized() throws Exception {
        // Arrange
        ObjectMapper objectMapper = new ObjectMapper();
        PageResult<String> page = PageResult.of(List.of("a", "b"), 100L, 1, 10);

        // Act
        String json = objectMapper.writeValueAsString(page);
        var tree = objectMapper.readTree(json);

        // Assert
        assertThat(tree.size()).isEqualTo(4);
        assertThat(tree.get("list").size()).isEqualTo(2);
        assertThat(tree.get("total").asLong()).isEqualTo(100L);
        assertThat(tree.get("pageNum").asInt()).isEqualTo(1);
        assertThat(tree.get("pageSize").asInt()).isEqualTo(10);
    }

    @Test
    void should_deserialize_generic_type_when_using_type_reference() throws Exception {
        // Arrange
        ObjectMapper objectMapper = new ObjectMapper();
        PageResult<Item> page = PageResult.of(List.of(new Item(1L, "sku-1")), 1L, 1, 10);

        // Act
        String json = objectMapper.writeValueAsString(page);
        PageResult<Item> restored = objectMapper.readValue(json, new TypeReference<PageResult<Item>>() {
        });

        // Assert
        assertThat(restored.getList()).hasSize(1);
        assertThat(restored.getList().get(0).id()).isEqualTo(1L);
        assertThat(restored.getList().get(0).skuId()).isEqualTo("sku-1");
        assertThat(restored.getTotal()).isEqualTo(1L);
        assertThat(restored.getPageNum()).isEqualTo(1);
        assertThat(restored.getPageSize()).isEqualTo(10);
    }

    private record Item(Long id, String skuId) {
    }
}
