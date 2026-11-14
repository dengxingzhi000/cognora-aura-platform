package com.cognora.platform.inventory.service.command;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cognora.platform.common.data.rw.annotation.Master;
import com.cognora.platform.common.exception.ServiceException;
import com.cognora.platform.common.response.ResultCode;
import com.cognora.platform.common.tenant.TenantContextHolder;
import com.cognora.platform.inventory.domain.ReplenishmentFlags;
import com.cognora.platform.inventory.domain.ReplenishmentStatus;
import com.cognora.platform.inventory.domain.entity.InvDemandForecast;
import com.cognora.platform.inventory.domain.entity.InvReplenishmentPolicy;
import com.cognora.platform.inventory.domain.entity.InvReplenishmentSuggestion;
import com.cognora.platform.inventory.domain.entity.InvReplenishmentSuggestionItem;
import com.cognora.platform.inventory.domain.entity.Inventory;
import com.cognora.platform.inventory.dto.ReplenishmentComputeResult;
import com.cognora.platform.inventory.dto.ReplenishmentGenerateRequest;
import com.cognora.platform.inventory.mapper.InvDemandForecastMapper;
import com.cognora.platform.inventory.mapper.InvInventoryMapper;
import com.cognora.platform.inventory.mapper.InvReplenishmentPolicyMapper;
import com.cognora.platform.inventory.mapper.InvReplenishmentSuggestionItemMapper;
import com.cognora.platform.inventory.mapper.InvReplenishmentSuggestionMapper;
import com.cognora.platform.inventory.service.query.DemandAggregationQueryService;
import com.cognora.platform.inventory.service.replenishment.InTransitQuantities;
import com.cognora.platform.inventory.service.replenishment.InTransitQuantityResolver;
import com.cognora.platform.inventory.service.replenishment.ReorderPointCalculator;
import com.cognora.platform.inventory.service.replenishment.ReplenishmentConstraintChecker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 补货计算编排：读预测 → 算 ROP → 算量 → 约束裁剪 → 建建议单（DRAFT）。
 *
 * <p>职责边界很窄：本类只做「编排 + 落库」，全部数学都在纯函数
 * {@link ReorderPointCalculator} / {@link ReplenishmentConstraintChecker} 里，
 * 因此回测只需要重放这两段。
 *
 * <p><b>幂等：</b>{@code businessKey = tenant|warehouse|period}。同一个周期重复触发时，
 * 已存在建议单就直接返回它（{@code existingSuggestionId}），不再建第二张，也不覆盖
 * 人审已经改过的量——重复计算绝不能让已采纳的决策退回 DRAFT。
 *
 * <p><b>在途去重：</b>触发判定与缺口都用 {@code available + inTransit}，
 * 其中 inTransit 由 {@link InTransitQuantityResolver} 汇总「采购未收货 + 已采纳未收货」，
 * 并显式排除本周期正在生成的这张单本身。
 *
 * <p><b>为什么全部跳过时也建单：</b>建一张空的 DRAFT 会让人审看到「这周没什么要补的」
 * 并留下计算留痕；比什么都不生成更容易解释。但如果一行都没触发，仍会落单，
 * 由 {@code item_count=0} 体现。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
@Slf4j
@Service
public class ReplenishmentComputeService {

  private static final DateTimeFormatter SUGGESTION_NO_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

  /** 默认回看天数（与 {@link DemandAggregationQueryService} 的默认窗口保持同一量级）。 */
  private static final int DEFAULT_LOOKBACK_DAYS = 28;

  /** 无策略时的兜底：交期 7 天、复核 7 天、安全 3 天、MOQ/包装 1。 */
  private static final int DEFAULT_LEAD_TIME_DAYS = 7;
  private static final int DEFAULT_REVIEW_PERIOD_DAYS = 7;
  private static final BigDecimal DEFAULT_SAFETY_DAYS = new BigDecimal("3");

