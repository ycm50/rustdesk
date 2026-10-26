// Migrated from A:\Downloads\tmpscripts\terminal\TerminalMsgTest.java -- JVM-only regression suite, see tools/selftest/suites/README.md
package com.rustdesk.java.selftest;

import com.rustdesk.java.session.TerminalMessages;
import hbb.MessageOuterClass.LoginRequest;
import hbb.MessageOuterClass.Message;
import hbb.MessageOuterClass.MouseEvent;
import hbb.MessageOuterClass.TerminalAction;
import hbb.MessageOuterClass.TerminalClosed;
import hbb.MessageOuterClass.TerminalData;
import hbb.MessageOuterClass.TerminalError;
import hbb.MessageOuterClass.TerminalOpened;
import hbb.MessageOuterClass.TerminalResponse;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Plain-JVM self test for com.rustdesk.java.session.TerminalMessages.
 * Every builder is serialized, parsed back and compared field by field.
 */
public final class TerminalMsgTest {

    private static final PrintStream OUT = utf8Stdout();

    private static PrintStream utf8Stdout() {
        try {
            return new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    // Chinese text plus a bell and an ANSI colour sequence: control bytes must survive.
    private static final String SECRET = "\u4f60\u597d\u0003\u001b[31mred\u001b[0m \u4e2d\u6587";
    private static final byte[] SECRET_BYTES = SECRET.getBytes(StandardCharsets.UTF_8);

    private static int checks = 0;
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        testOpenDefault();
        testOpenExplicitId();
        testDataAscii();
        testDataUnicodeControl();
        testDataEmpty();
        testResize();
        testClose();
        testDescribeRequests();
        testDescribeNeverLeaksPayload();
        testDescribeNonTerminal();
        testDescribeResponses();

        OUT.println();
        OUT.println("checks: " + checks + ", failures: " + failures);
        if (failures == 0) {
            OUT.println("RESULT: ALL PASSED");
        } else {
            OUT.println("RESULT: FAILED");
            System.exit(1);
        }
    }

    private static void testOpenDefault() throws Exception {
        OUT.println("--- open(rows, cols) ---");
        Message sent = TerminalMessages.open(24, 80);
        checkTrue("open hasTerminalAction", sent.hasTerminalAction());
        checkEquals("open message union", Message.UnionCase.TERMINAL_ACTION, sent.getUnionCase());
        checkEquals("open action union", TerminalAction.UnionCase.OPEN, sent.getTerminalAction().getUnionCase());

        Message got = roundTrip(sent);
        checkTrue("open(r) hasTerminalAction", got.hasTerminalAction());
        checkEquals("open(r) action union", TerminalAction.UnionCase.OPEN, got.getTerminalAction().getUnionCase());
        checkEquals("open(r) terminal_id", 0, got.getTerminalAction().getOpen().getTerminalId());
        checkEquals("open(r) rows", 24, got.getTerminalAction().getOpen().getRows());
        checkEquals("open(r) cols", 80, got.getTerminalAction().getOpen().getCols());
        checkTrue("open(r) no reply field", !got.hasTerminalResponse());
    }

    private static void testOpenExplicitId() throws Exception {
        OUT.println("--- open(terminalId, rows, cols) ---");
        Message got = roundTrip(TerminalMessages.open(7, 48, 200));
        checkEquals("open(id) terminal_id", 7, got.getTerminalAction().getOpen().getTerminalId());
        checkEquals("open(id) rows", 48, got.getTerminalAction().getOpen().getRows());
        checkEquals("open(id) cols", 200, got.getTerminalAction().getOpen().getCols());
    }

    private static void testDataAscii() throws Exception {
        OUT.println("--- data(byte[]) ascii ---");
        byte[] payload = "ls -l\r".getBytes(StandardCharsets.UTF_8);
        Message got = roundTrip(TerminalMessages.data(payload));
        checkEquals("data union", TerminalAction.UnionCase.DATA, got.getTerminalAction().getUnionCase());
        checkEquals("data terminal_id", 0, got.getTerminalAction().getData().getTerminalId());
        checkTrue("data bytes equal", Arrays.equals(payload,
                got.getTerminalAction().getData().getData().toByteArray()));
        checkEquals("data compressed default", false, got.getTerminalAction().getData().getCompressed());
    }

    private static void testDataUnicodeControl() throws Exception {
        OUT.println("--- data unicode + control ---");
        Message got = roundTrip(TerminalMessages.data(3, SECRET_BYTES));
        TerminalData data = got.getTerminalAction().getData();
        checkEquals("data(id) terminal_id", 3, data.getTerminalId());
        checkTrue("data(id) bytes equal", Arrays.equals(SECRET_BYTES, data.getData().toByteArray()));
        checkEquals("data(id) byte count", SECRET_BYTES.length, data.getData().size());
        checkEquals("data(id) text round trip", SECRET, data.getData().toStringUtf8());
        checkTrue("data(id) keeps BEL", data.getData().toStringUtf8().indexOf('\u0003') >= 0);
        checkTrue("data(id) keeps ESC", data.getData().toStringUtf8().indexOf('\u001b') >= 0);
    }

    private static void testDataEmpty() throws Exception {
        OUT.println("--- data(empty) ---");
        Message got = roundTrip(TerminalMessages.data(new byte[0]));
        checkEquals("data(empty) union", TerminalAction.UnionCase.DATA, got.getTerminalAction().getUnionCase());
        checkEquals("data(empty) size", 0, got.getTerminalAction().getData().getData().size());
    }

    private static void testResize() throws Exception {
        OUT.println("--- resize ---");
        Message got = roundTrip(TerminalMessages.resize(40, 132));
        checkEquals("resize union", TerminalAction.UnionCase.RESIZE, got.getTerminalAction().getUnionCase());
        checkEquals("resize terminal_id", 0, got.getTerminalAction().getResize().getTerminalId());
        checkEquals("resize rows", 40, got.getTerminalAction().getResize().getRows());
        checkEquals("resize cols", 132, got.getTerminalAction().getResize().getCols());

        Message got2 = roundTrip(TerminalMessages.resize(5, 10, 20));
        checkEquals("resize(id) terminal_id", 5, got2.getTerminalAction().getResize().getTerminalId());
        checkEquals("resize(id) rows", 10, got2.getTerminalAction().getResize().getRows());
        checkEquals("resize(id) cols", 20, got2.getTerminalAction().getResize().getCols());
    }

    private static void testClose() throws Exception {
        OUT.println("--- close ---");
        Message got = roundTrip(TerminalMessages.close());
        checkEquals("close union", TerminalAction.UnionCase.CLOSE, got.getTerminalAction().getUnionCase());
        checkEquals("close terminal_id", 0, got.getTerminalAction().getClose().getTerminalId());

        Message got2 = roundTrip(TerminalMessages.close(9));
        checkEquals("close(id) terminal_id", 9, got2.getTerminalAction().getClose().getTerminalId());
    }

    private static void testDescribeRequests() {
        OUT.println("--- describe requests ---");
        String open = TerminalMessages.describe(TerminalMessages.open(24, 80));
        checkTrue("describe open non-null", open != null);
        checkTrue("describe open text", open != null && open.startsWith("TerminalAction{open ")
                && open.contains("rows=24") && open.contains("cols=80"));

        String data = TerminalMessages.describe(TerminalMessages.data(SECRET_BYTES));
        checkTrue("describe data non-null", data != null);
        checkTrue("describe data reports size",
                data != null && data.contains("bytes=" + SECRET_BYTES.length));

        String resize = TerminalMessages.describe(TerminalMessages.resize(40, 132));
        checkTrue("describe resize non-null", resize != null
                && resize.contains("resize") && resize.contains("rows=40"));

        String close = TerminalMessages.describe(TerminalMessages.close(9));
        checkTrue("describe close non-null", close != null && close.contains("close")
                && close.contains("terminal_id=9"));
    }

    private static void testDescribeNeverLeaksPayload() {
        OUT.println("--- describe payload leak check ---");
        String data = TerminalMessages.describe(TerminalMessages.data(1, SECRET_BYTES));
        checkTrue("describe not null", data != null);
        checkTrue("leak: full payload absent", data != null && !data.contains(SECRET));
        checkTrue("leak: ascii part absent", data != null && !data.contains("red"));
        checkTrue("leak: cjk part absent", data != null && !data.contains("\u4f60\u597d"));
        checkTrue("leak: ESC absent", data != null && data.indexOf('\u001b') < 0);
        checkTrue("leak: BEL absent", data != null && data.indexOf('\u0003') < 0);
        OUT.println("  describe(data) = " + data);

        String empty = TerminalMessages.describe(TerminalMessages.data(new byte[0]));
        checkTrue("describe empty payload", empty != null && empty.contains("bytes=0"));
    }

    private static void testDescribeNonTerminal() {
        OUT.println("--- describe non-terminal ---");
        Message mouse = Message.newBuilder()
                .setMouseEvent(MouseEvent.newBuilder().setX(11).setY(22).build())
                .build();
        checkTrue("describe mouseEvent null", TerminalMessages.describe(mouse) == null);
        checkTrue("describe empty message null", TerminalMessages.describe(Message.getDefaultInstance()) == null);
        checkTrue("describe null null", TerminalMessages.describe(null) == null);

        Message login = Message.newBuilder()
                .setLoginRequest(LoginRequest.newBuilder().setMyId("123456789").build())
                .build();
        checkTrue("describe loginRequest null", TerminalMessages.describe(login) == null);
    }

    private static void testDescribeResponses() throws Exception {
        OUT.println("--- describe responses ---");
        TerminalOpened openedMsg = TerminalOpened.newBuilder()
                .setTerminalId(2).setSuccess(true).setPid(4242).setServiceId("svc-abc").build();
        Message opened = Message.newBuilder()
                .setTerminalResponse(TerminalResponse.newBuilder().setOpened(openedMsg)).build();
        Message openedBack = roundTrip(opened);
        checkEquals("opened terminal_id", 2, openedBack.getTerminalResponse().getOpened().getTerminalId());
        checkEquals("opened success", true, openedBack.getTerminalResponse().getOpened().getSuccess());
        String openedText = TerminalMessages.describe(openedBack);
        checkTrue("describe opened non-null", openedText != null && openedText.contains("opened")
                && openedText.contains("pid=4242") && openedText.contains("svc-abc"));

        Message dataResp = Message.newBuilder().setTerminalResponse(TerminalResponse.newBuilder()
                .setData(TerminalData.newBuilder().setTerminalId(2)
                        .setData(com.google.protobuf.ByteString.copyFrom(SECRET_BYTES))
                        .setCompressed(true))).build();
        Message dataBack = roundTrip(dataResp);
        String dataText = TerminalMessages.describe(dataBack);
        checkTrue("describe response data non-null", dataText != null
                && dataText.contains("bytes=" + SECRET_BYTES.length));
        checkTrue("leak: response payload absent", dataText != null && !dataText.contains(SECRET));
        checkTrue("response payload kept", Arrays.equals(SECRET_BYTES,
                dataBack.getTerminalResponse().getData().getData().toByteArray()));

        Message closedResp = Message.newBuilder().setTerminalResponse(TerminalResponse.newBuilder()
                .setClosed(TerminalClosed.newBuilder().setTerminalId(2).setExitCode(130))).build();
        String closedText = TerminalMessages.describe(roundTrip(closedResp));
        checkTrue("describe closed", closedText != null && closedText.contains("exit_code=130"));

        Message errResp = Message.newBuilder().setTerminalResponse(TerminalResponse.newBuilder()
                .setError(TerminalError.newBuilder().setTerminalId(2).setMessage("no pty"))).build();
        String errText = TerminalMessages.describe(roundTrip(errResp));
        checkTrue("describe error", errText != null && errText.contains("no pty"));

        String unset = TerminalMessages.describe(TerminalMessages.open(1, 1));
        checkTrue("describe action never response", unset != null && unset.startsWith("TerminalAction{"));
    }

    private static Message roundTrip(Message m) throws Exception {
        byte[] wire = m.toByteArray();
        Message back = Message.parseFrom(wire);
        checkTrue("roundTrip stable (" + wire.length + "B)", Arrays.equals(wire, back.toByteArray()));
        return back;
    }

    private static void checkTrue(String label, boolean ok) {
        checks++;
        if (ok) {
            OUT.println("[PASS] " + label);
        } else {
            failures++;
            OUT.println("[FAIL] " + label);
        }
    }

    private static void checkEquals(String label, Object expected, Object actual) {
        checks++;
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            OUT.println("[PASS] " + label);
        } else {
            failures++;
            OUT.println("[FAIL] " + label + " expected=" + expected + " actual=" + actual);
        }
    }
}
