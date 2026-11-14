package com.cognora.platform.inventory.job;

import com.cognora.platform.common.tenant.TenantContextHolder;
import com.cognora.platform.inventory.dto.ReplenishmentComputeResult;
import com.cognora.platform.inventory.service.command.ReplenishmentComputeService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 补货计算定时任务。
 *
 * <p><b>租户上下文：</b>XXL-Job 的调度线程没有租户，而 {@code TenantInterceptor}
 * 在没有租户上下文时会跳过租户过滤（等于全库可见）。因此这里必须按租户循环，
 * 用 {@link TenantContextHolder#executeInTenantContext} 为每个租户单独包一层，
 * 否则一次扫描会跨租户读写。
 *
 * <p>租户来源是本服务的库存台账：DISTINCT tenant_id。没有台账的租户不需要算补货。
 *
 * <p>建议执行频率：每天一次（或按 policy 的复核周期，留到统计版），
 * cron {@code 0 30 2 * * ?}。
 *
 * @author SCM Platform Team
 * @since 2026-11-10
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReplenishmentComputeJobHandler {

  private final ReplenishmentComputeService computeService;
  private final com.cognora.platform.inventory.mapper.InvInventoryMapper inventoryMapper;

  @XxlJob("replenishmentComputeJobHandler")
  public void execute() {
    long startTime = System.currentTimeMillis();
    XxlJobHelper.log("🧮 [智能补货] 开始按租户计算补货建议");

    AtomicInteger tenants = new AtomicInteger();
    AtomicInteger suggestions = new AtomicInteger();
    AtomicInteger triggered = new AtomicInteger();
    AtomicInteger failed = new AtomicInteger();

    for (String rawTenant : inventoryMapper.selectDistinctTenantIds()) {
      UUID tenantId;
      try {
        tenantId = UUID.fromString(rawTenant);
      } catch (IllegalArgumentException e) {
        log.warn("跳过非 UUID 租户: {}", rawTenant);
        continue;
      }
      tenants.incrementAndGet();
      try {
        ReplenishmentComputeResult result = TenantContextHolder.executeInTenantContext(tenantId,
            computeService::generateDefault);
        if (result != null) {
          if (result.getExistingSuggestionId() == null && !result.isDryRun()) {
            suggestions.incrementAndGet();
          }
          triggered.addAndGet(result.getTriggeredItems());
        }
      } catch (RuntimeException e) {
        failed.incrementAndGet();
        log.warn("租户 {} 补货计算失败: {}", tenantId, e.getMessage());
      }
    }

    long duration = System.currentTimeMillis() - startTime;
    String message = String.format("租户=%d, 新建议单=%d, 触发行=%d, 失败=%d, 耗时=%dms",
        tenants.get(), suggestions.get(), triggered.get(), failed.get(), duration);
    XxlJobHelper.log("🎉 [智能补货] 完成: " + message);
    if (failed.get() > 0) {
      // 有租户失败时让调度台看到失败，但不影响其它租户已经生成的建议单
      XxlJobHelper.handleFail(message);
    } else {
      XxlJobHelper.handleSuccess(message);
    }
  }
}
