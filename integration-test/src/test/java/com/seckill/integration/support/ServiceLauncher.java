package com.seckill.integration.support;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;

/**
 * 编程式启动业务服务上下文（真实 Spring Boot，随机端口，命令行参数隔离配置）。
 */
public final class ServiceLauncher {

    public record RunningService(ConfigurableApplicationContext context, int port, String name) {
        public void stop() {
            context.close();
        }
    }

    private ServiceLauncher() {
    }

    public static RunningService start(Class<?> applicationClass, String name, List<String> args) {
        SpringApplication application = new SpringApplication(applicationClass);
        ConfigurableApplicationContext context = application.run(args.toArray(String[]::new));
        // 兼容 Servlet（业务服务）与 Reactive（gateway）两种 Web 容器
        String portValue = context.getEnvironment().getProperty("local.server.port");
        if (portValue == null) {
            throw new IllegalStateException("web server port not available for " + name);
        }
        int port = Integer.parseInt(portValue);
        return new RunningService(context, port, name);
    }
}
