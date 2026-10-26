package com.rustdesk.java.session;

import com.google.protobuf.ByteString;

import hbb.MessageOuterClass.CloseTerminal;
import hbb.MessageOuterClass.Message;
import hbb.MessageOuterClass.OpenTerminal;
import hbb.MessageOuterClass.ResizeTerminal;
import hbb.MessageOuterClass.TerminalAction;
import hbb.MessageOuterClass.TerminalClosed;
import hbb.MessageOuterClass.TerminalData;
import hbb.MessageOuterClass.TerminalError;
import hbb.MessageOuterClass.TerminalOpened;
import hbb.MessageOuterClass.TerminalResponse;

/**
 * Builds outgoing terminal messages and renders incoming ones for logging.
 *
 * <p>Client requests travel in {@code Message.terminal_action} (field 31) as a
 * {@code TerminalAction} oneof; peer replies travel in {@code Message.terminal_response}
 * (field 32). Session setup is a separate message: {@code LoginRequest.terminal} (field 16)
 * carries only {@code Terminal.service_id} and belongs to the login code, not here.
 *
 * <p>This class keeps no terminal content of its own and {@link #describe(Message)} never
 * renders a payload: byte counts only.
 */
public final class TerminalMessages {

    /** {@code terminal_id} 0 selects the peer's default terminal; see OpenTerminal in message.proto. */
    public static final int DEFAULT_TERMINAL_ID = 0;

    private TerminalMessages() {}

    /** Opens a terminal on the peer with its default id. */
    public static Message open(int rows, int cols) {
        return open(DEFAULT_TERMINAL_ID, rows, cols);
    }

    public static Message open(int terminalId, int rows, int cols) {
        OpenTerminal open = OpenTerminal.newBuilder()
                .setTerminalId(terminalId)
                .setRows(rows)
                .setCols(cols)
                .build();
        return action(TerminalAction.newBuilder().setOpen(open));
    }

    /** Sends stdin bytes to the peer's default terminal. */
    public static Message data(byte[] bytes) {
        return data(DEFAULT_TERMINAL_ID, bytes);
    }

    /**
     * Sends stdin bytes.
     *
     * <p>{@code TerminalData.compressed} is set by the peer on its output; upstream
     * send_terminal_input() (ui_session_interface.rs) leaves it unset for stdin, which encodes
     * the same as false, so it is not set here either.
     */
    public static Message data(int terminalId, byte[] bytes) {
        TerminalData data = TerminalData.newBuilder()
                .setTerminalId(terminalId)
                .setData(ByteString.copyFrom(bytes))
                .build();
        return action(TerminalAction.newBuilder().setData(data));
    }

    /** Resizes the peer's default terminal. */
    public static Message resize(int rows, int cols) {
        return resize(DEFAULT_TERMINAL_ID, rows, cols);
    }

    public static Message resize(int terminalId, int rows, int cols) {
        ResizeTerminal resize = ResizeTerminal.newBuilder()
                .setTerminalId(terminalId)
                .setRows(rows)
                .setCols(cols)
                .build();
        return action(TerminalAction.newBuilder().setResize(resize));
    }

    /** Closes the peer's default terminal. */
    public static Message close() {
        return close(DEFAULT_TERMINAL_ID);
    }

    public static Message close(int terminalId) {
        CloseTerminal close = CloseTerminal.newBuilder()
                .setTerminalId(terminalId)
                .build();
        return action(TerminalAction.newBuilder().setClose(close));
    }

    /**
     * One-line summary of a terminal message (request or reply), or {@code null} when
     * {@code m} is not terminal traffic.
     *
     * <p>Payloads are reported as sizes, never as text, so an error log cannot leak what the
     * user typed or what the peer printed.
     */
    public static String describe(Message m) {
        if (m == null) {
            return null;
        }
        switch (m.getUnionCase()) {
            case TERMINAL_ACTION:
                return describeAction(m.getTerminalAction());
            case TERMINAL_RESPONSE:
                return describeResponse(m.getTerminalResponse());
            default:
                return null;
        }
    }

    private static Message action(TerminalAction.Builder action) {
        return Message.newBuilder().setTerminalAction(action).build();
    }

    private static String describeAction(TerminalAction action) {
        switch (action.getUnionCase()) {
            case OPEN: {
                OpenTerminal open = action.getOpen();
                return "TerminalAction{open terminal_id=" + open.getTerminalId()
                        + " rows=" + open.getRows()
                        + " cols=" + open.getCols() + "}";
            }
            case DATA: {
                TerminalData data = action.getData();
                return "TerminalAction{data terminal_id=" + data.getTerminalId()
                        + " bytes=" + data.getData().size()
                        + " compressed=" + data.getCompressed() + "}";
            }
            case RESIZE: {
                ResizeTerminal resize = action.getResize();
                return "TerminalAction{resize terminal_id=" + resize.getTerminalId()
                        + " rows=" + resize.getRows()
                        + " cols=" + resize.getCols() + "}";
            }
            case CLOSE:
                return "TerminalAction{close terminal_id=" + action.getClose().getTerminalId() + "}";
            default:
                return "TerminalAction{union_not_set}";
        }
    }

    private static String describeResponse(TerminalResponse response) {
        switch (response.getUnionCase()) {
            case OPENED: {
                TerminalOpened opened = response.getOpened();
                StringBuilder line = new StringBuilder("TerminalResponse{opened terminal_id=")
                        .append(opened.getTerminalId())
                        .append(" success=").append(opened.getSuccess())
                        .append(" pid=").append(opened.getPid());
                if (!opened.getServiceId().isEmpty()) {
                    line.append(" service_id=").append(opened.getServiceId());
                }
                if (opened.getPersistentSessionsCount() > 0) {
                    line.append(" persistent_sessions=").append(opened.getPersistentSessionsCount());
                }
                if (opened.getReplayTerminalOutput()) {
                    line.append(" replay_terminal_output=true");
                }
                if (!opened.getMessage().isEmpty()) {
                    line.append(" message=").append(opened.getMessage());
                }
                return line.append('}').toString();
            }
            case DATA: {
                TerminalData data = response.getData();
                return "TerminalResponse{data terminal_id=" + data.getTerminalId()
                        + " bytes=" + data.getData().size()
                        + " compressed=" + data.getCompressed() + "}";
            }
            case CLOSED: {
                TerminalClosed closed = response.getClosed();
                return "TerminalResponse{closed terminal_id=" + closed.getTerminalId()
                        + " exit_code=" + closed.getExitCode() + "}";
            }
            case ERROR: {
                TerminalError error = response.getError();
                return "TerminalResponse{error terminal_id=" + error.getTerminalId()
                        + " message=" + error.getMessage() + "}";
            }
            default:
                return "TerminalResponse{union_not_set}";
        }
    }
}
