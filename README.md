# LUT beta 1.3.0

**索尼 α7C II → 三星 S24 Ultra 的「拍完即传 + 自动套 LUT」工具。**

1.3.0 新增了第三条连接方式：**相机 Wi-Fi Direct + PTP/IP**。手机扫描相机屏幕上的 Wi-Fi Direct 二维码，加入相机热点，然后由手机作为 PTP/IP 客户端主动读取照片。原有的手机热点 / 同一 Wi-Fi + FTP 上传方式保留，作为备用方案。

- 目标设备：Samsung Galaxy S24 Ultra，Android 15（One UI 7）
- 最低系统：Android 10（API 29）
- 相机：Sony α7C II / ILCE-7CM2
- 全部处理在本机完成，照片不会上传到任何服务器

## 1.3.0 更新

- **第三种连接方式：相机 Wi-Fi Direct + PTP/IP**
  - BLE 搜索/配对（首次）
  - 相机开启「遥控拍摄」
  - 扫描相机「Wi-Fi Direct 信息 → QR Code」
  - 解析二维码中的 SSID / 密码 / 机型 / 相机 ID
  - 通过 Android `WifiNetworkSpecifier` 加入相机热点，系统确认框正常弹出
  - 手机作为 PTP/IP 客户端主动读取照片，不再依赖相机 FTP 上传
- **真实 PTP/IP 与 Sony Content Transfer 会话**
  - 使用 `SDIO_OpenSession`（0x9210，FunctionMode=1）进入 Content Transfer Mode
  - 执行 Sony SDIO 认证（0x9201 / 0x9202）
  - 使用 `SDIO_SetContentsTransferMode`（0x9212）开启/关闭内容传输
  - 再执行 `GetStorageIDs` / `GetObjectHandles` / `GetObjectInfo` / `GetObject`
  - 这是针对 `0x1004 GetStorageIDs` / `0x2013 StoreNotAvailable` 的正式处理路径：标准 `OpenSession` 进入的是 Remote Control Mode，不暴露内容存储；Content Transfer Mode 才会暴露。
- **二维码格式**
  - A7C II 的 Wi-Fi Direct 信息二维码当前格式为：

    ```text
    W01:
    S: DIRECT-j7U1:ILCE-7CM2
    P: zKabmS47
    C: ILCE-7CM2
    M: 3490EAFAF701
    ```

  - `S` = SSID，`P` = Password，`C` = Camera Model，`M` = Camera ID。
  - 同时兼容标准 `WIFI:T:WPA;S:...;P:...;;`（SSID 必须以 `DIRECT-` 开头）。
  - 不再把「Wi-Fi Direct 信息 QR」和「显示 Wi-Fi 信息 → MAC 地址 QR」混为一谈。
- **LUT 强度实时预览修复**
  - LUT 库的「强度调整」不再只在松手时保存数值，而是拖动时用 720×405 缓存底图实时重绘。
  - 单张编辑同样使用 `snapshotFlow` + conflate 渲染，拖动时画面即时变化。
- **最终导出始终使用原图**
  - 自动导入和单张重新导出都强制 `maxDimension = 0`，JPEG 质量不低于 95。
  - 720px 预览只用于界面，不参与导出；不会覆盖旧成片。
- **安全边界**
  - 只读取、下载照片；不删除相机照片、不格式化存储卡、不修改相机系统或固件。
  - `SDIO_SetContentsTransferMode` 只临时切换传输状态，结束时会关闭。
- 版本：`1.3.0`（versionCode 4）。

---

## 1. 交付内容

| 文件 | 说明 |
|---|---|
| `dist/LUT-beta-1.3.0.apk` | 可直接安装的 release APK（Android Debug 证书签名） |
| `app/` | 完整 Android Studio 工程源码（Kotlin + Jetpack Compose） |
| `extras/LUTBeta_SLog3_Warm_17.cube` | 演示 LUT 源文件 |
| `extras/preview_studio.jpg` / `preview_outdoor.jpg` | 「灰片」/「灰片1」示例图 |
| `extras/test_shot.jpg` | 测试照片素材 |
| `README.md` | 本说明 |

> APK 使用本机 Android Debug 证书签名，方便侧载；不是应用商店签名。后续自行构建时请固定同一个 keystore，否则覆盖安装会失败。
>
> 版本：`1.3.0` / `versionCode 4`
>
> 大小：约 47.7 MB
>
> SHA-256：`AF3FD405402C08488D3DDB265F5F108AB3E72486A7CAB378AA1080B757ED1DBE`

---

## 2. 五分钟上手

### 2.1 安装 APK

1. 把 `dist/LUT-beta-1.3.0.apk` 传到手机。
2. 用「我的文件」打开 APK。
3. 若系统提示「已阻止安装」，允许「我的文件」安装未知应用。
4. 三星 Auto Blocker 如拦截：`设置 → 安全与隐私 → 自动拦截器`，临时关闭后再安装。
5. Play Protect 若提示「未知开发者」，选择「仍要安装」。

### 2.2 导入 LUT

