# 权限目录缺口报告（D1）

> 生成：`py -3 scripts/tools/permission_annotation_map.py`（2026-11-13）  
> 机器可读映射：`docs/superpowers/specs/2026-11-12-permission-mapping.json`  
> 输入：`platform|scm|ecom|lms` 四域 `*Controller.java` + `scripts/db/seed/*.sql`

## 口径

- 端点 = 控制器类级 `@RequestMapping` 与方法级 `@(Get|Post|Put|Delete|Patch)Mapping` 拼出的（HTTP 方法, 路径）。
- `code` 来自 seed 目录行（`scripts/db/seed/*.sql` 中同时含 URL 与 HTTP 方法的行）。`{var}` 占位符按结构匹配：目录 `{id}` 与控制器 `{orderNo}` 等价；目录里的 `*` / `**` 按 Ant 语义匹配。
- 先判豁免，顺序与 `ApiAccessControlFilter` 一致：`whitelist` → `exemptPaths`（先扣 `exemptPathExclusions`）→ `internalSignaturePaths` → `signatureOrPermissionPaths`。
- **缺口 = 未豁免且目录里没有匹配行的端点。** 这类端点当前就会被 `ApiAccessControlFilter` 以 `API_NOT_REGISTERED` 403（`unregistered-endpoint-policy` 默认 `DENY`），本轮不为它们补 `@PreAuthorize`。

## 总览

| 指标 | 数量 |
| --- | ---: |
| 服务（有 controller 的模块） | 24 |
| 端点总数 | 821 |
| seed 目录行 / 唯一 (url, method) 对 | 663 / 662 |
| 已映射到权限码 | 670 |
| 豁免端点 | 100 |
| 其中既命中目录又被豁免 | 24 |
| **目录缺口** | **75** |

`已映射 + 豁免 - 既映射又豁免 + 缺口 = 670 + 100 - 24 + 75 = 821`。「既命中目录又被豁免」的端点（如 `/api/v1/payments/{id}`）运行时先命中豁免，目录行不参与放行。

豁免端点按规则分布：

| 豁免规则 | 数量 |
| --- | ---: |
| `exemptPaths` | 86 |
| `internalSignaturePaths` | 7 |
| `signatureOrPermissionPaths` | 5 |
| `whitelist` | 2 |

## 各服务概览

| 服务 | 端点 | 已映射 | 豁免 | 缺口 |
| --- | ---: | ---: | ---: | ---: |
| `ecom/analytics/service` | 36 | 36 | 0 | 0 |
| `ecom/fulfillment/service` | 12 | 12 | 0 | 0 |
| `ecom/mall/service` | 10 | 2 | 8 | 0 |
| `ecom/member/service` | 14 | 4 | 14 | 0 |
| `ecom/order-center/service` | 8 | 7 | 1 | 0 |
| `ecom/payment/service` | 11 | 5 | 7 | 0 |
| `ecom/promotion/service` | 12 | 2 | 12 | 0 |
| `ecom/search/service` | 8 | 5 | 8 | 0 |
| `lms/tms/service` | 56 | 36 | 0 | 20 |
| `platform/approval/service` | 10 | 10 | 0 | 0 |
| `platform/audit/service` | 10 | 9 | 1 | 0 |
| `platform/auth` | 23 | 0 | 14 | 9 |
| `platform/document/service` | 21 | 21 | 0 | 0 |
| `platform/file/service` | 13 | 13 | 0 | 0 |
| `platform/inventory/service` | 57 | 35 | 6 | 17 |
| `platform/notify/service` | 27 | 13 | 19 | 0 |
| `platform/product/service` | 40 | 40 | 0 | 0 |
| `platform/system/service` | 85 | 52 | 5 | 29 |
| `platform/tenant/service` | 52 | 52 | 0 | 0 |
| `scm/finance/service` | 68 | 68 | 0 | 0 |
| `scm/order/service` | 24 | 24 | 4 | 0 |
| `scm/purchase/service` | 116 | 116 | 1 | 0 |
| `scm/supplier/service` | 62 | 62 | 0 | 0 |
| `scm/warehouse/service` | 46 | 46 | 0 | 0 |

## 缺口明细

### `lms/tms/service`（20 个）

