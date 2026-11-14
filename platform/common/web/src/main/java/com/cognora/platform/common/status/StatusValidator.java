package com.cognora.platform.common.status;

import com.cognora.platform.system.api.StatusMachineDubboService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 通用状态流转校验器。
 *
 * <p>封装 {@link StatusMachineDubboService} 调用，提供简洁的校验 API。</p>
 *
 * <pre>
 * statusValidator.validateTransition("ORDER", "PAID", "SHIPPED");
 * </pre>
 *
 * <h2>为什么这是 {@code ObjectProvider} 而不是 {@code @DubboReference} 字段</h2>
 * <p>本类是 {@code @Component}，而每个服务的 {@code scanBasePackages} 都包含
 * {@code com.cognora.platform.common}，因此**所有**服务都会实例化它。此前它带一个无条件的
 * {@code @DubboReference} 字段，于是每个服务的启动都硬性依赖一个可达的注册中心——包括那些
 * 从不调用状态机的服务，以及没有 Nacos 的单元测试 / 上下文冒烟 / 离线构建。缺注册中心时
 * Dubbo 直接让整个 Spring 上下文启动失败：</p>
 * <pre>
 * IllegalStateException: No such any registry to reference
 *   com.cognora.platform.system.api.StatusMachineDubboService on the consumer ...
 * </pre>
 *
 * <p>这是真实的可部署性缺陷（一个可选能力把全部服务的启动绑死在注册中心上），不只是测试
 * 环境的问题。改为 {@link ObjectProvider} 之后：</p>
 * <ul>
 *   <li>注册中心存在时，provider 解析到 Dubbo 代理，调用行为与以前一致；</li>
 *   <li>注册中心缺失时，服务照常启动，只有**真正调用**状态流转时才抛出带指引的异常；</li>
 *   <li>「服务能否启动」与「状态机能否调用」这两件事终于解耦，各自反映真实依赖。</li>
 * </ul>
 *
 * <p>{@code StatusMachineDubboService} 的 Dubbo 代理由 {@code @EnableDubbo} 注册为 bean，
 * 因此 {@code ObjectProvider} 能解析到它，且解析发生在首次调用时而非启动时。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StatusValidator {

    /**
     * 懒解析的状态机引用。用 {@code ObjectProvider} 而不是 {@code @DubboReference} 字段：
     * 字段注入会在容器启动时解析，缺少注册中心就直接失败；provider 只在调用时解析。
     */
    private final ObjectProvider<StatusMachineDubboService> statusMachineProvider;

    /**
     * 校验状态流转是否合法。不合法时抛出 {@link IllegalStateException}。
     *
     * @throws IllegalStateException 流转非法，或状态机服务不可用
     */
    public void validateTransition(String bizType, String fromStatus, String toStatus) {
        StatusMachineDubboService.TransitionCheckDTO check =
                requireStatusMachine().canTransition(bizType, fromStatus, toStatus);
        if (!check.allowed()) {
            throw new IllegalStateException(
                    "非法状态流转: " + bizType + " " + fromStatus + " -> " + toStatus
                            + ": " + check.reason());
        }
    }

    /**
     * 校验状态流转是否合法。不合法时返回 false。
     */
    public boolean canTransition(String bizType, String fromStatus, String toStatus) {
        return requireStatusMachine().canTransition(bizType, fromStatus, toStatus).allowed();
    }

    /**
     * 执行状态流转。返回目标状态编码。
     */
    public String transition(String bizType, String fromStatus, String actionCode) {
        StatusMachineDubboService.TransitionResultDTO result =
                requireStatusMachine().transition(bizType, fromStatus, actionCode);
        if (!result.success()) {
            throw new IllegalStateException(
                    "状态流转失败: " + bizType + " " + fromStatus + " action=" + actionCode
                            + ": " + result.errorMessage());
        }
        return result.toStatus();
    }

    /**
     * 把「状态机不可用」翻译成一条能照着修的报错，而不是 NPE。
     */
    private StatusMachineDubboService requireStatusMachine() {
        StatusMachineDubboService machine = statusMachineProvider.getIfAvailable();
        if (machine == null) {
            throw new IllegalStateException(
                    "状态机服务不可用：StatusMachineDubboService 未注册。请确认 platform-system 已启动、"
                            + "该服务已配置 Dubbo 注册中心（dubbo.registry.address）且目标服务已注册。"
                            + " 注意「服务启动」本身不依赖注册中心；只有实际调用状态流转时才需要。");
        }
        return machine;
    }
}
