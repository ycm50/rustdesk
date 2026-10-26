package com.rustdesk.java;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.rustdesk.java.crypto.Ed25519;
import com.rustdesk.java.crypto.Sodium;
import com.rustdesk.java.host.HostAgent;
import com.rustdesk.java.host.HostConfig;
import com.rustdesk.java.host.InputInjector;
import com.rustdesk.java.host.InputInjectorSink;
import com.rustdesk.java.host.ScreenCaptureService;
import com.rustdesk.java.net.RendezvousClient;
import com.rustdesk.java.ui.RemoteActivity;

import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The main screen: viewer controls on top, this device's own host credentials and the
 * start/stop button below.
 *
 * <p>Host identity: {@link HostConfig} persists only the id, the password and the rendezvous
 * server, so the curve25519 box key pair and the ed25519 signing seed that {@link HostAgent}
 * requires are generated in memory by {@link Sodium#genBoxKeyPair()} and
 * {@link Ed25519#generateKeyPair()} the first time the host is started. They are kept for the
 * lifetime of this process, so a stop/start cycle keeps the same host identity, but a process
 * restart produces new keys: a viewer that had trusted this host before has to trust it again.
 * Persisting them (for example in {@link HostConfig}) is what would remove that limitation.</p>
 */
public class MainActivity extends Activity {

    private static final String TAG = "RDJAVA";

    /** Distinct request codes so the two result callbacks can dispatch on the code alone. */
    private static final int REQ_POST_NOTIFICATIONS = 1001;
    private static final int REQ_MEDIA_PROJECTION = 1002;

    /**
     * Contract with {@link ScreenCaptureService}: these two fixed keys are how the MediaProjection
     * consent result is handed to that service, because only the service may call
     * {@code getMediaProjection} and the consent data cannot be rebuilt from primitives.
     *
     * <p>The service does not read them yet. Its onStartCommand must do, before returning
     * START_NOT_STICKY, plus startForeground with the mediaProjection type:
     * <pre>
     * setProjectionResult(intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED),
     *                     intent.getParcelableExtra(EXTRA_RESULT_DATA));
     * startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
     * </pre>
     * The startForeground call is not optional: a startForegroundService() launch that never posts
     * a notification is killed by the platform with RemoteServiceException after about five seconds.
     */
    public static final String EXTRA_RESULT_CODE = "com.rustdesk.java.host.EXTRA_RESULT_CODE";
    public static final String EXTRA_RESULT_DATA = "com.rustdesk.java.host.EXTRA_RESULT_DATA";

    /** Same shape as RustDesk passwords: short, no look-alike characters, no punctuation. */
    private static final int PASSWORD_LENGTH = 8;
    private static final String PASSWORD_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";

    /**
     * Viewer-side history in its own prefs file so the host credentials in
     * {@link HostConfig} can never be shadowed or cleared by it.
     */
    private static final String RECENT_PREFS = "rustdesk_java_recent";
    /** Newline separated records, each {@code id|timestamp} (milliseconds). */
    private static final String KEY_RECENT = "recent";
    private static final String KEY_LAST_ID = "last_id";
    private static final int MAX_RECENT = 5;
    private static final String ID_HINT = "ID must be 9-10 digits, or IP[:port]";

    private EditText idInput;
    private EditText passwordInput;
    private TextView viewerStatusView;

    private SharedPreferences recentPrefs;
    private LinearLayout recentSection;

    private HostConfig hostConfig;
    private TextView hostIdView;
    private TextView hostPasswordView;
    private TextView hostStatusView;
    private TextView hostAccessibilityView;
    private TextView hostNotificationView;

    /** The running host agent, or null while the host side is idle. */
    private HostAgent hostAgent;
    /** The start/stop button, kept so the label can follow {@link #hostAgent}. */
    private Button hostToggleButton;
    /** Curve25519 box keys for the host agent: [0] public, [1] secret; null until first start. */
    private byte[] hostBoxPublicKey;
    private byte[] hostBoxSecretKey;
    /** Ed25519 seed whose public key hbbs hands to the viewer; null until first start. */
    private byte[] hostSigningSeed;

    /**
     * Forwards the agent's progress to the status line. Agent callbacks arrive on its accept and
     * session threads, so every view touch is bounced to the UI thread.
     */
    private final HostAgent.Listener hostListener = new HostAgent.Listener() {
        @Override
        public void onStatus(final String status) {
            Log.i(TAG, "host: " + status);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setHostStatus(status);
                }
            });
        }

        @Override
        public void onViewerConnected(String viewerId) {
            Log.i(TAG, "viewer connected: id=" + viewerId);
            final String text = "Viewer " + viewerId + " connected";
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setHostStatus(text);
                }
            });
        }

        @Override
        public void onViewerClosed(String reason) {
            Log.i(TAG, "viewer closed: " + reason);
            final String text = "Viewer disconnected: " + reason;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setHostStatus(text);
                }
            });
        }
    };

    private final SecureRandom random = new SecureRandom();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        recentPrefs = getSharedPreferences(RECENT_PREFS, Context.MODE_PRIVATE);

        // Test hook: `am start ... --es id 123456` jumps straight into the remote view, since
        // RemoteActivity itself is not exported.
        String directId = getIntent() == null ? null : getIntent().getStringExtra("id");
        if (directId != null && !directId.isEmpty()) {
            Intent direct = new Intent(this, RemoteActivity.class);
            direct.putExtra(RemoteActivity.EXTRA_ID, directId);
            direct.putExtra(RemoteActivity.EXTRA_PASSWORD,
                    getIntent().getStringExtra("password"));
            startActivity(direct);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 96, 48, 48);

        TextView title = new TextView(this);
        title.setText("RustDesk (Java client)");
        title.setTextSize(22);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        idInput = new EditText(this);
        // One field for both ways in: a rendezvous id, or an address to dial directly. Direct
        // needs no server at all, which is the only thing that works on a network without internet.
        idInput.setHint("Remote ID or IP[:port]");
        // Plain text on purpose: direct connections may be typed as IP or [IPv6]:port, which a
        // numeric-only keyboard cannot produce; validation happens on Connect instead.
        idInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        root.addView(idInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Restore the id the last successful connect used, so a repeat session is one tap away.
        String lastId = recentPrefs.getString(KEY_LAST_ID, "");
        if (lastId != null && !lastId.isEmpty()) {
            idInput.setText(lastId);
            idInput.setSelection(lastId.length());
        }

        passwordInput = new EditText(this);
        passwordInput.setHint("Password");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(passwordInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button connect = new Button(this);
        connect.setText("Connect");
        connect.setOnClickListener(v -> {
            String id = idInput.getText().toString().trim();
            if (!isValidPeerId(id)) {
                Log.i(TAG, "connect blocked: invalid peer id, length=" + id.length());
                setViewerStatus(ID_HINT);
                return;
            }
            // Record before leaving, so the id is in the history even if the session fails.
            recentPrefs.edit().putString(KEY_LAST_ID, id).apply();
            rememberRecentConnection(id);
            Intent i = new Intent(this, RemoteActivity.class);
            i.putExtra(RemoteActivity.EXTRA_ID, id);
            i.putExtra(RemoteActivity.EXTRA_PASSWORD, passwordInput.getText().toString());
            startActivity(i);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END;
        root.addView(connect, lp);

        viewerStatusView = new TextView(this);
        root.addView(viewerStatusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addHostSection(root);
        addRecentSection(root);

        setContentView(root);

        requestNotificationPermissionIfNeeded();
    }

    /** Host side: this device's own id/password plus the button that starts being controlled. */
    private void addHostSection(LinearLayout root) {
        hostConfig = new HostConfig(this);
        if (hostConfig.getPassword().isEmpty()) {
            // First run: HostConfig only generates the id, an empty password would mean any
            // viewer on the relay could connect.
            hostConfig.setPassword(generatePassword());
            Log.i(TAG, "host password generated on first run");
        }

        LinearLayout.LayoutParams matchWrap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        TextView hostTitle = new TextView(this);
        hostTitle.setText("This device (host)");
        hostTitle.setTextSize(18);
        hostTitle.setPadding(0, 48, 0, 0);
        root.addView(hostTitle, matchWrap);

        hostIdView = new TextView(this);
        root.addView(hostIdView, matchWrap);

        hostPasswordView = new TextView(this);
        root.addView(hostPasswordView, matchWrap);

        refreshHostInfo();

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button copy = new Button(this);
        copy.setText("Copy");
        copy.setOnClickListener(v -> copyHostCredentials());
        buttons.addView(copy);

        Button regenerate = new Button(this);
        regenerate.setText("New password");
        regenerate.setOnClickListener(v -> {
            hostConfig.setPassword(generatePassword());
            refreshHostInfo();
            Log.i(TAG, "host password regenerated");
            toast("Host password regenerated");
        });
        buttons.addView(regenerate);

        hostToggleButton = new Button(this);
        hostToggleButton.setText("Start host");
        hostToggleButton.setOnClickListener(v -> toggleHostAgent());
        buttons.addView(hostToggleButton);

        root.addView(buttons, matchWrap);

        hostStatusView = new TextView(this);
        hostStatusView.setText("Host is idle");
        root.addView(hostStatusView, matchWrap);

        // Read-only self-check: the two things that silently make "Start host" useless or its
        // notification invisible, both one look away from the button that needs them.
        hostAccessibilityView = new TextView(this);
        hostAccessibilityView.setTextSize(12);
        root.addView(hostAccessibilityView, matchWrap);

        hostNotificationView = new TextView(this);
        hostNotificationView.setTextSize(12);
        root.addView(hostNotificationView, matchWrap);

        refreshHostSelfCheck();
    }

    private void refreshHostSelfCheck() {
        if (hostAccessibilityView != null) {
            hostAccessibilityView.setText("Accessibility: "
                    + (isAccessibilityServiceEnabled() ? "on" : "off"));
        }
        if (hostNotificationView != null) {
            hostNotificationView.setText("Notifications: "
                    + (isNotificationPermissionGranted() ? "on" : "off"));
        }
    }

    /** Pre-Tiramisu there is no runtime permission, so the notification channel can always post. */
    private boolean isNotificationPermissionGranted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void refreshHostInfo() {
        hostIdView.setText("Host ID: " + hostConfig.getId());
        hostPasswordView.setText("Password: " + hostConfig.getPassword());
    }

    private String generatePassword() {
        StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            sb.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }

    private void copyHostCredentials() {
        String text = "ID: " + hostConfig.getId() + "\nPassword: " + hostConfig.getPassword();
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Log.e(TAG, "no ClipboardManager, cannot copy host credentials");
            setHostStatus("Clipboard is not available on this device");
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("RustDesk host", text));
        Log.i(TAG, "host credentials copied to clipboard");
        toast("Host ID and password copied");
    }

    // ---------------------------------------------------------------- host startup

    /**
     * Accessibility is checked first because input injection is what makes the session useful; a
     * viewer that can see the screen but not touch it is not what "start host" promises.
     */
    private void startHost() {
        if (!isAccessibilityServiceEnabled()) {
            Log.i(TAG, "start host: accessibility service not enabled");
            setHostStatus("Accessibility service is off; opening system settings");
            toast("Enable the RustDesk input service in Accessibility");
            openAccessibilitySettings();
            return;
        }
        requestScreenCapture();
    }

    private boolean isAccessibilityServiceEnabled() {
        AccessibilityManager manager =
                (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null) return false;
        ComponentName component = new ComponentName(this, InputInjector.class);
        String flat = component.flattenToString();
        String shortFlat = component.flattenToShortString();
        List<AccessibilityServiceInfo> enabled =
                manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        if (enabled == null) return false;
        for (AccessibilityServiceInfo info : enabled) {
            String id = info == null ? null : info.getId();
            if (id == null) continue;
            if (id.equals(flat) || id.equals(shortFlat)) return true;
        }
        return false;
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (ActivityNotFoundException e) {
            // Some vendor builds strip the accessibility settings activity from the launcher
            // surface; there is nothing to fall back to, so tell the user where to go by hand.
            Log.e(TAG, "no activity for Settings.ACTION_ACCESSIBILITY_SETTINGS", e);
            setHostStatus("Open system settings > Accessibility and enable RustDesk input");
            toast("Cannot open accessibility settings on this device");
        }
    }

    private void requestScreenCapture() {
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            Log.e(TAG, "no MediaProjectionManager on this device");
            setHostStatus("Screen capture is not supported on this device");
            return;
        }
        try {
            startActivityForResult(manager.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "no activity handles the screen capture consent dialog", e);
            setHostStatus("The screen capture consent dialog is unavailable");
            toast("Cannot request screen capture on this device");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_MEDIA_PROJECTION) return;

        if (resultCode != RESULT_OK || data == null) {
            // The user pressed cancel (or an OEM dialog returned without consent data).
            Log.i(TAG, "screen capture not granted: resultCode=" + resultCode
                    + " data=" + (data != null));
            setHostStatus("Screen capture was not granted; host not started");
            toast("Screen capture was not granted");
            return;
        }

        // Order matters: Android 10+ rejects getMediaProjection() unless the calling process is
        // already a foreground service of type mediaProjection, so the service is started first
        // and only afterwards is the reason for the service explained.
        startCaptureService(resultCode, data);
        // Written before the agent starts on purpose: the agent's own "listening on ..." status is
        // the more actionable message and must therefore be the one left on screen.
        explainForegroundService();
        startHostAgent();
    }

    private void startCaptureService(int resultCode, Intent data) {
        Intent service = new Intent(this, ScreenCaptureService.class);
        service.putExtra(EXTRA_RESULT_CODE, resultCode);
        service.putExtra(EXTRA_RESULT_DATA, data);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }
            Log.i(TAG, "ScreenCaptureService started, consent resultCode=" + resultCode);
        } catch (RuntimeException e) {
            // e.g. ForegroundServiceStartNotAllowedException when the app is not visible, or the
            // service missing from the manifest.
            Log.e(TAG, "could not start ScreenCaptureService", e);
            setHostStatus("Could not start the screen capture service: "
                    + e.getClass().getSimpleName());
            toast("Could not start screen capture");
        }
    }

    /**
     * Screen capture must outlive this activity, and from Android 10 on the system only hands out
     * a MediaProjection to a foreground service of type mediaProjection. Without the notification
     * the capture would either be refused with SecurityException or be killed as soon as the
     * activity stops being visible, which is exactly what happens when the viewer takes over.
     */
    private void explainForegroundService() {
        String note = "Screen capture runs in a foreground service of type mediaProjection: "
                + "Android only grants the projection to such a service, and its ongoing "
                + "notification tells you while the screen is being captured.";
        Log.i(TAG, note);
        setHostStatus(note);
    }

    // ---------------------------------------------------------------- host agent

    /** Start when idle, stop when running: the one button owns both directions. */
    private void toggleHostAgent() {
        HostAgent agent = hostAgent;
        if (agent != null && agent.isRunning()) {
            stopHostAgent("stop requested from the button");
            return;
        }
        startHost();
    }

    /**
     * Creates and starts the agent that owns the listening port and the hbbs registration. Called
     * after the capture service is up, because a session is useless without the projection.
     *
     * <p>The keys come from {@link #ensureHostKeys()}; see the class comment for why they are not
     * persisted yet.</p>
     */
    private void startHostAgent() {
        if (hostConfig == null) return;
        HostAgent running = hostAgent;
        if (running != null && running.isRunning()) {
            Log.i(TAG, "host agent already running on port " + running.getListenPort());
            updateHostButton();
            return;
        }

        ensureHostKeys();
        HostAgent agent;
        try {
            agent = new HostAgent(hostConfig.getId(), hostConfig.getPassword(),
                    hostConfig.getRendezvousServer(), RendezvousClient.DEFAULT_PORT,
                    hostBoxPublicKey, hostBoxSecretKey, hostSigningSeed, hostListener);
        } catch (RuntimeException e) {
            Log.e(TAG, "could not create HostAgent", e);
            setHostStatus("Could not start the host agent: " + e.getClass().getSimpleName());
            toast("Could not start the host agent");
            updateHostButton();
            return;
        }

        hostAgent = agent;
        // Injected here rather than inside HostAgent so HostAgent keeps zero android.* imports
        // and stays runnable from the JVM self-test.
        agent.setInputSink(InputInjectorSink.INSTANCE);
        // start() binds synchronously, so isRunning() already tells whether the port came up.
        agent.start();
        if (agent.isRunning()) {
            Log.i(TAG, "HostAgent listening on port " + agent.getListenPort()
                    + ", id=" + hostConfig.getId()
                    + ", rendezvous=" + hostConfig.getRendezvousServer());
            setHostStatus("Host is listening on port " + agent.getListenPort()
                    + " as " + hostConfig.getId());
        } else {
            // The bind failure reason is already in the status view, from Listener.onStatus.
            Log.e(TAG, "HostAgent did not start");
            hostAgent = null;
        }
        updateHostButton();
    }

    private void stopHostAgent(String reason) {
        HostAgent agent = hostAgent;
        hostAgent = null;
        if (agent != null) {
            Log.i(TAG, "stopping host agent: " + reason);
            // Synchronous: closes the listener and ends live sessions before this returns.
            agent.stop();
            setHostStatus("Host stopped");
        }
        updateHostButton();
    }

    private void updateHostButton() {
        if (hostToggleButton == null) return;
        HostAgent agent = hostAgent;
        boolean running = agent != null && agent.isRunning();
        hostToggleButton.setText(running ? "Stop host" : "Start host");
    }

    /**
     * Generates the host keys once per process. HostConfig has no slot for them, so they cannot
     * survive a restart; keeping them in fields at least keeps them stable across stop/start.
     */
    private void ensureHostKeys() {
        if (hostBoxPublicKey != null && hostBoxSecretKey != null && hostSigningSeed != null) {
            return;
        }
        byte[][] box = Sodium.genBoxKeyPair();
        hostBoxPublicKey = box[0];
        hostBoxSecretKey = box[1];
        hostSigningSeed = Ed25519.generateKeyPair()[1];
        Log.i(TAG, "host keys generated in memory; viewers must re-trust after a restart");
    }

    // ---------------------------------------------------------------- notification permission

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "POST_NOTIFICATIONS already granted");
            return;
        }
        Log.i(TAG, "requesting POST_NOTIFICATIONS");
        requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS},
                REQ_POST_NOTIFICATIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_POST_NOTIFICATIONS) return;

        boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            Log.i(TAG, "POST_NOTIFICATIONS granted");
            refreshHostSelfCheck();
            return;
        }
        // Denial only hides the foreground notification; capture itself still works, so this is a
        // warning with instructions instead of an error.
        Log.i(TAG, "POST_NOTIFICATIONS denied; the capture notification will stay hidden");
        setHostStatus("Notifications are disabled, so the screen capture notification stays "
                + "hidden. You can still enable notifications later in system settings.");
        toast("Notifications disabled; capture notification will be hidden");
        refreshHostSelfCheck();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Accessibility can be toggled while this page sits in the background, so re-read it
        // instead of trusting the state captured when the view was built.
        refreshHostSelfCheck();
    }

    @Override
    protected void onDestroy() {
        // The accept thread and the hbbs stream are plain JVM threads: without this the agent
        // would outlive the activity and keep the port and the registration alive.
        stopHostAgent("activity destroyed");
        super.onDestroy();
    }

    // ---------------------------------------------------------------- recent connections

    private void addRecentSection(LinearLayout root) {
        recentSection = new LinearLayout(this);
        recentSection.setOrientation(LinearLayout.VERTICAL);
        root.addView(recentSection, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        refreshRecentSection();
    }

    /** Rebuilds the list from prefs; the whole block disappears when there is no history. */
    private void refreshRecentSection() {
        if (recentSection == null) return;
        recentSection.removeAllViews();
        List<String[]> entries = loadRecentConnections();
        if (entries.isEmpty()) {
            recentSection.setVisibility(View.GONE);
            return;
        }
        recentSection.setVisibility(View.VISIBLE);

        TextView title = new TextView(this);
        title.setText("Recent connections");
        title.setTextSize(18);
        title.setPadding(0, 48, 0, 0);
        recentSection.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (String[] entry : entries) {
            final String id = entry[0];
            Button row = new Button(this);
            row.setText(id + "   " + formatRecentTime(Long.parseLong(entry[1])));
            // Fill only: connecting stays an explicit second tap, so a stray tap on the list
            // cannot start a session.
            row.setOnClickListener(v -> {
                idInput.setText(id);
                idInput.setSelection(id.length());
                setViewerStatus("Filled " + id + "; press Connect to connect");
            });
            recentSection.addView(row);
        }
    }

    /** Newest first, at most {@link #MAX_RECENT} entries, no duplicates. */
    private void rememberRecentConnection(String id) {
        List<String[]> old = loadRecentConnections();
        StringBuilder sb = new StringBuilder();
        sb.append(id).append('|').append(System.currentTimeMillis());
        int kept = 1;
        for (String[] entry : old) {
            if (kept >= MAX_RECENT) break;
            if (entry[0].equals(id)) continue;
            sb.append('\n').append(entry[0]).append('|').append(entry[1]);
            kept++;
        }
        recentPrefs.edit().putString(KEY_RECENT, sb.toString()).apply();
        Log.i(TAG, "recent connection recorded, entries=" + kept);
        refreshRecentSection();
    }

    private List<String[]> loadRecentConnections() {
        List<String[]> out = new ArrayList<>();
        String raw = recentPrefs.getString(KEY_RECENT, "");
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            int sep = line.indexOf('|');
            if (sep <= 0 || sep == line.length() - 1) continue;
            String id = line.substring(0, sep);
            long timestamp;
            try {
                timestamp = Long.parseLong(line.substring(sep + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            // Drop entries that no longer pass today's rules instead of offering a dead tap.
            if (!isValidPeerId(id)) continue;
            out.add(new String[] {id, Long.toString(timestamp)});
        }
        return out;
    }

    private static String formatRecentTime(long timestamp) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(timestamp));
    }

    // ---------------------------------------------------------------- peer id validation

    /**
     * What RustDesk itself accepts as a destination: a 9-10 digit id, or a direct address, either
     * a dotted-quad IPv4 or a bracketed IPv6 literal, each with an optional {@code :port}.
     */
    static boolean isValidPeerId(String raw) {
        if (raw == null) return false;
        String id = raw.trim();
        if (id.isEmpty()) return false;
        if (isAllDigits(id)) {
            return id.length() == 9 || id.length() == 10;
        }
        if (isTunnelDestination(id)) {
            return true;
        }
        return isIpDestination(id);
    }

    /**
     * A tunnel endpoint: an explicit ws(s)/http(s) URL, or a bare hostname such as the one a
     * Cloudflare tunnel hands out. These reach the peer through a WebSocket instead of a raw
     * socket, so they cannot be validated as an IP.
     */
    private static boolean isTunnelDestination(String s) {
        String lower = s.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("wss://") || lower.startsWith("ws://")
                || lower.startsWith("https://") || lower.startsWith("http://")) {
            return lower.length() > 8;
        }
        if (!lower.contains(".")) return false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == ':';
            if (!ok) return false;
        }
        // Reject a bare numeric IP here; isIpDestination owns that case.
        return !lower.matches("[0-9.:]+");
    }

    private static boolean isAllDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static boolean isIpDestination(String s) {
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            if (close <= 1) return false;
            if (!isIpv6Literal(s.substring(1, close))) return false;
            return isOptionalPort(s.substring(close + 1));
        }
        int colon = s.indexOf(':');
        if (colon >= 0 && s.indexOf(':', colon + 1) >= 0) return false;
        String host = colon < 0 ? s : s.substring(0, colon);
        if (!isIpv4(host)) return false;
        return colon < 0 || isPort(s.substring(colon));
    }

    /** No port given, or a valid {@code :port}. */
    private static boolean isOptionalPort(String s) {
        return s.isEmpty() || isPort(s);
    }

    private static boolean isPort(String s) {
        if (s.isEmpty() || s.charAt(0) != ':') return false;
        String digits = s.substring(1);
        if (digits.isEmpty() || digits.length() > 5 || !isAllDigits(digits)) return false;
        int port = Integer.parseInt(digits);
        return port >= 1 && port <= 65535;
    }

    private static boolean isIpv4(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !isAllDigits(part)) return false;
            if (Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    /**
     * Structural IPv6 check: eight groups, or fewer with exactly one "::", hex digits only, and at
     * most one trailing dotted-quad. Done by hand rather than through {@link java.net.InetAddress},
     * which can do DNS work on the UI thread.
     */
    private static boolean isIpv6Literal(String s) {
        if (s.isEmpty() || s.indexOf(':') < 0) return false;
        int marker = s.indexOf("::");
        if (marker >= 0 && s.indexOf("::", marker + 1) >= 0) return false;
        int groups = 0;
        if (marker >= 0) {
            if (marker > 0) {
                int n = countIpv6Groups(s.substring(0, marker), false);
                if (n < 0) return false;
                groups += n;
            }
            String tail = s.substring(marker + 2);
            if (!tail.isEmpty()) {
                int n = countIpv6Groups(tail, true);
                if (n < 0) return false;
                groups += n;
            }
            // "::" has to stand for at least one 16-bit group.
            return groups <= 7;
        }
        return countIpv6Groups(s, true) == 8;
    }

    /** 16-bit groups in one colon separated run, or -1 when the run is malformed. */
    private static int countIpv6Groups(String s, boolean allowIpv4Tail) {
        if (s.isEmpty()) return -1;
        String[] parts = s.split(":", -1);
        int groups = 0;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) return -1;
            boolean last = i == parts.length - 1;
            if (allowIpv4Tail && last && part.indexOf('.') >= 0) {
                if (!isIpv4(part)) return -1;
                groups += 2;
                continue;
            }
            if (part.length() > 4) return -1;
            for (int k = 0; k < part.length(); k++) {
                char c = Character.toLowerCase(part.charAt(k));
                boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
                if (!hex) return -1;
            }
            groups++;
        }
        return groups;
    }
    // ---------------------------------------------------------------- end peer id validation

    // ---------------------------------------------------------------- helpers

    private void setViewerStatus(String text) {
        if (viewerStatusView != null) {
            viewerStatusView.setText(text);
        }
    }

    private void setHostStatus(String text) {
        if (hostStatusView != null) {
            hostStatusView.setText(text);
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
}


