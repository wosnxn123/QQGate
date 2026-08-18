package dev.qqgate;

import java.util.List;

/**
 * 组件配置读取窄接口（纯 Java）。
 * QQGatePlugin 实现；测试用 Map 实现模拟。
 */
public interface BotConfig {

    String configString(String path, String def);

    int configInt(String path, int def);

    boolean configBool(String path, boolean def);

    List<String> configStringList(String path);
}
