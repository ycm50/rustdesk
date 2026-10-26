package com.rustdesk.java.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.rustdesk.java.codec.VideoDecoder;
import com.rustdesk.java.session.ClipboardMessages;
import com.rustdesk.java.session.DeviceIdentity;
import com.rustdesk.java.session.InputMessages;
import com.rustdesk.java.session.Session;

import hbb.MessageOuterClass.ControlKey;
import hbb.MessageOuterClass.Message;

import java.util.List;

public class RemoteActivity extends Activity implements InputSink, Session.Listener {

    public static final String EXTRA_ID = "id";
    public static final String EXTRA_PASSWORD = "password";
    public static final String EXTRA_SERVER = "server";

    /** Public RustDesk rendezvous server used when the intent carries no override. */
    private static final String DEFAULT_SERVER = "rs-ny.rustdesk.com";

    /** Status line refresh period. */
    private static final long STATS_INTERVAL_MS = 1000L;

    /**
     * Frame-stat log period. Separate from {@link #STATS_INTERVAL_MS}: the status line still
     * refreshes every second, but logging at that rate would flood logcat and bury the
     * connection lines that on-device verification also matches.
     */
    private static final long STATS_LOG_INTERVAL_MS = 5000L;

    /** Clipboard uplink poll period. */
    private static final long CLIPBOARD_INTERVAL_MS = 1500L;

    /**
     * How long a transient status line (session messages, reconnect banner) outranks the stats
     * line. Connection and disconnect messages pin indefinitely instead.
     */
    private static final long STATUS_NOTE_MS = 1000L;

    private RemoteView remoteView;
    private TextView status;
    private android.widget.EditText textInput;
    // set when the dispatch path already forwarded the Backspace for a key event, so the
    // TextWatcher does not send a second one for the same deletion
    private boolean skipWatcherBackspace;
    private VideoDecoder decoder;
    private DeviceIdentity identity;
    private Session session;

    private String peerPlatform;
    private int peerWidth;
    private int peerHeight;

    /** Clipboard text the peer already received; the uplink only sends on change. */
    private String lastSentClipboard;

    /**
     * Clipboard text most recently received from the peer. Read and written on the main thread
     * only: {@link #onClipboardText} posts to it and {@link #pollClipboard} already runs there.
     */
    private String lastRemoteClipboard;

    /** Transient status text plus the uptime at which the stats line takes over again. */
    private String statusNote;
    private long statusNoteUntil;

    /** Frame counter at the previous stats log; deltaFrames is the progress signal. */
    private long lastLoggedFrames;
    private long lastStatsLogUptime;
    /** Set on the first tick that sees a decoded-input frame, so it logs exactly once. */
    private boolean firstFrameLogged;
    /** Session owning the counters above, so a reconnect starts with a clean baseline. */
    private Session lastStatsSession;

    private final android.os.Handler uiHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private final Runnable statsTick = new Runnable() {
        @Override
        public void run() {
            // Throttled receive diagnostics: logcat is the only evidence of a live picture on a
            // real device, so log the counters at a fixed cadence instead of once per frame.
            Session current = session;
            if (current != null) {
                long frames = current.getFramesReceived();
                long bytes = current.getBytesReceived();
                if (current != lastStatsSession) {
                    // Reconnect or first session: the new Session starts its own counters.
                    lastStatsSession = current;
                    lastLoggedFrames = 0L;
                    lastStatsLogUptime = 0L;
                }
                if (!firstFrameLogged && frames > 0L) {
                    firstFrameLogged = true;
                    android.util.Log.i("RDJAVA", "first frame received");
                }
                long now = android.os.SystemClock.uptimeMillis();
                if (lastStatsLogUptime == 0L
                        || now - lastStatsLogUptime >= STATS_LOG_INTERVAL_MS) {
                    lastStatsLogUptime = now;
                    android.util.Log.i("RDJAVA", "stats: frames=" + frames + " bytes=" + bytes
                            + " deltaFrames=" + (frames - lastLoggedFrames));
                    lastLoggedFrames = frames;
                }
            }
            refreshStatus();
            uiHandler.postDelayed(this, STATS_INTERVAL_MS);
        }
    };

