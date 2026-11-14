"""Append the new inventory endpoints + schemas to the OpenAPI snapshot.

TARGET: docs/api/openapi-scm-inventory.json

Why a script: the snapshot is hand-maintained JSON; regenerating it by hand is
error-prone. This patch is additive only 鈥?existing paths/schemas are untouched
鈥?and it restores the two `0.000001` literals that json.dumps would otherwise
rewrite as `1e-06` (keeps the diff limited to genuinely new content).
"""
import json
import pathlib

SNAPSHOT = pathlib.Path("docs/api/openapi-scm-inventory.json")

INT32 = {"type": "integer", "format": "int32"}
INT64 = {"type": "integer", "format": "int64"}
DATE = {"type": "string", "format": "date"}
DATETIME = {"type": "string", "format": "date-time"}


def ref(name):
    return {"$ref": f"#/components/schemas/{name}"}


def api_response(schema_name):
    return {
        "type": "object",
        "properties": {
            "code": INT32,
            "message": {"type": "string"},
            "data": ref(schema_name),
            "timestamp": INT64,
        },
    }


def page_of(schema_name):
    return {
        "type": "object",
        "properties": {
            "records": {"type": "array", "items": ref(schema_name)},
            "total": INT64,
            "size": INT64,
            "current": INT64,
        },
    }


def body(schema_name):
    return {
        "content": {"application/json": {"schema": ref(schema_name)}},
        "required": True,
    }


def response(schema_name):
    return {
        "200": {
            "description": "OK",
            "content": {"*/*": {"schema": ref(f"ApiResponse{schema_name}")}},
        }
    }


def op(tag, summary, description, operation_id, schema_name,
       with_body=None, parameters=None, content_type="*/*"):
    operation = {
        "tags": [tag],
        "summary": summary,
        "description": description,
        "operationId": operation_id,
    }
    if parameters:
        operation["parameters"] = parameters
    if with_body:
        operation["requestBody"] = body(with_body)
    operation["responses"] = {
        "200": {
            "description": "OK",
            "content": {content_type: {"schema": ref(f"ApiResponse{schema_name}")}},
        }
    }
    return operation


def qp(name, description=None, required=False, schema=None):
    param = {
        "name": name,
        "in": "query",
        "required": required,
        "schema": schema or {"type": "string"},
    }
    if description:
        param["description"] = description
    return param


def path_param(name, description=None):
    param = {"name": name, "in": "path", "required": True,
             "schema": {"type": "string"}}
    if description:
        param["description"] = description
    return param


