package com.rustdesk.java.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.rustdesk.java.net.RendezvousClient;
import com.rustdesk.java.session.DeviceIdentity;
import com.rustdesk.java.session.Session;
import com.rustdesk.java.session.TerminalMessages;

import java.nio.charset.StandardCharsets;

/**
 * Terminal page: a scrollable output view over a one-line command input.
 *
 * <p>The peer terminal is opened as soon as the session reports a successful login, because
 * upstream only accepts {@code open_terminal} after the viewer is authenticated. Output arrives on
 * the session thread and is appended on the UI thread; the scrollback is bounded so a chatty
 * process cannot grow the TextView without limit.
 *
 * <p>The terminal is a raw byte pipe here: no ANSI escape interpretation, no local echo and no
 * font-metric based rows/cols.
 */
public class TerminalActivity extends Activity implements Session.Listener {

    private static final String TAG = "RDJAVA";

    /** Intent extras; same naming style as RemoteActivity.EXTRA_*. */
    public static final String EXTRA_PEER_ID = "peerId";
    public static final String EXTRA_PASSWORD = "password";
    public static final String EXTRA_SERVER = "server";

    /** Public RustDesk rendezvous server used when the intent carries no override. */
    private static final String DEFAULT_SERVER = "rs-ny.rustdesk.com";

    /** Size requested from the peer. Fixed for now; the view font is not measured. */
    private static final int TERMINAL_ROWS = 24;
    private static final int TERMINAL_COLS = 80;

    /** Characters kept in the TextView before the oldest output is dropped. */
    private static final int MAX_SCROLLBACK_CHARS = 20000;

    private static final int COLOR_BACKGROUND = 0xFF000000;
    private static final int COLOR_FOREGROUND = 0xFF00FF00;
    private static final int COLOR_STATUS = 0xFFCCCCCC;
    private static final int COLOR_HINT = 0xFF808080;

    /** Mirrors the TextView content so trimming does not need to read it back. */
    private final StringBuilder scrollback = new StringBuilder();

    private ScrollView scrollView;
    private TextView outputView;
    private EditText inputView;
    private Button sendButton;
    private TextView statusView;

    private Session session;

    /** Set once the open request has been sent, so repeated login statuses cannot reopen it. */
    private boolean terminalOpen;
    /** Set when the terminal is gone and the input must not send anything any more. */
    private boolean inputDisabled;