1. 打开 **LUT beta** → 底部 **LUT**。
2. 点 **导入 .cube LUT**，选择 `.cube` 文件。
3. 导入后会生成两张预览图（灰片 / 灰片1）；点选 LUT 作为当前 LUT。
4. 选中卡片的预览左上角「切换」可切换灰片 / 灰片1。
5. 拖动「强度调整」，预览会实时重绘；0% 保留原片，100% 完全套用。

支持 3D `.cube`：`LUT_3D_SIZE 2–129`、`DOMAIN_MIN/MAX`、`LUT_3D_INPUT_RANGE`。暂不支持 1D LUT、`.3dl`、`.look`。

### 2.3 连接相机：相机 Wi-Fi Direct + PTP/IP（1.3.0 新方式）

1. 相机：`MENU → 网络 → 连接/遥控拍摄 → 蓝牙功能 → 开`。
2. 连接页 → **相机 Wi-Fi Direct**。
3. 点 **搜索蓝牙相机**，选择 `ILCE-7CM2`，在相机上确认配对。
4. 相机：`MENU → 网络 → 连接/遥控拍摄 → 遥控拍摄功能 → 遥控拍摄 → 开`。
5. 相机：`网络 → 连接/遥控拍摄 → 遥控拍摄功能 → Wi-Fi Direct 信息 → QR Code`。
6. 手机点 **扫描相机热点**，把相机屏幕上的二维码放入取景框。
7. App 解析出 SSID / 密码 / 机型 / 相机 ID；Android 会弹出 **系统 Wi-Fi 确认框**，点允许。
8. App 连接 `DIRECT-...` 后启动 PTP/IP：
   - `SDIO_OpenSession` Content Transfer Mode
   - Sony SDIO 认证
   - `SDIO_SetContentsTransferMode`
   - 读取存储、对象列表、下载照片
9. 拍照后，照片会由手机主动读取，进入「最近作品」。

也可以不扫码，手动输入 SSID 和相机 Wi-Fi 密码；系统确认框仍会出现。

**Android 限制**：第三方 App 不能静默切换 Wi-Fi。`WifiNetworkSpecifier` 必须经过系统确认；首次一定需要，之后是否再次弹出取决于系统是否保持该网络。App 没有绕过这个限制。

### 2.4 保留的 FTP 方式

连接页仍提供：

- **手机热点直连**：手机开热点，相机连手机，相机 FTP 上传。
- **同一个 Wi-Fi**：手机和相机连同一个路由器，相机 FTP 上传。

FTP 方式原有的首次配对信息、被动模式、端口设置全部保留，没有被删除。新 PTP/IP 方式不需要 FTP 服务器，也不会启动 FTP 作为主传输。

### 2.5 首次 FTP 配对（仅备用方式需要）

相机菜单：

```text
MENU → 网络 → FTP传输 → FTP传输功能 → 服务器设置 → 服务器 1
```

按连接页折叠区显示的 IP / 端口 / 用户 / 密码填写，安全协议关闭、被动模式开启；然后打开 `FTP功能` 和 `自动FTP传输`。这是一次性设置。

---

## 3. PTP/IP 细节与 0x1004 / 0x2013

### 3.1 为什么标准 PTP 查询会失败

Sony 相机在标准 PTP `OpenSession` 下进入 **Remote Control Mode**。此模式可以遥控，但内容存储可能不暴露，因此：

- `GetStorageIDs`（0x1004）返回 `0x2013 StoreNotAvailable`
- 后续 `GetObjectHandles` / `GetObject` 无法正常读取照片

### 3.2 1.3.0 的处理

1. `SDIO_OpenSession(0x9210, SessionID=1, FunctionMode=1)`  
   进入 **Content Transfer Mode**，取代标准 `OpenSession`。
2. Sony SDIO 认证：
   - `SDIO_Connect`（0x9201）phase 1 / 2
   - `SDIO_GetExtDeviceInfo`（0x9202）协商版本
   - `SDIO_Connect` phase 3
3. `SDIO_SetContentsTransferMode`（0x9212）参数 `2, 1, 0` 开启传输。
4. `GetStorageIDs` → `GetObjectHandles` → `GetObjectInfo` → `GetObject`。
5. 结束或断开时 `SDIO_SetContentsTransferMode(..., 0)` 关闭。

如果相机仍返回错误，App 会显示真实的 PTP 响应码（例如 `0x2013 StoreNotAvailable`、`0x2005 OperationNotSupported`），不会伪造照片、不会用模拟数据继续。

### 3.3 照片传输协议

PTP/IP，TCP 端口 **15740**。不是 FTP，不是 HTTP。手机是 PTP/IP 客户端，相机是服务端。

### 3.4 实机验证状态

**代码是真实实现，并已通过本地 PTP/IP mock 协议测试；但本环境没有 α7C II 实物，尚未完成相机端实机验证。**  
如果 `SDIO_OpenSession` / `SDIO_SetContentsTransferMode` 在你的相机固件上返回错误，请把连接页显示的具体响应码发回；那将是 Sony 固件/权限层面的确切卡点，而不是 App 用假数据掩盖。

