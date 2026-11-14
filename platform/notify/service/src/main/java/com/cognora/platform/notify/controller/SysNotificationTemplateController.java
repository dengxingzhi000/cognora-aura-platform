package com.cognora.platform.notify.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.log.annotation.AuditLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.cognora.platform.notify.domain.entity.SysNotificationTemplate;
import com.cognora.platform.notify.service.ISysNotificationTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/sys-notification-template")
@Tag(name = "通知模板", description = "提供通知模板的创建、查询、更新、启停用与分页接口。")
public class SysNotificationTemplateController {

    private final ISysNotificationTemplateService notificationTemplateService;

    @Operation(summary = "创建通知模板", description = "创建通知模板并指定模板编码与通知渠道。")
    @PostMapping
    @PreAuthorize("hasAuthority('notify:template:create')")
    @AuditLog(operation = "创建通知模板", businessType = "NOTIFY", riskLevel = 3)
    public SysNotificationTemplate create(@RequestBody SysNotificationTemplate entity) {
        log.info("[API] 创建通知模板: templateCode={}, channel={}", (entity.getTemplateCode() + "").replace("\r", "_").replace("\n", "_"), (entity.getChannel() + "").replace("\r", "_").replace("\n", "_"));
        return notificationTemplateService.createTemplate(entity);
    }

    @Operation(summary = "查询通知模板", description = "根据模板ID查询通知模板详情。")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('notify:template:page', 'notify:template:view')")
