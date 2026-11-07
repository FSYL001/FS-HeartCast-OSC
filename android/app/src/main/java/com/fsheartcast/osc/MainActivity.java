package com.fsheartcast.osc;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.fsheartcast.osc.ui.HeartRateRingView;
import com.fsheartcast.osc.ui.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 手机端主界面：心率页 / 设置页两个标签，底部导航切换，带过渡与数值动画。 */
public final class MainActivity extends Activity {

    private static final int REQ_PERMISSIONS = 1001;
    private static final int REQ_ENABLE_BLUETOOTH = 1002;
    private static final long SAVE_DEBOUNCE_MS = 500;
    private static final int PAGE_LIVE = 0;
    private static final int PAGE_SETTINGS = 1;

    private HeartRateCore core;
    private final Handler main = new Handler(Looper.getMainLooper());

    // 页面
    private ScrollView pageLive;
    private ScrollView pageSettings;
    private int currentPage = PAGE_LIVE;

    // 标签栏
    private LinearLayout tabLive;
    private LinearLayout tabSettings;
    private ImageView iconLive;
    private ImageView iconSettings;
    private TextView labelLive;
    private TextView labelSettings;

    // 心率页
    private HeartRateRingView ring;
    private TextView tvBpm;
    private TextView tvDeviceName;
    private TextView tvLinkState;
    private TextView tvStatusChip;
    private TextView tvDeviceCount;
    private TextView tvEmpty;
    private TextView tvTarget;
    private TextView tvOscStatus;
    private View statusDot;
    private LinearLayout deviceList;
    private LinearLayout advancedPanel;
    private TextView btnScan;
    private TextView btnConnect;
    private TextView btnClear;
    private TextView btnTest;
    private TextView btnAdvanced;
    private Switch swOsc;
    private EditText etIp;
    private EditText etPort;
    private EditText etHr;
    private EditText etBeat;
    private TextView btnDiscover;
    private TextView tvDiscoverStatus;
    private TextView tvUpdateState;

    // 设置页
    private Switch swAutoScan;
    private Switch swBootStart;
    private Switch swKeepScreenOn;
    private Switch swBackground;
    private FrameLayout segTheme;
    private View segThemeThumb;
    private TextView segSystem;
    private TextView segLight;
    private TextView segDark;
    private LinearLayout accentRow;
    private TextView tvLanguageValue;
    private TextView tvVersion;
    private TextView tvEmail;

    private boolean serviceStarted;
    private boolean updatingSwitches;
    private boolean advancedExpanded;
    private int appliedStyle;
    private String appliedLanguage;
    private int displayedBpm = -1;
    private int lastDeviceCount = -1;
    private String deviceSignature;
    private int segmentIndex;
    private ValueAnimator bpmAnimator;
    private ValueAnimator bpmScaleAnimator;

    private final Runnable saveSettingsRunnable = new Runnable() {
        @Override
        public void run() {
            flushOscFields();
        }
    };

