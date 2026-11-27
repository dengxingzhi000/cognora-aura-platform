package com.cognora.platform.common.constant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据权限范围的两套编码（数值码 / 名称）必须一一对应：
 * 数值码存在 {@code sys_role.data_scope}，名称存在 {@code sys_user.data_scope}，
 * {@code PermissionQueryServiceImpl} 负责在两者之间换算。
 */
class DataScopeConstantsTest {

    @Test
    void mapsEveryScopeCodeToItsName() {
        assertThat(DataScopeConstants.nameOf(DataScopeConstants.SCOPE_ALL))
                .isEqualTo(DataScopeConstants.SCOPE_ALL_NAME);
        assertThat(DataScopeConstants.nameOf(DataScopeConstants.SCOPE_CUSTOM))
                .isEqualTo(DataScopeConstants.SCOPE_CUSTOM_NAME);
        assertThat(DataScopeConstants.nameOf(DataScopeConstants.SCOPE_DEPT))
                .isEqualTo(DataScopeConstants.SCOPE_DEPT_NAME);
        assertThat(DataScopeConstants.nameOf(DataScopeConstants.SCOPE_DEPT_AND_CHILD))
                .isEqualTo(DataScopeConstants.SCOPE_DEPT_AND_SUB_NAME);
        assertThat(DataScopeConstants.nameOf(DataScopeConstants.SCOPE_SELF))
                .isEqualTo(DataScopeConstants.SCOPE_SELF_NAME);
    }

    @Test
    void fallsBackToSelfForUnknownOrMissingScopes() {
        assertThat(DataScopeConstants.nameOf(null)).isEqualTo(DataScopeConstants.SCOPE_SELF_NAME);
        assertThat(DataScopeConstants.nameOf(99)).isEqualTo(DataScopeConstants.SCOPE_SELF_NAME);
        assertThat(DataScopeConstants.nameOf(0)).isEqualTo(DataScopeConstants.SCOPE_SELF_NAME);
    }

    @Test
    void defaultScopeIsTheMostRestrictiveOne() {
        assertThat(DataScopeConstants.SCOPE_DEFAULT).isEqualTo(DataScopeConstants.SCOPE_SELF);
    }

    @Test
    void scopeCodesMatchTheRoleColumnContract() {
        // scripts/db/microservices/003_db_permission.sql:
        // data_scope SMALLINT NOT NULL DEFAULT 5 CHECK (data_scope IN (1, 2, 3, 4, 5))
        assertThat(DataScopeConstants.SCOPE_ALL).isEqualTo(1);
        assertThat(DataScopeConstants.SCOPE_CUSTOM).isEqualTo(2);
        assertThat(DataScopeConstants.SCOPE_DEPT).isEqualTo(3);
        assertThat(DataScopeConstants.SCOPE_DEPT_AND_CHILD).isEqualTo(4);
        assertThat(DataScopeConstants.SCOPE_SELF).isEqualTo(5);
    }

    @Test
    void scopeNamesMatchTheUserColumnContract() {
        // scripts/db/migrations/004_transform_permission_system_multi_tenant.sql:
        // data_scope VARCHAR(20) DEFAULT 'SELF' CHECK (data_scope IN ('ALL','DEPT','DEPT_AND_SUB','SELF','CUSTOM'))
        assertThat(DataScopeConstants.SCOPE_ALL_NAME).isEqualTo("ALL");
        assertThat(DataScopeConstants.SCOPE_CUSTOM_NAME).isEqualTo("CUSTOM");
        assertThat(DataScopeConstants.SCOPE_DEPT_NAME).isEqualTo("DEPT");
        assertThat(DataScopeConstants.SCOPE_DEPT_AND_SUB_NAME).isEqualTo("DEPT_AND_SUB");
        assertThat(DataScopeConstants.SCOPE_SELF_NAME).isEqualTo("SELF");
    }
}