| HTTP | 路径 | Java 方法 | 源文件 |
| --- | --- | --- | --- |
| POST | `/api/v1/tms-exception` | `createCase` | `lms/tms/service/src/main/java/com/cognora/lms/logistics/controller/TmsExceptionController.java` |
| GET | `/api/v1/tms-exception/claims/list` | `pageClaims` | `lms/tms/service/src/main/java/com/cognora/lms/logistics/controller/TmsExceptionController.java` |
| POST | `/api/v1/tms-exception/claims/{id}/transition` | `transitionClaim` | `lms/tms/service/src/main/java/com/cognora/lms/logistics/controller/TmsExceptionController.java` |
| GET | `/api/v1/tms-exception/list` | `pageCases` | `lms/tms/service/src/main/java/com/cognora/lms/logistics/controller/TmsExceptionController.java` |
| POST | `/api/v1/tms-exception/{id}/claim` | `claim` | `lms/tms/service/src/main/java/com/cognora/lms/logistics/controller/TmsExceptionController.java` |
| POST | `/api/v1/tms-exception/{id}/claims` | `createClaim` | `lms/tms/service/src/main/java/com/cognora/lms/logistics/controller/TmsExceptionController.java` |
| POST | `/api/v1/tms-exception/{id}/transition` | `transition` | `lms/tms/service/src/main/java/com/cognora/lms/logistics/controller/TmsExceptionController.java` |
| POST | `/api/v1/tms-freight/quote` | `quote` | `lms/tms/service/src/main/java/com/cognora/lms/logistics/controller/TmsFreightController.java` |
| POST | `/api/v1/tms-freight/reconciliation` | `reconcile` | `lms/tms/service/src/main/java/com/cognora/lms/logistics/controller/TmsFreightController.java` |
| GET | `/api/v1/tms-freight/reconciliation/list` | `pageReconciliations` | `lms/tms/service/src/main/java/com/cognora/lms/logistics/controller/TmsFreightController.java` |
| POST | `/api/v1/tms-freight/reconciliation/{id}/confirm` | `confirmReconciliation` | `lms/tms/service/src/main/java/com/cognora/lms/logistics/controller/TmsFreightController.java` |
| POST | `/api/v1/tms-freight/template` | `createTemplate` | `lms/tms/service/src/main/java/com/cognora/lms/logistics/controller/TmsFreightController.java` |
| GET | `/api/v1/tms-freight/template/carrier/{carrierId}` | `listByCarrier` | `lms/tms/service/src/main/java/com/cognora/lms/logistics/controller/TmsFreightController.java` |
| GET | `/api/v1/tms-freight/template/list` | `pageTemplates` | `lms/tms/service/src/main/java/com/cognora/lms/logistics/controller/TmsFreightController.java` |
| DELETE | `/api/v1/tms-freight/template/{id}` | `deleteTemplate` | `lms/tms/service/src/main/java/com/cognora/lms/logistics/controller/TmsFreightController.java` |
| PUT | `/api/v1/tms-freight/template/{id}` | `updateTemplate` | `lms/tms/service/src/main/java/com/cognora/lms/logistics/controller/TmsFreightController.java` |
| GET | `/api/v1/tms-sla/list` | `pageList` | `lms/tms/service/src/main/java/com/cognora/lms/logistics/controller/TmsSlaController.java` |
| POST | `/api/v1/tms-sla/scan` | `scan` | `lms/tms/service/src/main/java/com/cognora/lms/logistics/controller/TmsSlaController.java` |
| POST | `/api/v1/tms-sla/{id}/ack` | `acknowledge` | `lms/tms/service/src/main/java/com/cognora/lms/logistics/controller/TmsSlaController.java` |
| POST | `/api/v1/tms-tracking/callback/{carrierCode}` | `callback` | `lms/tms/service/src/main/java/com/cognora/lms/logistics/controller/TmsTrackingController.java` |

### `platform/auth`（9 个）

| HTTP | 路径 | Java 方法 | 源文件 |
| --- | --- | --- | --- |
| POST | `/api/auth/force-logout/{userId}` | `forceLogout` | `platform/auth/src/main/java/com/cognora/platform/auth/controller/SysAuthController.java` |
| POST | `/api/auth/webauthn/authenticate/challenge` | `generateAuthenticationChallenge` | `platform/auth/src/main/java/com/cognora/platform/auth/controller/WebAuthnAuthenticationController.java` |
| POST | `/api/auth/webauthn/authenticate/verify` | `authenticateAndUpgradeToken` | `platform/auth/src/main/java/com/cognora/platform/auth/controller/WebAuthnAuthenticationController.java` |
| GET | `/api/auth/webauthn/credentials` | `listCredentials` | `platform/auth/src/main/java/com/cognora/platform/auth/controller/WebAuthnCredentialController.java` |
| DELETE | `/api/auth/webauthn/credentials/{credentialId}` | `deleteCredential` | `platform/auth/src/main/java/com/cognora/platform/auth/controller/WebAuthnCredentialController.java` |
| PUT | `/api/auth/webauthn/credentials/{credentialId}/deactivate` | `deactivateCredential` | `platform/auth/src/main/java/com/cognora/platform/auth/controller/WebAuthnCredentialController.java` |
| PUT | `/api/auth/webauthn/credentials/{credentialId}/name` | `updateDeviceName` | `platform/auth/src/main/java/com/cognora/platform/auth/controller/WebAuthnCredentialController.java` |
| POST | `/api/auth/webauthn/register/challenge` | `generateRegistrationChallenge` | `platform/auth/src/main/java/com/cognora/platform/auth/controller/WebAuthnRegistrationController.java` |
| POST | `/api/auth/webauthn/register/verify` | `registerCredential` | `platform/auth/src/main/java/com/cognora/platform/auth/controller/WebAuthnRegistrationController.java` |

