package com.cognora.platform.inventory.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.platform.common.response.ApiResponse;
import com.cognora.platform.common.response.ResultCode;
import com.cognora.platform.inventory.domain.entity.InvDemandForecast;
import com.cognora.platform.inventory.dto.ReplenishmentComputeResult;
import com.cognora.platform.inventory.dto.ReplenishmentDecisionRequest;
import com.cognora.platform.inventory.dto.ReplenishmentGenerateRequest;
import com.cognora.platform.inventory.dto.ReplenishmentItemAdjustRequest;
import com.cognora.platform.inventory.dto.ReplenishmentPolicyRequest;
import com.cognora.platform.inventory.dto.ReplenishmentPolicyResponse;
import com.cognora.platform.inventory.dto.ReplenishmentSuggestionItemResponse;
import com.cognora.platform.inventory.dto.ReplenishmentSuggestionQueryRequest;
import com.cognora.platform.inventory.dto.ReplenishmentSuggestionResponse;
import com.cognora.platform.inventory.service.command.ReplenishmentComputeService;
import com.cognora.platform.inventory.service.command.ReplenishmentPolicyCommandService;
import com.cognora.platform.inventory.service.command.ReplenishmentSuggestionCommandService;
import com.cognora.platform.inventory.service.query.ReplenishmentPolicyQueryService;
import com.cognora.platform.inventory.service.query.ReplenishmentSuggestionQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 智能补货控制器。
 *
 * <p>端到端链路：需求预测 → ROP（含在途）→ 目标水位补货量 → 约束裁剪 →
 * 建议单草稿 → 人审（改量/采纳/驳回）→ 转采购计划（事件驱动）。
 *
 * <p>对应设计稿 §6 的接口契约；所有路径登记在
 * {@code scripts/db/seed/033_replenishment_permissions.sql}，未登记的端点会被
 * {@code ApiAccessControlFilter} 默认拒绝。
 *
 * <p><b>注解顺序有约束：</b>{@code @PreAuthorize} 必须紧贴方法声明、位于
 * {@code @XxxMapping} <b>之后</b>。{@code scripts/ci/check_permission_annotation_drift.py}
 * 用「注解到方法声明之间不得出现 {@code {};}」来判定归属，而 mapping 的路径里
 * 含 {@code {id}} 占位符——把 {@code @PreAuthorize} 放在 mapping 之前会被判成
 * 「未绑定到任何 request mapping」（CONCERN）。同仓库其它 controller 也是这个顺序。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/inventory/replenishment")
@Tag(name = "智能补货", description = "需求驱动的补货建议：计算、人审、转采购计划与策略维护。")
public class ReplenishmentController {

  private final ReplenishmentComputeService computeService;
  private final ReplenishmentSuggestionCommandService suggestionCommandService;
  private final ReplenishmentSuggestionQueryService suggestionQueryService;
  private final ReplenishmentPolicyCommandService policyCommandService;
  private final ReplenishmentPolicyQueryService policyQueryService;

  // ------------------------------------------------------------ 建议单

  @Operation(summary = "触发补货计算",
      description = "按仓/范围计算 ROP 与补货量并生成 DRAFT 建议单；同周期重复触发返回已有单据。"
          + "dryRun=true 只算不落库。")
  @PostMapping("/suggestions/generate")
  @PreAuthorize("hasAuthority('inventory:replenishment:generate')")
  @AuditLog(operation = "触发补货计算", businessType = "INVENTORY", riskLevel = 3)
  public ApiResponse<ReplenishmentComputeResult> generate(
      @RequestBody(required = false) @Valid ReplenishmentGenerateRequest request) {
    return ApiResponse.success(computeService.generate(request));
  }

  @Operation(summary = "分页查询建议单", description = "按仓库、状态、周期与单号分页查询建议单据头（不含明细）。")
  @PostMapping("/suggestions/query")
  @PreAuthorize("hasAuthority('inventory:replenishment:query')")
  public ApiResponse<Page<ReplenishmentSuggestionResponse>> query(
      @RequestBody @Valid ReplenishmentSuggestionQueryRequest request) {
    return ApiResponse.success(suggestionQueryService.query(request));
  }

