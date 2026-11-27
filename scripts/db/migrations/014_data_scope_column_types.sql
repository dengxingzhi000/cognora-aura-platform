-- ======================================================================
-- 014 — 收敛 data_scope 的列类型
--
-- 目标库：db_permission（sys_role）、db_user（sys_user）
-- 类型：schema 归一 + 数据换算，幂等，可重复执行
--
-- 背景
--   data_scope 有两列、两套编码，而两个脚本对 **同一列** 的类型还互相矛盾：
--
--     sys_role.data_scope
--       * 003_db_permission.sql（init-all-databases 真正执行的建表脚本）
--         建为 SMALLINT + CHECK (data_scope IN (1,2,3,4,5))；
--       * 004_transform_permission_system_multi_tenant.sql 原本写的是
--         ADD COLUMN IF NOT EXISTS data_scope VARCHAR(20) ... CHECK (名称集合)。
--         同一列两种类型，取决于谁先跑：
--           - 先跑 003 的库：004 的 ADD COLUMN IF NOT EXISTS 是空操作，列保持 SMALLINT（正常）；
--           - 先跑 004 的库：列是 VARCHAR，此时
--               * SysUserRoleMapper#MIN(r.data_scope) 取到文本，映射不到 Integer；
--               * 002_rbac_permission.sql 种子里的数字 1/3/4 被 VARCHAR 的 CHECK 拒绝。
--         004 已改为数值码，本脚本负责把已经跑歪的库纠正回来。
--
--     sys_user.data_scope
--       名称编码（VARCHAR），001_db_user.sql 现在直接内联声明，
--       已有环境由本脚本补齐 / 换算。
--
--   口径（与代码、实体、DTO、种子、前端契约一致）：
--     sys_role.data_scope  SMALLINT     1=ALL 2=CUSTOM 3=DEPT 4=DEPT_AND_SUB 5=SELF
--     sys_user.data_scope  VARCHAR(20)  'ALL' 'CUSTOM' 'DEPT' 'DEPT_AND_SUB' 'SELF'
--   数值码 ↔ 名称 的唯一换算入口：
--     com.cognora.platform.common.constant.DataScopeConstants#nameOf
--
-- 做法
--   换算用 ALTER ... TYPE ... USING 表达式一次完成（列类型与取值同时换），
--   避免中间态：先 UPDATE 再改类型会在「往整型列赋文本」时直接报错。
--   USING 表达式里的 CASE 以旧值为准，NULL 与未知值一律落到最严格的「仅本人」。
--   每段都包在 DO 块里并按 information_schema 判断，因此同一个文件可以
--   对着 db_permission 和 db_user 各跑一次：只处理当前库里真实存在的表。
--
-- 幂等
--   全部按 information_schema 判断列是否存在、按列类型决定是否换算；
--   无 DELETE/TRUNCATE；重复执行得到同样的结果。
--
-- 执行（现网）：
--   psql -h <host> -U <user> -d db_permission -v ON_ERROR_STOP=1 \
--        -f scripts/db/migrations/014_data_scope_column_types.sql
--   psql -h <host> -U <user> -d db_user       -v ON_ERROR_STOP=1 \
--        -f scripts/db/migrations/014_data_scope_column_types.sql
-- ======================================================================