  private final InvInventoryMapper inventoryMapper;
  private final InvReplenishmentPolicyMapper policyMapper;
  private final InvDemandForecastMapper forecastMapper;
  private final InvReplenishmentSuggestionMapper suggestionMapper;
  private final InvReplenishmentSuggestionItemMapper suggestionItemMapper;
  private final DemandAggregationQueryService demandAggregation;
  private final InTransitQuantityResolver inTransitResolver;

  public ReplenishmentComputeService(InvInventoryMapper inventoryMapper,
                                     InvReplenishmentPolicyMapper policyMapper,
                                     InvDemandForecastMapper forecastMapper,
                                     InvReplenishmentSuggestionMapper suggestionMapper,
                                     InvReplenishmentSuggestionItemMapper suggestionItemMapper,
                                     DemandAggregationQueryService demandAggregation,
                                     InTransitQuantityResolver inTransitResolver) {
    this.inventoryMapper = inventoryMapper;
    this.policyMapper = policyMapper;
    this.forecastMapper = forecastMapper;
    this.suggestionMapper = suggestionMapper;
    this.suggestionItemMapper = suggestionItemMapper;
    this.demandAggregation = demandAggregation;
    this.inTransitResolver = inTransitResolver;
  }

  /**
   * 触发一次补货计算。
   *
   * @throws ServiceException 缺少租户上下文，或周期参数非法
   */
  @Master(reason = "补货计算需要写预测与建议单")
  @Transactional(rollbackFor = Exception.class)
  public ReplenishmentComputeResult generate(ReplenishmentGenerateRequest request) {
    UUID tenantId = TenantContextHolder.getRequiredTenantId();
    ReplenishmentGenerateRequest safe = request == null ? new ReplenishmentGenerateRequest() : request;
    String period = resolvePeriod(safe.getPeriod());
    String warehouseId = safe.getWarehouseId();
    boolean dryRun = Boolean.TRUE.equals(safe.getDryRun());

    String businessKey = businessKey(tenantId, warehouseId, period);
    if (!dryRun) {
      InvReplenishmentSuggestion existing = findByBusinessKey(businessKey);
      if (existing != null) {
        log.info("补货计算幂等命中: businessKey={}, suggestionNo={}", businessKey,
            existing.getSuggestionNo());
        return existingResult(existing, period, businessKey);
      }
    }

    int lookbackDays = safe.getLookbackDays() == null
        ? DEFAULT_LOOKBACK_DAYS : safe.getLookbackDays();
    // key = skuId|warehouseId；聚合只返回历史上有销售出库的组合
    Map<String, DemandAggregationQueryService.SkuDemand> demands =
        demandAggregation.aggregateAll(lookbackDays);
    InTransitQuantities inTransit = inTransitResolver.resolve(businessKey);

    List<Inventory> inventories = loadInventories(warehouseId, safe.getSkuIds());
    Map<String, InvReplenishmentPolicy> policies = loadPolicies();
    BigDecimal remainingBudget = safe.getTotalBudget();

    ReplenishmentComputeResult result = new ReplenishmentComputeResult();
    result.setPeriod(period);
    result.setBusinessKey(businessKey);
    result.setDryRun(dryRun);
    result.setScannedInventories(inventories.size());

    List<InvReplenishmentSuggestionItem> itemsToPersist = new ArrayList<>();
    List<ReplenishmentComputeResult.Line> lines = new ArrayList<>();

    for (Inventory inventory : inventories) {
      if (inventory.getSkuId() == null || inventory.getWarehouseId() == null) {
        continue;
      }
      InvReplenishmentPolicy policy = policies.get(
          InTransitQuantities.key(inventory.getSkuId(), inventory.getWarehouseId()));

      ComputedLine computed = computeLine(inventory, policy, demands, inTransit);
      if (computed == null) {
        result.setSkippedItems(result.getSkippedItems() + 1);
        continue;
      }

      // 预算：从总量里扣减，后面的行只能拿到剩余额度
      int feasible = computed.suggestedQty;
      Set<ReplenishmentFlags> flags = new LinkedHashSet<>(computed.flags);
      String reason = computed.reason;
      if (feasible > 0 && remainingBudget != null) {
        BigDecimal unitPrice = inventory.getAverageCost() == null
            ? BigDecimal.ZERO : inventory.getAverageCost();
        BigDecimal cost = unitPrice.multiply(BigDecimal.valueOf(feasible));
        if (cost.compareTo(remainingBudget) > 0) {
          int affordable = unitPrice.compareTo(BigDecimal.ZERO) <= 0
              ? feasible
              : remainingBudget.divide(unitPrice, 0, java.math.RoundingMode.FLOOR).intValue();
          if (affordable < 0) {
            affordable = 0;
          }
          if (affordable < feasible) {
            flags.add(ReplenishmentFlags.CAPPED_BY_BUDGET);
            reason = reason + "；预算仅够 " + affordable + " 件";
            feasible = affordable;
          }
        }
        remainingBudget = remainingBudget.subtract(
            unitPrice.multiply(BigDecimal.valueOf(feasible)));
      }

      if (feasible <= 0) {
        result.setSkippedItems(result.getSkippedItems() + 1);
        continue;
      }

      if (flags.contains(ReplenishmentFlags.NEEDS_MANUAL)) {
        result.setNeedsManualItems(result.getNeedsManualItems() + 1);
      }
      result.setTriggeredItems(result.getTriggeredItems() + 1);
      result.setTotalSuggestedQty(result.getTotalSuggestedQty() + feasible);

      InvReplenishmentSuggestionItem item = new InvReplenishmentSuggestionItem();
      item.setSkuId(inventory.getSkuId());
      item.setWarehouseId(inventory.getWarehouseId());
      item.setAvailableStock(computed.availableStock);
      item.setInTransitQty(computed.inTransitQty);
      item.setReorderPoint(computed.reorderPoint);
      item.setSafetyStock(computed.safetyStock);
      item.setTargetStock(computed.targetStock);
      item.setSuggestedQty(feasible);
      item.setSupplierId(policy == null ? null : policy.getSupplierId());
      item.setFlags(ReplenishmentFlags.join(flags));
      item.setReasonSnapshot(reason);
      itemsToPersist.add(item);

      ReplenishmentComputeResult.Line line = new ReplenishmentComputeResult.Line();
      line.setSkuId(item.getSkuId());
      line.setWarehouseId(item.getWarehouseId());
      line.setAvailableStock(item.getAvailableStock());
      line.setInTransitQty(item.getInTransitQty());
      line.setReorderPoint(item.getReorderPoint());
      line.setSafetyStock(item.getSafetyStock());
      line.setTargetStock(item.getTargetStock());
      line.setSuggestedQty(feasible);
      line.setFlags(item.getFlags());
      line.setReason(reason);
      lines.add(line);
    }

    result.setLines(lines);
    if (dryRun) {
      log.info("补货 dry-run 完成: period={}, scanned={}, triggered={}",
          period, result.getScannedInventories(), result.getTriggeredItems());
      return result;
    }

    InvReplenishmentSuggestion head = persistSuggestion(period, warehouseId, businessKey,
        itemsToPersist, result.getTotalSuggestedQty(), safe.getOperatorId(), tenantId);
    result.setExistingSuggestionId(null);
    log.info("补货建议单已生成: suggestionNo={}, items={}, qty={}",
        head.getSuggestionNo(), itemsToPersist.size(), result.getTotalSuggestedQty());
    return result;
  }