  @Operation(summary = "建议单详情", description = "返回建议单据头与全部明细（含决策快照）。")
  @GetMapping("/suggestions/{id}")
  @PreAuthorize("hasAuthority('inventory:replenishment:get')")
  public ApiResponse<ReplenishmentSuggestionResponse> detail(
      @Parameter(description = "建议单 ID") @PathVariable @NotBlank(message = "建议单 ID 不能为空")
      String id) {
    return ApiResponse.success(suggestionCommandService.detail(id));
  }

  @Operation(summary = "建议单明细", description = "单独读取建议明细，便于大单据按需加载。")
  @GetMapping("/suggestions/{id}/items")
  @PreAuthorize("hasAuthority('inventory:replenishment:items')")
  public ApiResponse<List<ReplenishmentSuggestionItemResponse>> items(
      @Parameter(description = "建议单 ID") @PathVariable @NotBlank(message = "建议单 ID 不能为空")
      String id) {
    return ApiResponse.success(suggestionQueryService.items(id));
  }

  @Operation(summary = "人审改量/换供应商", description = "仅草稿状态可改；改量后以采纳量作为转出数量。")
  @PutMapping("/suggestions/{id}/items/{itemId}")
  @PreAuthorize("hasAuthority('inventory:replenishment:edit-item')")
  @AuditLog(operation = "补货建议改量", businessType = "INVENTORY", riskLevel = 3)
  public ApiResponse<ReplenishmentSuggestionResponse> adjustItem(
      @Parameter(description = "建议单 ID") @PathVariable @NotBlank(message = "建议单 ID 不能为空")
      String id,
      @Parameter(description = "建议明细 ID") @PathVariable @NotBlank(message = "明细 ID 不能为空")
      String itemId,
      @RequestBody(required = false) @Valid ReplenishmentItemAdjustRequest request) {
    return ApiResponse.success(suggestionCommandService.adjustItem(id, itemId, request));
  }

  @Operation(summary = "采纳建议单", description = "DRAFT → ADOPTED。未改量的明细自动按建议量采纳。")
  @PutMapping("/suggestions/{id}/adopt")
  @PreAuthorize("hasAuthority('inventory:replenishment:adopt')")
  @AuditLog(operation = "采纳补货建议", businessType = "INVENTORY", riskLevel = 3)
  public ApiResponse<ReplenishmentSuggestionResponse> adopt(
      @Parameter(description = "建议单 ID") @PathVariable @NotBlank(message = "建议单 ID 不能为空")
      String id,
      @RequestBody(required = false) ReplenishmentDecisionRequest request) {
    return ApiResponse.success(suggestionCommandService.adopt(id, request));
  }

  @Operation(summary = "驳回建议单", description = "DRAFT → REJECTED，必须填写原因（用于反馈闭环）。")
  @PutMapping("/suggestions/{id}/reject")
  @PreAuthorize("hasAuthority('inventory:replenishment:reject')")
  @AuditLog(operation = "驳回补货建议", businessType = "INVENTORY", riskLevel = 3)
  public ApiResponse<ReplenishmentSuggestionResponse> reject(
      @Parameter(description = "建议单 ID") @PathVariable @NotBlank(message = "建议单 ID 不能为空")
      String id,
      @RequestBody @Valid ReplenishmentDecisionRequest request) {
    return ApiResponse.success(suggestionCommandService.reject(id, request));
  }

  @Operation(summary = "取消建议单", description = "DRAFT/ADOPTED → CANCELLED。")
  @PutMapping("/suggestions/{id}/cancel")
  @PreAuthorize("hasAuthority('inventory:replenishment:cancel')")
  @AuditLog(operation = "取消补货建议", businessType = "INVENTORY", riskLevel = 3)
  public ApiResponse<ReplenishmentSuggestionResponse> cancel(
      @Parameter(description = "建议单 ID") @PathVariable @NotBlank(message = "建议单 ID 不能为空")
      String id,
      @RequestBody(required = false) ReplenishmentDecisionRequest request) {
    return ApiResponse.success(suggestionCommandService.cancel(id, request));
  }

