# Kuroshin APK 签名密钥使用说明（中文版）

本项目是独立发行版本，使用项目自己的签名密钥，不需要、也不会使用上游作者的私人密钥。

你已经拿到我为本项目生成的密钥包。**不需要重新生成密钥**，按下面步骤把密钥配置到 GitHub 即可。

## 一、先认识密钥包里的文件

解压 `kuroshinkey_bundle.zip` 后，会看到以下文件：

| 文件名 | 用途 | 需要复制到 GitHub 吗 |
|---|---|---|
| `kuroshinkey.jks` | 真正的签名密钥库，必须妥善备份 | 不直接上传到仓库 |
| `kuroshinkey.password` | 密钥库密码 | 复制内容到 GitHub 的第二个密钥项 |
| `KUROSHIN_KEYSTORE_BASE64.txt` | 将 JKS 文件转换成的一整段文本 | 复制内容到 GitHub 的第一个密钥项 |
| `README.txt` | 密钥包的简要说明 | 不需要 |
| `certificate-info.txt` | 签名证书信息，供核对使用 | 不需要 |

请把整个 ZIP 和解压后的原始文件保存在自己可靠的位置，最好再备份一份。**尤其不要丢失 `kuroshinkey.jks` 和密码**，以后每次发布新版 APK 都要使用同一把密钥。

## 二、在 GitHub 打开密钥设置页面

1. 打开项目仓库：<https://github.com/Otakukisaragishintaro/DiscordAutoTranslateX>
2. 点击仓库上方的 **Settings（设置）**。
3. 在左侧找到 **Secrets and variables（机密和变量）**。
4. 点击下面的 **Actions**。
5. 点击 **New repository secret（新建仓库机密）**。

注意：必须在仓库设置里添加，不是在个人账号设置里添加。

## 三、添加第一个机密：KUROSHIN_KEYSTORE_BASE64

1. 在 **Name（名称）** 一栏，完整填写：

   `KUROSHIN_KEYSTORE_BASE64`

2. 打开解压目录中的 `KUROSHIN_KEYSTORE_BASE64.txt`。
3. 复制文件里的**全部内容**。
4. 粘贴到 GitHub 的 **Secret（机密内容）** 一栏。
5. 点击 **Add secret（添加机密）**。

不要把这个值发到聊天、提交到代码仓库或公开发布。复制时不要自行删减字符。

## 四、添加第二个机密：KUROSHIN_KEYSTORE_PASSWORD

再次点击 **New repository secret（新建仓库机密）**：

1. **Name（名称）** 填写：

   `KUROSHIN_KEYSTORE_PASSWORD`

2. 打开解压目录中的 `kuroshinkey.password`。
3. 复制里面的密码，粘贴到 **Secret（机密内容）**。
4. 点击 **Add secret（添加机密）**。

密码不要发给我，也不要贴到任何公开位置。

## 五、检查是否添加完成

在 **Settings → Secrets and variables → Actions** 页面，应该能看到以下两个名称：

- `KUROSHIN_KEYSTORE_BASE64`
- `KUROSHIN_KEYSTORE_PASSWORD`

GitHub 不会再次显示机密的实际内容，这是正常的。只要两个名称都出现，就说明已经保存了这两项配置；但这**还不代表 APK 已经成功签名**。

添加完成后，回到仓库的 **Actions（操作）** 页面，打开最新一次构建记录。需要等构建流程全部完成，并确认结果为绿色的 **Success（成功）**，才能认定自动构建通过。若显示失败，请把失败步骤的错误日志截图发来，注意不要泄露密钥内容。

## 六、以后安装和更新时要注意

- 以后所有 Kuroshin 版本都必须继续使用这把密钥。
- 如果丢失 JKS 文件或密码，就无法再用原签名正常覆盖更新。
- 第一次安装 Kuroshin 签名的 APK 时，如果手机里已经安装了由其他密钥签名的旧模块，Android 可能拒绝直接覆盖。可能需要先卸载旧版本，再安装新版本。
- 第一次安装成功后，只要以后一直使用这把相同的密钥签名，就可以正常更新。
- **不要把 JKS 文件、密码或 Base64 内容提交到 GitHub 仓库。** 开源的是程序源代码，不代表应该公开 APK 的签名私钥；公开私钥会让其他人也能制作看起来由同一密钥签名的 APK。

## 七、关于密钥别名

项目构建配置已经固定使用别名：

`kuroshinkey`

你不需要另外创建名为别名的 GitHub Secret，也不要修改这个别名。

---

**你现在只需要完成第三、第四部分：在 GitHub 添加这两个机密。完成后告诉我“两个密钥已添加”，我再检查自动构建结果。**