  // ---------------------------------------------------------------- compute

  /**
   * 单行计算：返回 null 表示该行不参与（未触发或约束裁到 0）。
   */
  private ComputedLine computeLine(Inventory inventory,
                                   InvReplenishmentPolicy policy,
                                   Map<String, DemandAggregationQueryService.SkuDemand> demands,
                                   InTransitQuantities inTransit) {
    String key = InTransitQuantities.key(inventory.getSkuId(), inventory.getWarehouseId());
    if (key == null) {
      return null;
    }

    DemandAggregationQueryService.SkuDemand demand = demands.get(key);
    if (demand == null) {
      // 没有任何销售出库历史：不做无依据的补货，交给人工或告警链路
      return null;
    }

    int available = nvl(inventory.getAvailableStock());
    int onOrder = inTransit.of(key);
    int leadTime = policy != null && policy.getLeadTimeDays() != null
        ? policy.getLeadTimeDays() : DEFAULT_LEAD_TIME_DAYS;
    int reviewPeriod = policy != null && policy.getReviewPeriodDays() != null
        ? policy.getReviewPeriodDays() : DEFAULT_REVIEW_PERIOD_DAYS;
    BigDecimal safetyDays = policy != null && policy.getSafetyDays() != null
        ? policy.getSafetyDays() : DEFAULT_SAFETY_DAYS;

    // manualOverride：人工上下限优先，用台账 safety_stock 反推等效安全天数
    boolean manual = policy != null && Boolean.TRUE.equals(policy.getManualOverride());
    if (manual && inventory.getSafetyStock() != null && inventory.getSafetyStock() > 0
        && demand.dailyDemand().compareTo(BigDecimal.ZERO) > 0) {
      safetyDays = BigDecimal.valueOf(inventory.getSafetyStock())
          .divide(demand.dailyDemand(), 4, java.math.RoundingMode.HALF_UP);
    }

    ReorderPointCalculator.Result rp = ReorderPointCalculator.calculate(
        demand.dailyDemand(), leadTime, reviewPeriod, safetyDays, available, onOrder);

    Set<ReplenishmentFlags> flags = new LinkedHashSet<>();
    if (demand.lowConfidence()) {
      flags.add(ReplenishmentFlags.NEEDS_MANUAL);
    }
    if (policy == null || policy.getLeadTimeDays() == null) {
      flags.add(ReplenishmentFlags.NO_LEAD_TIME);
      flags.add(ReplenishmentFlags.NEEDS_MANUAL);
    }
    if (policy == null || policy.getSupplierId() == null || policy.getSupplierId().isBlank()) {
      flags.add(ReplenishmentFlags.NO_SUPPLIER);
    }

    if (!rp.triggered()) {
      return null;
    }

    BigDecimal unitPrice = inventory.getAverageCost();
    ReplenishmentConstraintChecker.Result constrained = ReplenishmentConstraintChecker.apply(
        new ReplenishmentConstraintChecker.Input(
            Math.max(0, rp.rawQuantity().setScale(0, java.math.RoundingMode.FLOOR).intValue()),
            policy == null || policy.getMinOrderQty() == null ? 1 : policy.getMinOrderQty(),
            policy == null || policy.getPackMultiple() == null ? 1 : policy.getPackMultiple(),
            inventory.getMaxStock(),
            available,
            onOrder,
            unitPrice,
            null));
    flags.addAll(constrained.flags());

    String reason = rp.reason() + "；" + constrained.reason();
    return new ComputedLine(available, onOrder, rp.reorderPoint(), rp.safetyStock(),
        rp.targetStock(), constrained.feasibleQuantity(), flags, reason);
  }

