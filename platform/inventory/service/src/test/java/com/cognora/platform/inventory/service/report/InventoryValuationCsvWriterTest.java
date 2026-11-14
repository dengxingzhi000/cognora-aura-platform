package com.cognora.platform.inventory.service.report;

import com.cognora.platform.inventory.dto.InventoryValuationRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CSV 导出用例：表头、BOM、数值格式与公式注入防护。
 */
class InventoryValuationCsvWriterTest {

  @Test
  @DisplayName("输出包含 UTF-8 BOM、表头与记录数")
  void writesHeaderAndBom() {
    String csv = InventoryValuationCsvWriter.write(
        List.of(row("SKU-1", "WH-1", "A-01", 10, new BigDecimal("2.50")),
            row("SKU-2", "WH-2", null, 0, BigDecimal.ZERO)),
        LocalDateTime.of(2026, 11, 7, 18, 0));

    byte[] bytes = InventoryValuationCsvWriter.writeBytes(
        List.of(row("SKU-1", "WH-1", "A-01", 10, new BigDecimal("2.50")),
            row("SKU-2", "WH-2", null, 0, BigDecimal.ZERO)),
        LocalDateTime.of(2026, 11, 7, 18, 0));

    assertTrue(csv.startsWith("\uFEFF"), "CSV 必须以 BOM 开头，Excel 打开中文才不乱码");
    assertTrue(csv.contains("SKU,仓库,库位"), csv);
    assertTrue(csv.contains("# 记录数"), csv);
    assertEquals(StandardCharsets.UTF_8, StandardCharsets.UTF_8);
    assertEquals(csv.getBytes(StandardCharsets.UTF_8).length, bytes.length);
  }

  @Test
  @DisplayName("金额以 plainString 输出，不使用科学计数法")
  void writesDecimalPlain() {
    String csv = InventoryValuationCsvWriter.write(
        List.of(row("SKU-1", "WH-1", "A-01", 3, new BigDecimal("1E+3")),
            row("SKU-2", "WH-2", "B-01", 1, new BigDecimal("0.01"))),
        LocalDateTime.of(2026, 11, 7, 18, 0));

    assertTrue(csv.contains("1000"), csv);
    assertTrue(csv.contains("0.01"), csv);
    assertTrue(!csv.contains("1E+3"), "不应出现科学计数法: " + csv);
  }

  @Test
  @DisplayName("以 = + - @ 开头的字段被加前缀，防止 Excel 公式注入")
  void guardsAgainstCsvInjection() {
    InventoryValuationRow injected = row("=HYPERLINK(\"http://evil\")", "@SUM(1)", "-1", 1,
        BigDecimal.ONE);

    String csv = InventoryValuationCsvWriter.write(List.of(injected),
        LocalDateTime.of(2026, 11, 7, 18, 0));

    // 公式前缀被单引号转义，且内部双引号按 CSV 规则翻倍
    assertTrue(csv.contains("'=HYPERLINK"), "公式前缀必须被转义: " + csv);
    assertTrue(csv.contains("'@SUM"), "公式前缀必须被转义: " + csv);
    assertTrue(csv.contains("\"\"http://evil\"\""), "内部引号必须翻倍: " + csv);
  }

  @Test
  @DisplayName("逗号与换行被引号包裹")
  void quotesSeparators() {
    InventoryValuationRow row = row("SKU-1", "WH,2", "A\n01", 1, BigDecimal.ONE);
    String csv = InventoryValuationCsvWriter.write(List.of(row),
        LocalDateTime.of(2026, 11, 7, 18, 0));
    assertTrue(csv.contains("\"WH,2\""), csv);
    assertTrue(csv.contains("\"A\n01\""), csv);
  }

  @Test
  @DisplayName("空值输出为空串而不是字面量 null")
  void handlesNulls() {
    String csv = InventoryValuationCsvWriter.write(
        List.of(row(null, null, null, null, null)), LocalDateTime.now());
    assertTrue(!csv.contains("null"), csv);
  }

  private static InventoryValuationRow row(String skuId, String warehouseId, String location,
                                           Integer totalStock, BigDecimal averageCost) {
    InventoryValuationRow row = new InventoryValuationRow();
    row.setSkuId(skuId);
    row.setWarehouseId(warehouseId);
    row.setLocationCode(location);
    row.setTotalStock(totalStock);
    row.setAvailableStock(totalStock);
    row.setLockedStock(0);
    row.setDamagedStock(0);
    row.setSafetyStock(10);
    row.setAverageCost(averageCost);
    row.setStockValue(averageCost == null || totalStock == null ? null
        : averageCost.multiply(BigDecimal.valueOf(totalStock)));
    row.setStockStatus(totalStock == null || totalStock == 0 ? "OUT_OF_STOCK" : "NORMAL");
    return row;
  }
}
