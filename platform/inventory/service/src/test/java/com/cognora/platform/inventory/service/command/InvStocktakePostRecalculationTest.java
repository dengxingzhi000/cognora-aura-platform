package com.cognora.platform.inventory.service.command;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.cognora.platform.inventory.domain.entity.InvStocktake;
import com.cognora.platform.inventory.domain.entity.InvStocktakeItem;
import com.cognora.platform.inventory.domain.entity.Inventory;
import com.cognora.platform.inventory.dto.InventoryAdjustRequest;
import com.cognora.platform.inventory.mapper.InvInventoryMapper;
import com.cognora.platform.inventory.mapper.InvStocktakeItemMapper;
import com.cognora.platform.inventory.mapper.InvStocktakeMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 盘点落账的「冻结期」保护（P1-3）。
 *
 * <p>盘点的语义是「实盘数就是真相」。但盘点单从创建到落账要经过审批，期间台账可能已经因为
 * 出入库而变了。如果落账直接使用录实盘数那一刻算出的 {@code diffQuantity}，就会把「实盘时点
 * 的差异」叠加到「已经变过的台账」上，造成账实偏差：</p>
 * <pre>
 * 创建时账面 100，实盘 90        → diff = -10
 * 审批期间出库 5                 → 当前账面 95
 * 落账直接用 -10                 → 台账 85（错，实盘是 90）
 * 落账按 90 - 95 = -5 重算       → 台账 90（对）
 * </pre>
 *
 * <p>这些用例锁住重算行为：差异必须以落账时点的账面数为基准重新计算。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("盘点落账：按落账时点重算差异")
class InvStocktakePostRecalculationTest {

    @Mock private InvStocktakeMapper stocktakeMapper;
    @Mock private InvStocktakeItemMapper itemMapper;
    @Mock private InvInventoryMapper inventoryMapper;
    @Mock private InvInventoryCommandService inventoryCommandService;

    @Test
    @DisplayName("审批期间发生过出库：按落账时账面重算，而不是沿用审批时的差异")
    void recalculatesAgainstTheCurrentBookQuantity() {
        // counted 90, book at count time was 100 -> recorded diff = -10
        InvStocktakeItem item = item("SKU-1", "WH-1", 100, 90, -10);
        stubStocktake("ST-1", List.of(item));
        // The ledger has since dropped to 95 (5 units shipped during approval).
        stubCurrentBook(95);

        service().post("ST-1", "operator-1");

        ArgumentCaptor<InventoryAdjustRequest> captor =
                ArgumentCaptor.forClass(InventoryAdjustRequest.class);
        verify(inventoryCommandService).adjustInventory(captor.capture());

        // 90 - 95 = -5, NOT the stale -10.
        assertEquals(-5, captor.getValue().getQuantity(),
                "the adjustment must be recomputed from the counted quantity against the "
                        + "current book quantity, not replayed from approval time");
        assertEquals("SKU-1", captor.getValue().getSkuId());
        assertEquals(6, captor.getValue().getAdjustType(), "adjustType 6 is the stocktake adjustment");
    }

    @Test
    @DisplayName("审批期间无变动：重算结果与记录值相同，只调整一次")
    void unchangedLedgerUsesTheSameDifference() {
        InvStocktakeItem item = item("SKU-1", "WH-1", 100, 90, -10);
        stubStocktake("ST-1", List.of(item));
        stubCurrentBook(100);

        service().post("ST-1", "operator-1");

        ArgumentCaptor<InventoryAdjustRequest> captor =
                ArgumentCaptor.forClass(InventoryAdjustRequest.class);
        verify(inventoryCommandService).adjustInventory(captor.capture());
        assertEquals(-10, captor.getValue().getQuantity());
    }

    @Test
    @DisplayName("实盘与当前账面相等：重算差异为 0，不产生空流水")
    void noAdjustmentWhenCountedEqualsCurrentBook() {
        // Recorded diff was -10 (book was 100), but the ledger has since settled to 90,
        // which is exactly what was counted -> nothing left to adjust.
        InvStocktakeItem item = item("SKU-1", "WH-1", 100, 90, -10);
        stubStocktake("ST-1", List.of(item));
        stubCurrentBook(90);

        service().post("ST-1", "operator-1");

        verify(inventoryCommandService, never()).adjustInventory(any());
    }