    private final Runnable clipboardTick = new Runnable() {
        @Override
        public void run() {
            pollClipboard();
            uiHandler.postDelayed(this, CLIPBOARD_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        remoteView = new RemoteView(this);
        remoteView.setInputSink(this);
        root.addView(remoteView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        status = new TextView(this);
        status.setText("Remote: " + getIntent().getStringExtra(EXTRA_ID));
        status.setTextColor(0xFFFFFFFF);
        status.setShadowLayer(4f, 0f, 0f, 0xFF000000);
        root.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(new ClickButtonsOverlay(this, remoteView),
                new FrameLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.MATCH_PARENT));

        textInput = new android.widget.EditText(this);
        textInput.setHint("type here");
        textInput.setVisibility(android.view.View.GONE);
        textInput.setSingleLine(true);
        // A single-line field reports Enter as an editor action instead of a '\n' text change.
        // Without imeOptions many IMEs (Chinese ones in particular) have no action to report and
        // simply swallow the key, so the editor-action listener below never fired.
        textInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        FrameLayout.LayoutParams textLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textLp.gravity = android.view.Gravity.BOTTOM;
        root.addView(textInput, textLp);
        textInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (session == null) return;
                if (count > 0) {
                    String added = s.subSequence(start, start + count).toString();
                    // Some IMEs still deliver Enter as a newline; the peer needs a Return key
                    // press, never a '\n' inside the typed text.
                    if (added.indexOf('\n') >= 0) {
                        String text = added.replace("\n", "");
                        if (!text.isEmpty()) {
                            session.sendMessage(InputMessages.text(text));
                        }
                        for (int i = 0; i < added.length(); i++) {
                            if (added.charAt(i) == '\n') {
                                sendControlKey(ControlKey.Return);
                            }
                        }
                        android.util.Log.i("RDJAVA", "soft enter via newline");
                        return;
                    }
                    session.sendMessage(InputMessages.text(added));
                    return;
                }
                // A pure deletion (the IME's Backspace) leaves no character to send, so the
                // peer has to be told about the key instead. Only reached while the input is
                // visible, because dispatchKeyEvent hands every key to the IME in that state.
                if (before > 0) {
                    // The dispatch path already forwarded the key event that caused this deletion;
                    // sending again here would delete two characters on the peer.
                    if (skipWatcherBackspace) {
                        skipWatcherBackspace = false;
                        return;
                    }
                    sendControlKey(ControlKey.Backspace);
                    android.util.Log.i("RDJAVA", "soft backspace x" + before);
                }
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });
        // A single-line field turns the IME's Enter into an editor action instead of a '\n'
        // text change, so the TextWatcher above never sees it. The reference client maps that
        // newline to VK_RETURN (flutter/lib/mobile/pages/remote_page.dart:inputChar), which is
        // this. Consumed, so the field survives the key and keeps receiving text.
        textInput.setOnEditorActionListener((v, actionId, event) -> {
            if (session == null) return false;
            boolean enterKey = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            // IME_NULL with no key event means "some other action" (focus move, etc.): ignore it
            // instead of typing a stray Return. Every real send/done/go/enter is forwarded.
            if (actionId == android.view.inputmethod.EditorInfo.IME_NULL && !enterKey) return false;
            sendControlKey(ControlKey.Return);
            android.util.Log.i("RDJAVA", "soft enter action=" + actionId + " enterKey=" + enterKey);
            return true;
        });

        // The two corner buttons live in one horizontal row so they tile instead of overlapping.
        android.widget.LinearLayout topButtons = new android.widget.LinearLayout(this);
        topButtons.setOrientation(android.widget.LinearLayout.VERTICAL);
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topLp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        root.addView(topButtons, topLp);

        android.widget.Button reconnect = new android.widget.Button(this);
        reconnect.setText("\u27F3");
        reconnect.setOnClickListener(v -> reconnectSession());
        topButtons.addView(reconnect);

        android.widget.Button keyboardToggle = new android.widget.Button(this);
        keyboardToggle.setText("\u2328");
        keyboardToggle.setOnClickListener(v -> {
            boolean show = textInput.getVisibility() != android.view.View.VISIBLE;
            textInput.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
            android.view.inputmethod.InputMethodManager ime =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
            if (show) {
                textInput.requestFocus();
                if (ime != null) ime.showSoftInput(textInput, 0);
            } else if (ime != null) {
                ime.hideSoftInputFromWindow(textInput.getWindowToken(), 0);
            }
        });
        topButtons.addView(keyboardToggle);

        // Three ways to send keys: plain text on the soft keyboard, shortcut combinations, and a
        // full on-screen keyboard. The key panel is draggable and carries its own close button.
        final android.widget.Button inputModeButton = new android.widget.Button(this);
        final KeyPanel keyPanel = new KeyPanel(this, message -> {
            Session current = session;
            if (current != null) {
                current.sendMessage(message);
            }
        });
        keyPanel.setVisibility(android.view.View.GONE);
        final android.content.SharedPreferences prefs =
                getSharedPreferences("rustdesk_java_prefs", MODE_PRIVATE);
        final String[] savedMode = { prefs.getString("input_mode", "Normal") };
        keyPanel.setOnCloseListener(() -> {
            savedMode[0] = "Normal";
            prefs.edit().putString("input_mode", "Normal").apply();
            inputModeButton.setText(inputModeLabel("Normal"));
            applyInputMode("Normal", keyPanel);
        });
        inputModeButton.setText(inputModeLabel(savedMode[0]));
        applyInputMode(savedMode[0], keyPanel);
        inputModeButton.setOnClickListener(v -> {
            String next = "Normal".equals(savedMode[0]) ? "Shortcut"
                    : "Shortcut".equals(savedMode[0]) ? "Virtual" : "Normal";
            savedMode[0] = next;
            prefs.edit().putString("input_mode", next).apply();
            inputModeButton.setText(inputModeLabel(next));
            applyInputMode(next, keyPanel);
        });
        topButtons.addView(inputModeButton);
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelLp.gravity = android.view.Gravity.TOP;
        root.addView(keyPanel, panelLp);

        setContentView(root);
        // Free the top edge for the video: the status text sits there and the system status bar
        // would otherwise overlap it.
        getWindow().getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        remoteView.setOnSurfaceReady(this::startSession);
        uiHandler.postDelayed(statsTick, STATS_INTERVAL_MS);
        uiHandler.postDelayed(clipboardTick, CLIPBOARD_INTERVAL_MS);
    }

