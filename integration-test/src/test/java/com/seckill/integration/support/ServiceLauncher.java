package com.seckill.integration.support;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
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
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        return new RunningService(context, port, name);
    }
}
