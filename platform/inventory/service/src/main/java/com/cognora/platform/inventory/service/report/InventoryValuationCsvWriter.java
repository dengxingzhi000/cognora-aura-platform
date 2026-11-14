package com.cognora.platform.inventory.service.report;

import com.cognora.platform.inventory.dto.InventoryValuationRow;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 库存估值 CSV 导出。
 *
 * <p>安全约束（导出文件常被直接拖进 Excel，属于经典的注入面）：
 * <ul>
 *   <li>仅当字段以 {@code = + - @ \t \r} 开头时加单引号前缀，Excel 才不会把它
 *       当成公式执行；这是 OWASP CSV Injection 的推荐处置。</li>
 *   <li>值内的双引号按 CSV 规则翻倍转义，逗号/换行用引号包裹。</li>
 *   <li>以 UTF-8 BOM 开头，避免 Excel 打开中文列名乱码。</li>
 * </ul>
 *
 * <p>纯函数实现，不依赖 Servlet，便于单元测试。
 *
 * @author SCM Platform Team
 * @since 2026-11-07
 */
public final class InventoryValuationCsvWriter {

  private static final String BOM = "\uFEFF";

  private static final String[] HEADER = {
      "SKU", "仓库", "库位", "总库存", "可用", "锁定", "损坏", "安全库存",
      "平均成本", "库存金额", "呆滞天数", "库存状态"
  };

  private InventoryValuationCsvWriter() {
  }

  /**
   * 生成 CSV 正文（含 BOM），行数据由调用方按筛选条件一次性取出。
   */
  public static String write(List<InventoryValuationRow> rows, LocalDateTime exportedAt) {
    StringBuilder builder = new StringBuilder(BOM);
    appendLine(builder, join(HEADER));
    appendLine(builder, join(new String[]{"# 导出时间",
        exportedAt == null ? "" : exportedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}));
    appendLine(builder, join(new String[]{"# 记录数", String.valueOf(rows.size())}));

    for (InventoryValuationRow row : rows) {
      appendLine(builder, join(new String[]{
          safe(row.getSkuId()),
          safe(row.getWarehouseId()),
          safe(row.getLocationCode()),
          number(row.getTotalStock()),
          number(row.getAvailableStock()),
          number(row.getLockedStock()),
          number(row.getDamagedStock()),
          number(row.getSafetyStock()),
          decimal(row.getAverageCost()),
          decimal(row.getStockValue()),
          row.getAgingDays() == null ? "" : String.valueOf(row.getAgingDays()),
          safe(row.getStockStatus())
      }));
    }
    return builder.toString();
  }

  /** 以 UTF-8 字节输出，便于直接写入响应流。 */
  public static byte[] writeBytes(List<InventoryValuationRow> rows, LocalDateTime exportedAt) {
    return write(rows, exportedAt).getBytes(StandardCharsets.UTF_8);
  }

  private static void appendLine(StringBuilder builder, String line) {
    builder.append(line).append("\r\n");
  }

  private static String join(String[] values) {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < values.length; i++) {
      if (i > 0) {
        builder.append(',');
      }
      builder.append(quote(values[i]));
    }
    return builder.toString();
  }

  /**
   * CSV 注入防护 + 常规引号转义。
   */
  private static String quote(String value) {
    if (value == null) {
      return "";
    }
    String guarded = needsFormulaGuard(value) ? "'" + value : value;
    if (guarded.indexOf('"') >= 0 || guarded.indexOf(',') >= 0
        || guarded.indexOf('\r') >= 0 || guarded.indexOf('\n') >= 0) {
      return '"' + guarded.replace("\"", "\"\"") + '"';
    }
    return guarded;
  }

  private static boolean needsFormulaGuard(String value) {
    if (value.isEmpty()) {
      return false;
    }
    char first = value.charAt(0);
    return first == '=' || first == '+' || first == '-' || first == '@'
        || first == '\t' || first == '\r';
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }

  private static String number(Integer value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static String decimal(BigDecimal value) {
    return value == null ? "" : value.toPlainString();
  }
}