    // --- keyboard: physical keys and the soft keyboard ---

    /**
     * Routes key events that the IME is not handling to the peer.
     *
     * <p>While the soft keyboard is up the IME owns the key stream: it composes the text and
     * reports it through {@link #textInput}. Sending those keys here as well would type
     * everything twice, so a visible input field hands the whole method back to the platform;
     * that field's TextWatcher and editor-action listener forward the text, Backspace and Enter.
     *
     * <p>Otherwise the event is translated and consumed, so no view also acts on it. Keys the
     * mapper does not understand (Back, volume, unmapped codes) still fall through to the
     * platform, which keeps Back working as the way out of the activity.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Back is handled here rather than left to the platform: while the text field is visible
        // the platform hands the key to the IME, which only closes the keyboard, so the activity
        // could never be left with the remote screen open.
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                handleBack();
            }
            return true;
        }
        if (textInput != null && textInput.getVisibility() == android.view.View.VISIBLE) {
            // Delete and Enter arrive here as key events from the IME (the character keys arrive
            // as committed text instead, which is why only text used to work). Forward them to
            // the peer and keep the local field in step:
            //   - Delete: send Backspace, then let the field delete its own character too, so the
            //     local text never drifts from the peer's. The watcher must not send a second one.
            //   - Enter: send Return and consume it; a single-line field has no newline to insert.
            if (session != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_DEL
                    && event.getAction() == KeyEvent.ACTION_DOWN) {
                skipWatcherBackspace = true;
                sendControlKey(ControlKey.Backspace);
                android.util.Log.i("RDJAVA", "soft backspace key, len=" + textInput.getText().length());
                return super.dispatchKeyEvent(event);
            }
            if (session != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN) {
                sendControlKey(ControlKey.Return);
                android.util.Log.i("RDJAVA", "soft enter key");
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        Session current = session;
        if (current == null) {
            return super.dispatchKeyEvent(event);
        }
        List<Message> messages = KeyEventMapper.map(event);
        if (messages == null) {
            return super.dispatchKeyEvent(event);
        }
        for (Message message : messages) {
            current.sendMessage(message);
        }
        if (!messages.isEmpty()) {
            // Key code and modifier presence only: the typed character never reaches the log.
            android.util.Log.i("RDJAVA", "key " + event.getKeyCode()
                    + " action=" + event.getAction() + " msgs=" + messages.size()
                    + " mods=" + !KeyEventMapper.modifiers(event.getMetaState()).isEmpty());
        }
        return true;
    }

    /**
     * Sends a control key as an explicit down+up pair instead of relying on the host's
     * `press` shortcut. The peer's Windows input path injects `key_down`/`key_up` per edge, so
     * the pair is the form that is easiest to verify against a real keyboard.
     */
    private void sendControlKey(ControlKey key) {
        if (session == null) return;
        session.sendMessage(InputMessages.controlKey(key, true));
        session.sendMessage(InputMessages.controlKey(key, false));
    }