  @Operation(summary = "转采购计划",
      description = "ADOPTED → CONVERTED：同事务写 outbox 事件，由 scm-purchase 消费建 PurPlan(DRAFT)。"
          + "SKU→material 映射缺失时失败并回写「待补映射」，单据保持 ADOPTED 可重试。")
  @PostMapping("/suggestions/{id}/convert")
  @PreAuthorize("hasAuthority('inventory:replenishment:convert')")
  @AuditLog(operation = "补货建议转采购计划", businessType = "INVENTORY", riskLevel = 4)
  public ApiResponse<ReplenishmentSuggestionResponse> convert(
      @Parameter(description = "建议单 ID") @PathVariable @NotBlank(message = "建议单 ID 不能为空")
      String id,
      @RequestBody(required = false) ReplenishmentDecisionRequest request) {
    return ApiResponse.success(suggestionCommandService.convert(id, request));
  }

  // ------------------------------------------------------------ 策略

  @Operation(summary = "新增/更新补货策略", description = "upsert 语义：同 SKU×仓 已存在则更新。")
  @PostMapping("/policies")
  @PreAuthorize("hasAuthority('inventory:replenishment:policy-save')")
  @AuditLog(operation = "维护补货策略", businessType = "INVENTORY", riskLevel = 3)
  public ApiResponse<ReplenishmentPolicyResponse> savePolicy(
      @RequestBody @Valid ReplenishmentPolicyRequest request) {
    return ApiResponse.success(policyCommandService.upsert(request));
  }

  @Operation(summary = "分页查询补货策略", description = "按仓库与 SKU 分页查询策略。")
  @GetMapping("/policies/query")
  @PreAuthorize("hasAuthority('inventory:replenishment:policy-query')")
  public ApiResponse<Page<ReplenishmentPolicyResponse>> queryPolicies(
      @RequestParam(required = false) String warehouseId,
      @RequestParam(required = false) String skuId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.success(policyQueryService.query(warehouseId, skuId, page, size));
  }

  @Operation(summary = "补货策略详情", description = "按 ID 查询单条策略。")
  @GetMapping("/policies/{id}")
  @PreAuthorize("hasAuthority('inventory:replenishment:policy-get')")
  public ApiResponse<ReplenishmentPolicyResponse> getPolicy(
      @Parameter(description = "策略 ID") @PathVariable @NotBlank(message = "策略 ID 不能为空")
      String id) {
    ReplenishmentPolicyResponse policy = policyQueryService.getById(id);
    return policy == null
        ? ApiResponse.fail(ResultCode.NOT_FOUND.getCode(), "补货策略不存在")
        : ApiResponse.success(policy);
  }

  @Operation(summary = "删除补货策略", description = "逻辑删除单条补货策略。")
  @DeleteMapping("/policies/{id}")
  @PreAuthorize("hasAuthority('inventory:replenishment:policy-delete')")
  @AuditLog(operation = "删除补货策略", businessType = "INVENTORY", riskLevel = 3)
  public ApiResponse<Boolean> deletePolicy(
      @Parameter(description = "策略 ID") @PathVariable @NotBlank(message = "策略 ID 不能为空")
      String id) {
    return ApiResponse.success(policyCommandService.remove(id));
  }

  // ------------------------------------------------------------ 预测

  @Operation(summary = "查询需求预测",
      description = "返回某 SKU×仓 最新一版预测（μ/σ/置信度/方法），用于解释补货建议的来源。")
  @GetMapping("/forecasts/{skuId}")
  @PreAuthorize("hasAuthority('inventory:replenishment:forecast')")
  public ApiResponse<InvDemandForecast> forecast(
      @Parameter(description = "SKU ID") @PathVariable @NotBlank(message = "SKU ID 不能为空")
      String skuId,
      @Parameter(description = "仓库 ID") @RequestParam(required = false) String warehouseId) {
    InvDemandForecast forecast = suggestionQueryService.latestForecast(skuId, warehouseId);
    return forecast == null
        ? ApiResponse.fail(ResultCode.NOT_FOUND.getCode(), "该 SKU 尚无需求预测")
        : ApiResponse.success(forecast);
  }
}