def build_new_paths():
    stocktake_id = path_param("id", "鐩樼偣鍗?ID")
    operator = qp("operatorId", "鎿嶄綔浜?ID")

    return {
        # ---------------- 瀹夊叏搴撳瓨 ----------------
        "/api/v1/inventory/safety-stock": {
            "put": op(
                "搴撳瓨绠＄悊",
                "璁剧疆瀹夊叏搴撳瓨",
                "涓烘寚瀹?SKU 涓庝粨搴撹缃畨鍏ㄥ簱瀛橀槇鍊硷紙鍛婅涓庢按浣嶅垽瀹氱殑鍞竴鏉ユ簮锛夛紝鍙€夊悓鏃惰缃渶澶у簱瀛樹笂闄愩€?,
                "updateSafetyStock",
                "InventoryResponse",
                with_body="InventorySafetyStockRequest",
                parameters=[
                    qp("skuId", "SKU ID", required=True),
                    qp("warehouseId", "浠撳簱 ID", required=True),
                ],
            )
        },
        # ---------------- 鎵规鎶ヨ〃 ----------------
        "/api/v1/inventory/batches/query": {
            "post": op(
                "搴撳瓨鎵规",
                "鍒嗛〉鏌ヨ鎵规",
                "鎸?SKU銆佷粨搴撱€佹壒娆″彿銆佽川閲忕姸鎬併€佹壒娆＄姸鎬佷笌鏁堟湡鍖洪棿鍒嗛〉鏌ヨ鎵规锛岄檮鍓╀綑澶╂暟涓庡墿浣欓噾棰濄€?,
                "queryBatch",
                "PageInvBatchResponse",
                with_body="InvBatchQueryRequest",
            )
        },
        "/api/v1/inventory/batches/stats": {
            "post": op(
                "搴撳瓨鎵规",
                "鎵规鏁堟湡姹囨€?,
                "姹囨€诲悓涓€绛涢€夋潯浠朵笅鐨勮繃鏈?涓存湡/鑰楀敖鎵规鏁伴噺涓庡墿浣欓噾棰濓紝鐢ㄤ簬鎶ヨ〃 KPI銆?,
                "batchStats",
                "InvBatchStatsResponse",
                with_body="InvBatchQueryRequest",
            )
        },
        # ---------------- 浼板€兼姤琛?----------------
        "/api/v1/inventory/valuation/query": {
            "post": op(
                "搴撳瓨浼板€?,
                "鍒嗛〉鏌ヨ搴撳瓨浼板€?,
                "鎸?SKU銆佷粨搴撱€佸簱浣嶄笌搴撳瓨鐘舵€佸垎椤垫煡璇紝杩斿洖鏁伴噺銆侀噾棰濅笌鍛嗘粸澶╂暟銆?,
                "queryValuation",
                "PageInventoryValuationRow",
                with_body="InventoryValuationQueryRequest",
            )
        },
        "/api/v1/inventory/valuation/stats": {
            "post": op(
                "搴撳瓨浼板€?,
                "搴撳瓨浼板€兼眹鎬?,
                "姹囨€诲悓涓€绛涢€夋潯浠朵笅鐨勬暟閲忎笌閲戦锛屽彲閫夋寜鍛嗘粸澶╂暟缁熻鍛嗘粸鍗犵敤銆?,
                "valuationStats",
                "InventoryValuationStats",
                with_body="InventoryValuationQueryRequest",
            )
        },
        "/api/v1/inventory/valuation/export": {
            "post": {
                "tags": ["搴撳瓨浼板€?],
                "summary": "瀵煎嚭搴撳瓨浼板€?CSV",
                "description": "鎸夌瓫閫夋潯浠跺鍑?CSV锛圲TF-8 BOM锛夛紝鍗曟鏈€澶?20000 琛屻€?,
                "operationId": "exportValuation",
                "requestBody": body("InventoryValuationQueryRequest"),
                "responses": {
                    "200": {
                        "description": "OK",
                        "content": {
                            "text/csv": {
                                "schema": {"type": "string", "format": "byte"}
                            }
                        },
                    }
                },
            }
        },
        # ---------------- 鐩樼偣 ----------------
        "/api/v1/inventory/stocktakes": {
            "post": op(
                "搴撳瓨鐩樼偣",
                "鍒涘缓鐩樼偣鍗?,
                "鎸変粨搴?搴撲綅/SKU 鑼冨洿鍒涘缓鐩樼偣鍗曪紝骞跺喕缁撳垱寤烘椂鐐圭殑璐﹂潰鏁伴噺銆?,
                "createStocktake",
                "StocktakeResponse",
                with_body="StocktakeCreateRequest",
            )
        },
        "/api/v1/inventory/stocktakes/query": {
            "post": op(
                "搴撳瓨鐩樼偣",
                "鍒嗛〉鏌ヨ鐩樼偣鍗?,
                "鎸変粨搴撱€佺姸鎬佷笌鍗曞彿鍒嗛〉鏌ヨ鐩樼偣鍗曟嵁澶淬€?,
                "queryStocktakes",
                "PageStocktakeResponse",
                with_body="StocktakeQueryRequest",
            )
        },
        "/api/v1/inventory/stocktakes/{id}": {
            "get": op(
                "搴撳瓨鐩樼偣",
                "鐩樼偣鍗曡鎯?,
                "杩斿洖鐩樼偣鍗曟嵁澶翠笌鍏ㄩ儴鏄庣粏銆?,
                "stocktakeDetail",
                "StocktakeResponse",
                parameters=[stocktake_id],
            )
        },
        "/api/v1/inventory/stocktakes/{id}/items": {
            "get": op(
                "搴撳瓨鐩樼偣",
                "鐩樼偣鍗曟槑缁?,
                "鍗曠嫭璇诲彇鐩樼偣鏄庣粏锛屼究浜庡ぇ鍗曟嵁鍒嗛〉灞曠ず銆?,
                "stocktakeItems",
                "ListStocktakeItemResponse",
                parameters=[stocktake_id],
            )
        },
        "/api/v1/inventory/stocktakes/{id}/count": {
            "put": op(
                "搴撳瓨鐩樼偣",
                "褰曞叆瀹炵洏鏁伴噺",
                "浠呰崏绋跨姸鎬佸彲褰曞叆锛屽綍鍏ュ悗鑷姩璁＄畻宸紓銆?,
                "countStocktake",
                "StocktakeResponse",
                with_body="StocktakeCountRequest",
                parameters=[stocktake_id],
            )
        },
        "/api/v1/inventory/stocktakes/{id}/submit": {
            "put": op(
                "搴撳瓨鐩樼偣",
                "鎻愪氦鐩樼偣鍗?,
                "鍏ㄩ儴鏄庣粏褰曞叆瀹屾垚鍚庢彁浜わ紝杩涘叆寰呭鎵广€?,
                "submitStocktake",
                "StocktakeResponse",
                parameters=[stocktake_id, operator],
            )
        },
        "/api/v1/inventory/stocktakes/{id}/approve": {
            "put": op(
                "搴撳瓨鐩樼偣",
                "瀹℃壒閫氳繃",
                "瀹℃壒宸叉彁浜ょ殑鐩樼偣鍗曪紝閫氳繃鍚庡厑璁歌惤璐︺€?,
                "approveStocktake",
                "StocktakeResponse",
                parameters=[stocktake_id, operator],
            )
        },
        "/api/v1/inventory/stocktakes/{id}/reject": {
            "put": op(
                "搴撳瓨鐩樼偣",
                "椹冲洖鐩樼偣鍗?,
                "椹冲洖宸叉彁浜ょ殑鐩樼偣鍗曪紝鍗曟嵁杩涘叆缁堟€?REJECTED銆?,
                "rejectStocktake",
                "StocktakeResponse",
                parameters=[
                    stocktake_id,
                    operator,
                    qp("reason", "椹冲洖鍘熷洜"),
                ],
            )
        },
        "/api/v1/inventory/stocktakes/{id}/cancel": {
            "put": op(
                "搴撳瓨鐩樼偣",
                "鍙栨秷鐩樼偣鍗?,
                "鍙栨秷灏氭湭钀借处鐨勭洏鐐瑰崟銆?,
                "cancelStocktake",
                "StocktakeResponse",
                parameters=[stocktake_id, operator],
            )
        },
        "/api/v1/inventory/stocktakes/{id}/post": {
            "put": op(
                "搴撳瓨鐩樼偣",
                "鐩樼偣宸紓钀借处",
                "浠呭凡瀹℃壒鍗曟嵁鍙惤璐︼細鎸夊樊寮傞€愭潯鍐欏簱瀛樿皟鏁翠笌娴佹按锛屾垚鍔熷悗鍗曟嵁杩涘叆 POSTED銆?,
                "postStocktake",
                "StocktakeResponse",
                parameters=[stocktake_id, operator],
            )
        },
    }


def build_new_schemas():
    return {
        # ---------- 瀹夊叏搴撳瓨 ----------
        "InventorySafetyStockRequest": {
            "type": "object",
            "properties": {
                "safetyStock": dict(INT32, description="瀹夊叏搴撳瓨闃堝€硷紝蹇呴』 >= 0"),
                "maxStock": dict(INT32, description="鏈€澶у簱瀛橈紝鍙€夛紱涓虹┖琛ㄧず涓嶄慨鏀?),
                "operatorId": {"type": "string"},
                "remark": {"type": "string"},
            },
            "required": ["safetyStock"],
        },
        # ---------- 鎵规 ----------
        "InvBatchQueryRequest": {
            "type": "object",
            "properties": {
                "skuId": {"type": "string"},
                "warehouseId": {"type": "string"},
                "batchCode": {"type": "string", "description": "鎵规鍙凤紝鏀寔妯＄硦鍖归厤"},
                "qualityStatus": dict(INT32, description="1-鍚堟牸 2-寰呮 3-涓嶅悎鏍?4-闅旂"),
                "status": dict(INT32, description="0-宸茶€楀敖 1-姝ｅ父 2-涓存湡 3-宸茶繃鏈?),
                "expiryFrom": DATE,
                "expiryTo": DATE,
                "withinDays": dict(INT32, description="浠婂ぉ璧?N 澶╁唴鍒版湡锛沞xpiryTo 鏈寚瀹氭椂鐢熸晥"),
                "hasAvailable": {"type": "boolean"},
                "page": dict(INT32, minimum=1),
                "size": dict(INT32, minimum=1, maximum=500),
            },
        },
        "InvBatchResponse": {
            "type": "object",
            "properties": {
                "id": {"type": "string"},
                "batchCode": {"type": "string"},
                "skuId": {"type": "string"},
                "warehouseId": {"type": "string"},
                "locationCode": {"type": "string"},
                "productionDate": DATE,
                "expiryDate": DATE,
                "shelfLifeDays": INT32,
                "qualityStatus": INT32,
                "status": INT32,
                "totalQuantity": INT32,
                "availableQuantity": INT32,
                "lockedQuantity": INT32,
                "damagedQuantity": INT32,
                "unitCost": {"type": "number"},
                "daysToExpiry": dict(INT64, description="璐熸暟琛ㄧず宸茶繃鏈燂紝null 琛ㄧず鏃犳湁鏁堟湡"),
                "remainingValue": {"type": "number"},
                "createTime": DATETIME,
                "updateTime": DATETIME,
            },
        },
        "PageInvBatchResponse": page_of("InvBatchResponse"),
        "ApiResponsePageInvBatchResponse": api_response("PageInvBatchResponse"),
        "InvBatchStatsResponse": {
            "type": "object",
            "properties": {
                "totalBatches": INT64,
                "expiredBatches": INT64,
                "nearExpiryBatches": INT64,
                "depletedBatches": INT64,
                "availableQuantity": INT64,
                "availableValue": {"type": "number"},
                "expiredQuantity": INT64,
                "expiredValue": {"type": "number"},
            },
        },
        "ApiResponseInvBatchStatsResponse": api_response("InvBatchStatsResponse"),
        # ---------- 浼板€?----------
        "InventoryValuationQueryRequest": {
            "type": "object",
            "properties": {
                "skuId": {"type": "string"},
                "warehouseId": {"type": "string"},
                "locationCode": {"type": "string"},
                "stockStatus": {
                    "type": "string",
                    "description": "NORMAL / LOW_STOCK / OUT_OF_STOCK",
                },
                "agingDays": dict(INT32, description="鍛嗘粸闃堝€硷紙澶╋級"),
                "page": dict(INT32, minimum=1),
                "size": dict(INT32, minimum=1, maximum=500),
            },
        },
        "InventoryValuationRow": {
            "type": "object",
            "properties": {
                "skuId": {"type": "string"},
                "warehouseId": {"type": "string"},
                "locationCode": {"type": "string"},
                "totalStock": INT32,
                "availableStock": INT32,
                "lockedStock": INT32,
                "damagedStock": INT32,
                "safetyStock": INT32,
                "averageCost": {"type": "number"},
                "stockValue": {"type": "number"},
                "agingDays": INT64,
                "stockStatus": {"type": "string"},
            },
        },
        "PageInventoryValuationRow": page_of("InventoryValuationRow"),
        "ApiResponsePageInventoryValuationRow": api_response("PageInventoryValuationRow"),
        "InventoryValuationStats": {
            "type": "object",
            "properties": {
                "rowCount": INT64,
                "totalStockQuantity": INT64,
                "availableStockQuantity": INT64,
                "lockedStockQuantity": INT64,
                "damagedStockQuantity": INT64,
                "totalStockValue": {"type": "number"},
                "slowMovingCount": INT64,
                "slowMovingValue": {"type": "number"},
            },
        },
        "ApiResponseInventoryValuationStats": api_response("InventoryValuationStats"),
        # ---------- 鐩樼偣 ----------
        "StocktakeCreateRequest": {
            "type": "object",
            "properties": {
                "warehouseId": {"type": "string"},
                "locationCode": {"type": "string"},
                "skuIds": {"type": "array", "items": {"type": "string"}},
                "operatorId": {"type": "string"},
                "remark": {"type": "string"},
            },
            "required": ["warehouseId"],
        },
        "StocktakeCountRequest": {
            "type": "object",
            "properties": {
                "items": {
                    "type": "array",
                    "items": ref("StocktakeCountItem"),
                },
                "operatorId": {"type": "string"},
            },
            "required": ["items"],
        },
        "StocktakeCountItem": {
            "type": "object",
            "properties": {
                "skuId": {"type": "string"},
                "countedQuantity": dict(INT32, minimum=0),
                "remark": {"type": "string"},
            },
            "required": ["skuId", "countedQuantity"],
        },
        "StocktakeQueryRequest": {
            "type": "object",
            "properties": {
                "warehouseId": {"type": "string"},
                "status": {
                    "type": "string",
                    "description": "DRAFT/SUBMITTED/APPROVED/POSTED/REJECTED/CANCELLED",
                },
                "stocktakeNo": {"type": "string"},
                "page": dict(INT32, minimum=1),
                "size": dict(INT32, minimum=1, maximum=500),
            },
        },
        "StocktakeItemResponse": {
            "type": "object",
            "properties": {
                "id": {"type": "string"},
                "skuId": {"type": "string"},
                "warehouseId": {"type": "string"},
                "locationCode": {"type": "string"},
                "bookTotal": INT32,
                "bookAvailable": INT32,
                "bookLocked": INT32,
                "countedQuantity": INT32,
                "diffQuantity": INT32,
                "remark": {"type": "string"},
            },
        },
        "StocktakeResponse": {
            "type": "object",
            "properties": {
                "id": {"type": "string"},
                "stocktakeNo": {"type": "string"},
                "warehouseId": {"type": "string"},
                "locationCode": {"type": "string"},
                "status": {"type": "string"},
                "snapshotAt": DATETIME,
                "itemCount": INT32,
                "diffCount": INT32,
                "diffQuantity": INT64,
                "remark": {"type": "string"},
                "createdBy": {"type": "string"},
                "submittedBy": {"type": "string"},
                "approvedBy": {"type": "string"},
                "postedBy": {"type": "string"},
                "submitTime": DATETIME,
                "approveTime": DATETIME,
                "postTime": DATETIME,
                "createTime": DATETIME,
                "items": {"type": "array", "items": ref("StocktakeItemResponse")},
            },
        },
        "PageStocktakeResponse": page_of("StocktakeResponse"),
        "ApiResponsePageStocktakeResponse": api_response("PageStocktakeResponse"),
        "ApiResponseStocktakeResponse": api_response("StocktakeResponse"),
        "ApiResponseListStocktakeItemResponse": api_response("ListStocktakeItemResponse"),
        "ListStocktakeItemResponse": {
            "type": "array",
            "items": ref("StocktakeItemResponse"),
        },
    }


def main():
    original = SNAPSHOT.read_text(encoding="utf-8")
    data = json.loads(original)

    new_paths = build_new_paths()
    new_schemas = build_new_schemas()

    collisions = set(new_paths) & set(data["paths"])
    if collisions:
        raise SystemExit(f"refusing to overwrite existing paths: {sorted(collisions)}")
    schema_collisions = set(new_schemas) & set(data["components"]["schemas"])
    if schema_collisions:
        raise SystemExit(f"refusing to overwrite existing schemas: {sorted(schema_collisions)}")

    data["paths"].update(new_paths)
    data["components"]["schemas"].update(new_schemas)

    out = json.dumps(data, indent=2, ensure_ascii=False)
    # restore literals json.dumps normalises, so the diff only shows new content
    out = out.replace('"multipleOf": 1e-06', '"multipleOf": 0.000001')
    # The checked-in snapshot uses LF; Windows text mode would otherwise rewrite
    # every line and bury the real additions in a whole-file diff.
    out = out.replace("\r\n", "\n")
    SNAPSHOT.write_bytes(out.encode("utf-8"))

    print(f"paths: {len(data['paths'])} (+{len(new_paths)})")
    print(f"schemas: {len(data['components']['schemas'])} (+{len(new_schemas)})")


if __name__ == "__main__":
    main()
