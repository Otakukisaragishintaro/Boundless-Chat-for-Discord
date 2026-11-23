/**
 * Discord 宿主专属适配层预留包。
 *
 * <p>未来迁入 Discord 包名与版本识别、Hook 目标、混淆成员定位、消息视图绑定、
 * 输入框监听、翻译按钮布局、原生发送动作及 Discord 节点投影代码。
 * 这些内容不得在此重新实现 API、翻译调度、配置存储或持久化缓存。</p>
 *
 * <p>为避免本次仅做结构准备时改变已验证的调用路径，现有实现暂时仍位于 hooks 包。
 * 本包目前只建立职责边界；迁移时应整批更新 package/import，并通过 GitHub Actions
 * 构建、单元测试及 APK 检查后再合并。</p>
 */
package io.github.otakukisaragishintaro.boundlesschatdiscord.platform.discord;
