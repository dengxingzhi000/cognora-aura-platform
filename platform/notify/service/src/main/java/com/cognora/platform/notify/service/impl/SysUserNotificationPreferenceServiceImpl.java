package com.cognora.platform.notify.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.cognora.platform.common.exception.BusinessException;
import com.cognora.platform.common.response.ResultCode;
import lombok.extern.slf4j.Slf4j;
import com.cognora.platform.notify.domain.entity.SysUserNotificationPreference;
import com.cognora.platform.notify.mapper.SysUserNotificationPreferenceMapper;
import com.cognora.platform.notify.service.ISysUserNotificationPreferenceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class SysUserNotificationPreferenceServiceImpl
        extends ServiceImpl<SysUserNotificationPreferenceMapper, SysUserNotificationPreference>
        implements ISysUserNotificationPreferenceService {

    private static final Set<String> NOTIFICATION_TYPES = Set.of(
            "SYSTEM", "ORDER", "INVENTORY", "APPROVAL", "SECURITY", "MARKETING");
    private static final Set<String> CHANNELS = Set.of(
            "EMAIL", "SMS", "DINGTALK", "WECOM", "WEBHOOK");
    private static final String MARKETING_TYPE = "MARKETING";

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SysUserNotificationPreference createPreference(SysUserNotificationPreference entity) {
        if (entity == null || !StringUtils.hasText(entity.getUserId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "用户信息不能为空");
        }
        entity.setNotificationType(normalizeType(entity.getNotificationType()));
        entity.setChannel(normalizeChannel(entity.getChannel()));
        validateQuietHours(entity.getQuietHoursStart(), entity.getQuietHoursEnd());
        // 默认策略：营销消息需要主动订阅，其余业务通知默认开启。
        if (entity.getEnabled() == null) {
            entity.setEnabled(!MARKETING_TYPE.equals(entity.getNotificationType()));
        }

        // (user_id, notification_type, channel) 有唯一约束：重复提交时更新而不是 500。
        SysUserNotificationPreference existing = findExisting(
                entity.getUserId(), entity.getNotificationType(), entity.getChannel());
        if (existing != null) {
            entity.setId(existing.getId());
            // POST 的语义是「确保这一格存在」：调用方没带免打扰时保留原值，
            // 不能因为重复提交把已设置的免打扰时段清空（清空走 PUT 的显式 null）。
            if (entity.getQuietHoursStart() == null && entity.getQuietHoursEnd() == null) {
                entity.setQuietHoursStart(existing.getQuietHoursStart());
                entity.setQuietHoursEnd(existing.getQuietHoursEnd());
            }
            return updatePreference(entity);
        }

        entity.setId(UUID.randomUUID().toString());
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());
        baseMapper.insert(entity);
        log.info("用户通知偏好创建成功: id={}", entity.getId());
        return entity;
    }

    @Override
    public SysUserNotificationPreference getById(String id) {
        return baseMapper.selectOne(Wrappers.<SysUserNotificationPreference>lambdaQuery()
                .eq(SysUserNotificationPreference::getId, id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SysUserNotificationPreference updatePreference(SysUserNotificationPreference entity) {
        if (entity == null || !StringUtils.hasText(entity.getId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "偏好 ID 不能为空");
        }
        SysUserNotificationPreference current = getById(entity.getId());
        if (current == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "通知偏好不存在");
        }
        if (StringUtils.hasText(entity.getNotificationType())
                && !normalizeType(entity.getNotificationType()).equals(current.getNotificationType())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "通知类型不可修改");
        }
        if (StringUtils.hasText(entity.getChannel())
                && !normalizeChannel(entity.getChannel()).equals(current.getChannel())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "通知渠道不可修改");
        }
        validateQuietHours(entity.getQuietHoursStart(), entity.getQuietHoursEnd());

        boolean enabled = entity.getEnabled() == null
                ? Boolean.TRUE.equals(current.getEnabled())
                : entity.getEnabled();

        // updateById 会忽略 null，无法清空免打扰；这里显式 set，null 即写回 NULL。
        baseMapper.update(null, Wrappers.<SysUserNotificationPreference>lambdaUpdate()
                .eq(SysUserNotificationPreference::getId, entity.getId())
                .set(SysUserNotificationPreference::getEnabled, enabled)
                .set(SysUserNotificationPreference::getQuietHoursStart, entity.getQuietHoursStart())
                .set(SysUserNotificationPreference::getQuietHoursEnd, entity.getQuietHoursEnd())
                .set(SysUserNotificationPreference::getUpdateTime, LocalDateTime.now()));
        log.info("用户通知偏好更新成功: id={}", entity.getId());
        return getById(entity.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteById(String id) {
        log.info("删除用户通知偏好: id={}", id);
        return baseMapper.deleteById(id) > 0;
    }

    @Override
    public List<SysUserNotificationPreference> getByUserId(String userId) {
        return baseMapper.selectList(Wrappers.<SysUserNotificationPreference>lambdaQuery()
                .eq(SysUserNotificationPreference::getUserId, userId)
                .orderByAsc(SysUserNotificationPreference::getNotificationType)
                .orderByAsc(SysUserNotificationPreference::getChannel));
    }

    @Override
    public Page<SysUserNotificationPreference> pageQuery(int page, int size, String userId,
                                                          String notificationType, String channel) {
        log.debug("分页查询用户通知偏好: page={}, size={}, userId={}, notificationType={}, channel={}",
                (page + "").replace("\r", "_").replace("\n", "_"), (size + "").replace("\r", "_").replace("\n", "_"), (userId + "").replace("\r", "_").replace("\n", "_"), (notificationType + "").replace("\r", "_").replace("\n", "_"), (channel + "").replace("\r", "_").replace("\n", "_"));

        LambdaQueryWrapper<SysUserNotificationPreference> wrapper = Wrappers.lambdaQuery();

        if (StringUtils.hasText(userId)) {
            wrapper.eq(SysUserNotificationPreference::getUserId, userId);
        }
        if (StringUtils.hasText(notificationType)) {
            wrapper.eq(SysUserNotificationPreference::getNotificationType, notificationType);
        }
        if (StringUtils.hasText(channel)) {
            wrapper.eq(SysUserNotificationPreference::getChannel, channel);
        }
        wrapper.orderByDesc(SysUserNotificationPreference::getCreateTime);

        Page<SysUserNotificationPreference> pageParam = new Page<>(page, size);
        return baseMapper.selectPage(pageParam, wrapper);
    }

    private SysUserNotificationPreference findExisting(String userId, String notificationType, String channel) {
        return baseMapper.selectOne(Wrappers.<SysUserNotificationPreference>lambdaQuery()
                .eq(SysUserNotificationPreference::getUserId, userId)
                .eq(SysUserNotificationPreference::getNotificationType, notificationType)
                .eq(SysUserNotificationPreference::getChannel, channel));
    }

    private String normalizeType(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!NOTIFICATION_TYPES.contains(normalized)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "不支持的通知类型: " + value);
        }
        return normalized;
    }

    private String normalizeChannel(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!CHANNELS.contains(normalized)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "不支持的通知渠道: " + value);
        }
        return normalized;
    }

    private void validateQuietHours(LocalTime start, LocalTime end) {
        if ((start == null) != (end == null)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                    "免打扰开始与结束时间必须同时设置或同时留空");
        }
        if (start != null && start.equals(end)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                    "免打扰开始与结束时间不能相同");
        }
    }
}