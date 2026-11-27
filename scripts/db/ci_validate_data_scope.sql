-- ======================================================================
-- CI 校验：data_scope 的列类型与取值口径
--
-- 目标库：db_permission（sys_role）、db_user（sys_user）——两个库各跑一次
--
-- 口径
--   sys_role.data_scope  SMALLINT     1=ALL 2=CUSTOM 3=DEPT 4=DEPT_AND_SUB 5=SELF
--   sys_user.data_scope  VARCHAR(20)  'ALL' 'CUSTOM' 'DEPT' 'DEPT_AND_SUB' 'SELF'
--
-- 失败条件
--   * 目标列不存在；
--   * 类型与口径不符（角色列不是整型、用户列不是字符型）；
--   * 角色列里出现了名称值、用户列里出现了数值码；
--   * 角色列的值不在 1..5 之内。
--
-- 说明
--   角色级与用户级本来就是两套编码（对应 DataScopeConstants 的数值码与名称），
--   本脚本校验的是「每列各自的类型与取值」，不是「两列必须相同」。
-- ======================================================================

DO $$
DECLARE
    col_type TEXT;
    bad_rows BIGINT;
BEGIN
    -- ---------------- sys_role.data_scope：整型 + 1..5 ----------------
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'sys_role'
    ) THEN
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'sys_role' AND column_name = 'data_scope'
        ) THEN
            RAISE EXCEPTION 'sys_role.data_scope 列不存在';
        END IF;

        SELECT data_type INTO col_type
        FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'sys_role' AND column_name = 'data_scope';
        IF col_type NOT IN ('smallint', 'integer', 'bigint') THEN
            RAISE EXCEPTION 'sys_role.data_scope 类型应为整型，实际为 %', col_type;
        END IF;

        SELECT count(*) INTO bad_rows
        FROM sys_role
        WHERE data_scope IS NOT NULL AND data_scope NOT IN (1, 2, 3, 4, 5);
        IF bad_rows > 0 THEN
            RAISE EXCEPTION 'sys_role.data_scope 有 % 行取值不在 1..5 内（应为数值码）', bad_rows;
        END IF;
    END IF;

    -- ---------------- sys_user.data_scope：字符型 + 名称 ----------------
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'sys_user'
    ) THEN
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'sys_user' AND column_name = 'data_scope'
        ) THEN
            RAISE EXCEPTION 'sys_user.data_scope 列不存在';
        END IF;

        SELECT data_type INTO col_type
        FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'sys_user' AND column_name = 'data_scope';
        IF col_type NOT IN ('character varying', 'text', 'character') THEN
            RAISE EXCEPTION 'sys_user.data_scope 类型应为字符型，实际为 %', col_type;
        END IF;

        SELECT count(*) INTO bad_rows
        FROM sys_user
        WHERE data_scope IS NOT NULL
          AND data_scope NOT IN ('ALL', 'CUSTOM', 'DEPT', 'DEPT_AND_SUB', 'SELF');
        IF bad_rows > 0 THEN
            RAISE EXCEPTION 'sys_user.data_scope 有 % 行取值不是名称编码', bad_rows;
        END IF;
    END IF;

    RAISE NOTICE 'CI DATA SCOPE VALIDATION PASSED: data_scope 列类型与取值口径一致';
END
$$;
