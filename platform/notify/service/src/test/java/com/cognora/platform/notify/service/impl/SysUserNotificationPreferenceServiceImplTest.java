package com.cognora.platform.notify.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.cognora.platform.common.exception.BusinessException;
import com.cognora.platform.notify.domain.entity.SysUserNotificationPreference;
import com.cognora.platform.notify.mapper.SysUserNotificationPreferenceMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SysUserNotificationPreferenceServiceImplTest {

    @Mock
    private SysUserNotificationPreferenceMapper mapper;

    private SysUserNotificationPreferenceServiceImpl service;

    @BeforeEach
    void setUp() {
        // Lambda wrappers need MyBatis-Plus entity metadata; a plain Mockito test has no Spring context.
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                SysUserNotificationPreference.class);
        service = new SysUserNotificationPreferenceServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    @Test
    void createRejectsUnsupportedNotificationType() {
        SysUserNotificationPreference entity = preference("NOT_A_TYPE", "EMAIL");

        assertThrows(BusinessException.class, () -> service.createPreference(entity));
        verifyNoInteractions(mapper);
    }

    @Test
    void createRejectsHalfConfiguredQuietHours() {
        SysUserNotificationPreference entity = preference("SYSTEM", "EMAIL");
        entity.setQuietHoursStart(LocalTime.of(22, 0));

        assertThrows(BusinessException.class, () -> service.createPreference(entity));
        verifyNoInteractions(mapper);
    }

    @Test
    void createDefaultsEnabledAndInsertsANewRow() {
        SysUserNotificationPreference entity = preference("SYSTEM", "EMAIL");
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(mapper.insert(any(SysUserNotificationPreference.class))).thenReturn(1);

        SysUserNotificationPreference saved = service.createPreference(entity);

        assertEquals(Boolean.TRUE, saved.getEnabled());
        assertNotNull(saved.getId());
        verify(mapper).insert(entity);
    }

    @Test
    void createDefaultsMarketingNotificationsToDisabled() {
        SysUserNotificationPreference entity = preference("MARKETING", "EMAIL");
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(mapper.insert(any(SysUserNotificationPreference.class))).thenReturn(1);

        SysUserNotificationPreference saved = service.createPreference(entity);

        assertEquals(Boolean.FALSE, saved.getEnabled());
    }

    @Test
    void createUpdatesTheExistingRowInsteadOfFailingOnTheUniqueKey() {
        SysUserNotificationPreference existing = preference("SYSTEM", "EMAIL");
        existing.setId("pref-1");
        existing.setEnabled(true);
        existing.setQuietHoursStart(LocalTime.of(22, 0));
        existing.setQuietHoursEnd(LocalTime.of(8, 0));
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        when(mapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        SysUserNotificationPreference request = preference("SYSTEM", "EMAIL");
        request.setEnabled(false);

        SysUserNotificationPreference saved = service.createPreference(request);

        assertEquals("pref-1", saved.getId());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<SysUserNotificationPreference>> captor =
                ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<?> wrapper = (LambdaUpdateWrapper<?>) captor.getValue();
        // 重复 POST 不带免打扰时保留已有窗口，而不是写回 NULL。
        assertTrue(wrapper.getParamNameValuePairs().containsValue(LocalTime.of(22, 0)));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(LocalTime.of(8, 0)));
        verify(mapper, never()).insert(any(SysUserNotificationPreference.class));
    }

    @Test
    void updateWritesQuietHoursExplicitlySoTheyCanBeCleared() {
        SysUserNotificationPreference existing = preference("SYSTEM", "EMAIL");
        existing.setId("pref-1");
        existing.setEnabled(true);
        existing.setQuietHoursStart(LocalTime.of(22, 0));
        existing.setQuietHoursEnd(LocalTime.of(8, 0));
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        when(mapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        SysUserNotificationPreference request = new SysUserNotificationPreference();
        request.setId("pref-1");
        request.setEnabled(true);

        service.updatePreference(request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<SysUserNotificationPreference>> captor =
                ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(eq(null), captor.capture());
        LambdaUpdateWrapper<?> wrapper = (LambdaUpdateWrapper<?>) captor.getValue();
        // quiet_hours_start/end are set to NULL instead of being ignored by updateById.
        assertTrue(wrapper.getSqlSet().contains("quiet_hours_start"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(null));
    }

    private static SysUserNotificationPreference preference(String type, String channel) {
        SysUserNotificationPreference entity = new SysUserNotificationPreference();
        entity.setUserId("user-1");
        entity.setNotificationType(type);
        entity.setChannel(channel);
        return entity;
    }
}