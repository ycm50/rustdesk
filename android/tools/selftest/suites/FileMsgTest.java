// Migrated from A:\Downloads\tmpscripts\filemsg\FileMsgTest.java -- JVM-only regression suite, see tools/selftest/suites/README.md
package com.rustdesk.java.selftest;

import com.rustdesk.java.session.FileMessages;
import com.google.protobuf.ByteString;
import hbb.MessageOuterClass.FileAction;
import hbb.MessageOuterClass.FileDirectory;
import hbb.MessageOuterClass.FileEntry;
import hbb.MessageOuterClass.FileResponse;
import hbb.MessageOuterClass.FileTransferBlock;
import hbb.MessageOuterClass.FileTransferDigest;
import hbb.MessageOuterClass.FileTransferDone;
import hbb.MessageOuterClass.FileTransferError;
import hbb.MessageOuterClass.FileTransferSendRequest;
import hbb.MessageOuterClass.FileType;
import hbb.MessageOuterClass.Message;
import hbb.MessageOuterClass.MouseEvent;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;

/**
 * Plain-JVM self test for com.rustdesk.java.session.FileMessages.
 * Every builder is serialized, parsed back and compared field by field.
 */
public final class FileMsgTest {

    private static final PrintStream OUT = utf8Stdout();

    private static PrintStream utf8Stdout() {
        try {
            return new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String CN_DIR = "/sdcard/\u6211\u7684 \u6587\u6863/\u7167\u7247 2024";
    private static final String CN_FILE = "A:\\\u6d4b\u8bd5 \u76ee\u5f55\\\u6587\u4ef6 \u540d.txt";

    private static int checks = 0;
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        testReadDir();
        testSendFile();
        testNewDir();
        testRemoveDir();
        testRemoveFile();
        testRename();
        testCancel();
        testDescribeResponses();
        testDescribeIgnoresNonFileMessages();

        OUT.println();
        OUT.println("checks: " + checks + ", failures: " + failures);
        if (failures == 0) {
            OUT.println("RESULT: ALL PASSED");
        } else {
            OUT.println("RESULT: FAILED");
            System.exit(1);
        }
    }

    private static void testReadDir() throws Exception {
        OUT.println("--- readDirRequest ---");
        Message sent = FileMessages.readDirRequest(101, CN_DIR, true);
        Message got = roundTrip(sent, "readDir");
        checkTrue("readDir hasFileAction", got.hasFileAction());
        checkEquals("readDir union", FileAction.UnionCase.READ_DIR, got.getFileAction().getUnionCase());
        checkEquals("readDir path", CN_DIR, got.getFileAction().getReadDir().getPath());
        checkEquals("readDir includeHidden", true, got.getFileAction().getReadDir().getIncludeHidden());

        Message hiddenOff = roundTrip(FileMessages.readDirRequest(102, "/sdcard", false), "readDir2");
        checkEquals("readDir2 includeHidden", false, hiddenOff.getFileAction().getReadDir().getIncludeHidden());

        String line = FileMessages.describe(sent);
        checkTrue("describe(readDir) non-empty", line != null && !line.isEmpty());
        checkTrue("describe(readDir) keeps path", line != null && line.contains(CN_DIR));
        checkTrue("describe(readDir) shows union", line != null && line.contains("read_dir"));
    }

    private static void testSendFile() throws Exception {
        OUT.println("--- sendFileRequest ---");
        Message sent = FileMessages.sendFileRequest(11, CN_FILE, 3);
        Message got = roundTrip(sent, "sendFile");
        checkTrue("sendFile hasFileAction", got.hasFileAction());
        checkEquals("sendFile union", FileAction.UnionCase.SEND, got.getFileAction().getUnionCase());
        FileTransferSendRequest req = got.getFileAction().getSend();
        checkEquals("sendFile id", 11, req.getId());
        checkEquals("sendFile path", CN_FILE, req.getPath());
        checkEquals("sendFile fileNum", 3, req.getFileNum());
        checkEquals("sendFile includeHidden default", false, req.getIncludeHidden());
        checkEquals("sendFile fileType", FileTransferSendRequest.FileType.Generic, req.getFileType());

        Message printer = roundTrip(
                FileMessages.sendFileRequest(12, CN_DIR, 4, true, FileMessages.SEND_TYPE_PRINTER), "sendFilePrinter");
        FileTransferSendRequest preq = printer.getFileAction().getSend();
        checkEquals("sendPrinter id", 12, preq.getId());
        checkEquals("sendPrinter includeHidden", true, preq.getIncludeHidden());
        checkEquals("sendPrinter fileType", FileTransferSendRequest.FileType.Printer, preq.getFileType());

        String line = FileMessages.describe(sent);
        checkTrue("describe(send) non-empty", line != null && !line.isEmpty());
        checkTrue("describe(send) keeps path", line != null && line.contains(CN_FILE));
        checkTrue("describe(send) shows id", line != null && line.contains("id=11"));
    }

    private static void testNewDir() throws Exception {
        OUT.println("--- newDirRequest ---");
        String path = "/sdcard/\u65b0\u5efa \u76ee\u5f55";
        Message got = roundTrip(FileMessages.newDirRequest(13, path), "newDir");
        checkTrue("newDir hasFileAction", got.hasFileAction());
        checkEquals("newDir union", FileAction.UnionCase.CREATE, got.getFileAction().getUnionCase());
        checkEquals("newDir id", 13, got.getFileAction().getCreate().getId());
        checkEquals("newDir path", path, got.getFileAction().getCreate().getPath());

        String line = FileMessages.describe(FileMessages.newDirRequest(13, path));
        checkTrue("describe(newDir) non-empty", line != null && !line.isEmpty());
        checkTrue("describe(newDir) keeps path", line != null && line.contains(path));
    }

    private static void testRemoveDir() throws Exception {
        OUT.println("--- removeDirRequest ---");
        Message got = roundTrip(FileMessages.removeDirRequest(14, CN_DIR), "removeDir");
        checkTrue("removeDir hasFileAction", got.hasFileAction());
        checkEquals("removeDir union", FileAction.UnionCase.REMOVE_DIR, got.getFileAction().getUnionCase());
        checkEquals("removeDir id", 14, got.getFileAction().getRemoveDir().getId());
        checkEquals("removeDir path", CN_DIR, got.getFileAction().getRemoveDir().getPath());
        checkEquals("removeDir recursive default", false, got.getFileAction().getRemoveDir().getRecursive());

        Message recursive = roundTrip(FileMessages.removeDirRequest(15, CN_DIR, true), "removeDirRecursive");
        checkEquals("removeDir recursive true", true, recursive.getFileAction().getRemoveDir().getRecursive());

        String line = FileMessages.describe(got);
        checkTrue("describe(removeDir) non-empty", line != null && !line.isEmpty());
        checkTrue("describe(removeDir) keeps path", line != null && line.contains(CN_DIR));
    }

    private static void testRemoveFile() throws Exception {
        OUT.println("--- removeFileRequest ---");
        Message got = roundTrip(FileMessages.removeFileRequest(16, CN_FILE), "removeFile");
        checkTrue("removeFile hasFileAction", got.hasFileAction());
        checkEquals("removeFile union", FileAction.UnionCase.REMOVE_FILE, got.getFileAction().getUnionCase());
        checkEquals("removeFile id", 16, got.getFileAction().getRemoveFile().getId());
        checkEquals("removeFile path", CN_FILE, got.getFileAction().getRemoveFile().getPath());
        checkEquals("removeFile fileNum default", 0, got.getFileAction().getRemoveFile().getFileNum());

        Message numbered = roundTrip(FileMessages.removeFileRequest(17, CN_FILE, 5), "removeFileNum");
        checkEquals("removeFile fileNum", 5, numbered.getFileAction().getRemoveFile().getFileNum());

        String line = FileMessages.describe(got);
        checkTrue("describe(removeFile) non-empty", line != null && !line.isEmpty());
        checkTrue("describe(removeFile) keeps path", line != null && line.contains(CN_FILE));
    }

    private static void testRename() throws Exception {
        OUT.println("--- renameRequest ---");
        String newName = "\u65b0\u540d \u5b57.jpg";
        Message got = roundTrip(FileMessages.renameRequest(18, CN_FILE, newName), "rename");
        checkTrue("rename hasFileAction", got.hasFileAction());
        checkEquals("rename union", FileAction.UnionCase.RENAME, got.getFileAction().getUnionCase());
        checkEquals("rename id", 18, got.getFileAction().getRename().getId());
        checkEquals("rename path", CN_FILE, got.getFileAction().getRename().getPath());
        checkEquals("rename newName", newName, got.getFileAction().getRename().getNewName());

        String line = FileMessages.describe(got);
        checkTrue("describe(rename) non-empty", line != null && !line.isEmpty());
        checkTrue("describe(rename) keeps newName", line != null && line.contains(newName));
    }

    private static void testCancel() throws Exception {
        OUT.println("--- cancelRequest ---");
        Message got = roundTrip(FileMessages.cancelRequest(19), "cancel");
        checkTrue("cancel hasFileAction", got.hasFileAction());
        checkEquals("cancel union", FileAction.UnionCase.CANCEL, got.getFileAction().getUnionCase());
        checkEquals("cancel id", 19, got.getFileAction().getCancel().getId());

        String line = FileMessages.describe(got);
        checkTrue("describe(cancel) non-empty", line != null && !line.isEmpty());
        checkTrue("describe(cancel) keeps id", line != null && line.contains("id=19"));
    }

    private static void testDescribeResponses() {
        OUT.println("--- describe(FileResponse) ---");

        FileDirectory dir = FileDirectory.newBuilder()
                .setId(7)
                .setPath(CN_DIR)
                .addEntries(FileEntry.newBuilder()
                        .setEntryType(FileType.Dir)
                        .setName("\u5b50\u76ee\u5f55")
                        .setIsHidden(true)
                        .setSize(0)
                        .setModifiedTime(1700000000L)
                        .build())
                .build();
        Message dirMsg = Message.newBuilder()
                .setFileResponse(FileResponse.newBuilder().setDir(dir))
                .build();
        String dirLine = FileMessages.describe(dirMsg);
        checkTrue("describe(response dir) non-empty",
                dirLine != null && dirLine.contains("entries=1") && dirLine.contains(CN_DIR));

        FileTransferBlock block = FileTransferBlock.newBuilder()
                .setId(9)
                .setFileNum(2)
                .setBlkId(3)
                .setCompressed(true)
                .setData(ByteString.copyFromUtf8("SECRET-PAYLOAD"))
                .build();
        String blockLine = FileMessages.describe(Message.newBuilder()
                .setFileResponse(FileResponse.newBuilder().setBlock(block))
                .build());
        checkTrue("describe(response block) non-empty", blockLine != null && blockLine.contains("blk_id=3"));
        checkTrue("describe(response block) prints no payload",
                blockLine != null && !blockLine.contains("SECRET") && blockLine.contains("data_len=14"));

        String errLine = FileMessages.describe(Message.newBuilder()
                .setFileResponse(FileResponse.newBuilder().setError(FileTransferError.newBuilder()
                        .setId(1).setError("\u6743\u9650\u4e0d\u8db3").setFileNum(0).build()))
                .build());
        checkTrue("describe(response error) non-empty", errLine != null && errLine.contains("\u6743\u9650\u4e0d\u8db3"));

        String doneLine = FileMessages.describe(Message.newBuilder()
                .setFileResponse(FileResponse.newBuilder().setDone(FileTransferDone.newBuilder()
                        .setId(1).setFileNum(4).build()))
                .build());
        checkTrue("describe(response done) non-empty", doneLine != null && doneLine.contains("file_num=4"));

        String digestLine = FileMessages.describe(Message.newBuilder()
                .setFileResponse(FileResponse.newBuilder().setDigest(FileTransferDigest.newBuilder()
                        .setId(1).setFileNum(1).setFileSize(1024).setIsIdentical(false).build()))
                .build());
        checkTrue("describe(response digest) non-empty",
                digestLine != null && digestLine.contains("file_size=1024") && digestLine.contains("digest"));
    }

    private static void testDescribeIgnoresNonFileMessages() {
        OUT.println("--- describe(non-file) ---");
        Message mouse = Message.newBuilder()
                .setMouseEvent(MouseEvent.newBuilder().setMask(0).setX(10).setY(20).build())
                .build();
        checkTrue("describe(mouseEvent) is null", FileMessages.describe(mouse) == null);
        String nullLine = FileMessages.describe(null);
        checkTrue("describe(null) is null", nullLine == null);

        Message empty = Message.getDefaultInstance();
        checkTrue("describe(empty message) is null", FileMessages.describe(empty) == null);
    }

    private static Message roundTrip(Message sent, String tag) throws Exception {
        byte[] bytes = sent.toByteArray();
        Message parsed = Message.parseFrom(bytes);
        checkTrue(tag + " serialized non-empty", bytes.length > 0);
        return parsed;
    }

    private static void checkTrue(String what, boolean ok) {
        checks++;
        if (ok) {
            OUT.println("[PASS] " + what);
        } else {
            failures++;
            OUT.println("[FAIL] " + what);
        }
    }

    private static void checkEquals(String what, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            checks++;
            OUT.println("[PASS] " + what);
        } else {
            failures++;
            OUT.println("[FAIL] " + what + " expected=" + expected + " actual=" + actual);
        }
    }
}