---

## 4. 强度调整与导出

### 4.1 强度调整

- LUT 库卡片：拖动滑块时，使用 `preview_studio.jpg` / `preview_outdoor.jpg` 的 720×405 底图，按当前强度做 CPU 三线性 LUT，实时重绘。
- 单张编辑：同样缓存 720px 底图，拖动时持续重绘；不再等松手、不再重复解码原图。
- 强度公式：`输出 = 原片 × (1 - 强度) + LUT × 强度`。

### 4.2 最终导出

- 自动导入：`maxDimension = 0`，JPEG 质量 ≥95。
- 单张重新导出：从 `originals/` 读取原片，`maxDimension = 0`，JPEG 质量 ≥95。
- 预览图从不参与导出，也不会覆盖旧成片。
- 设置页的「输出尺寸」已固定为原始分辨率；720px 只用于预览。

---

## 5. 权限说明

| 权限 | 用途 |
|---|---|
| `CAMERA` | 扫描相机屏幕上的 Wi-Fi Direct 二维码 |
| `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` | 搜索并配对 ILCE-7CM2 |
| `NEARBY_WIFI_DEVICES` / `ACCESS_FINE_LOCATION` | `WifiNetworkSpecifier` 连接相机热点 |
| `POST_NOTIFICATIONS` | 前台接收服务通知 |
| `READ_MEDIA_IMAGES` | 相册监听备用方案 |

相机权限只用于扫码；二维码内容只在本机解析，不上传。

---

## 6. 安全边界

- 只读取和下载照片。
- 不删除相机照片、不格式化存储卡、不修改相机系统或固件。
- PTP/IP 只执行读取对象所需的标准操作；`SDIO_SetContentsTransferMode` 只临时切换传输状态，结束时关闭。
- FTP 方式只接收相机主动上传的文件，不访问相机存储卡。

---

## 7. 常见问题

### 扫描二维码后没有连接

1. 确认相机屏幕显示的是 **Wi-Fi Direct 信息 → QR Code**，不是「显示 Wi-Fi 信息 → MAC 地址 QR」。
2. 二维码应以 `W01:` 开头，包含 `S:` / `P:` / `C:` / `M:` 四行。
3. Android 系统 Wi-Fi 确认框是否点了允许。
4. 是否还有其他手机连着相机热点；Sony 通常只允许有限客户端。
5. 相机是否已开启「遥控拍摄」。
6. 仍失败时，把连接页的 `PTP/IP` 状态和具体响应码发回。

### 出现 0x2013 StoreNotAvailable

1. 确认 App 走的是 **相机 Wi-Fi Direct + PTP/IP**，不是 FTP。
2. 确认相机固件支持 Content Transfer；先拍一张照片，再让 App 重试。
3. 如果仍显示 `GetStorageIDs 0x2013`，说明相机的 Content Transfer 没有暴露存储；这是相机端状态/固件限制，App 不会用假数据继续。
4. 可切回 FTP 备用方式完成拍摄。

### 出现 0x1004 GetStorageIDs

`0x1004` 是 GetStorageIDs 的操作码；关键看返回的响应码。1.3.0 已改用 SDIO Content Transfer 会话；如果仍失败，请记录 `SDIO_OpenSession` / `SDIO_SetContentsTransferMode` / `GetStorageIDs` 哪一步返回什么码。

### 强度滑块画面不变

1. 确认使用的是 1.3.0。
2. 在 LUT 库选中卡片上拖动「强度调整」，预览应在 720×405 底图上实时变化。
3. 单张编辑中拖动同一个滑块，画面也应实时变化。
4. 若仍不变，检查是否选中了 LUT、原片是否存在。

### 导出变小/变糊

1.3.0 的自动导入和单张导出都强制原始分辨率；若仍看到小图，确认不是在看 720px 预览，而是打开相册 `Pictures/LUT Beta` 里的成片。

### 相册里找不到

路径：`Pictures/LUT Beta`。部分相册 App 需要下拉刷新。

---

## 8. 构建与测试

```text
JAVA_HOME=X:\gongzuoqu\.tools\jdk\jdk-17.0.20.1+1
ANDROID_HOME=X:\gongzuoqu\.tools\android-sdk
ANDROID_SDK_HOME=X:\gongzuoqu\.tools\android-home
GRADLE_USER_HOME=X:\gongzuoqu\.tools\gradle-home
```

构建 release：

```bat
gradlew.bat :app:assembleRelease --no-daemon
```

单元测试：

```bat
gradlew.bat :app:testDebugUnitTest --no-daemon
```

当前测试：5 个 `.cube` 解析测试、2 个 FTP 集成测试、3 个二维码解析测试、1 个 PTP/IP mock 协议测试，共 11 个通过。

**未完成**：α7C II 实机 PTP/IP 连接、Samsung S24 Ultra 上的系统 Wi-Fi 确认框/三星后台策略实机验证。代码不会把这些未验证项说成已验证。