    private final HeartRateCore.Listener coreListener = new HeartRateCore.Listener() {
        @Override
        public void onSnapshot(HeartRateCore.Snapshot snapshot) {
            render(snapshot);
        }

        @Override
        public void onDevicesChanged() {
            renderDevices(core.deviceList(), core.getActiveAddress());
        }

        @Override
        public void onLog(String line) {
            // 界面不展示日志流
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(Lang.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        appliedStyle = Appearance.styleRes(this);
        appliedLanguage = Prefs.language(this);
        Appearance.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        core = HeartRateCore.get(this);
        bindViews();
        setupTabs();
        setupLivePage();
        setupSettingsPage();
        loadOscFields();

        if (savedInstanceState != null) {
            currentPage = savedInstanceState.getInt("page", PAGE_LIVE);
        }
        applyPage(currentPage, false);
        applyKeepScreenOn();

        if (Prefs.autoScan(this) && hasBluetoothPermissions() && isBluetoothEnabled()) {
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!isFinishing() && currentPage == PAGE_LIVE) {
                        startScan();
                    }
                }
            }, 420);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("page", currentPage);
    }

    // ---------------------------------------------------------------- 绑定

    private void bindViews() {
        pageLive = (ScrollView) findViewById(R.id.pageLive);
        pageSettings = (ScrollView) findViewById(R.id.pageSettings);

        tabLive = (LinearLayout) findViewById(R.id.tabLive);
        tabSettings = (LinearLayout) findViewById(R.id.tabSettings);
        iconLive = (ImageView) findViewById(R.id.iconLive);
        iconSettings = (ImageView) findViewById(R.id.iconSettings);
        labelLive = (TextView) findViewById(R.id.labelLive);
        labelSettings = (TextView) findViewById(R.id.labelSettings);

        ring = (HeartRateRingView) findViewById(R.id.ring);
        tvBpm = (TextView) findViewById(R.id.tvBpm);
        tvDeviceName = (TextView) findViewById(R.id.tvDeviceName);
        tvLinkState = (TextView) findViewById(R.id.tvLinkState);
        tvStatusChip = (TextView) findViewById(R.id.tvStatusChip);
        tvDeviceCount = (TextView) findViewById(R.id.tvDeviceCount);
        tvEmpty = (TextView) findViewById(R.id.tvEmpty);
        tvTarget = (TextView) findViewById(R.id.tvTarget);
        tvOscStatus = (TextView) findViewById(R.id.tvOscStatus);
        statusDot = findViewById(R.id.statusDot);
        deviceList = (LinearLayout) findViewById(R.id.deviceList);
        advancedPanel = (LinearLayout) findViewById(R.id.advancedPanel);
        btnScan = (TextView) findViewById(R.id.btnScan);
        btnConnect = (TextView) findViewById(R.id.btnConnect);
        btnClear = (TextView) findViewById(R.id.btnClear);
        btnTest = (TextView) findViewById(R.id.btnTest);
        btnAdvanced = (TextView) findViewById(R.id.btnAdvanced);
        swOsc = (Switch) findViewById(R.id.swOsc);
        btnDiscover = (TextView) findViewById(R.id.btnDiscover);
        tvDiscoverStatus = (TextView) findViewById(R.id.tvDiscoverStatus);
        tvUpdateState = (TextView) findViewById(R.id.tvUpdateState);
        etIp = (EditText) findViewById(R.id.etIp);
        etPort = (EditText) findViewById(R.id.etPort);
        etHr = (EditText) findViewById(R.id.etHr);
        etBeat = (EditText) findViewById(R.id.etBeat);

        swAutoScan = (Switch) findViewById(R.id.swAutoScan);
        swBootStart = (Switch) findViewById(R.id.swBootStart);
        swKeepScreenOn = (Switch) findViewById(R.id.swKeepScreenOn);
        swBackground = (Switch) findViewById(R.id.swBackground);
        segTheme = (FrameLayout) findViewById(R.id.segTheme);
        segThemeThumb = findViewById(R.id.segThemeThumb);
        segSystem = (TextView) findViewById(R.id.segSystem);
        segLight = (TextView) findViewById(R.id.segLight);
        segDark = (TextView) findViewById(R.id.segDark);
        accentRow = (LinearLayout) findViewById(R.id.accentRow);
        tvLanguageValue = (TextView) findViewById(R.id.tvLanguageValue);
        tvVersion = (TextView) findViewById(R.id.tvVersion);
        tvEmail = (TextView) findViewById(R.id.tvEmail);
    }

    // ---------------------------------------------------------------- 标签栏

    private void setupTabs() {
        tabLive.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyPage(PAGE_LIVE, true);
            }
        });
        tabSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyPage(PAGE_SETTINGS, true);
            }
        });
        Ui.pressAnimation(tabLive);
        Ui.pressAnimation(tabSettings);
    }

    private void applyPage(final int page, boolean animate) {
        currentPage = page;
        final View in = page == PAGE_LIVE ? pageLive : pageSettings;
        final View out = page == PAGE_LIVE ? pageSettings : pageLive;

        int accent = Ui.color(this, R.attr.accent);
        int muted = Ui.color(this, R.attr.textMuted);
        int active = accent;
        int inactive = muted;

        if (page == PAGE_LIVE) {
            animateTab(iconLive, labelLive, true, accent, muted);
            animateTab(iconSettings, labelSettings, false, active, inactive);
        } else {
            animateTab(iconLive, labelLive, false, accent, muted);
            animateTab(iconSettings, labelSettings, true, active, inactive);
        }

        if (!animate) {
            in.setVisibility(View.VISIBLE);
            in.setAlpha(1f);
            in.setTranslationY(0f);
            out.setVisibility(View.GONE);
            return;
        }

        out.animate().cancel();
        in.animate().cancel();
        out.animate().alpha(0f).translationY(Ui.dp(this, 6)).setDuration(120)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        out.setVisibility(View.GONE);
                    }
                }).start();

        in.setVisibility(View.VISIBLE);
        in.setAlpha(0f);
        in.setTranslationY(Ui.dp(this, 14));
        in.animate().alpha(1f).translationY(0f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator(1.4f)).start();
    }

    private void animateTab(final ImageView icon, final TextView label, final boolean selected,
                            final int accent, final int muted) {
        int fromLabel = label.getCurrentTextColor();
        int toLabel = selected ? accent : muted;
        Ui.animateColor(fromLabel, toLabel, new Ui.ColorSink() {
            @Override
            public void accept(int color) {
                label.setTextColor(color);
            }
        }, 200);

        if (selected) {
            icon.setColorFilter(accent);
            icon.animate().scaleX(1.14f).scaleY(1.14f).setDuration(140)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            icon.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
                        }
                    }).start();
        } else {
            icon.setColorFilter(muted);
        }
    }

    // ---------------------------------------------------------------- 心率页

    private void setupLivePage() {
        btnScan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (core.isScanning()) {
                    core.stopScan();
                    stopServiceIfNeeded();
                } else {
                    startScan();
                }
            }
        });

        btnConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (core.isConnected()) {
                    core.disconnect();
                } else if (core.getActiveAddress() == null) {
                    toast(getString(R.string.select_device_first));
                } else {
                    core.connectActive();
                }
            }
        });

        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                core.clearDevices();
                renderDevices(core.deviceList(), null);
            }
        });

        btnTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean ok = core.sendTest();
                toast(getString(ok ? R.string.osc_test_sent : R.string.osc_test_failed));
            }
        });

        Ui.pressAnimation(btnScan);
        Ui.pressAnimation(btnConnect);
        Ui.pressAnimation(btnTest);

        btnDiscover.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                discoverPc();
            }
        });
        Ui.pressAnimation(btnDiscover);

        btnAdvanced.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                advancedExpanded = !advancedExpanded;
                TransitionManager.beginDelayedTransition((ViewGroup) findViewById(android.R.id.content),
                        new AutoTransition().setDuration(220));
                advancedPanel.setVisibility(advancedExpanded ? View.VISIBLE : View.GONE);
                setEndIcon(btnAdvanced,
                        advancedExpanded ? R.drawable.ic_chevron_down : R.drawable.ic_chevron_right,
                        Ui.color(MainActivity.this, R.attr.textSecondary));
            }
        });

        swOsc.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                if (updatingSwitches) {
                    return;
                }
                core.setOscEnabled(isChecked);
            }
        });

        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                main.removeCallbacks(saveSettingsRunnable);
                main.postDelayed(saveSettingsRunnable, SAVE_DEBOUNCE_MS);
            }
        };
        etIp.addTextChangedListener(watcher);
        etPort.addTextChangedListener(watcher);
        etHr.addTextChangedListener(watcher);
        etBeat.addTextChangedListener(watcher);
    }

    private void loadOscFields() {
        etIp.setText(Prefs.ip(this));
        etPort.setText(String.valueOf(Prefs.port(this)));
        etHr.setText(Prefs.hrAddress(this));
        etBeat.setText(Prefs.beatAddress(this));
        updatingSwitches = true;
        swOsc.setChecked(Prefs.oscEnabled(this));
        updatingSwitches = false;
    }

    private void render(HeartRateCore.Snapshot snapshot) {
        if (snapshot == null) {
            return;
        }

        showBpm(snapshot.bpm);

        int accent = Ui.color(this, R.attr.accent);
        int muted = Ui.color(this, R.attr.textMuted);
        int success = Ui.color(this, R.attr.success);
        int danger = Ui.color(this, R.attr.danger);

        String chip;
        int chipColor;
        int dotColor;
        String linkState;

        if (snapshot.lastError != null && snapshot.lastError.length() > 0) {
            chip = getString(R.string.idle_chip);
            chipColor = danger;
            dotColor = danger;
            linkState = snapshot.lastError;
        } else if (snapshot.connected) {
            chip = getString(R.string.connected_chip);
            chipColor = success;
            dotColor = success;
            linkState = getString(R.string.status_connected);
        } else if (snapshot.connecting) {
            chip = getString(R.string.scanning_chip);
            chipColor = accent;
            dotColor = accent;
            linkState = getString(R.string.status_connecting);
        } else if (snapshot.scanning) {
            chip = getString(R.string.scanning_chip);
            chipColor = accent;
            dotColor = accent;
            linkState = getString(R.string.status_scanning);
        } else {
            chip = getString(R.string.idle_chip);
            chipColor = muted;
            dotColor = muted;
            linkState = getString(R.string.status_idle);
        }

        tvStatusChip.setText(chip);
        tvStatusChip.setTextColor(chipColor);
        tvLinkState.setText(linkState);
        statusDot.setBackgroundTintList(ColorStateList.valueOf(dotColor));

        if (snapshot.activeName != null && snapshot.activeName.length() > 0) {
            tvDeviceName.setText(snapshot.activeName);
        } else {
            tvDeviceName.setText(R.string.no_device);
        }

        boolean scanning = snapshot.scanning;
        btnScan.setText(scanning ? R.string.scan_stop : R.string.scan_start);
        setStartIcon(btnScan, scanning ? R.drawable.ic_stop : R.drawable.ic_play,
                Ui.color(this, R.attr.accentOn));

        boolean connected = snapshot.connected;
        btnConnect.setText(connected ? R.string.disconnect : R.string.connect_device);
        setStartIcon(btnConnect, connected ? R.drawable.ic_link : R.drawable.ic_watch,
                Ui.color(this, R.attr.textPrimary));
        btnConnect.setEnabled(connected || snapshot.activeAddress != null);
        btnConnect.setAlpha(btnConnect.isEnabled() ? 1f : 0.45f);

        tvTarget.setText(getString(R.string.target_label) + "  ·  " + snapshot.target);

        if (!updatingSwitches) {
            updatingSwitches = true;
            swOsc.setChecked(snapshot.oscEnabled);
            updatingSwitches = false;
        }

        String status = getString(R.string.status_packets,
                Integer.valueOf((int) snapshot.packetsSent));
        if (snapshot.lastError != null && snapshot.lastError.length() > 0) {
            status = snapshot.lastError;
        }
        tvOscStatus.setText(status);
    }

    /** 数值滚动 + 环进度 + 一次心跳脉冲。 */
    private void showBpm(int bpm) {
        if (bpm == displayedBpm) {
            return;
        }
        displayedBpm = bpm;

        if (bpmAnimator != null) {
            bpmAnimator.cancel();
        }
        if (bpm <= 0) {
            tvBpm.setText("--");
            ring.setBpm(0, true);
            return;
        }

        ring.setBpm(bpm, true);
        ring.pulse();

        int from = 0;
        try {
            from = Integer.parseInt(tvBpm.getText().toString().trim());
        } catch (NumberFormatException ignored) {
            from = 0;
        }
        if (from < 0 || from > 260) {
            from = 0;
        }
        final int target = bpm;
        bpmAnimator = ValueAnimator.ofInt(from, target);
        bpmAnimator.setDuration(420);
        bpmAnimator.setInterpolator(new DecelerateInterpolator(1.5f));
        bpmAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                tvBpm.setText(String.valueOf(animation.getAnimatedValue()));
            }
        });
        bpmAnimator.start();

        if (bpmScaleAnimator != null) {
            bpmScaleAnimator.cancel();
        }
        bpmScaleAnimator = ValueAnimator.ofFloat(1.06f, 1f);
        bpmScaleAnimator.setDuration(260);
        bpmScaleAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float v = (Float) animation.getAnimatedValue();
                tvBpm.setScaleX(v);
                tvBpm.setScaleY(v);
            }
        });
        bpmScaleAnimator.start();
    }

    private void renderDevices(List<DeviceEntry> devices, String selectedAddress) {
        if (deviceList == null) {
            return;
        }
        String signature = deviceSignatureOf(devices, selectedAddress);
        if (signature.equals(deviceSignature) && deviceList.getChildCount() > 0) {
            return;
        }
        deviceSignature = signature;

        boolean countChanged = devices.size() != lastDeviceCount;
        lastDeviceCount = devices.size();

        deviceList.removeAllViews();
        tvEmpty.setVisibility(devices.isEmpty() ? View.VISIBLE : View.GONE);
        tvDeviceCount.setText(devices.isEmpty() ? "" : String.valueOf(devices.size()));

        LayoutInflater inflater = LayoutInflater.from(this);
        int accent = Ui.color(this, R.attr.accent);
        int muted = Ui.color(this, R.attr.textMuted);
        int success = Ui.color(this, R.attr.success);
        int danger = Ui.color(this, R.attr.danger);

        for (int i = 0; i < devices.size(); i++) {
            final DeviceEntry entry = devices.get(i);
            View row = inflater.inflate(R.layout.item_device_row, deviceList, false);

            TextView avatar = (TextView) row.findViewById(R.id.tvAvatar);
            TextView name = (TextView) row.findViewById(R.id.tvName);
            TextView addr = (TextView) row.findViewById(R.id.tvAddr);
            TextView bpm = (TextView) row.findViewById(R.id.tvBpm);
            TextView status = (TextView) row.findViewById(R.id.tvStatus);
            View dot = row.findViewById(R.id.tvDot);

            String displayName = entry.displayName(this);
            name.setText(displayName);
            addr.setText(entry.address);
            bpm.setText(entry.bpmText());
            status.setText(entry.statusText(this));
            avatar.setText(initial(displayName));

            boolean selected = selectedAddress != null && selectedAddress.equals(entry.address);
            row.setBackgroundResource(selected ? R.drawable.bg_row_selected : R.drawable.bg_row);
            row.setAlpha(entry.online ? 1f : 0.5f);

            int stateColor = muted;
            if (entry.linkState == DeviceEntry.LINK_CONNECTED) {
                stateColor = success;
            } else if (entry.linkState == DeviceEntry.LINK_ERROR) {
                stateColor = danger;
            } else if (entry.linkState == DeviceEntry.LINK_CONNECTING) {
                stateColor = accent;
            }
            dot.setBackgroundTintList(ColorStateList.valueOf(stateColor));
            bpm.setTextColor(entry.bpm > 0 ? accent : muted);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    core.selectDevice(entry.address);
                    renderDevices(core.deviceList(), entry.address);
                }
            });

            deviceList.addView(row);
        }

        if (countChanged && !devices.isEmpty()) {
            Ui.staggerChildren(deviceList);
        }
    }

    private String deviceSignatureOf(List<DeviceEntry> devices, String selected) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < devices.size(); i++) {
            DeviceEntry e = devices.get(i);
            builder.append(e.address).append('|').append(e.name).append('|')
                    .append(e.bpm).append('|').append(e.rssi).append('|')
                    .append(e.linkState).append('|').append(e.online).append(';');
        }
        builder.append("sel=").append(selected == null ? "" : selected);
        return builder.toString();
    }

    private String initial(String name) {
        if (name == null || name.length() == 0) {
            return "?";
        }
        String trimmed = name.trim();
        if (trimmed.length() == 0) {
            return "?";
        }
        return trimmed.substring(0, 1).toUpperCase(Locale.getDefault());
    }

    // ---------------------------------------------------------------- 设置页

    private void setupSettingsPage() {
        bindSwitch(swAutoScan, Prefs.autoScan(this), new PrefSetter() {
            @Override
            public void set(boolean value) {
                Prefs.setAutoScan(MainActivity.this, value);
            }
        });
        bindSwitch(swBootStart, Prefs.bootStart(this), new PrefSetter() {
            @Override
            public void set(boolean value) {
                Prefs.setBootStart(MainActivity.this, value);
            }
        });
        bindSwitch(swKeepScreenOn, Prefs.keepScreenOn(this), new PrefSetter() {
            @Override
            public void set(boolean value) {
                Prefs.setKeepScreenOn(MainActivity.this, value);
                applyKeepScreenOn();
            }
        });
        bindSwitch(swBackground, Prefs.backgroundRun(this), new PrefSetter() {
            @Override
            public void set(boolean value) {
                Prefs.setBackgroundRun(MainActivity.this, value);
            }
        });

        setupThemeSegment();
        setupAccentDots();
        setupLanguageRow();
        setupAbout();

        tvVersion.setText(getString(R.string.version_label) + " " + versionName());
        tvEmail.setText("email@fsyl001.sbs");

        Ui.pressAnimation(segTheme);
        Ui.pressAnimation(tvLanguageValue);
    }

    private interface PrefSetter {
        void set(boolean value);
    }

    private void bindSwitch(final Switch view, boolean checked, final PrefSetter setter) {
        updatingSwitches = true;
        view.setChecked(checked);
        updatingSwitches = false;
        view.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                if (updatingSwitches) {
                    return;
                }
                setter.set(isChecked);
            }
        });
    }

    private void setupThemeSegment() {
        final String[] values = { "system", "light", "dark" };
        segmentIndex = themeIndex(Prefs.theme(this));
        segTheme.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                layoutThumb(segmentIndex, false);
            }
        });

        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int i = v == segSystem ? 0 : (v == segLight ? 1 : 2);
                if (!values[i].equals(Prefs.theme(MainActivity.this))) {
                    Prefs.setTheme(MainActivity.this, values[i]);
                    segmentIndex = i;
                    layoutThumb(i, true);
                    main.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            recreate();
                        }
                    }, 190);
                }
            }
        };
        segSystem.setOnClickListener(listener);
        segLight.setOnClickListener(listener);
        segDark.setOnClickListener(listener);
        styleSegmentLabels(segmentIndex);
        layoutThumb(segmentIndex, false);
    }

    private static int themeIndex(String theme) {
        if ("light".equals(theme)) {
            return 1;
        }
        if ("dark".equals(theme)) {
            return 2;
        }
        return 0;
    }

    private void layoutThumb(int index, boolean animate) {
        int width = segTheme.getWidth() - segTheme.getPaddingLeft() - segTheme.getPaddingRight();
        if (width <= 0) {
            return;
        }
        int item = width / 3;
        int targetX = segTheme.getPaddingLeft() + item * index;
        if (animate) {
            segThemeThumb.animate().translationX(targetX).setDuration(200)
                    .setInterpolator(new DecelerateInterpolator()).start();
        } else {
            segThemeThumb.setTranslationX(targetX);
        }
        segThemeThumb.getLayoutParams().width = item;
        segThemeThumb.requestLayout();
        styleSegmentLabels(index);
    }

    private void styleSegmentLabels(int index) {
        int active = Ui.color(this, R.attr.textPrimary);
        int inactive = Ui.color(this, R.attr.textSecondary);
        segSystem.setTextColor(index == 0 ? active : inactive);
        segLight.setTextColor(index == 1 ? active : inactive);
        segDark.setTextColor(index == 2 ? active : inactive);
    }

    private void setupAccentDots() {
        accentRow.removeAllViews();
        int selected = Appearance.accentIndex(Prefs.accent(this));
        int size = Ui.dp(this, 24);
        int gap = Ui.dp(this, 12);

        for (int i = 0; i < Appearance.ACCENTS.length; i++) {
            final int index = i;
            int color = getColor(accentColorRes(i));
            FrameLayout holder = new FrameLayout(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.setMarginEnd(gap);
            holder.setLayoutParams(params);

            View dot = new View(this);
            FrameLayout.LayoutParams dotParams = new FrameLayout.LayoutParams(size - Ui.dp(this, 8),
                    size - Ui.dp(this, 8));
            dotParams.gravity = android.view.Gravity.CENTER;
            dot.setLayoutParams(dotParams);
            dot.setBackground(Ui.circle(color, size - Ui.dp(this, 8)));
            holder.addView(dot);

            if (i == selected) {
                holder.setBackground(Ui.ring(color, Ui.dp(this, 2)));
            }

            holder.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!Appearance.ACCENTS[index].equals(Prefs.accent(MainActivity.this))) {
                        Prefs.setAccent(MainActivity.this, Appearance.ACCENTS[index]);
                        v.animate().scaleX(1.15f).scaleY(1.15f).setDuration(120)
                                .withEndAction(new Runnable() {
                                    @Override
                                    public void run() {
                                        recreate();
                                    }
                                }).start();
                    }
                }
            });
            Ui.pressAnimation(holder);
            accentRow.addView(holder);
        }
    }

    private int accentColorRes(int index) {
        boolean dark = Appearance.isDark(this);
        switch (index) {
            case 1:
                return dark ? R.color.blue_dark : R.color.blue_light;
            case 2:
                return dark ? R.color.purple_dark : R.color.purple_light;
            case 3:
                return dark ? R.color.green_dark : R.color.green_light;
            case 4:
                return dark ? R.color.orange_dark : R.color.orange_light;
            case 5:
                return dark ? R.color.pink_dark : R.color.pink_light;
            default:
                return dark ? R.color.teal_dark : R.color.teal_light;
        }
    }

    /** 代码里设置的 drawable 不会继承 xml 的 drawableTint，这里手动上色。 */
    private void setStartIcon(TextView button, int drawableRes, int tint) {
        Drawable drawable = getDrawable(drawableRes);
        if (drawable != null) {
            drawable = drawable.mutate();
            drawable.setTint(tint);
        }
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null);
    }

    private void setEndIcon(TextView button, int drawableRes, int tint) {
        Drawable drawable = getDrawable(drawableRes);
        if (drawable != null) {
            drawable = drawable.mutate();
            drawable.setTint(tint);
        }
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, drawable, null);
    }

    private void setupLanguageRow() {
        tvLanguageValue.setText(languageLabel(Prefs.language(this)));
        findViewById(R.id.rowLanguage).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLanguageDialog();
            }
        });
    }

    private String languageLabel(String code) {
        int index = Lang.indexOf(code);
        String[] labels = {
                getString(R.string.language_system),
                "简体中文",
                "繁體中文",
                "English",
                "日本語"
        };
        return labels[Math.max(0, Math.min(index, labels.length - 1))];
    }

    private void showLanguageDialog() {
        final String[] labels = {
                getString(R.string.language_system),
                "简体中文",
                "繁體中文",
                "English",
                "日本語"
        };
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.language)
                .setSingleChoiceItems(labels, Lang.indexOf(Prefs.language(this)),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                d.dismiss();
                                String value = Lang.VALUES[which];
                                if (!value.equals(Prefs.language(MainActivity.this))) {
                                    Prefs.setLanguage(MainActivity.this, value);
                                    main.postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            recreate();
                                        }
                                    }, 120);
                                }
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
    }

    private void setupAbout() {
        findViewById(R.id.linkGitHub).setOnClickListener(open("https://github.com/FSYL001/FS-HeartCast-OSC"));
        findViewById(R.id.linkBilibili).setOnClickListener(open("https://space.bilibili.com/450000657"));
        findViewById(R.id.linkDouyin).setOnClickListener(open("https://www.douyin.com/user/MS4wLjABAAAAzWfG071S0Ol0_wF3xT1i0oL5eSpPdiYZZY_gKib9S_M"));
        findViewById(R.id.linkAifadian).setOnClickListener(open("https://ifdian.net/a/fsyl001"));
        findViewById(R.id.linkEmail).setOnClickListener(open("mailto:email@fsyl001.sbs"));
        findViewById(R.id.linkBlog).setOnClickListener(open("https://blog.fsyl001.sbs"));
        findViewById(R.id.rowCheckUpdate).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runUpdateCheck(true);
            }
        });
    }

    // ---------------------------------------------------------------- 更新检查

    private void maybeAutoCheckUpdate() {
        long last = Prefs.updateCheckedAt(this);
        if (System.currentTimeMillis() - last < 24L * 60L * 60L * 1000L) {
            return;
        }
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing()) {
                    runUpdateCheck(false);
                }
            }
        }, 1500);
    }

    private void runUpdateCheck(final boolean manual) {
        if (!manual) {
            Prefs.setUpdateCheckedAt(this, System.currentTimeMillis());
        } else if (tvUpdateState != null) {
            tvUpdateState.setText(R.string.update_checking);
        }
        UpdateChecker.check(this, new UpdateChecker.Callback() {
            @Override
            public void onResult(UpdateChecker.Release release, boolean newer, String error) {
                if (tvUpdateState != null) {
                    tvUpdateState.setText("");
                }
                if (error != null) {
                    if (manual) {
                        toast(getString(R.string.update_failed));
                    }
                    return;
                }
                if (manual) {
                    Prefs.setUpdateCheckedAt(MainActivity.this, System.currentTimeMillis());
                }
                if (release == null || !newer) {
                    if (manual) {
                        toast(getString(R.string.update_latest));
                    }
                    return;
                }
                if (!manual && release.tag.equals(Prefs.updateIgnoredTag(MainActivity.this))) {
                    return;
                }
                showUpdateDialog(release);
            }
        });
    }

    private void showUpdateDialog(final UpdateChecker.Release release) {
        String notes = release.notes == null ? "" : release.notes.trim();
        if (notes.length() > 600) {
            notes = notes.substring(0, 600) + "…";
        }
        if (notes.length() == 0) {
            notes = getString(R.string.update_notes_empty);
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.update_found_title, release.tag))
                .setMessage(notes)
                .setPositiveButton(R.string.update_now, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        openUrl(release.apkUrl != null ? release.apkUrl : release.pageUrl);
                    }
                })
                .setNegativeButton(R.string.update_later, null)
                .setNeutralButton(R.string.update_ignore, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Prefs.setUpdateIgnoredTag(MainActivity.this, release.tag);
                    }
                })
                .show();
    }

    private void openUrl(String url) {
        if (url == null || url.length() == 0) {
            toast(getString(R.string.update_failed));
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Exception e) {
            toast(url);
        }
    }

    // ---------------------------------------------------------------- 局域网检测

    private void discoverPc() {
        btnDiscover.setEnabled(false);
        tvDiscoverStatus.setVisibility(View.VISIBLE);
        tvDiscoverStatus.setText(R.string.discovery_scanning);

        // 1) 首选：mDNS 直接发现 VRChat 的 OSC 服务（电脑端不需要装任何东西）
        VrchatDiscovery.scan(this, new VrchatDiscovery.Callback() {
            @Override
            public void onFinished(VrchatDiscovery.Result result) {
                if (isFinishing()) {
                    return;
                }
                if (result != null) {
                    applyDiscovered(result.host, result.port, R.string.discovery_vrchat);
                    return;
                }
                // 2) 备选：找电脑上的中继（可选安装）
                LanDiscovery.scan(new LanDiscovery.Callback() {
                    @Override
                    public void onFinished(LanDiscovery.Result relay) {
                        if (isFinishing()) {
                            return;
                        }
                        if (relay != null) {
                            applyDiscovered(relay.host, relay.port, R.string.discovery_relay);
                        } else {
                            btnDiscover.setEnabled(true);
                            tvDiscoverStatus.setText(R.string.discovery_not_found);
                        }
                    }
                });
            }
        });
    }

    private void applyDiscovered(String host, int port, int stringRes) {
        btnDiscover.setEnabled(true);
        etIp.setText(host);
        etPort.setText(String.valueOf(port));
        flushOscFields();
        String text = getString(stringRes, host, Integer.valueOf(port));
        tvDiscoverStatus.setText(text);
        toast(text);
    }

    private View.OnClickListener open(final String url) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                } catch (Exception e) {
                    toast(url);
                }
            }
        };
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0.0";
        }
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onStart() {
        super.onStart();
        core.addListener(coreListener);
        renderDevices(core.deviceList(), core.getActiveAddress());
    }

    @Override
    protected void onStop() {
        core.removeListener(coreListener);
        super.onStop();
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(saveSettingsRunnable);
        flushOscFields();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Appearance.styleRes(this) != appliedStyle
                || !Prefs.language(this).equals(appliedLanguage)) {
            recreate();
            return;
        }
        applyKeepScreenOn();
    }

    private void applyKeepScreenOn() {
        if (Prefs.keepScreenOn(this)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    // ---------------------------------------------------------------- 扫描 / 服务

    private void startScan() {
        if (!hasBluetoothPermissions()) {
            requestMissingPermissions();
            toast(getString(R.string.bt_permission_needed));
            return;
        }
        if (!isBluetoothEnabled()) {
            toast(getString(R.string.bt_enable_prompt));
            requestEnableBluetooth();
            return;
        }
        startServiceIfNeeded();
        if (!core.startScan()) {
            toast(getString(R.string.scan_failed));
        }
    }

    private boolean isBluetoothEnabled() {
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            return false;
        }
        try {
            return adapter.isEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private void requestEnableBluetooth() {
        try {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BLUETOOTH);
        } catch (Exception ignored) {
            // ignore
        }
    }

    private void startServiceIfNeeded() {
        if (serviceStarted || !Prefs.backgroundRun(this)) {
            return;
        }
        Intent intent = new Intent(this, HeartRateService.class);
        try {
            startForegroundService(intent);
            serviceStarted = true;
        } catch (Exception e) {
            try {
                startService(intent);
                serviceStarted = true;
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private void stopServiceIfNeeded() {
        if (!Prefs.backgroundRun(this)) {
            return;
        }
        try {
            stopService(new Intent(this, HeartRateService.class));
        } catch (Exception ignored) {
            // ignore
        }
        serviceStarted = false;
    }

    // ---------------------------------------------------------------- OSC 字段

    private void flushOscFields() {
        String ip = etIp.getText().toString().trim();
        if (!Prefs.isValidHost(ip)) {
            return;
        }
        int port;
        try {
            port = Integer.parseInt(etPort.getText().toString().trim());
        } catch (NumberFormatException e) {
            return;
        }
        if (port < 1 || port > 65535) {
            return;
        }
        String hr = etHr.getText().toString().trim();
        String beat = etBeat.getText().toString().trim();
        if (hr.length() == 0 || hr.charAt(0) != '/' || beat.length() == 0 || beat.charAt(0) != '/') {
            return;
        }

        boolean changed = !ip.equals(Prefs.ip(this))
                || port != Prefs.port(this)
                || !hr.equals(Prefs.hrAddress(this))
                || !beat.equals(Prefs.beatAddress(this));
        if (!changed) {
            return;
        }

        Prefs.setIp(this, ip);
        Prefs.setPort(this, port);
        Prefs.setHrAddress(this, hr);
        Prefs.setBeatAddress(this, beat);
        core.loadSettings();
    }

    // ---------------------------------------------------------------- 权限

    private String[] neededPermissions() {
        List<String> list = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_SCAN);
            list.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            list.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return list.toArray(new String[list.size()]);
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return isGranted(Manifest.permission.BLUETOOTH_SCAN)
                    && isGranted(Manifest.permission.BLUETOOTH_CONNECT);
        }
        return isGranted(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private boolean isGranted(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestMissingPermissions() {
        List<String> missing = new ArrayList<String>();
        String[] needed = neededPermissions();
        for (int i = 0; i < needed.length; i++) {
            if (!isGranted(needed[i])) {
                missing.add(needed[i]);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        requestPermissions(missing.toArray(new String[missing.size()]), REQ_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            toast(getString(hasBluetoothPermissions()
                    ? R.string.bt_permission_granted : R.string.bt_permission_needed));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ENABLE_BLUETOOTH && resultCode == RESULT_OK) {
            startScan();
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
