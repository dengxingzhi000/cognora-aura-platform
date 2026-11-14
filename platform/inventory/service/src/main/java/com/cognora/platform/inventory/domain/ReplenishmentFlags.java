package com.cognora.platform.inventory.domain;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 补货建议明细上的人审提示位。
 *
 * <p>提示位不是错误码：它们表示这一行「可以补，但请人看一眼」。设计稿 §8 要求
 * 这些场景都不能被静默丢弃——要么给可行量加原因，要么标出来交给人决定。
 *
 * <p>存库形态是逗号分隔的短字符串（{@code inv_replenishment_suggestion_item.flags}），
 * 便于列表页直接用 LIKE 过滤；本类负责与 Set 互转，避免各调用方各写一份拼接逻辑。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
public enum ReplenishmentFlags {

  /** 冷启动/样本不足，需人工确认预测。 */
  NEEDS_MANUAL,
  /** 策略缺少交期，无法算 ROP。 */
  NO_LEAD_TIME,
  /** 没有可用供应商关系，不可自动下单。 */
  NO_SUPPLIER,
  /** SKU→material 映射缺失，转采购计划会失败。 */
  MISSING_MATERIAL_MAPPING,
  /** 补货量被 MOQ/包装倍数向上取整。 */
  ROUNDED_TO_MOQ,
  /** 补货量被仓容（maxStock）裁剪。 */
  CAPPED_BY_CAPACITY,
  /** 补货量被预算裁剪。 */
  CAPPED_BY_BUDGET,
  /** 在途量已覆盖缺口，本周期无需补货。 */
  COVERED_BY_IN_TRANSIT;

  /**
   * 序列化为逗号分隔字符串；空集合返回 {@code null}（库里该列为空而不是空串）。
   */
  public static String join(Set<ReplenishmentFlags> flags) {
    if (flags == null || flags.isEmpty()) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    for (ReplenishmentFlags flag : flags) {
      if (sb.length() > 0) {
        sb.append(',');
      }
      sb.append(flag.name());
    }
    return sb.toString();
  }

  /**
   * 解析逗号分隔字符串；未知标记被忽略（向前兼容新增标记）。
   */
  public static Set<ReplenishmentFlags> parse(String value) {
    Set<ReplenishmentFlags> result = new LinkedHashSet<>();
    if (value == null || value.isBlank()) {
      return result;
    }
    for (String part : value.split(",")) {
      String trimmed = part.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      for (ReplenishmentFlags flag : values()) {
        if (flag.name().equals(trimmed)) {
          result.add(flag);
          break;
        }
      }
    }
    return result;
  }
}