-- ----------------------------------------------------------------------
-- 1. sys_role.data_scope：数值码（SMALLINT）
-- ----------------------------------------------------------------------
DO $$
DECLARE
    col_type TEXT;
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'sys_role' AND column_name = 'data_scope'
    ) THEN
        SELECT data_type INTO col_type
        FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'sys_role' AND column_name = 'data_scope';

        IF col_type IN ('character varying', 'text', 'character') THEN
            -- 改列类型前必须先去掉依赖该列的 CHECK，否则 PG 无法把
            -- CHECK(data_scope IN ('ALL',...)) 转成数值语义
            ALTER TABLE sys_role DROP CONSTRAINT IF EXISTS sys_role_data_scope_check;
            ALTER TABLE sys_role DROP CONSTRAINT IF EXISTS chk_data_scope;

            -- 名称 → 数值码；NULL 与无法识别的值一律落到最严格的「仅本人」(5)
            ALTER TABLE sys_role
                ALTER COLUMN data_scope TYPE SMALLINT
                USING (CASE data_scope
                             WHEN 'ALL' THEN 1
                             WHEN 'CUSTOM' THEN 2
                             WHEN 'DEPT' THEN 3
                             WHEN 'DEPT_AND_SUB' THEN 4
                             WHEN 'SELF' THEN 5
                             WHEN '1' THEN 1
                             WHEN '2' THEN 2
                             WHEN '3' THEN 3
                             WHEN '4' THEN 4
                             WHEN '5' THEN 5
                             ELSE 5
                         END);
        END IF;

        -- 数值列上的 CHECK 统一为 1..5（可能是 004 留下的名称集合 CHECK）
        ALTER TABLE sys_role DROP CONSTRAINT IF EXISTS sys_role_data_scope_check;
        ALTER TABLE sys_role DROP CONSTRAINT IF EXISTS chk_data_scope;
        ALTER TABLE sys_role ADD CONSTRAINT chk_data_scope CHECK (data_scope IN (1, 2, 3, 4, 5));

        UPDATE sys_role SET data_scope = 5 WHERE data_scope IS NULL;
        ALTER TABLE sys_role ALTER COLUMN data_scope SET DEFAULT 5;
        ALTER TABLE sys_role ALTER COLUMN data_scope SET NOT NULL;

        EXECUTE 'COMMENT ON COLUMN sys_role.data_scope IS '
            || quote_literal('数据权限:1-全部,2-自定义,3-本部门,4-本部门及以下,5-仅本人');
    ELSIF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'sys_role'
    ) THEN
        -- 表在但列缺失：按数值码建出来
        ALTER TABLE sys_role
            ADD COLUMN data_scope SMALLINT NOT NULL DEFAULT 5
                CONSTRAINT chk_data_scope CHECK (data_scope IN (1, 2, 3, 4, 5));
        EXECUTE 'COMMENT ON COLUMN sys_role.data_scope IS '
            || quote_literal('数据权限:1-全部,2-自定义,3-本部门,4-本部门及以下,5-仅本人');
    END IF;
END
$$;

-- ----------------------------------------------------------------------
-- 2. sys_user.data_scope：名称（VARCHAR(20)）
-- ----------------------------------------------------------------------
DO $$
DECLARE
    col_type TEXT;
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'sys_user'
    ) THEN
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'sys_user' AND column_name = 'data_scope'
        ) THEN
            ALTER TABLE sys_user ADD COLUMN data_scope VARCHAR(20) DEFAULT 'SELF';
        ELSE
            SELECT data_type INTO col_type
            FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'sys_user' AND column_name = 'data_scope';

            IF col_type IN ('smallint', 'integer', 'bigint', 'numeric') THEN
                ALTER TABLE sys_user DROP CONSTRAINT IF EXISTS sys_user_data_scope_check;
                ALTER TABLE sys_user DROP CONSTRAINT IF EXISTS chk_user_data_scope;

                -- 数值码 → 名称
                ALTER TABLE sys_user
                    ALTER COLUMN data_scope TYPE VARCHAR(20)
                    USING (CASE data_scope
                                 WHEN 1 THEN 'ALL'
                                 WHEN 2 THEN 'CUSTOM'
                                 WHEN 3 THEN 'DEPT'
                                 WHEN 4 THEN 'DEPT_AND_SUB'
                                 WHEN 5 THEN 'SELF'
                                 ELSE 'SELF'
                             END);
            END IF;
        END IF;

        ALTER TABLE sys_user DROP CONSTRAINT IF EXISTS sys_user_data_scope_check;
        ALTER TABLE sys_user DROP CONSTRAINT IF EXISTS chk_user_data_scope;
        ALTER TABLE sys_user ADD CONSTRAINT chk_user_data_scope
            CHECK (data_scope IS NULL OR data_scope IN ('ALL', 'CUSTOM', 'DEPT', 'DEPT_AND_SUB', 'SELF'));

        UPDATE sys_user SET data_scope = 'SELF' WHERE data_scope IS NULL;
        ALTER TABLE sys_user ALTER COLUMN data_scope SET DEFAULT 'SELF';

        EXECUTE 'COMMENT ON COLUMN sys_user.data_scope IS '
            || quote_literal('数据权限范围：ALL=全部, DEPT=本部门, DEPT_AND_SUB=本部门及下级, SELF=仅本人, CUSTOM=自定义');
    END IF;
END
$$;