### `platform/inventory/service`（17 个）

| HTTP | 路径 | Java 方法 | 源文件 |
| --- | --- | --- | --- |
| POST | `/api/v1/inventory/batches/fefo-preview` | `preview` | `platform/inventory/service/src/main/java/com/cognora/platform/inventory/controller/InvBatchController.java` |
| POST | `/api/v1/inventory/batches/query` | `query` | `platform/inventory/service/src/main/java/com/cognora/platform/inventory/controller/InvBatchController.java` |
| POST | `/api/v1/inventory/batches/stats` | `stats` | `platform/inventory/service/src/main/java/com/cognora/platform/inventory/controller/InvBatchController.java` |
| PUT | `/api/v1/inventory/safety-stock` | `updateSafetyStock` | `platform/inventory/service/src/main/java/com/cognora/platform/inventory/controller/InvInventoryController.java` |
| POST | `/api/v1/inventory/stocktakes` | `create` | `platform/inventory/service/src/main/java/com/cognora/platform/inventory/controller/InvStocktakeController.java` |
| POST | `/api/v1/inventory/stocktakes/query` | `query` | `platform/inventory/service/src/main/java/com/cognora/platform/inventory/controller/InvStocktakeController.java` |
| GET | `/api/v1/inventory/stocktakes/{id}` | `detail` | `platform/inventory/service/src/main/java/com/cognora/platform/inventory/controller/InvStocktakeController.java` |
| PUT | `/api/v1/inventory/stocktakes/{id}/approve` | `approve` | `platform/inventory/service/src/main/java/com/cognora/platform/inventory/controller/InvStocktakeController.java` |
| PUT | `/api/v1/inventory/stocktakes/{id}/cancel` | `cancel` | `platform/inventory/service/src/main/java/com/cognora/platform/inventory/controller/InvStocktakeController.java` |
| PUT | `/api/v1/inventory/stocktakes/{id}/count` | `count` | `platform/inventory/service/src/main/java/com/cognora/platform/inventory/controller/InvStocktakeController.java` |
| GET | `/api/v1/inventory/stocktakes/{id}/items` | `items` | `platform/inventory/service/src/main/java/com/cognora/platform/inventory/controller/InvStocktakeController.java` |
| PUT | `/api/v1/inventory/stocktakes/{id}/post` | `post` | `platform/inventory/service/src/main/java/com/cognora/platform/inventory/controller/InvStocktakeController.java` |
| PUT | `/api/v1/inventory/stocktakes/{id}/reject` | `reject` | `platform/inventory/service/src/main/java/com/cognora/platform/inventory/controller/InvStocktakeController.java` |
| PUT | `/api/v1/inventory/stocktakes/{id}/submit` | `submit` | `platform/inventory/service/src/main/java/com/cognora/platform/inventory/controller/InvStocktakeController.java` |
| POST | `/api/v1/inventory/valuation/export` | `export` | `platform/inventory/service/src/main/java/com/cognora/platform/inventory/controller/InventoryValuationController.java` |
| POST | `/api/v1/inventory/valuation/query` | `query` | `platform/inventory/service/src/main/java/com/cognora/platform/inventory/controller/InventoryValuationController.java` |
| POST | `/api/v1/inventory/valuation/stats` | `stats` | `platform/inventory/service/src/main/java/com/cognora/platform/inventory/controller/InventoryValuationController.java` |

### `platform/system/service`（29 个）

