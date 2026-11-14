package com.cognora.platform.inventory.job;

import com.cognora.platform.common.tenant.TenantContextHolder;
import com.cognora.platform.inventory.dto.ReplenishmentComputeResult;
import com.cognora.platform.inventory.mapper.InvInventoryMapper;
import com.cognora.platform.inventory.service.command.ReplenishmentComputeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 补货 Job 的租户隔离用例。
 *
 * <p><b>为什么这条最重要：</b>{@code TenantInterceptor} 在没有租户上下文时**跳过**
 * 租户过滤（等于全库可见）。XXL-Job 的调度线程天然没有租户，所以一旦 Job 忘了按租户
 * 包上下文，一次扫描就会跨租户读库存、跨租户写建议单——是数据越权，不是性能问题。
 *
 * <p>本用例直接断言两件事：每个租户都在自己的上下文里被调用；调用期间 ThreadLocal
 * 里确实是该租户。跑完不残留上下文（否则同线程的后续任务会继承错误租户）。
 */
class ReplenishmentComputeJobTenantIsolationTest {

  private static final UUID TENANT_A = UUID.fromString("019a0aee-0000-7000-8000-0000000000a1");
  private static final UUID TENANT_B = UUID.fromString("019a0aee-0000-7000-8000-0000000000b2");

  private final ReplenishmentComputeService computeService = mock(ReplenishmentComputeService.class);
  private final InvInventoryMapper inventoryMapper = mock(InvInventoryMapper.class);

  @AfterEach
  void clearTenant() {
    TenantContextHolder.clear();
  }

  @Test
  @DisplayName("Job 按租户循环，每次调用都处在对应租户上下文里")
  void eachTenantRunsInsideItsOwnContext() {
    when(inventoryMapper.selectDistinctTenantIds())
        .thenReturn(List.of(TENANT_A.toString(), TENANT_B.toString()));

    // 记录每次调用时 ThreadLocal 里的租户，验证上下文真的是被包起来的
    List<UUID> observedDuringCall = new CopyOnWriteArrayList<>();
    when(computeService.generateDefault()).thenAnswer(invocation -> {
      observedDuringCall.add(TenantContextHolder.getTenantId());
      ReplenishmentComputeResult r = new ReplenishmentComputeResult();
      r.setTriggeredItems(1);
      return r;
    });

    new ReplenishmentComputeJobHandler(computeService, inventoryMapper).execute();

    verify(computeService, times(2)).generateDefault();
    assertThat(observedDuringCall)
        .as("每次计算都必须带着自己的租户，否则 TenantInterceptor 会放开全库")
        .containsExactly(TENANT_A, TENANT_B);
  }

  @Test
  @DisplayName("Job 结束后不残留租户上下文")
  void tenantContextIsRestoredAfterJob() {
    when(inventoryMapper.selectDistinctTenantIds())
        .thenReturn(List.of(TENANT_A.toString()));
    when(computeService.generateDefault()).thenReturn(new ReplenishmentComputeResult());

    new ReplenishmentComputeJobHandler(computeService, inventoryMapper).execute();

    assertThat(TenantContextHolder.getTenantId())
        .as("Job 跑完必须清空，否则同线程后续任务会继承错误租户")
        .isNull();
  }

  @Test
  @DisplayName("一个租户失败不影响其它租户，且上下文仍然被恢复")
  void failingTenantDoesNotLeakToOthers() {
    when(inventoryMapper.selectDistinctTenantIds())
        .thenReturn(List.of(TENANT_A.toString(), TENANT_B.toString()));

    List<UUID> succeeded = new ArrayList<>();
    when(computeService.generateDefault()).thenAnswer(invocation -> {
      UUID current = TenantContextHolder.getTenantId();
      if (TENANT_A.equals(current)) {
        throw new IllegalStateException("模拟租户 A 计算失败");
      }
      succeeded.add(current);
      return new ReplenishmentComputeResult();
    });

    new ReplenishmentComputeJobHandler(computeService, inventoryMapper).execute();

    assertThat(succeeded)
        .as("A 失败后 B 仍要在自己的上下文里正常跑")
        .containsExactly(TENANT_B);
    assertThat(TenantContextHolder.getTenantId()).isNull();
  }

  @Test
  @DisplayName("非 UUID 租户被跳过，不会把脏数据带进计算")
  void nonUuidTenantIsSkipped() {
    when(inventoryMapper.selectDistinctTenantIds())
        .thenReturn(List.of("not-a-uuid", TENANT_A.toString()));
    when(computeService.generateDefault()).thenReturn(new ReplenishmentComputeResult());

    new ReplenishmentComputeJobHandler(computeService, inventoryMapper).execute();

    verify(computeService, times(1)).generateDefault();
    assertThat(TenantContextHolder.getTenantId()).isNull();
  }
}
