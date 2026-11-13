package com.xiaopeng.qa.camera;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * v1.24：主界面仅作配置界面，联动轮询与浮窗全部由常驻前台服务 CameraOverlayService 负责，
 * 退后台/退出本界面不影响服务；本界面定时拉取服务状态与日志刷新 UI。
 */
public class MainActivity extends Activity implements View.OnClickListener {

    private static final String TAG = "CameraAssist";
    private static final int REQ_CAMERA = 100;
    // v1.57 beta2：官方签名证书 SHA-256（hex 小写，取自 cameraassist.keystore 签名证书）
    private static final String OFFICIAL_SIGNATURE_SHA256 =
            "6abd911ba20d3a242d1d1a58f2099cc506b9c62085304cb79a10cad849799cc2";
    // v1.33：日志区滚动窗口，最多保留最近 500 行
    private static final int MAX_LOG_LINES = 500;

    // v1.61：调试页隐藏入口——连点主界面「默认开启」按钮 N 次才进入双路镜头预览页
    private static final int CAMERA_TEST_CLICK_TARGET = 10;
    private static final long CAMERA_TEST_CLICK_INTERVAL_MS = 1500L;
    private int cameraTestClickCount = 0;
    private long cameraTestLastClickMs = 0L;

    private Button btnOverlay;
    private Button btnCamera;
    private Button btnCameraTest;
    // v1.65 beta1：双闪联动影像开关（开启后打双闪同时出左右两侧影像，便于测试双窗）
    private Button btnHazardDual;
    private EditText etHost;
    private EditText etPort;
    private Button swEnable;
    // v1.41：一键还原左右转向浮窗默认
    private Button btnSlotLeft;
    private Button btnSlotRight;
    private Button btnSlotSyncSize;
    private Button btnSlotReset;
    // v1.46：右浮窗对齐左浮窗（位置/大小/形状）
    // v1.52：BSD 提醒开关
    private Button btnBsd;
    // v1.53 beta2：BSD 无车常显调试开关（无车也显示窄条，方便拖动调位置/测试）
    private Button btnBsdAlways;
    // v1.54：BSD 预警自动弹窗开关 / BSD 常显位置一键复位
    private Button btnBsdAutoOverlay;
    // v1.54 beta3：BSD 预警红色遮罩开关
    private Button btnBsdRedOverlay;
    // v1.54 beta4：预警浮窗自定义编辑（召唤左右预警窗编辑/关闭保存）/ 预警浮窗同步转向灯浮窗
    private Button btnClearLog;
    private TextView tvState;
    private TextView tvLog;
    /** v1.67 beta3：主窗更新探针面板。 */
    private TextView tvProbe;
    private Button btnClearProbe;
    private String lastProbeText = "";
    private ScrollView scrollLog;
    // v1.57 beta2：开源声明区与关于按钮
    private TextView tvOpenSource;
    private TextView tvRepoLink;
    private Button btnAbout;
    private SharedPreferences prefs;

    // 本地镜像状态（每 1s 从服务同步刷新）
    private boolean linkOn = false;

