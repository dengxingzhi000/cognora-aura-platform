package com.cognora.platform.common.constant;

/**
 * 数据权限范围常量。
 *
 * <p>范围在系统里有两套等价编码，这里同时定义并给出唯一的换算方法：</p>
 * <ul>
 *   <li><b>数值码</b>（{@code sys_role.data_scope} 的 SMALLINT 列，
 *       见 {@code scripts/db/microservices/003_db_permission.sql}）：{@code 1..5}；</li>
 *   <li><b>名称</b>（{@code sys_user.data_scope} 的 VARCHAR 列，见
 *       {@code scripts/db/migrations/004_transform_permission_system_multi_tenant.sql}）：
 *       {@code ALL / CUSTOM / DEPT / DEPT_AND_SUB / SELF}。</li>
 * </ul>
 *
 * <p>两套编码必须一一对应：{@link #nameOf(int)} 是唯一的换算入口，
 * {@code PermissionQueryServiceImpl} 读出的数值码经它变成名称后，
 * 才能和 {@code PermissionChecker} 里的字符串比较对上。</p>
 *
 * <p>取值语义：数值越小权限越大（{@code MIN(data_scope)} 取用户所有角色中范围最大的那个）。</p>
 */
public final class DataScopeConstants {

    private DataScopeConstants() {
        throw new UnsupportedOperationException("This is a constants class and cannot be instantiated");
    }

    // ==================== 数值码（sys_role.data_scope，SMALLINT） ====================

    /** 全部数据权限：可以查看所有数据。 */
    public static final int SCOPE_ALL = 1;

    /** 自定义数据权限：通过 {@code custom_dept_ids} 指定部门。 */
    public static final int SCOPE_CUSTOM = 2;

    /** 本部门数据权限：只能查看本部门的数据。 */
    public static final int SCOPE_DEPT = 3;

    /** 本部门及下级部门数据权限。 */
    public static final int SCOPE_DEPT_AND_CHILD = 4;

    /** 仅本人数据权限：只能查看自己创建的数据。 */
    public static final int SCOPE_SELF = 5;

    /** 范围未知或缺失时的默认值：仅本人（最严格）。 */
    public static final int SCOPE_DEFAULT = SCOPE_SELF;

    // ==================== 名称（sys_user.data_scope，VARCHAR） ====================

    /** 全部数据权限。 */
    public static final String SCOPE_ALL_NAME = "ALL";

    /** 自定义数据权限。 */
    public static final String SCOPE_CUSTOM_NAME = "CUSTOM";

    /** 本部门数据权限。 */
    public static final String SCOPE_DEPT_NAME = "DEPT";

    /** 本部门及下级部门数据权限。 */
    public static final String SCOPE_DEPT_AND_SUB_NAME = "DEPT_AND_SUB";

    /** 仅本人数据权限。 */
    public static final String SCOPE_SELF_NAME = "SELF";

    /**
     * 把数值码换成名称。
     *
     * <p>未知或 {@code null} 的数值码一律按 {@link #SCOPE_SELF_NAME} 处理（最严格），
     * 与 {@code PermissionQueryServiceImpl} 的历史行为一致。</p>
     *
     * @param scope 数值码
     * @return 对应的名称
     */
    public static String nameOf(Integer scope) {
        if (scope == null) {
            return SCOPE_SELF_NAME;
        }
        return switch (scope) {
            case SCOPE_ALL -> SCOPE_ALL_NAME;
            case SCOPE_CUSTOM -> SCOPE_CUSTOM_NAME;
            case SCOPE_DEPT -> SCOPE_DEPT_NAME;
            case SCOPE_DEPT_AND_CHILD -> SCOPE_DEPT_AND_SUB_NAME;
            default -> SCOPE_SELF_NAME;
        };
    }
}
