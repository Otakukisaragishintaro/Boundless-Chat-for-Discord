/**
 * 当前 Discord 版本适配实现所在包。
 *
 * <p>此包中的 Hook、Discord 原生视图绑定和组件重建逻辑均属于 Discord 宿主层，
 * 并非可直接复制到其他聊天软件的通用业务代码。新宿主应建立独立适配实现，
 * 复用 core 与 translate 中的业务能力，不得在宿主包内复制 HookBridge 所协调的翻译业务链路。</p>
 *
 * <p>预留的新目录为 platform.discord；在完成统一宿主接口和完整引用迁移前，
 * 此包保持现状，避免仅为目录美观而改动运行路径。</p>
 */
package io.github.otakukisaragishintaro.boundlesschatdiscord.hooks;
