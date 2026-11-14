package com.cognora.scm.purchase.service;

import com.cognora.scm.purchase.domain.dto.PurchaseInTransitRow;
import com.cognora.scm.purchase.mapper.PurOrderItemMapper;
import com.cognora.scm.purchase.mapper.PurOrderMapper;
import com.cognora.scm.purchase.service.query.PurOrderQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采购在途查询用例：确认委托到正确的聚合查询、透传仓库过滤。
 */
class PurOrderQueryServiceInTransitTest {

  private final PurOrderMapper orderMapper = mock(PurOrderMapper.class);
  private final PurOrderItemMapper itemMapper = mock(PurOrderItemMapper.class);
  private final PurOrderQueryService service =
      new PurOrderQueryService(orderMapper, itemMapper);

  @Test
  @DisplayName("按仓库过滤时透传 warehouseId")
  void passesWarehouseFilter() {
    PurchaseInTransitRow row = new PurchaseInTransitRow();
    row.setSkuId("sku-1");
    row.setWarehouseId("wh-1");
    row.setInTransitQty(30);
    when(itemMapper.sumUnreceivedBySkuAndWarehouse("wh-1")).thenReturn(List.of(row));

    List<PurchaseInTransitRow> result = service.sumUnreceivedInTransit("wh-1");

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getInTransitQty()).isEqualTo(30);
    verify(itemMapper).sumUnreceivedBySkuAndWarehouse("wh-1");
  }

  @Test
  @DisplayName("不传仓库时返回全部仓库的在途")
  void withoutWarehouseFilter() {
    when(itemMapper.sumUnreceivedBySkuAndWarehouse(isNull())).thenReturn(List.of());

    assertThat(service.sumUnreceivedInTransit(null)).isEmpty();
    verify(itemMapper).sumUnreceivedBySkuAndWarehouse(null);
  }
}