| HTTP | 路径 | Java 方法 | 源文件 |
| --- | --- | --- | --- |
| DELETE | `/api/system/depts/{id}` | `delete` | `platform/system/service/src/main/java/com/cognora/platform/system/controller/SysDeptController.java` |
| PUT | `/api/system/depts/{id}` | `update` | `platform/system/service/src/main/java/com/cognora/platform/system/controller/SysDeptController.java` |
| POST | `/api/system/dicts/items` | `createItem` | `platform/system/service/src/main/java/com/cognora/platform/system/controller/SysDictController.java` |
| PUT | `/api/system/dicts/items` | `updateItem` | `platform/system/service/src/main/java/com/cognora/platform/system/controller/SysDictController.java` |
| GET | `/api/system/dicts/items/{dictCode}` | `listItems` | `platform/system/service/src/main/java/com/cognora/platform/system/controller/SysDictController.java` |
| DELETE | `/api/system/dicts/items/{id}` | `deleteItem` | `platform/system/service/src/main/java/com/cognora/platform/system/controller/SysDictController.java` |
| PUT | `/api/system/dicts/types` | `updateType` | `platform/system/service/src/main/java/com/cognora/platform/system/controller/SysDictController.java` |
| GET | `/api/system/dicts/types/code/{dictCode}` | `getTypeByCode` | `platform/system/service/src/main/java/com/cognora/platform/system/controller/SysDictController.java` |
| DELETE | `/api/system/dicts/types/{id}` | `deleteType` | `platform/system/service/src/main/java/com/cognora/platform/system/controller/SysDictController.java` |
| GET | `/api/system/permissions/api` | `findApiPermissions` | `platform/system/service/src/main/java/com/cognora/platform/system/controller/SysPermissionController.java` |
| DELETE | `/api/system/permissions/{id}` | `delete` | `platform/system/service/src/main/java/com/cognora/platform/system/controller/SysPermissionController.java` |
| GET | `/api/system/permissions/{id}` | `getById` | `platform/system/service/src/main/java/com/cognora/platform/system/controller/SysPermissionController.java` |
| PUT | `/api/system/permissions/{id}` | `update` | `platform/system/service/src/main/java/com/cognora/platform/system/controller/SysPermissionController.java` |
| GET | `/api/system/roles/all` | `listAll` | `platform/system/service/src/main/java/com/cognora/platform/system/controller/SysRoleController.java` |
| DELETE | `/api/system/roles/{id}` | `delete` | `platform/system/service/src/main/java/com/cognora/platform/system/controller/SysRoleController.java` |
| PUT | `/api/system/roles/{id}` | `update` | `platform/system/service/src/main/java/com/cognora/platform/system/controller/SysRoleController.java` |
| POST | `/api/system/roles/{id}/grant-permissions` | `grantPermissions` | `platform/system/service/src/main/java/com/cognora/platform/system/controller/SysRoleController.java` |
| GET | `/api/system/roles/{id}/permissions` | `getRolePermissions` | `platform/system/service/src/main/java/com/cognora/platform/system/controller/SysRoleController.java` |
| GET | `/api/system/status/actions` | `getAvailableActions` | `platform/system/service/src/main/java/com/cognora/platform/system/controller/SysStatusDictController.java` |
| GET | `/api/system/status/check` | `checkTransition` | `platform/system/service/src/main/java/com/cognora/platform/system/controller/SysStatusDictController.java` |
| PUT | `/api/system/status/dict` | `updateStatusDict` | `platform/system/service/src/main/java/com/cognora/platform/system/controller/SysStatusDictController.java` |
| GET | `/api/system/status/dict/{bizType}` | `listStatusDict` | `platform/system/service/src/main/java/com/cognora/platform/system/controller/SysStatusDictController.java` |
| GET | `/api/system/status/dict/{bizType}/{statusCode}` | `getStatusByCode` | `platform/system/service/src/main/java/com/cognora/platform/system/controller/SysStatusDictController.java` |
| DELETE | `/api/system/status/dict/{id}` | `deleteStatusDict` | `platform/system/service/src/main/java/com/cognora/platform/system/controller/SysStatusDictController.java` |
| GET | `/api/system/status/next-statuses` | `getValidNextStatuses` | `platform/system/service/src/main/java/com/cognora/platform/system/controller/SysStatusDictController.java` |
| POST | `/api/system/status/transitions` | `createTransition` | `platform/system/service/src/main/java/com/cognora/platform/system/controller/SysStatusDictController.java` |
| PUT | `/api/system/status/transitions` | `updateTransition` | `platform/system/service/src/main/java/com/cognora/platform/system/controller/SysStatusDictController.java` |
| GET | `/api/system/status/transitions/{bizType}` | `listTransitions` | `platform/system/service/src/main/java/com/cognora/platform/system/controller/SysStatusDictController.java` |
| DELETE | `/api/system/status/transitions/{id}` | `deleteTransition` | `platform/system/service/src/main/java/com/cognora/platform/system/controller/SysStatusDictController.java` |

## 目录数据备注

- `POST /api/v1/inventory/alerts/scan`（`inventory:alert:scan`）重复登记：`019_api_permission_coverage.sql` 与 `023_inventory_permissions.sql`，按一条处理