    // 定时刷新服务状态与日志
    private final Handler logPoller = new Handler(Looper.getMainLooper());
    private boolean pollerRunning = false;
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!pollerRunning) return;
            linkOn = CameraOverlayService.isLinkActive();
            updateLinkButton();
            updateStateText();
            for (String line : CameraOverlayService.drainLogs()) {
                appendLog(line);
            }
            refreshProbePanel();
            logPoller.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("cameraassist", MODE_PRIVATE);

        btnOverlay = findViewById(R.id.btn_overlay);
        btnCamera = findViewById(R.id.btn_camera);
        btnCameraTest = findViewById(R.id.btn_camera_test);
        btnHazardDual = findViewById(R.id.btn_hazard_dual);
        etHost = findViewById(R.id.et_host);
        etPort = findViewById(R.id.et_port);
        swEnable = findViewById(R.id.sw_enable);
        btnSlotLeft = findViewById(R.id.btn_slot_left);
        btnSlotRight = findViewById(R.id.btn_slot_right);
        btnBsd = findViewById(R.id.btn_bsd);
        btnBsdAlways = findViewById(R.id.btn_bsd_always);
        btnBsdAutoOverlay = findViewById(R.id.btn_bsd_auto_overlay);
        btnBsdRedOverlay = findViewById(R.id.btn_bsd_red_overlay);
        btnSlotSyncSize = findViewById(R.id.btn_slot_sync_size);
        btnSlotReset = findViewById(R.id.btn_slot_reset);
        btnClearLog = findViewById(R.id.btn_clear_log);
        tvState = findViewById(R.id.tv_state);
        tvLog = findViewById(R.id.tv_log);
        tvProbe = findViewById(R.id.tv_probe);
        btnClearProbe = findViewById(R.id.btn_clear_probe);
        if (btnClearProbe != null) btnClearProbe.setOnClickListener(this);
        refreshProbePanel();
        tvOpenSource = findViewById(R.id.tv_open_source);
        tvRepoLink = findViewById(R.id.tv_repo_link);
        btnAbout = findViewById(R.id.btn_about);
        scrollLog = findViewById(R.id.scroll_log);

        btnOverlay.setOnClickListener(this);
        btnCamera.setOnClickListener(this);
        btnCameraTest.setOnClickListener(this);
        btnHazardDual.setOnClickListener(this);
        btnSlotLeft.setOnClickListener(this);
        btnSlotRight.setOnClickListener(this);
        btnBsd.setOnClickListener(this);
        btnBsdAlways.setOnClickListener(this);
        btnBsdAutoOverlay.setOnClickListener(this);
        btnBsdRedOverlay.setOnClickListener(this);
        btnSlotSyncSize.setOnClickListener(this);
        btnSlotReset.setOnClickListener(this);
        btnClearLog.setOnClickListener(this);
        tvRepoLink.setOnClickListener(this);
        btnAbout.setOnClickListener(this);

        etHost.setText(prefs.getString("host", "127.0.0.1"));
        etPort.setText(String.valueOf(prefs.getInt("port", 5050)));

        swEnable.setOnClickListener(this);

        updateConfigButtons();

        // 启动日志显示版本号，便于手机端直接核对安装版本
        appendLog("CameraAssist v1.67_beta5 启动（versionCode=79）");

        // v1.57 beta2：启动即校验 APK 签名指纹，非官方签名仅警告不阻断
        checkSignature();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionButtons();
        updateConfigButtons();
        updateStateText();
        updateLinkButton();
        startLogPoller();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopLogPoller();
    }

    @Override
    protected void onDestroy() {
        // v1.24：联动与浮窗全部由常驻前台服务负责，退出主界面不影响服务
        stopLogPoller();
        super.onDestroy();
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btn_camera_test) {
            // v1.61：单次点击仅提示"打灯辅助已开启"；1.5s 内连点 10 次才进入双路镜头预览页（隐藏调试入口）
            long now = System.currentTimeMillis();
            if (now - cameraTestLastClickMs > CAMERA_TEST_CLICK_INTERVAL_MS) {
                cameraTestClickCount = 0;
            }
            cameraTestLastClickMs = now;
            cameraTestClickCount++;
            if (cameraTestClickCount >= CAMERA_TEST_CLICK_TARGET) {
                cameraTestClickCount = 0;
                appendLog("调试入口已触发：进入双路摄像头预览");
                startActivity(new Intent(this, CameraTestActivity.class));
            } else if (cameraTestClickCount >= CAMERA_TEST_CLICK_TARGET - 2) {
                Toast.makeText(this, "打灯辅助已开启（再点 "
                        + (CAMERA_TEST_CLICK_TARGET - cameraTestClickCount) + " 次进入调试页）",
                        Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "打灯辅助已开启", Toast.LENGTH_SHORT).show();
            }
        } else if (id == R.id.btn_hazard_dual) {
            // v1.65 beta1：双闪联动影像开关（默认关；开启后双闪同时触发左右两侧影像，便于测双窗）
            boolean on = !prefs.getBoolean("hazard_dual_enabled", false);
            CameraOverlayService.setHazardDualEnabled(this, on);
            if (on) {
                appendLog("双闪联动影像：开启（打双闪时优先窗出左侧、次级窗出右侧）");
                Toast.makeText(this, "双闪联动已开启：打双闪即出左右两窗", Toast.LENGTH_LONG).show();
            } else {
                appendLog("双闪联动影像：关闭（打双闪不再显示影像）");
                Toast.makeText(this, "双闪联动已关闭：打双闪不显示影像", Toast.LENGTH_SHORT).show();
            }
            updateConfigButtons();
        } else if (id == R.id.btn_overlay) {
            if (hasOverlay()) {
                Toast.makeText(this, "悬浮窗权限已授予", Toast.LENGTH_SHORT).show();
            } else {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        } else if (id == R.id.btn_camera) {
            if (checkSelfPermission(Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "相机权限已授予", Toast.LENGTH_SHORT).show();
            } else {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            }
        } else if (id == R.id.sw_enable) {
            if (linkOn) {
                disableLink();
            } else {
                enableLink();
            }
        } else if (id == R.id.btn_slot_left) {
            // v1.58：进入左状态浮窗编辑——同时弹出优先窗 P 与次级窗 S
            CameraOverlayService.editSlotLayout(this, "LEFT");
            appendLog("已请求进入左状态浮窗编辑（P/S 两窗同时可拖拽/缩放，点 ✕ 保存退出）");
            Toast.makeText(this, "左状态：拖动改位置，拖边改大小，双指缩放画面", Toast.LENGTH_LONG).show();
        } else if (id == R.id.btn_slot_right) {
            // v1.58：进入右状态浮窗编辑（窗口位置沿用左状态，仅改画面）
            CameraOverlayService.editSlotLayout(this, "RIGHT");
            appendLog("已请求进入右状态浮窗编辑（两窗画面按右状态落盘，几何沿用左状态）");
            Toast.makeText(this, "右状态：拖动改位置，拖边改大小，双指缩放画面", Toast.LENGTH_LONG).show();
        } else if (id == R.id.btn_bsd) {
            // v1.52：BSD 盲区提醒开关（联动运行时挂载/摘除窄条）
            boolean on = !prefs.getBoolean("bsd_warning_enabled", false);
            CameraOverlayService.applyConfig(this, on,
                    prefs.getBoolean("bsd_show_always", false),
                    prefs.getBoolean("bsd_auto_overlay", true),
                    prefs.getBoolean("bsd_red_overlay", true));
            appendLog("BSD 盲区提醒：" + (on ? "开启" : "关闭") + "（Phase 1 车机验证态，属性未确认时显示灰条）");
            updateConfigButtons();
        } else if (id == R.id.btn_bsd_always) {
            // v1.53 beta2：BSD 无车常显调试开关（无车也显示窄条，供拖动调位置/测试）
            boolean on = !prefs.getBoolean("bsd_show_always", false);
            CameraOverlayService.applyConfig(this,
                    prefs.getBoolean("bsd_warning_enabled", false), on,
                    prefs.getBoolean("bsd_auto_overlay", true),
                    prefs.getBoolean("bsd_red_overlay", true));
            appendLog("BSD 无车常显：" + (on ? "开启" : "关闭") + "（无车时窄条保持显示，可拖动调位置）");
            updateConfigButtons();
        } else if (id == R.id.btn_bsd_auto_overlay) {
            // v1.54：BSD 预警自动弹窗开关（默认开；开启后边沿 0->1 自动弹对应侧浮窗）
            boolean on = !prefs.getBoolean("bsd_auto_overlay", true);
            CameraOverlayService.applyConfig(this,
                    prefs.getBoolean("bsd_warning_enabled", false),
                    prefs.getBoolean("bsd_show_always", false), on,
                    prefs.getBoolean("bsd_red_overlay", true));
            appendLog("BSD 自动弹窗：" + (on ? "开启" : "关闭") + "（BSD 预警边沿自动弹对应侧摄像头浮窗，最低优先级）");
            updateConfigButtons();
        } else if (id == R.id.btn_bsd_red_overlay) {
            // v1.54 beta3：BSD 预警红色遮罩开关（默认开；关闭后预警弹窗不再显示红色边界遮罩/呼吸动画）
            boolean on = !prefs.getBoolean("bsd_red_overlay", true);
            CameraOverlayService.applyConfig(this,
                    prefs.getBoolean("bsd_warning_enabled", false),
                    prefs.getBoolean("bsd_show_always", false),
                    prefs.getBoolean("bsd_auto_overlay", true), on);
            appendLog("BSD 红色遮罩：" + (on ? "开启" : "关闭") + "（预警弹窗边界红色警示呼吸圈）");
            updateConfigButtons();
        } else if (id == R.id.btn_slot_sync_size) {
            // v1.58：次级窗 S 宽高套用优先窗 P，各自位置保持不变
            CameraOverlayService.syncSlotSize(this);
            appendLog("已请求一键同步大小（次级窗 S 宽高跟随优先窗 P）");
            Toast.makeText(this, "次级窗大小已同步为优先窗", Toast.LENGTH_SHORT).show();
        } else if (id == R.id.btn_slot_reset) {
            // v1.58：两窗位置/大小与四套画面配置复位为默认
            CameraOverlayService.resetSlotGeometry(this);
            appendLog("已请求复位浮窗位置/大小/画面为默认");
            Toast.makeText(this, "浮窗位置/大小/画面已复位", Toast.LENGTH_SHORT).show();
        } else if (id == R.id.btn_clear_log) {
            // v1.41：清空日志显示区与内存日志
            tvLog.setText("");
            CameraOverlayService.clearLogs();
            appendLog("日志已清空");
        } else if (id == R.id.btn_clear_probe) {
            // v1.67 beta3：清空主窗更新探针记录
            CameraOverlayService.clearProbeRecords();
            lastProbeText = "";
            refreshProbePanel();
        } else if (id == R.id.btn_about) {
            // v1.57 beta2：关于弹窗（版本/作者/仓库/LICENSE 摘要/防倒卖提示）
            showAboutDialog();
        } else if (id == R.id.tv_repo_link) {
            // v1.57 beta2：开源仓库链接交系统浏览器打开
            openRepoLink();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            boolean ok = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            appendLog(ok ? "相机权限已授予" : "相机权限被拒绝");
            refreshPermissionButtons();
        }
    }

    private boolean hasOverlay() {
        return Settings.canDrawOverlays(this);
    }

    private void refreshPermissionButtons() {
        boolean overlay = hasOverlay();
        boolean camera = checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        btnOverlay.setText(overlay ? "悬浮窗权限：已授予" : "申请悬浮窗权限（未授予）");
        btnCamera.setText(camera ? "相机权限：已授予" : "申请相机权限（未授予）");
    }

    /** v1.52/v1.53 beta2/v1.54/v1.54 beta4：按 prefs 刷新 BSD / BSD 无车常显 / BSD 自动弹窗开关按钮文本。
     * v1.54 beta4：焦点抑制已停用，不再刷新对应按钮。 */
    private void updateConfigButtons() {
        if (btnBsd == null) return;
        // v1.65 beta1：双闪联动影像开关（默认关）
        if (btnHazardDual != null) {
            boolean hazardDual = prefs.getBoolean("hazard_dual_enabled", false);
            btnHazardDual.setBackgroundResource(hazardDual ? R.drawable.bg_button_active : R.drawable.bg_button);
            btnHazardDual.setTextColor(hazardDual ? 0xFFFFD98A : 0xFFFFFFFF);
            btnHazardDual.setText(hazardDual ? "双闪联动影像：开启" : "双闪联动影像：关闭");
        }
        boolean bsd = prefs.getBoolean("bsd_warning_enabled", false);
        boolean bsdAlways = prefs.getBoolean("bsd_show_always", false);
        boolean bsdAuto = prefs.getBoolean("bsd_auto_overlay", true);
        boolean bsdRed = prefs.getBoolean("bsd_red_overlay", true);
        btnBsd.setBackgroundResource(bsd ? R.drawable.bg_button_active : R.drawable.bg_button);
        btnBsd.setTextColor(bsd ? 0xFFFFD98A : 0xFFFFFFFF);
        btnBsd.setText(bsd ? "BSD 盲区提醒：开启" : "BSD 盲区提醒：关闭");
        if (btnBsdAlways != null) {
            btnBsdAlways.setBackgroundResource(bsdAlways ? R.drawable.bg_button_active : R.drawable.bg_button);
            btnBsdAlways.setTextColor(bsdAlways ? 0xFFFFD98A : 0xFFFFFFFF);
            btnBsdAlways.setText(bsdAlways ? "BSD 无车常显：开" : "BSD 无车常显：关");
        }
        if (btnBsdAutoOverlay != null) {
            btnBsdAutoOverlay.setBackgroundResource(bsdAuto ? R.drawable.bg_button_active : R.drawable.bg_button);
            btnBsdAutoOverlay.setTextColor(bsdAuto ? 0xFFFFD98A : 0xFFFFFFFF);
            btnBsdAutoOverlay.setText(bsdAuto ? "BSD 自动弹窗：开" : "BSD 自动弹窗：关");
        }
        if (btnBsdRedOverlay != null) {
            btnBsdRedOverlay.setBackgroundResource(bsdRed ? R.drawable.bg_button_active : R.drawable.bg_button);
            btnBsdRedOverlay.setTextColor(bsdRed ? 0xFFFFD98A : 0xFFFFFFFF);
            btnBsdRedOverlay.setText(bsdRed ? "BSD 红遮罩：开" : "BSD 红遮罩：关");
        }
    }

    /** v1.52/v1.53 beta2/v1.54 beta3：把原厂相机包名输入框写入 prefs 并推送配置广播让服务即时生效。
     * v1.54 beta4：焦点抑制与原厂相机包名功能已移除，本方法废弃。 */
    private void saveCameraPkgPrefsAndApply() {
        CameraOverlayService.applyConfig(this,
                prefs.getBoolean("bsd_warning_enabled", false),
                prefs.getBoolean("bsd_show_always", false),
                prefs.getBoolean("bsd_auto_overlay", true),
                prefs.getBoolean("bsd_red_overlay", true));
        appendLog("配置已同步（原厂相机包名输入框已移除）");
    }

    private void updateStateText() {
        boolean link = CameraOverlayService.isLinkActive();
        String st;
        if (link) {
            st = "状态：联动运行中（前台服务常驻，退后台可弹窗）";
        } else {
            st = "状态：未运行";
        }
        tvState.setText(st);
    }

    private void enableLink() {
        String host = etHost.getText().toString().trim();
        int port;
        try {
            port = Integer.parseInt(etPort.getText().toString().trim());
        } catch (Exception e) {
            appendLog("端口格式错误");
            linkOn = false;
            updateLinkButton();
            return;
        }
        prefs.edit().putString("host", host).putInt("port", port).apply();
        // v1.52：联动开启时同步配置广播（BSD/常显/自动弹窗/红遮罩一次生效；
        // v1.54 beta4：焦点抑制与原厂相机包名已移除，不再下发包名）
        saveCameraPkgPrefsAndApply();
        // v1.41：开启联动时先经已建立的 ADB 连接自动授予相机与悬浮窗权限（失败不阻塞开关流程）
        appendLog("正在经 ADB 自动授权相机与悬浮窗权限…");
        autoGrant(host, port);
        if (!hasOverlay()) {
            appendLog("悬浮窗权限仍不可用（自动授权未生效或已跳过），浮窗可能无法弹出");
        }
        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            appendLog("相机权限仍不可用（自动授权未生效或已跳过），预览可能黑屏");
        }
        // v1.24：轮询由常驻前台服务 CameraOverlayService 持有，Activity 不再直接创建 SignalMonitor
        CameraOverlayService.startLink(this, host, port);
        linkOn = CameraOverlayService.isLinkActive();
        updateLinkButton();
        updateStateText();
    }

    /**
     * v1.41：经 ADB shell 自动授权相机与悬浮窗权限。
     * 使用与 v1.40 相同的 adb_key.pem 建立已认证连接，以 shell 身份执行授权命令；
     * 已授权则跳过对应命令；任一步失败仅写日志，不阻塞打灯联动开关流程。
     */
    private void autoGrant(String host, int port) {
        byte[] pem;
        try {
            pem = readRawAdbKey();
        } catch (Exception e) {
            appendLog("读取 adb_key.pem 失败，跳过自动授权：" + e.getMessage());
            return;
        }
        AdbClient adb = null;
        try {
            adb = new AdbClient(host, port, pem);
            boolean cameraGranted = checkSelfPermission(Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED;
            if (!cameraGranted) {
                String r = adb.exec("pm grant " + getPackageName() + " android.permission.CAMERA");
                appendLog("ADB 授权相机：" + (r == null || r.trim().isEmpty() ? "已执行（无输出）" : r.trim()));
            } else {
                appendLog("相机权限已授予，跳过授权命令");
            }
            if (!hasOverlay()) {
                String r = adb.exec("appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow");
                appendLog("ADB 授权悬浮窗：" + (r == null || r.trim().isEmpty() ? "已执行（无输出）" : r.trim()));
            } else {
                appendLog("悬浮窗权限已授予，跳过授权命令");
            }
        } catch (Exception e) {
            appendLog("ADB 自动授权失败（不影响联动开关）：" + e.getMessage());
        } finally {
            if (adb != null) adb.close();
        }
    }

    /** 读取内置 ADB 私钥（PKCS#8 PEM），供自动授权复用已认证连接。 */
    private byte[] readRawAdbKey() throws Exception {
        InputStream is = getResources().openRawResource(R.raw.adb_key);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            is.close();
        }
    }

    private void disableLink() {
        CameraOverlayService.stopLink(this);
        linkOn = false;
        updateLinkButton();
        updateStateText();
    }

    /** 启动 1s 定时刷新：同步服务状态与日志到主界面。 */
    private void startLogPoller() {
        if (pollerRunning) return;
        pollerRunning = true;
        logPoller.post(refreshRunnable);
    }

    /** 停止定时刷新。 */
    private void stopLogPoller() {
        pollerRunning = false;
        logPoller.removeCallbacks(refreshRunnable);
    }

    /** 刷新打灯联动按钮样式：开启=金色激活态，关闭=普通深色。 */
    private void updateLinkButton() {
        if (swEnable == null) return;
        swEnable.setBackgroundResource(linkOn ? R.drawable.bg_button_active : R.drawable.bg_button);
        swEnable.setTextColor(linkOn ? 0xFFFFD98A : 0xFFFFFFFF);
        swEnable.setText(linkOn ? "浮窗总开关：开启" : "浮窗总开关：关闭");
    }

    /** v1.67 beta3：刷新「主窗更新探针」面板。内容有变化才写，避免每秒重排。 */
    private void refreshProbePanel() {
        if (tvProbe == null) return;
        // v1.67 beta4：面板每秒刷新时兜底确认探针在跑——服务活着但探针沉默会自动重启，
        // 杜绝「主窗卡住、面板却一条记录都没有」的静默失败。
        CameraOverlayService.ensureProbeRunning();
        String text = CameraOverlayService.getProbeText();
        if (text.equals(lastProbeText)) return;
        lastProbeText = text;
        tvProbe.setText(text);
    }

    private void appendLog(String line) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String text = tvLog.getText().toString();
        text = text + "\n" + time + " " + line;
        String[] lines = text.split("\\n");
        if (lines.length > MAX_LOG_LINES) {
            StringBuilder sb = new StringBuilder();
            for (int i = lines.length - MAX_LOG_LINES; i < lines.length; i++) {
                sb.append(lines[i]).append('\n');
            }
            text = sb.toString().trim();
        }
        tvLog.setText(text);
        // v1.33：新日志默认滚到底部；用户已手动上滑回看（不贴近底部）时不强制拉底
        scrollLog.post(() -> {
            View child = scrollLog.getChildAt(0);
            if (child != null
                    && scrollLog.getScrollY() + scrollLog.getHeight() >= child.getHeight() - 40) {
                scrollLog.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    /** v1.57 beta2：启动校验当前 APK 签名证书 SHA-256 与官方指纹是否一致；不一致仅警告不阻断。 */
    private void checkSignature() {
        String cur = getApkSignatureSha256();
        if (OFFICIAL_SIGNATURE_SHA256.equalsIgnoreCase(cur)) {
            appendLog("签名校验通过（官方签名）");
        } else {
            appendLog("签名校验失败：非官方签名，可能被二次打包倒卖");
            AlertDialog.Builder b = new AlertDialog.Builder(this);
            b.setTitle("签名校验警告");
            b.setMessage("检测到非官方签名版本，可能被二次打包倒卖，请从官方渠道 github.com/Hades-Heyds/P7_ViewAssist 获取");
            b.setPositiveButton("我知道了", null);
            b.show();
        }
    }

    /** 读取当前 APK 签名证书字节并计算 SHA-256（hex 小写）。 */
    private String getApkSignatureSha256() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(),
                    PackageManager.GET_SIGNATURES);
            if (pi.signatures == null || pi.signatures.length == 0) return "";
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(pi.signatures[0].toByteArray());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            appendLog("读取签名失败：" + e.getMessage());
            return "";
        }
    }

    /** v1.57 beta2：关于弹窗——版本/作者/仓库链接/LICENSE 全文/防倒卖提示，可关闭。 */
    private void showAboutDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(48, 28, 48, 20);

        TextView tvApp = new TextView(this);
        tvApp.setText("P7全视影像");
        tvApp.setTextSize(21);
        tvApp.setTextColor(0xFFFFFFFF);
        tvApp.setTypeface(tvApp.getTypeface(), android.graphics.Typeface.BOLD);
        panel.addView(tvApp);

        TextView tvMeta = new TextView(this);
        tvMeta.setText("版本：1.67_beta5\n作者：Hades-Heyds");
        tvMeta.setTextSize(15);
        tvMeta.setTextColor(0xFFFFFFFF);
        tvMeta.setLineSpacing(0, 1.1f);
        tvMeta.setPadding(0, 10, 0, 8);
        panel.addView(tvMeta);

        TextView tvLink = new TextView(this);
        tvLink.setText("开源仓库：https://github.com/Hades-Heyds/P7_ViewAssist");
        tvLink.setTextSize(14);
        tvLink.setTextColor(0xFF80C8FF);
        tvLink.setPadding(0, 0, 0, 12);
        tvLink.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openRepoLink();
            }
        });
        panel.addView(tvLink);

        TextView tvLicense = new TextView(this);
        String license;
        try {
            license = readRawLicense();
        } catch (Exception e) {
            license = "免费开源 · 个人学习使用 · 禁止未经授权的商业转售/二次分发/倒卖\n分发须保留版权与开源地址 · 作者不担责";
        }
        tvLicense.setText(license);
        tvLicense.setTextSize(13);
        tvLicense.setTextColor(0xC7FFFFFF);
        tvLicense.setLineSpacing(0, 1.15f);
        panel.addView(tvLicense);

        TextView tvWarn = new TextView(this);
        tvWarn.setText("本软件免费开源 · 禁止倒卖，请勿购买倒卖版本");
        tvWarn.setTextSize(15);
        tvWarn.setTextColor(0xFFFFD98A);
        tvWarn.setTypeface(tvWarn.getTypeface(), android.graphics.Typeface.BOLD);
        tvWarn.setPadding(0, 14, 0, 0);
        panel.addView(tvWarn);

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("关于 P7全视影像");
        b.setView(panel);
        b.setPositiveButton("关闭", null);
        b.show();
    }

    /** v1.57 beta2：开源仓库链接交系统浏览器打开（ACTION_VIEW）。 */
    private void openRepoLink() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/Hades-Heyds/P7_ViewAssist"));
            startActivity(i);
        } catch (Exception e) {
            appendLog("打开开源仓库链接失败：" + e.getMessage());
            Toast.makeText(this, "无法打开浏览器，请手动访问 github.com/Hades-Heyds/P7_ViewAssist",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** 读取 res/raw/LICENSE.txt 全文（关于弹窗引用）。 */
    private String readRawLicense() throws Exception {
        InputStream is = getResources().openRawResource(R.raw.LICENSE);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } finally {
            is.close();
        }
    }
}
