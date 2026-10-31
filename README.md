# USB 摄像头（Android / UVC）

一个可以直接装到安卓手机/平板/盒子上的 USB 摄像头 App：插上免驱 UVC 摄像头就能预览、拍照、录像，还能切分辨率、旋转、镜像、切多摄像头。

- 语言：Kotlin（Gradle 8.9 + AGP 8.7.3 + Kotlin 2.0.21）
- minSdk 24（Android 7.0）/ targetSdk 34 / compileSdk 35
- 核心依赖：`com.herohan:UVCAndroid:1.0.13`（libuvc + libusb + libjpeg-turbo，Apache-2.0，Maven Central 可拉取）
- 不依赖系统 Camera2 是否支持"外接相机"，走 USB 原生视频流，兼容性最好

## 功能

| 功能 | 说明 |
| --- | --- |
| 实时预览 | 自动检测插入的 UVC 摄像头，自动申请 USB 权限并预览 |
| 拍照 | 输出 JPG，质量 95 |
| 录像 | 输出 MP4（H.264 + AAC 音轨；没有麦克风权限时自动录无声视频） |
| 分辨率 / 格式切换 | 列出摄像头真实支持的所有 `格式(MJPEG/YUYV) × 分辨率 × 帧率`，选中后热切换 |
| 旋转 | 0° → 90° → 180° → 270° 循环 |
| 镜像 | 关 → 水平 → 垂直 循环 |
| 多摄像头 | 同时插多个 UVC 摄像头时可在"切换"里选择 |
| 文件保存 | 先写 App 私有目录；Android 10+ 自动复制到系统相册 `Pictures/USBCam`、`Movies/USBCam` |

## 目录结构

```
usbcam/
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml          # USB host、CAMERA/RECORD_AUDIO、插入自动拉起
│       ├── java/com/usbcam/
│       │   ├── App.kt                   # 初始化 libuvc 库
│       │   ├── MainActivity.kt          # 全部界面 + 相机逻辑（纯代码 UI，无 XML 布局）
│       │   └── MediaStoreExport.kt      # 保存到系统相册
│       └── res/
│           ├── drawable/ic_launcher.xml # 矢量图标
│           ├── values/{strings,themes}.xml
│           └── xml/device_filter.xml    # 只匹配 USB Video Class 设备
├── build.gradle
├── settings.gradle
├── gradle.properties
└── .github/workflows/build-apk.yml      # 云端编译，产出 APK
```

## 拿到 APK（没有开发环境，用 GitHub Actions 编译）

1. 注册/登录 GitHub，新建一个仓库（Private 也行）。
2. 把这个 `usbcam` 目录里的**全部文件**上传到仓库根目录（GitHub 网页版可以直接拖拽上传文件夹；用命令行更快）：

   ```bash
   cd usbcam
   git init
   git add .
   git commit -m "USB camera app"
   git branch -M main
   git remote add origin https://github.com/<你的用户名>/<仓库名>.git
   git push -u origin main
   ```

3. 打开仓库的 **Actions** 标签页，等 `Build APK` 跑完（首次约 3~6 分钟，主要在下载 Gradle 和 Android SDK）。
4. 点进这次运行 → 页面底部 **Artifacts** → 下载 `usbcam-debug-apk.zip` → 解压得到 `app-debug.apk`。
5. 传到手机安装（需要允许"安装未知来源应用"）。debug 包用调试签名，可以直接装。

> Actions 的 Artifact 有保存期限（默认 90 天），过期就重新跑一次。

## 本地编译（可选）

- Android Studio（Ladybug 及以上）+ JDK 17，直接 `Open` 这个目录。
- 仓库里**没有放 `gradle-wrapper.jar`**（二进制文件），所以首次同步如果提示找不到 Gradle wrapper：
  - 在 Android Studio 里 `Settings → Build, Execution, Deployment → Build Tools → Gradle`，把 `Gradle distribution` 选成本机安装的 Gradle 8.9+；
  - 或者在有 Gradle 的机器上执行 `gradle wrapper --gradle-version 8.9`，生成的 `gradle/wrapper/*` 提交进仓库后就正常了。

## 使用步骤

1. 手机插上 OTG 转接头，再接 USB 摄像头。
2. 打开 App，允许「相机」和「麦克风」权限。
3. 系统弹出「是否允许 App 访问该 USB 设备」→ 勾选"默认用于此设备"并允许。
4. 出现画面后即可拍照 / 录像 / 切分辨率。

## 常见问题

| 现象 | 原因与处理 |
| --- | --- |
| 一直显示"等待 USB 摄像头" | 手机不支持 USB Host（OTG）或 OTG 没打开；换根 OTG 线/换手机试。 |
| 弹权限框但没画面，提示 busy | 摄像头被系统相机（Camera2 外接相机）或其他 App 占用，先关掉系统相机再打开本 App；或拔插一次。 |
| 预览黑屏但有"已连接" | 摄像头只输出未压缩 YUYV 且带宽不够，切到 MJPEG 或降低分辨率。 |
| 画面很卡、掉帧 | USB 2.0 带宽限制：1080p 建议 MJPEG；或降到 720p。 |
| 拍照/录像失败 | 检查存储空间；Android 10+ 不需要存储权限，低于 10 时文件写在 App 私有目录。 |
| 录像没有声音 | 没给麦克风权限，App 会自动录无声视频。 |
| 极少数摄像头完全不出图 | 该设备只支持 isochronous 传输且内核不支持，属于硬件/内核限制。 |

## 想改的地方

- 打开摄像头时用的默认分辨率：`MainActivity.onDeviceOpen()` 里 `helper.openCamera()` 可换成 `openCamera(size)`。
- 录像码率 / 帧率 / 关键帧间隔：`MainActivity.initCameraHelper()` 里的 `setBitRate`、`setVideoFrameRate`、`setIFrameInterval`。
- 照片质量：`setJpegCompressionQuality(95)`。
- 保存目录：`newMediaFile()`。
- 界面：全部在 `buildUi()` 里用代码搭，没有 XML 布局，改起来直观。

## 协议与授权

- 本项目代码：MIT。
- `UVCAndroid`（含 libuvc/libusb/libjpeg-turbo 绑定）：Apache-2.0，见其仓库说明。