    private final Runnable scrollToBottom = new Runnable() {
        @Override
        public void run() {
            scrollView.fullScroll(View.FOCUS_DOWN);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BACKGROUND);

        scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        outputView = new TextView(this);
        outputView.setTypeface(Typeface.MONOSPACE);
        outputView.setTextColor(COLOR_FOREGROUND);
        outputView.setTextSize(12f);
        outputView.setTextIsSelectable(true);
        scrollView.addView(outputView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Weight 1: the output takes every pixel the command row and status line do not need.
        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        statusView = new TextView(this);
        statusView.setTextColor(COLOR_STATUS);
        statusView.setTextSize(12f);
        statusView.setPadding(dp(8), dp(2), dp(8), dp(2));
        statusView.setText("connecting");
        root.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);

        inputView = new EditText(this);
        inputView.setHint("command");
        inputView.setSingleLine(true);
        inputView.setTextColor(COLOR_FOREGROUND);
        inputView.setHintTextColor(COLOR_HINT);
        inputView.setTypeface(Typeface.MONOSPACE);
        inputView.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        inputRow.addView(inputView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        sendButton = new Button(this);
        sendButton.setText("Send");
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendLine();
            }
        });
        inputRow.addView(sendButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(inputRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        String peerId = getIntent().getStringExtra(EXTRA_PEER_ID);
        if (peerId == null || peerId.isEmpty()) {
            Log.e(TAG, "terminal: no peer id in intent");
            setStatus("no peer id");
            disableInput();
            return;
        }
        String server = getIntent().getStringExtra(EXTRA_SERVER);
        if (server == null || server.isEmpty()) server = DEFAULT_SERVER;

        DeviceIdentity identity = DeviceIdentity.load(this);
        // decoder = null: the terminal session never receives video, so no codec is created.
        session = new Session(peerId, getIntent().getStringExtra(EXTRA_PASSWORD), server,
                RendezvousClient.DEFAULT_PORT, identity, null, this);
        session.start();
        Log.i(TAG, "terminal session started for " + peerId);
    }

    /** Sends the current command line plus the newline a shell needs, then clears the field. */
    private void sendLine() {
        Session current = session;
        if (current == null || inputDisabled) return;
        String text = inputView.getText().toString();
        byte[] bytes = (text + "\n").getBytes(StandardCharsets.UTF_8);
        boolean sent = current.sendMessage(TerminalMessages.data(bytes));
        // Length only: the command itself never reaches the log.
        Log.i(TAG, "terminal input bytes=" + bytes.length + " sent=" + sent);
        if (sent) {
            inputView.setText("");
        } else {
            setStatus("not connected; command kept");
        }
    }

    // --- output buffer ---

    private void appendOutput(String text) {
        if (text.isEmpty()) return;
        scrollback.append(text);
        int overflow = scrollback.length() - MAX_SCROLLBACK_CHARS;
        if (overflow > 0) {
            scrollback.delete(0, overflow);
            outputView.setText(scrollback);
        } else {
            outputView.append(text);
        }
        scrollView.post(scrollToBottom);
    }

    private void setStatus(String text) {
        if (statusView != null) statusView.setText(text);
    }

    private void disableInput() {
        inputDisabled = true;
        if (inputView != null) inputView.setEnabled(false);
        if (sendButton != null) sendButton.setEnabled(false);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // --- session lifecycle ---

    private void openTerminal() {
        if (terminalOpen) return;
        terminalOpen = true;
        Session current = session;
        boolean sent = current != null && current.sendMessage(
                TerminalMessages.open(TERMINAL_ROWS, TERMINAL_COLS));
        Log.i(TAG, "terminal open rows=" + TERMINAL_ROWS + " cols=" + TERMINAL_COLS
                + " sent=" + sent);
    }

    @Override
    protected void onDestroy() {
        Session current = session;
        session = null;
        if (current != null) current.stop();
        super.onDestroy();
    }

    // --- Session.Listener ---

    @Override
    public void onStatus(final String text) {
        Log.i(TAG, "terminal status: " + text);
        if ("login ok".equals(text)) {
            // Called on the session thread; sendMessage is safe from there.
            openTerminal();
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!inputDisabled) setStatus(text);
            }
        });
    }

    @Override
    public void onPeerInfo(String platform, int width, int height) {
        Log.i(TAG, "terminal peer " + platform + " " + width + "x" + height);
        // The terminal does not render video, so the display size is only diagnostic.
    }

    @Override
    public void onClosed(final String reason) {
        Log.e(TAG, "terminal session closed: " + reason);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                appendOutput("\n[disconnected: " + reason + "]\n");
                disableInput();
                setStatus("closed: " + reason);
            }
        });
    }

    @Override
    public void onClipboardText(String text) {
        // A terminal page has no clipboard surface of its own; sizes only, never the text.
        Log.i(TAG, "terminal clipboard ignored: chars=" + (text == null ? 0 : text.length()));
    }

    @Override
    public void onTerminalData(int terminalId, byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;
        Log.i(TAG, "terminal data id=" + terminalId + " bytes=" + bytes.length);
        final String text = new String(bytes, StandardCharsets.UTF_8);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                appendOutput(text);
            }
        });
    }

    @Override
    public void onTerminalClosed(final int terminalId, final int exitCode) {
        Log.i(TAG, "terminal closed id=" + terminalId + " exit=" + exitCode);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                appendOutput("\n[terminal closed, exit=" + exitCode + "]\n");
                disableInput();
                setStatus("terminal closed, exit=" + exitCode);
            }
        });
    }
}