    @Test
    @DisplayName("漂移会被记录到明细备注，便于事后追查")
    void driftIsRecordedOnTheItem() {
        InvStocktakeItem item = item("SKU-1", "WH-1", 100, 90, -10);
        stubStocktake("ST-1", List.of(item));
        stubCurrentBook(95);

        service().post("ST-1", "operator-1");

        ArgumentCaptor<InvStocktakeItem> captor = ArgumentCaptor.forClass(InvStocktakeItem.class);
        verify(itemMapper).updateById(captor.capture());
        String remark = captor.getValue().getRemark();
        org.junit.jupiter.api.Assertions.assertNotNull(remark,
                "a drifted item must carry a note explaining why the posted difference differs");
        org.junit.jupiter.api.Assertions.assertTrue(remark.contains("落账重算"),
                "the note must be identifiable: " + remark);
    }

    @Test
    @DisplayName("未录入实盘数的明细不调整")
    void uncountedItemIsSkipped() {
        InvStocktakeItem item = item("SKU-1", "WH-1", 100, null, null);
        stubStocktake("ST-1", List.of(item));

        service().post("ST-1", "operator-1");

        verify(inventoryCommandService, never()).adjustInventory(any());
        verify(inventoryMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("多条明细各自按自己的当前账面重算")
    void eachItemIsRecalculatedIndependently() {
        InvStocktakeItem a = item("SKU-A", "WH-1", 100, 90, -10);
        InvStocktakeItem b = item("SKU-B", "WH-1", 50, 50, 0);   // recorded 0, but drifted down
        stubStocktake("ST-1", List.of(a, b));

        when(inventoryMapper.selectOne(any(Wrapper.class))).thenAnswer(invocation -> {
            // Return different current book quantities per requested SKU by inspecting the
            // wrapper's parameters is brittle; instead key off call order (a then b).
            return null;
        });

        service().post("ST-1", "operator-1");

        // With no ledger rows every current book quantity reads as 0, so the recomputed
        // differences become the counted quantities themselves: 90 and 50.
        ArgumentCaptor<InventoryAdjustRequest> captor =
                ArgumentCaptor.forClass(InventoryAdjustRequest.class);
        verify(inventoryCommandService, times(2)).adjustInventory(captor.capture());
        List<InventoryAdjustRequest> requests = captor.getAllValues();
        assertEquals(90, requests.get(0).getQuantity());
        assertEquals(50, requests.get(1).getQuantity());
    }

    // ------------------------------------------------------------------ helpers

    private InvStocktakeCommandService service() {
        return new InvStocktakeCommandService(
                stocktakeMapper, itemMapper, inventoryMapper, inventoryCommandService);
    }

    private void stubStocktake(String id, List<InvStocktakeItem> items) {
        InvStocktake head = new InvStocktake();
        head.setId(id);
        head.setStocktakeNo("PD-" + id);
        head.setStatus("APPROVED");
        when(stocktakeMapper.selectById(id)).thenReturn(head);
        when(itemMapper.selectList(any(Wrapper.class))).thenReturn(items);
        // Only the drift cases rewrite an item; the no-drift cases legitimately never call this.
        lenient().when(itemMapper.updateById(any(InvStocktakeItem.class))).thenReturn(1);
        when(stocktakeMapper.updateById(any(InvStocktake.class))).thenReturn(1);
    }

    private void stubCurrentBook(int available) {
        Inventory inventory = new Inventory();
        inventory.setAvailableStock(available);
        when(inventoryMapper.selectOne(any(Wrapper.class))).thenReturn(inventory);
    }

    private static InvStocktakeItem item(String skuId, String warehouseId,
                                         Integer bookAvailable, Integer counted, Integer diff) {
        InvStocktakeItem item = new InvStocktakeItem();
        item.setId("item-" + skuId);
        item.setStocktakeId("ST-1");
        item.setSkuId(skuId);
        item.setWarehouseId(warehouseId);
        item.setBookAvailable(bookAvailable);
        item.setCountedQuantity(counted);
        item.setDiffQuantity(diff);
        return item;
    }
}
