# AM++ 局域网遥控

在手机上用 AM++ 的播放页给另一台设备选歌、切歌、暂停或拖动进度。在播放设备列表中可以把当前歌曲交接给另一台已配对设备。控制端显示远端的歌曲、封面和进度，也能使用 Android 媒体通知控制播放。声音由当前播放设备输出。

## 兼容性

- 实验版 v0.7.5。原生会话和播放界面入口适配 AM++ 修改版中 Apple Music Android 7.0.0（versionCode 1614）。
- 开发期间用 Galaxy S24 Ultra 和 Galaxy Tab S9 Ultra 验证了主要的双端控制流程。其他机型及宿主版本尚待验证。
- 双端需要在同一可信局域网，手机热点也可以。配对地址包含控制令牌，请私下分享。局域网连接使用 HTTP。
- 这是个人业余项目。欢迎反馈和提交 PR，更新随个人时间安排。

## 安装和配对

1. 在两台设备的 AM++「插件」页导入 ZIP，启用后重启应用。
2. 在播放端打开插件设置，展开「本机接收与配对地址」，启用接收并复制完整配对地址。
3. 在控制端的插件设置中选择「添加设备」，输入名称并粘贴该地址。开启「自动接入已配对设备」后，AM++ 会接入唯一正在播放的已配对设备。也可以从插件设置手动接入。
4. 若要在两台设备间来回交接播放，让两端都开启接收并互相配对。点击播放页星标旁或迷你播放器上的设备图标选择目标。在三台及以上设备间交接时，当前播放设备也需要配对目标。

持续失联后，控制端会恢复本机播放器及保存的队列。在线时点「退出遥控」会先尝试暂停原播放设备，并确认它是否已暂停。离线时原设备可能继续播放，可在原设备上暂停，或重新联网后从播放设备列表接入遥控。

上一首、下一首和播放模式作用于播放端自己的队列。两端更新后，可以从原生播放页控制随机、循环全部、单曲循环和无限续播。按钮是否可用取决于播放端当前的队列和曲目。设备交接仍只交接当前曲目及进度。

## 从源码构建

需要 Python 3、JDK 17 或以上、Android 平台及 Build Tools，以及 [AM++ 插件 SDK](https://github.com/Zennmn/AM-plus-plus/blob/v1.6.8/docs/plugin-development.md) 导出的 `ampp-plugin-api-v1.jar`。可使用上游示例采用的 Android 平台 37 和 Build Tools 37.0.0。

~~~bash
python3 build.py \
  --android-jar /path/to/android-sdk/platforms/android-37/android.jar \
  --d8 /path/to/android-sdk/build-tools/37.0.0/lib/d8.jar \
  --ampp-sdk /path/to/ampp-plugin-api-v1.jar
~~~

构建产物在 `build/ampp-native-session-0.7.5.zip`。Android 与 AM++ SDK 仅参与编译。Apple Music 安装包无需参与构建。宿主适配涉及内部类和方法，验证其他版本时请检查播放会话和 UI 入口。

本项目是独立的 AM++ 插件，与 Apple 无隶属关系。
