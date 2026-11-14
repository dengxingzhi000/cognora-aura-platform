package com.cognora.platform.inventory.service.command;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.cognora.platform.common.data.rw.annotation.Master;
import com.cognora.platform.common.data.rw.annotation.Slave;
import com.cognora.platform.common.exception.ServiceException;
import com.cognora.platform.common.integration.outbox.OutboxService;
import com.cognora.platform.common.response.ResultCode;
import com.cognora.platform.common.tenant.TenantContextHolder;
import com.cognora.platform.inventory.domain.ReplenishmentStatus;
import com.cognora.platform.inventory.domain.entity.InvReplenishmentSuggestion;
import com.cognora.platform.inventory.domain.entity.InvReplenishmentSuggestionItem;
import com.cognora.platform.inventory.dto.ReplenishmentDecisionRequest;
import com.cognora.platform.inventory.dto.ReplenishmentItemAdjustRequest;
import com.cognora.platform.inventory.dto.ReplenishmentSuggestionItemResponse;
import com.cognora.platform.inventory.dto.ReplenishmentSuggestionResponse;
import com.cognora.platform.inventory.event.ReplenishmentConvertedEvent;
import com.cognora.platform.inventory.mapper.InvReplenishmentSuggestionItemMapper;
import com.cognora.platform.inventory.mapper.InvReplenishmentSuggestionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 补货建议单命令服务：人审改量 / 采纳 / 驳回 / 取消 / 转采购计划。
 *
 * <p>所有状态变更都过 {@link ReplenishmentStatus#canTransitionTo}，硬规则：
 * <ul>
 *   <li>只有 DRAFT 可改量、换供应商（{@link #adjustItem}）；</li>
 *   <li>只有 ADOPTED 可转采购计划（{@link #convert}）；</li>
 *   <li>驳回必须带原因——没有原因的驳回无法做反馈闭环；</li>
 *   <li>{@code convert} 与 outbox 事件写在<b>同一个事务</b>里：状态置 CONVERTED
 *       与事件落库原子生效，不会出现「状态说转了、事件没发」或反之。</li>
 * </ul>
 *
 * <p><b>转换失败不回收决策：</b>如果 SKU→material 映射缺失，{@link #convert} 抛错并回滚，
 * 单据保持 ADOPTED，人可以补映射后重试。采纳这件事本身已经生效，不应该因为下游失败
 * 让人再审一遍。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
@Slf4j
@Service
public class ReplenishmentSuggestionCommandService {

  private final InvReplenishmentSuggestionMapper suggestionMapper;
  private final InvReplenishmentSuggestionItemMapper itemMapper;
  private final OutboxService outboxService;
  private final SkuMaterialMappingResolver materialMappingResolver;

  public ReplenishmentSuggestionCommandService(
      InvReplenishmentSuggestionMapper suggestionMapper,
      InvReplenishmentSuggestionItemMapper itemMapper,
      OutboxService outboxService,
      SkuMaterialMappingResolver materialMappingResolver) {
    this.suggestionMapper = suggestionMapper;
    this.itemMapper = itemMapper;
    this.outboxService = outboxService;
    this.materialMappingResolver = materialMappingResolver;
  }

  /**
   * 人审改量 / 换供应商（仅 DRAFT）。
   */
  @Master(reason = "写操作必须走主库")
  @Transactional(rollbackFor = Exception.class)
  public ReplenishmentSuggestionResponse adjustItem(String suggestionId, String itemId,
                                                    ReplenishmentItemAdjustRequest request) {
    InvReplenishmentSuggestion head = requireHead(suggestionId);
    requireStatus(head, ReplenishmentStatus.DRAFT, "只有草稿状态的建议单可以改量");

    InvReplenishmentSuggestionItem item = itemMapper.selectById(itemId);
    if (item == null || !suggestionId.equals(item.getSuggestionId())) {
      throw new ServiceException(ResultCode.NOT_FOUND.getCode(), HttpStatus.NOT_FOUND,
          "建议明细不存在或不属于本单: " + itemId);
    }

    if (request == null) {
      return detail(suggestionId);
    }
    boolean changed = false;
    if (request.getAdoptedQty() != null) {
      if (request.getAdoptedQty() < 0) {
        throw new ServiceException(ResultCode.BAD_REQUEST.getCode(), HttpStatus.BAD_REQUEST,
            "采纳数量不能为负数");
      }
      item.setAdoptedQty(request.getAdoptedQty());
      changed = true;
    }
    if (StringUtils.hasText(request.getSupplierId())) {
      item.setSupplierId(request.getSupplierId());
      changed = true;
    }
    if (changed) {
      if (StringUtils.hasText(request.getReason())) {
        item.setReasonSnapshot(appendReason(item.getReasonSnapshot(), request.getReason()));
      }
      item.setUpdateTime(LocalDateTime.now());
      itemMapper.updateById(item);
      refreshTotals(head);
    }
    return detail(suggestionId);
  }

  /**
   * 采纳：DRAFT → ADOPTED。
   */
  @Master(reason = "写操作必须走主库")
  @Transactional(rollbackFor = Exception.class)
  public ReplenishmentSuggestionResponse adopt(String suggestionId,
                                               ReplenishmentDecisionRequest request) {
    InvReplenishmentSuggestion head = requireHead(suggestionId);
    requireStatus(head, ReplenishmentStatus.DRAFT, "只有草稿状态的建议单可以采纳");

    // 采纳量缺省等于建议量：人审不逐个改量时也应有一条完整记录
    for (InvReplenishmentSuggestionItem item : listItems(suggestionId)) {
      if (item.getAdoptedQty() == null) {
        item.setAdoptedQty(item.getSuggestedQty());
        item.setUpdateTime(LocalDateTime.now());
        itemMapper.updateById(item);
      }
    }

    transition(head, ReplenishmentStatus.ADOPTED);
    head.setAdoptedBy(request == null ? null : request.getOperatorId());
    head.setAdoptedAt(LocalDateTime.now());
    refreshTotals(head);
    return detail(suggestionId);
  }

  /**
   * 驳回：DRAFT → REJECTED，必须带原因。
   */
  @Master(reason = "写操作必须走主库")
  @Transactional(rollbackFor = Exception.class)
  public ReplenishmentSuggestionResponse reject(String suggestionId,
                                                ReplenishmentDecisionRequest request) {
    InvReplenishmentSuggestion head = requireHead(suggestionId);
    requireStatus(head, ReplenishmentStatus.DRAFT, "只有草稿状态的建议单可以驳回");
    if (request == null || !request.hasReason()) {
      throw new ServiceException(ResultCode.BAD_REQUEST.getCode(), HttpStatus.BAD_REQUEST,
          "驳回必须填写原因");
    }

    transition(head, ReplenishmentStatus.REJECTED);
    head.setRejectReason(request.getReason());
    suggestionMapper.updateById(head);
    return detail(suggestionId);
  }

  /**
   * 取消：DRAFT / ADOPTED → CANCELLED。
   */
  @Master(reason = "写操作必须走主库")
  @Transactional(rollbackFor = Exception.class)
  public ReplenishmentSuggestionResponse cancel(String suggestionId,
                                                ReplenishmentDecisionRequest request) {
    InvReplenishmentSuggestion head = requireHead(suggestionId);
    ReplenishmentStatus current = ReplenishmentStatus.from(head.getStatus());
    if (!current.canTransitionTo(ReplenishmentStatus.CANCELLED)) {
      throw new ServiceException(ResultCode.CONFLICT.getCode(), HttpStatus.CONFLICT,
          "当前状态不可取消: " + current);
    }
    transition(head, ReplenishmentStatus.CANCELLED);
    if (request != null && request.hasReason()) {
      head.setRejectReason(request.getReason());
    }
    suggestionMapper.updateById(head);
    return detail(suggestionId);
  }

  /**
   * 转采购计划：ADOPTED → CONVERTED，并在同一事务内写 outbox 事件。
   *
   * <p>SKU→material 映射在本侧完成：{@code pur_plan_item} 是 material 口径，
   * 建议单是 SKU 口径。任一行缺映射就整体失败并把原因写回建议单，单据留在 ADOPTED
   * 以便补映射后重试——不做「部分转出」，那会产生一张数量对不上的采购计划。
   *
   * @throws ServiceException 状态非法或映射缺失
   */
  @Master(reason = "写操作必须走主库")
  @Transactional(rollbackFor = Exception.class)
  public ReplenishmentSuggestionResponse convert(String suggestionId,
                                                 ReplenishmentDecisionRequest request) {
    InvReplenishmentSuggestion head = requireHead(suggestionId);
    requireStatus(head, ReplenishmentStatus.ADOPTED, "只有已采纳的建议单可以转采购计划");

    List<InvReplenishmentSuggestionItem> items = listItems(suggestionId);
    List<InvReplenishmentSuggestionItem> toConvert = new ArrayList<>();
    for (InvReplenishmentSuggestionItem item : items) {
      if (item.effectiveQty() > 0) {
        toConvert.add(item);
      }
    }
    if (toConvert.isEmpty()) {
      throw new ServiceException(ResultCode.BAD_REQUEST.getCode(), HttpStatus.BAD_REQUEST,
          "建议单没有可转出的数量（采纳量均为 0）");
    }

    List<ReplenishmentConvertedEvent.Item> eventItems = new ArrayList<>();
    for (InvReplenishmentSuggestionItem item : toConvert) {
      SkuMaterialMapping mapping = materialMappingResolver.resolve(item.getSkuId());
      if (mapping == null) {
        String message = "SKU→material 映射缺失，无法转采购计划: skuId=" + item.getSkuId();
        recordConvertFailure(head, message);
        throw new ServiceException(ResultCode.CONFLICT.getCode(), HttpStatus.CONFLICT, message);
      }
      eventItems.add(new ReplenishmentConvertedEvent.Item(
          item.getSkuId(),
          mapping.materialCode(),
          mapping.materialName(),
          mapping.materialCategory(),
          BigDecimal.valueOf(item.effectiveQty()),
          mapping.unit() == null ? "件" : mapping.unit(),
          null,
          item.getSupplierId(),
          "补货建议 " + head.getSuggestionNo()));
    }

    String planNo = "RP-" + head.getSuggestionNo();
    UUID tenantId = TenantContextHolder.getRequiredTenantId();
    ReplenishmentConvertedEvent event = new ReplenishmentConvertedEvent(
        tenantId,
        head.getId(),
        head.getSuggestionNo(),
        head.getPeriod(),
        head.getWarehouseId(),
        planNo,
        "补货计划 " + head.getSuggestionNo(),
        head.getPeriod(),
        LocalDate.now().plusDays(30).toString(),
        eventItems);

    // 状态与事件同事务：任一失败一起回滚
    transition(head, ReplenishmentStatus.CONVERTED);
    head.setPurPlanNo(planNo);
    head.setConvertedAt(LocalDateTime.now());
    head.setConvertError(null);
    suggestionMapper.updateById(head);

    outboxService.save(ReplenishmentConvertedEvent.EVENT_TYPE,
        ReplenishmentConvertedEvent.AGGREGATE_TYPE, head.getSuggestionNo(), event, tenantId);

    log.info("补货建议单已转采购计划: suggestionNo={}, planNo={}, items={}",
        head.getSuggestionNo(), planNo, eventItems.size());
    return detail(suggestionId);
  }

  // ---------------------------------------------------------------- helpers

  /** 转换失败只记原因，不回滚人审决策（调用方随后抛错回滚事务，此处用于留痕字段）。 */
  private void recordConvertFailure(InvReplenishmentSuggestion head, String message) {
    head.setConvertError(message);
    head.setUpdateTime(LocalDateTime.now());
    suggestionMapper.updateById(head);
    log.warn("补货建议单转换失败: suggestionNo={}, reason={}", head.getSuggestionNo(), message);
  }

  private InvReplenishmentSuggestion requireHead(String suggestionId) {
    InvReplenishmentSuggestion head = suggestionMapper.selectById(suggestionId);
    if (head == null || Boolean.TRUE.equals(head.getDeleted())) {
      throw new ServiceException(ResultCode.NOT_FOUND.getCode(), HttpStatus.NOT_FOUND,
          "补货建议单不存在: " + suggestionId);
    }
    return head;
  }

  private void requireStatus(InvReplenishmentSuggestion head, ReplenishmentStatus expected,
                             String message) {
    ReplenishmentStatus current = ReplenishmentStatus.from(head.getStatus());
    if (current != expected) {
      throw new ServiceException(ResultCode.CONFLICT.getCode(), HttpStatus.CONFLICT,
          message + "，当前状态: " + current);
    }
  }

  private void transition(InvReplenishmentSuggestion head, ReplenishmentStatus target) {
    ReplenishmentStatus current = ReplenishmentStatus.from(head.getStatus());
    if (!current.canTransitionTo(target)) {
      throw new ServiceException(ResultCode.CONFLICT.getCode(), HttpStatus.CONFLICT,
          "非法的状态流转: " + current + " → " + target);
    }
    head.setStatus(target.name());
    head.setUpdateTime(LocalDateTime.now());
  }

  /** 重算单据头的采纳量与条数。 */
  private void refreshTotals(InvReplenishmentSuggestion head) {
    List<InvReplenishmentSuggestionItem> items = listItems(head.getId());
    long total = 0;
    for (InvReplenishmentSuggestionItem item : items) {
      total += item.effectiveQty();
    }
    head.setTotalAdoptedQty(total);
    head.setItemCount(items.size());
    suggestionMapper.updateById(head);
  }

  private List<InvReplenishmentSuggestionItem> listItems(String suggestionId) {
    return itemMapper.selectList(
        Wrappers.<InvReplenishmentSuggestionItem>lambdaQuery()
            .eq(InvReplenishmentSuggestionItem::getSuggestionId, suggestionId)
            .orderByAsc(InvReplenishmentSuggestionItem::getSkuId));
  }

  /** 读取详情（含明细）。 */
  @Slave
  public ReplenishmentSuggestionResponse detail(String suggestionId) {
    InvReplenishmentSuggestion head = requireHead(suggestionId);
    List<ReplenishmentSuggestionItemResponse> items = new ArrayList<>();
    for (InvReplenishmentSuggestionItem item : listItems(suggestionId)) {
      items.add(ReplenishmentSuggestionItemResponse.from(item));
    }
    return ReplenishmentSuggestionResponse.from(head, items);
  }

  private static String appendReason(String origin, String reason) {
    if (!StringUtils.hasText(reason)) {
      return origin;
    }
    return StringUtils.hasText(origin) ? origin + " / 人审: " + reason : "人审: " + reason;
  }
}