  private record ComputedLine(int availableStock, int inTransitQty, BigDecimal reorderPoint,
                              BigDecimal safetyStock, BigDecimal targetStock, int suggestedQty,
                              Set<ReplenishmentFlags> flags, String reason) {
  }

  // ---------------------------------------------------------------- persist

  private InvReplenishmentSuggestion persistSuggestion(String period, String warehouseId,
                                                       String businessKey,
                                                       List<InvReplenishmentSuggestionItem> items,
                                                       long totalQty, String operatorId,
                                                       UUID tenantId) {
    InvReplenishmentSuggestion head = new InvReplenishmentSuggestion();
    head.setTenantId(tenantId.toString());
    head.setSuggestionNo(nextSuggestionNo(period));
    head.setWarehouseId(warehouseId);
    head.setPeriod(period);
    head.setStatus(ReplenishmentStatus.DRAFT.name());
    head.setBusinessKey(businessKey);
    head.setItemCount(items.size());
    head.setTotalSuggestedQty(totalQty);
    head.setTotalAdoptedQty(totalQty);
    head.setCreatedBy(operatorId);
    suggestionMapper.insert(head);

    for (InvReplenishmentSuggestionItem item : items) {
      item.setSuggestionId(head.getId());
      suggestionItemMapper.insert(item);
    }
    return head;
  }

