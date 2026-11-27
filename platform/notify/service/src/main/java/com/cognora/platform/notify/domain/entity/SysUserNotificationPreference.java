package com.cognora.platform.notify.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import java.time.LocalTime;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 鐢ㄦ埛閫氱煡鍋忓ソ锟?
 * </p>
 *
 * @author deng
 * @since 2025-12-26
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("sys_user_notification_preference")
public class SysUserNotificationPreference implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.NONE)
    private String id;

    @TableField("user_id")
    private String userId;

    @TableField("notification_type")
    private String notificationType;

    @TableField("channel")
    private String channel;

    @TableField("enabled")
    private Boolean enabled;

    /** PostgreSQL `TIME` column; serialised as HH:mm so the UI can bind it to a time input. */
    @JsonFormat(pattern = "HH:mm")
    @TableField("quiet_hours_start")
    private LocalTime quietHoursStart;

    @JsonFormat(pattern = "HH:mm")
    @TableField("quiet_hours_end")
    private LocalTime quietHoursEnd;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

}