    /** Back closes the on-screen keyboard first, then leaves the remote screen. */
    private void handleBack() {
        if (textInput != null && textInput.getVisibility() == android.view.View.VISIBLE) {
            textInput.setVisibility(android.view.View.GONE);
            android.view.inputmethod.InputMethodManager ime =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
            if (ime != null) {
                ime.hideSoftInputFromWindow(textInput.getWindowToken(), 0);
            }
            return;
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    private void startSession() {
        if (session != null) return;
        String id = getIntent().getStringExtra(EXTRA_ID);
        if (id == null || id.isEmpty()) {
            pinStatus("no remote id");
            return;
        }
        String server = getIntent().getStringExtra(EXTRA_SERVER);
        if (server == null || server.isEmpty()) server = DEFAULT_SERVER;

        android.graphics.SurfaceTexture texture = remoteView.obtainSurfaceTexture();
        if (texture == null) {
            pinStatus("surface not ready");
            return;
        }
        decoder = new VideoDecoder(new Surface(texture));
        decoder.start();

        if (identity == null) identity = DeviceIdentity.load(this);
        session = new Session(id, getIntent().getStringExtra(EXTRA_PASSWORD), server,
                21116, identity, decoder, this);
        session.start();
    }

    /**
     * Drops the current session and dials again on the same surface. The decoder is bound to the
     * existing texture and the device identity is immutable, so both are reused as is.
     */
    private void reconnectSession() {
        String id = getIntent().getStringExtra(EXTRA_ID);
        if (id == null || id.isEmpty()) {
            pinStatus("no remote id");
            return;
        }
        Session previous = session;
        session = null;
        if (previous != null) previous.stop();
        // Pinned: the new session's own status lines take over from here.
        pinStatus("reconnecting\u2026");

        if (decoder == null || identity == null) {
            // Nothing usable to reuse yet: let the normal entry point build both.
            startSession();
            return;
        }
        String server = getIntent().getStringExtra(EXTRA_SERVER);
        if (server == null || server.isEmpty()) server = DEFAULT_SERVER;
        android.util.Log.i("RDJAVA", "manual reconnect to " + id);
        session = new Session(id, getIntent().getStringExtra(EXTRA_PASSWORD), server,
                21116, identity, decoder, this);
        session.start();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // --- status line: live stats by default, transient messages on top ---

    private void refreshStatus() {
        if (status == null) return;
        if (statusNote != null && statusNoteUntil != Long.MAX_VALUE
                && android.os.SystemClock.uptimeMillis() >= statusNoteUntil) {
            statusNote = null;
        }
        status.setText(statusNote != null ? statusNote : statsLine());
    }

    /** Shows a message that the stats tick does not overwrite. */
    private void pinStatus(String text) {
        statusNote = text;
        statusNoteUntil = Long.MAX_VALUE;
        refreshStatus();
    }

    private String statsLine() {
        String platform = peerPlatform == null || peerPlatform.isEmpty() ? "unknown" : peerPlatform;
        Session current = session;
        long frames = current == null ? 0L : current.getFramesReceived();
        long bytes = current == null ? 0L : current.getBytesReceived();
        return platform + " " + peerWidth + "x" + peerHeight
                + " | n/a fps / " + frames + " \u5e27 / " + (bytes / 1024L) + " KB";
    }

    // --- clipboard uplink ---

    private void pollClipboard() {
        Session current = session;
        if (current == null || !current.isRunning()) return;
        try {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip()) return;
            android.content.ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            CharSequence text = clip.getItemAt(0).coerceToText(this);
            if (text == null) return;
            String cur = text.toString();
            if (cur.isEmpty() || cur.equals(lastSentClipboard)) return;
            // Echo guard. Writing the peer's text to the system clipboard makes it look like a
            // local change, so without this the next poll would send it straight back and the
            // two sides would bounce the same value forever. Deliberate trade-off: a local copy
            // of that exact text later on is skipped too, because a clip only reports content,
            // not who wrote it. A skipped uplink is harmless here (the peer already holds that
            // text); a feedback loop is not.
            if (cur.equals(lastRemoteClipboard)) return;
            if (current.sendMessage(ClipboardMessages.text(cur))) {
                lastSentClipboard = cur;
                android.util.Log.i("RDJAVA", "clipboard up: " + cur.length() + " chars");
            }
        } catch (RuntimeException e) {
            // Clipboard reads can be denied (background app, restricted profile): just skip.
            android.util.Log.i("RDJAVA", "clipboard poll skipped: " + e);
        }
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacks(statsTick);
        uiHandler.removeCallbacks(clipboardTick);
        if (session != null) session.stop();
        if (decoder != null) decoder.release();
        super.onDestroy();
    }

    // --- InputSink: translate gestures into RustDesk mouse messages ---

    @Override
    public void onMouseMove(float remoteX, float remoteY) {
        if (session != null) {
            session.sendMessage(InputMessages.mouseMove((int) remoteX, (int) remoteY));
        }
    }

    @Override
    public void onMouseDown(float remoteX, float remoteY, int button) {
        if (session != null) {
            session.sendMessage(InputMessages.mouseDown((int) remoteX, (int) remoteY, button));
        }
    }

    @Override
    public void onMouseUp(float remoteX, float remoteY, int button) {
        if (session != null) {
            session.sendMessage(InputMessages.mouseUp((int) remoteX, (int) remoteY, button));
        }
    }

    /** Maps the stored mode name onto the protobuf enum and hands it to the message builders. */
    /** Label shown on the mode button. */
    private static String inputModeLabel(String mode) {
        if ("Shortcut".equals(mode)) return "SHORTCUT";
        if ("Virtual".equals(mode)) return "KEYS";
        return "TEXT";
    }

    /**
     * Plain input sends the text and lets the peer type it (the only mode that handles CJK);
     * shortcut and virtual both send key events, which the peer maps through its own layout.
     */
    /** Shows the key pad for the key modes and hides it for plain text input. */
    /** Shows or hides the key panel; the close button inside the panel calls this too. */
    private void applyInputMode(String mode, KeyPanel panel) {
        if ("Normal".equals(mode)) {
            InputMessages.setKeyboardMode(hbb.MessageOuterClass.KeyboardMode.Translate);
            panel.setVisibility(android.view.View.GONE);
        } else {
            InputMessages.setKeyboardMode(hbb.MessageOuterClass.KeyboardMode.Legacy);
            panel.showMode(mode, "Virtual".equals(mode));
            panel.setVisibility(android.view.View.VISIBLE);
        }
        android.util.Log.i("RDJAVA", "input mode: " + mode);
    }



    @Override
    public void onWheel(float remoteX, float remoteY, int delta) {
        if (session != null) {
            // The wire format carries no wheel position: the host scrolls at its cursor
            // (src/server/input_service.rs:1413). Our single-finger drag pans the canvas instead of
            // moving the cursor, so it can still sit at the top-left corner; park it under the
            // fingers first, otherwise the notches land wherever it was left.
            session.sendMessage(InputMessages.mouseMove(
                    Math.round(remoteX), Math.round(remoteY)));
            session.sendMessage(InputMessages.wheel(0, delta));
        }
    }

    // --- Session.Listener ---

    @Override
    public void onStatus(String text) {
        android.util.Log.i("RDJAVA", "status: " + text);
        runOnUiThread(() -> {
            statusNote = text;
            statusNoteUntil = android.os.SystemClock.uptimeMillis() + STATUS_NOTE_MS;
            refreshStatus();
        });
    }

    @Override
    public void onPeerInfo(String platform, int width, int height) {
        runOnUiThread(() -> {
            peerPlatform = platform;
            if (width > 0 && height > 0) {
                // The decoder renders into this texture: match its buffer to the remote desktop
                // before the first frame arrives.
                try {
                    android.graphics.SurfaceTexture texture = remoteView.obtainSurfaceTexture();
                    if (texture != null) texture.setDefaultBufferSize(width, height);
                } catch (RuntimeException e) {
                    android.util.Log.i("RDJAVA", "surface buffer size kept: " + e);
                }
                peerWidth = width;
                peerHeight = height;
                remoteView.setRemoteSize(width, height);
            }
            statusNote = "connected: " + platform + " " + width + "x" + height;
            statusNoteUntil = android.os.SystemClock.uptimeMillis() + STATUS_NOTE_MS;
            refreshStatus();
        });
    }

    @Override
    public void onClosed(String reason) {
        android.util.Log.e("RDJAVA", "closed: " + reason);
        runOnUiThread(() -> pinStatus("closed: " + reason));
    }

    @Override
    public void onClipboardText(String text) {
        if (text == null) return;
        // Called on the session thread. The clipboard write and the echo bookkeeping are posted
        // together, so a poll can never see the peer's text before lastRemoteClipboard is set.
        runOnUiThread(() -> {
            lastRemoteClipboard = text;
            try {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
                if (clipboard == null) return;
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("rustdesk", text));
                android.util.Log.i("RDJAVA", "clipboard down: " + text.length() + " chars");
            } catch (RuntimeException e) {
                // A restricted profile can refuse the write; the mirror value stays recorded so
                // the uplink still will not echo the peer's own text back to it.
                android.util.Log.i("RDJAVA", "clipboard write skipped: " + e);
            }
        });
    }

    @Override
    public void onTerminalData(int terminalId, byte[] bytes) {
        // No terminal UI this round: sizes only, so the log never carries terminal content.
        android.util.Log.i("RDJAVA", "terminal data id=" + terminalId
                + " bytes=" + (bytes == null ? 0 : bytes.length));
    }

    @Override
    public void onTerminalClosed(int terminalId, int exitCode) {
        android.util.Log.i("RDJAVA", "terminal closed id=" + terminalId + " exit=" + exitCode);
    }
}




