/**
 * 不依赖具体聊天软件的宿主适配契约预留包。
 *
 * <p>未来在此定义跨宿主共用的消息输入、宿主文本节点描述、渲染结果和生命周期接口。
 * 接口必须保持与 Android/LSPosed 业务实现解耦；不得在此复制 API 请求、翻译或缓存代码。
 * 当前仅建立目录边界，尚未抽取接口，也不改变任何运行行为。</p>
 */
package io.github.otakukisaragishintaro.boundlesschatdiscord.platform.common;
