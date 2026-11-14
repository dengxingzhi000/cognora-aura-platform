package com.cognora.platform.inventory.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 补货建议单状态机用例：全流转矩阵、终态不可再转、只有草稿可改量。
 */
class ReplenishmentStatusTest {

  @Test
  @DisplayName("DRAFT 可采纳/驳回/取消，不能跳到已转换")
  void draftTransitions() {
    assertTrue(ReplenishmentStatus.DRAFT.canTransitionTo(ReplenishmentStatus.ADOPTED));
    assertTrue(ReplenishmentStatus.DRAFT.canTransitionTo(ReplenishmentStatus.REJECTED));
    assertTrue(ReplenishmentStatus.DRAFT.canTransitionTo(ReplenishmentStatus.CANCELLED));
    // 不能跳过人审直接转采购计划
    assertFalse(ReplenishmentStatus.DRAFT.canTransitionTo(ReplenishmentStatus.CONVERTED));
  }

  @Test
  @DisplayName("ADOPTED 可转采购计划或取消，不能回退到 DRAFT")
  void adoptedTransitions() {
    assertTrue(ReplenishmentStatus.ADOPTED.canTransitionTo(ReplenishmentStatus.CONVERTED));
    assertTrue(ReplenishmentStatus.ADOPTED.canTransitionTo(ReplenishmentStatus.CANCELLED));
    assertFalse(ReplenishmentStatus.ADOPTED.canTransitionTo(ReplenishmentStatus.DRAFT));
    assertFalse(ReplenishmentStatus.ADOPTED.canTransitionTo(ReplenishmentStatus.REJECTED));
  }

  @Test
  @DisplayName("终态不可再流转，且拒绝 null")
  void terminalStatesAreFinal() {
    for (ReplenishmentStatus terminal : new ReplenishmentStatus[]{
        ReplenishmentStatus.CONVERTED,
        ReplenishmentStatus.REJECTED,
        ReplenishmentStatus.CANCELLED}) {
      assertTrue(terminal.isTerminal(), terminal + " 应为终态");
      for (ReplenishmentStatus target : ReplenishmentStatus.values()) {
        assertFalse(terminal.canTransitionTo(target),
            terminal + " 不应能转到 " + target);
      }
      assertFalse(terminal.canTransitionTo(null));
    }
  }

  @Test
  @DisplayName("只有 DRAFT 可以人审改量/换供应商")
  void onlyDraftIsEditable() {
    assertTrue(ReplenishmentStatus.DRAFT.isEditable());
    assertFalse(ReplenishmentStatus.ADOPTED.isEditable());
    assertFalse(ReplenishmentStatus.CONVERTED.isEditable());
    assertFalse(ReplenishmentStatus.REJECTED.isEditable());
    assertFalse(ReplenishmentStatus.CANCELLED.isEditable());
  }

  @Test
  @DisplayName("from 解析持久化字符串，未知值直接失败而不是默认成 DRAFT")
  void fromRejectsUnknownValues() {
    assertEquals(ReplenishmentStatus.DRAFT, ReplenishmentStatus.from("DRAFT"));
    assertEquals(ReplenishmentStatus.CONVERTED, ReplenishmentStatus.from("CONVERTED"));

    assertThrows(IllegalArgumentException.class, () -> ReplenishmentStatus.from(null));
    assertThrows(IllegalArgumentException.class, () -> ReplenishmentStatus.from(""));
    assertThrows(IllegalArgumentException.class, () -> ReplenishmentStatus.from("UNKNOWN"));
    // 大小写敏感：库里存的是枚举名，不做隐式归一化
    assertThrows(IllegalArgumentException.class, () -> ReplenishmentStatus.from("draft"));
  }
}
