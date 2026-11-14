package com.cognora.platform.inventory.service.command;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.cognora.platform.common.data.rw.annotation.Master;
import com.cognora.platform.common.data.rw.annotation.Slave;
import com.cognora.platform.common.exception.ServiceException;
import com.cognora.platform.common.response.ResultCode;
import com.cognora.platform.inventory.domain.StocktakeStatus;
import com.cognora.platform.inventory.domain.entity.InvStocktake;
import com.cognora.platform.inventory.domain.entity.InvStocktakeItem;
import com.cognora.platform.inventory.domain.entity.Inventory;
import com.cognora.platform.inventory.dto.InventoryAdjustRequest;
import com.cognora.platform.inventory.dto.StocktakeCountRequest;
import com.cognora.platform.inventory.dto.StocktakeCreateRequest;
import com.cognora.platform.inventory.dto.StocktakeItemResponse;
import com.cognora.platform.inventory.dto.StocktakeResponse;
import com.cognora.platform.inventory.mapper.InvInventoryMapper;
import com.cognora.platform.inventory.mapper.InvStocktakeItemMapper;
import com.cognora.platform.inventory.mapper.InvStocktakeMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 盘点单据命令服务。
 *
 * <p>核心规则：
 * <ul>
 *   <li>创建时冻结账面数，之后的台账变动不回写单据；</li>
 *   <li>只有 DRAFT 可以录入实盘数，防止审批期间数据漂移；</li>
 *   <li>只有 APPROVED 可以落账，且落账通过既有的
 *       {@link InvInventoryCommandService#adjustInventory} 走同一条
 *       台账更新与流水/成本记账路径，不并行写账本；</li>
 *   <li>落账后单据进入 POSTED 终态，重复落账被状态机拒绝。</li>
 * </ul>
 *
 * @author SCM Platform Team
 * @since 2026-11-07
 */
@Slf4j
@Service
public class InvStocktakeCommandService {

  private static final DateTimeFormatter NO_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

  private final InvStocktakeMapper stocktakeMapper;
  private final InvStocktakeItemMapper itemMapper;
  private final InvInventoryMapper inventoryMapper;
  private final InvInventoryCommandService inventoryCommandService;

  public InvStocktakeCommandService(InvStocktakeMapper stocktakeMapper,
                                    InvStocktakeItemMapper itemMapper,
                                    InvInventoryMapper inventoryMapper,
                                    InvInventoryCommandService inventoryCommandService) {
    this.stocktakeMapper = stocktakeMapper;
    this.itemMapper = itemMapper;
    this.inventoryMapper = inventoryMapper;
    this.inventoryCommandService = inventoryCommandService;
  }

  /**
   * 创建盘点单并冻结账面数。
   *
   * @throws ServiceException 指定 SKU 无台账记录
   */
  @Master(reason = "写操作必须走主库")
  @Transactional(rollbackFor = Exception.class)
  public StocktakeResponse create(StocktakeCreateRequest request) {
    String warehouseId = request.getWarehouseId();
    if (warehouseId == null || warehouseId.isBlank()) {
      throw new ServiceException(ResultCode.BAD_REQUEST.getCode(), HttpStatus.BAD_REQUEST,
          "仓库不能为空");
    }

    List<Inventory> inventories = inventoryMapper.selectList(
        new LambdaQueryWrapper<Inventory>()
            .eq(Inventory::getWarehouseId, warehouseId)
            .eq(Inventory::getDeleted, false)
            .eq(request.getLocationCode() != null && !request.getLocationCode().isBlank(),
                Inventory::getLocationCode, request.getLocationCode())
            .in(!CollectionUtils.isEmpty(request.getSkuIds()), Inventory::getSkuId,
                request.getSkuIds()));

    if (inventories.isEmpty()) {
      throw new ServiceException(ResultCode.NOT_FOUND.getCode(), HttpStatus.NOT_FOUND,
          "该仓库范围内没有可盘点的库存台账");
    }

    InvStocktake head = new InvStocktake();
    head.setStocktakeNo("ST" + LocalDateTime.now().format(NO_FORMAT)
        + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
    head.setWarehouseId(warehouseId);
    head.setLocationCode(request.getLocationCode());
    head.setStatus(StocktakeStatus.DRAFT.name());
    head.setSnapshotAt(LocalDateTime.now());
    head.setItemCount(inventories.size());
    head.setDiffCount(0);
    head.setDiffQuantity(0L);
    head.setRemark(request.getRemark());
    head.setCreatedBy(request.getOperatorId());
    stocktakeMapper.insert(head);

    for (Inventory inventory : inventories) {
      InvStocktakeItem item = new InvStocktakeItem();
      item.setStocktakeId(head.getId());
      item.setSkuId(inventory.getSkuId());
      item.setWarehouseId(inventory.getWarehouseId());
      item.setLocationCode(inventory.getLocationCode());
      item.setBookTotal(nvl(inventory.getTotalStock()));
      item.setBookAvailable(nvl(inventory.getAvailableStock()));
      item.setBookLocked(nvl(inventory.getLockedStock()));
      itemMapper.insert(item);
    }

    return detail(head.getId());
  }

  /**
   * 录入实盘数量（仅 DRAFT）。
   *
   * @throws ServiceException 单据状态非法、SKU 不在本单或数量非法
   */
  @Master(reason = "写操作必须走主库")
  @Transactional(rollbackFor = Exception.class)
  public StocktakeResponse count(String stocktakeId, StocktakeCountRequest request) {
    InvStocktake head = requireHead(stocktakeId);
    requireStatus(head, StocktakeStatus.DRAFT, "只有草稿状态的盘点单可以录入实盘数量");

    List<InvStocktakeItem> items = listItems(stocktakeId);
    for (StocktakeCountRequest.Item input : request.getItems()) {
      Integer counted = input.getCountedQuantity();
      if (counted == null || counted < 0) {
        throw new ServiceException(ResultCode.BAD_REQUEST.getCode(), HttpStatus.BAD_REQUEST,
            "实盘数量必须为非负数: skuId=" + input.getSkuId());
      }
      InvStocktakeItem target = items.stream()
          .filter(item -> item.getSkuId().equals(input.getSkuId()))
          .findFirst()
          .orElseThrow(() -> new ServiceException(
              ResultCode.BAD_REQUEST.getCode(), HttpStatus.BAD_REQUEST,
              "SKU 不在本次盘点范围内: " + input.getSkuId()));

      target.setCountedQuantity(counted);
      target.setDiffQuantity(counted - nvl(target.getBookAvailable()));
      if (input.getRemark() != null) {
        target.setRemark(input.getRemark());
      }
      itemMapper.updateById(target);
    }

    refreshSummary(head);
    return detail(stocktakeId);
  }

  @Master(reason = "写操作必须走主库")
  @Transactional(rollbackFor = Exception.class)
  public StocktakeResponse submit(String stocktakeId, String operatorId) {
    InvStocktake head = requireHead(stocktakeId);
    requireStatus(head, StocktakeStatus.DRAFT, "只有草稿状态的盘点单可以提交");

    List<InvStocktakeItem> items = listItems(stocktakeId);
    long uncounted = items.stream().filter(item -> item.getCountedQuantity() == null).count();
    if (uncounted > 0) {
      throw new ServiceException(ResultCode.BAD_REQUEST.getCode(), HttpStatus.BAD_REQUEST,
          "还有 " + uncounted + " 条明细未录入实盘数量，不能提交");
    }

    transition(head, StocktakeStatus.SUBMITTED, operatorId);
    head.setSubmittedBy(operatorId);
    head.setSubmitTime(LocalDateTime.now());
    stocktakeMapper.updateById(head);
    return detail(stocktakeId);
  }

  @Master(reason = "写操作必须走主库")
  @Transactional(rollbackFor = Exception.class)
  public StocktakeResponse approve(String stocktakeId, String operatorId) {
    InvStocktake head = requireHead(stocktakeId);
    requireStatus(head, StocktakeStatus.SUBMITTED, "只有已提交的盘点单可以审批");

    transition(head, StocktakeStatus.APPROVED, operatorId);
    head.setApprovedBy(operatorId);
    head.setApproveTime(LocalDateTime.now());
    stocktakeMapper.updateById(head);
    return detail(stocktakeId);
  }

  @Master(reason = "写操作必须走主库")
  @Transactional(rollbackFor = Exception.class)
  public StocktakeResponse reject(String stocktakeId, String operatorId, String reason) {
    InvStocktake head = requireHead(stocktakeId);
    requireStatus(head, StocktakeStatus.SUBMITTED, "只有已提交的盘点单可以驳回");

    transition(head, StocktakeStatus.REJECTED, operatorId);
    head.setRemark(appendReason(head.getRemark(), reason));
    stocktakeMapper.updateById(head);
    return detail(stocktakeId);
  }

  @Master(reason = "写操作必须走主库")
  @Transactional(rollbackFor = Exception.class)
  public StocktakeResponse cancel(String stocktakeId, String operatorId) {
    InvStocktake head = requireHead(stocktakeId);
    if (head.getStatus() == null
        || !StocktakeStatus.valueOf(head.getStatus()).canTransitionTo(StocktakeStatus.CANCELLED)) {
      throw new ServiceException(ResultCode.CONFLICT.getCode(), HttpStatus.CONFLICT,
          "当前状态不可取消: " + head.getStatus());
    }

    transition(head, StocktakeStatus.CANCELLED, operatorId);
    stocktakeMapper.updateById(head);
    return detail(stocktakeId);
  }

  /**
   * 落账：把差异写回台账（仅 APPROVED）。
   *
   * <p>逐条调用库存调整（adjustType=6 盘点调整，referenceNo=盘点单号），
   * 因此流水、成本与事件与手工盘点完全同源；差异为 0 的行跳过，不产生空流水。</p>
   *
   * <h2>为什么落账要重算差异，而不是直接用 {@code diffQuantity}</h2>
   * <p>{@code diffQuantity} 是<b>录实盘数那一刻</b>算出来的（实盘 − 当时的账面可用）。
   * 盘点单从创建到落账要经过审批，期间该 SKU 完全可能又发生了出入库。此时若直接
   * 按旧的 {@code diffQuantity} 调整，等于把「实盘时点的差异」叠加到「已经变过的台账」上：</p>
   * <pre>
   * 创建时账面 100，实盘 90        → diff = -10
   * 审批期间出库 5                 → 当前账面 95
   * 落账直接用 -10                 → 台账变成 85（错），实盘明明是 90
   * 落账按 90 - 95 = -5 重算       → 台账变成 90（对）
   * </pre>
   *
   * <p>所以这里以 <b>实盘数</b> 为锚点、用 <b>落账时点的账面数</b> 重算差异。当重算结果与
   * 单据上记录的 {@code diffQuantity} 不一致时，说明审批期间台账漂移过，会记 WARN 并把
   * 漂移量写入明细备注，便于事后追查「为什么落账数与审批数不同」。</p>
   *
   * @throws ServiceException 状态非法
   */
  @Master(reason = "写操作必须走主库")
  @Transactional(rollbackFor = Exception.class)
  public StocktakeResponse post(String stocktakeId, String operatorId) {
    InvStocktake head = requireHead(stocktakeId);
    requireStatus(head, StocktakeStatus.APPROVED, "只有已审批的盘点单可以落账");

    List<InvStocktakeItem> items = listItems(stocktakeId);
    int applied = 0;
    int drifted = 0;
    for (InvStocktakeItem item : items) {
      if (item.getCountedQuantity() == null) {
        // 未盘点：没有实盘锚点，无法重算，也不应有差异。
        continue;
      }

      // Recompute against the ledger as it stands NOW, not as it stood when counted.
      Integer currentBook = currentAvailable(item.getSkuId(), item.getWarehouseId());
      int recalculated = item.getCountedQuantity() - currentBook;
      Integer recorded = item.getDiffQuantity();
      if (recorded == null || recorded != recalculated) {
        drifted++;
        log.warn("盘点落账差异重算：stocktakeNo={}, skuId={}, warehouseId={}, "
                + "审批时差异={}, 落账时账面={}, 重算差异={}（审批期间台账发生过变动）",
            head.getStocktakeNo(), item.getSkuId(), item.getWarehouseId(),
            recorded, currentBook, recalculated);
        item.setRemark(appendDriftNote(item.getRemark(), recorded, currentBook, recalculated));
        itemMapper.updateById(item);
      }

      if (recalculated == 0) {
        continue;
      }
      InventoryAdjustRequest adjust = new InventoryAdjustRequest();
      adjust.setSkuId(item.getSkuId());
      adjust.setWarehouseId(item.getWarehouseId());
      adjust.setQuantity(recalculated);
      adjust.setAdjustType(6);
      adjust.setReferenceNo(head.getStocktakeNo());
      adjust.setReferenceId(stocktakeId);
      adjust.setOperatorId(operatorId);
      adjust.setRemark("盘点差异落账: " + head.getStocktakeNo());
      inventoryCommandService.adjustInventory(adjust);
      applied++;
    }

    transition(head, StocktakeStatus.POSTED, operatorId);
    head.setPostedBy(operatorId);
    head.setPostTime(LocalDateTime.now());
    stocktakeMapper.updateById(head);
    log.info("盘点落账完成 stocktakeNo={} appliedItems={} driftedItems={}",
        head.getStocktakeNo(), applied, drifted);
    return detail(stocktakeId);
  }

  /**
   * 当前可用库存。台账行不存在时视为 0（与 {@code adjustInventory} 的初始化分支一致）。
   *
   * <p>过滤条件与 {@code InvInventoryCommandService#findInventory} 保持一致：同一 SKU + 仓库、
   * 且未软删除。用 {@code LIMIT 1} 兜底，避免历史脏数据（同 SKU 同仓库多行）让 selectOne 抛错。</p>
   */
  private Integer currentAvailable(String skuId, String warehouseId) {
    Inventory inventory = inventoryMapper.selectOne(
        new LambdaQueryWrapper<Inventory>()
            .eq(Inventory::getSkuId, skuId)
            .eq(Inventory::getWarehouseId, warehouseId)
            .eq(Inventory::getDeleted, false)
            .last("LIMIT 1"));
    if (inventory == null || inventory.getAvailableStock() == null) {
      return 0;
    }
    return inventory.getAvailableStock();
  }

  /** 把漂移写进备注，保留原备注。 */
  private String appendDriftNote(String existing, Integer recorded, int currentBook, int recalculated) {
    String note = String.format("落账重算: 审批时差异=%s, 落账时账面=%d, 重算差异=%d",
        recorded, currentBook, recalculated);
    if (existing == null || existing.isBlank()) {
      return note;
    }
    return existing + " | " + note;
  }

  // ---------------------------------------------------------------- helpers

  private InvStocktake requireHead(String stocktakeId) {
    InvStocktake head = stocktakeMapper.selectById(stocktakeId);
    if (head == null) {
      throw new ServiceException(ResultCode.NOT_FOUND.getCode(), HttpStatus.NOT_FOUND,
          "盘点单不存在: " + stocktakeId);
    }
    return head;
  }

  private void requireStatus(InvStocktake head, StocktakeStatus expected, String message) {
    if (!expected.name().equals(head.getStatus())) {
      throw new ServiceException(ResultCode.CONFLICT.getCode(), HttpStatus.CONFLICT,
          message + "，当前状态: " + head.getStatus());
    }
  }

  /** 走状态机校验后再写状态，避免绕过 canTransitionTo。 */
  private void transition(InvStocktake head, StocktakeStatus target, String operatorId) {
    StocktakeStatus current = StocktakeStatus.valueOf(head.getStatus());
    if (!current.canTransitionTo(target)) {
      throw new ServiceException(ResultCode.CONFLICT.getCode(), HttpStatus.CONFLICT,
          "非法的状态流转: " + current + " → " + target);
    }
    head.setStatus(target.name());
    head.setUpdateTime(LocalDateTime.now());
  }

  /** 重算单据头的差异条数与差异合计。 */
  private void refreshSummary(InvStocktake head) {
    List<InvStocktakeItem> items = listItems(head.getId());
    int diffCount = 0;
    long diffQuantity = 0;
    for (InvStocktakeItem item : items) {
      Integer diff = item.getDiffQuantity();
      if (diff != null && diff != 0) {
        diffCount++;
        diffQuantity += diff;
      }
    }
    head.setDiffCount(diffCount);
    head.setDiffQuantity(diffQuantity);
    head.setItemCount(items.size());
    stocktakeMapper.updateById(head);
  }

  private List<InvStocktakeItem> listItems(String stocktakeId) {
    return itemMapper.selectList(
        Wrappers.<InvStocktakeItem>lambdaQuery()
            .eq(InvStocktakeItem::getStocktakeId, stocktakeId)
            .orderByAsc(InvStocktakeItem::getSkuId));
  }

  /** 读取单据详情（含明细）。 */
  @Slave
  public StocktakeResponse detail(String stocktakeId) {
    InvStocktake head = requireHead(stocktakeId);
    List<StocktakeItemResponse> items = new ArrayList<>();
    for (InvStocktakeItem item : listItems(stocktakeId)) {
      items.add(StocktakeItemResponse.from(item));
    }
    return StocktakeResponse.from(head, items);
  }

  private static String appendReason(String origin, String reason) {
    if (reason == null || reason.isBlank()) {
      return origin;
    }
    return origin == null || origin.isBlank() ? reason : origin + " / " + reason;
  }

  private static int nvl(Integer value) {
    return value == null ? 0 : value;
  }
}