  private String nextSuggestionNo(String period) {
    return "RP" + LocalDateTime.now().format(SUGGESTION_NO_FORMAT) + "-"
        + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
  }

  // ---------------------------------------------------------------- lookups

  private List<Inventory> loadInventories(String warehouseId, List<String> skuIds) {
    LambdaQueryWrapper<Inventory> wrapper = new LambdaQueryWrapper<Inventory>()
        .eq(Inventory::getDeleted, false)
        .eq(StringUtils.hasText(warehouseId), Inventory::getWarehouseId, warehouseId)
        .in(!CollectionUtils.isEmpty(skuIds), Inventory::getSkuId, skuIds);
    return inventoryMapper.selectList(wrapper);
  }

  private Map<String, InvReplenishmentPolicy> loadPolicies() {
    List<InvReplenishmentPolicy> policies = policyMapper.selectList(
        new LambdaQueryWrapper<InvReplenishmentPolicy>()
            .eq(InvReplenishmentPolicy::getDeleted, false)
            .eq(InvReplenishmentPolicy::getEnabled, true));
    Map<String, InvReplenishmentPolicy> indexed = new HashMap<>();
    for (InvReplenishmentPolicy policy : policies) {
      String key = InTransitQuantities.key(policy.getSkuId(), policy.getWarehouseId());
      if (key != null) {
        indexed.put(key, policy);
      }
    }
    return indexed;
  }

  private InvReplenishmentSuggestion findByBusinessKey(String businessKey) {
    return suggestionMapper.selectOne(
        new LambdaQueryWrapper<InvReplenishmentSuggestion>()
            .eq(InvReplenishmentSuggestion::getBusinessKey, businessKey)
            .eq(InvReplenishmentSuggestion::getDeleted, false)
            .last("LIMIT 1"));
  }

  private ReplenishmentComputeResult existingResult(InvReplenishmentSuggestion existing,
                                                    String period, String businessKey) {
    ReplenishmentComputeResult result = new ReplenishmentComputeResult();
    result.setPeriod(period);
    result.setBusinessKey(businessKey);
    result.setExistingSuggestionId(existing.getId());
    result.setTotalSuggestedQty(existing.effectiveTotalQty());
    result.setTriggeredItems(existing.getItemCount() == null ? 0 : existing.getItemCount());
    result.setLines(List.of());
    return result;
  }

  /**
   * 幂等键：tenant|warehouse|period。
   *
   * <p>{@code warehouseId} 为空时用 {@code ALL}，表示「该租户全仓一轮」，
   * 避免把「全仓计算」和「单仓计算」混成同一个键。
   */
  static String businessKey(UUID tenantId, String warehouseId, String period) {
    String warehousePart = StringUtils.hasText(warehouseId) ? warehouseId : "ALL";
    return tenantId + "|" + warehousePart + "|" + period;
  }

  /**
   * 解析补货周期：优先用调用方传入的，否则按当前 ISO 周推导（如 {@code 2026-W45}）。
   */
  static String resolvePeriod(String requested) {
    if (StringUtils.hasText(requested)) {
      String trimmed = requested.trim();
      if (trimmed.length() > 32) {
        throw new ServiceException(ResultCode.BAD_REQUEST.getCode(), HttpStatus.BAD_REQUEST,
            "补货周期过长: " + trimmed);
      }
      return trimmed;
    }
    LocalDate today = LocalDate.now();
    WeekFields iso = WeekFields.ISO;
    int week = today.get(iso.weekOfWeekBasedYear());
    int year = today.get(iso.weekBasedYear());
    return String.format("%d-W%02d", year, week);
  }

  private static int nvl(Integer value) {
    return value == null ? 0 : value;
  }

  /** 供 Job 使用：不带请求的默认计算。 */
  @Master(reason = "补货计算需要写预测与建议单")
  public ReplenishmentComputeResult generateDefault() {
    return generate(new ReplenishmentGenerateRequest());
  }
}
