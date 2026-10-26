package com.rustdesk.java.session;

import hbb.MessageOuterClass.FileAction;
import hbb.MessageOuterClass.FileDirCreate;
import hbb.MessageOuterClass.FileDirectory;
import hbb.MessageOuterClass.FileRemoveDir;
import hbb.MessageOuterClass.FileRemoveFile;
import hbb.MessageOuterClass.FileRename;
import hbb.MessageOuterClass.FileResponse;
import hbb.MessageOuterClass.FileTransferBlock;
import hbb.MessageOuterClass.FileTransferCancel;
import hbb.MessageOuterClass.FileTransferDigest;
import hbb.MessageOuterClass.FileTransferDone;
import hbb.MessageOuterClass.FileTransferError;
import hbb.MessageOuterClass.FileTransferReceiveRequest;
import hbb.MessageOuterClass.FileTransferSendConfirmRequest;
import hbb.MessageOuterClass.FileTransferSendRequest;
import hbb.MessageOuterClass.Message;
import hbb.MessageOuterClass.ReadAllFiles;
import hbb.MessageOuterClass.ReadDir;
import hbb.MessageOuterClass.ReadEmptyDirs;
import hbb.MessageOuterClass.ReadEmptyDirsResponse;

/**
 * Builds outgoing file-transfer messages and renders incoming ones for logging.
 *
 * <p>Requests live in {@code Message.file_action} (field 17), replies in
 * {@code Message.file_response} (field 18); see libs/base/protos/message.proto.
 * Nothing here touches the filesystem: every method only fills protobuf fields.
 */
public final class FileMessages {

    /** {@code FileTransferSendRequest.FileType.Generic}. */
    public static final int SEND_TYPE_GENERIC = 0;
    /** {@code FileTransferSendRequest.FileType.Printer}. */
    public static final int SEND_TYPE_PRINTER = 1;

    private FileMessages() {}

    /**
     * Asks the peer to list a directory.
     *
     * <p>{@code ReadDir} has no id field (proto only carries path + include_hidden), so the id
     * passed here never reaches the wire; the peer answers with a {@code FileDirectory} whose own
     * id field identifies the listing. It is kept in the signature for call sites that track a
     * request id locally.
     */
    public static Message readDirRequest(int id, String path, boolean includeHidden) {
        ReadDir readDir = ReadDir.newBuilder()
                .setPath(path)
                .setIncludeHidden(includeHidden)
                .build();
        return Message.newBuilder().setFileAction(FileAction.newBuilder().setReadDir(readDir)).build();
    }

    /**
     * Starts an outgoing file transfer.
     *
     * <p>No {@code to} overload: {@code FileTransferSendRequest} (proto) has no peer field, and
     * upstream new_send() (libs/base/src/fs.rs) does not set one either - the destination is the
     * connection the message is written to, not a protobuf field.
     */
    public static Message sendFileRequest(int id, String path, int fileNum) {
        return sendFileRequest(id, path, fileNum, false, SEND_TYPE_GENERIC);
    }

    public static Message sendFileRequest(int id, String path, int fileNum, boolean includeHidden,
            int fileType) {
        FileTransferSendRequest.Builder send = FileTransferSendRequest.newBuilder()
                .setId(id)
                .setPath(path)
                .setIncludeHidden(includeHidden)
                .setFileNum(fileNum);
        if (fileType == SEND_TYPE_PRINTER) {
            send.setFileType(FileTransferSendRequest.FileType.Printer);
        } else {
            send.setFileType(FileTransferSendRequest.FileType.Generic);
        }
        return Message.newBuilder().setFileAction(FileAction.newBuilder().setSend(send)).build();
    }

    /**
     * Creates a directory on the peer.
     *
     * <p>No {@code to} overload: {@code FileDirCreate} (proto) only has id + path; the peer is the
     * connection the message is sent on.
     */
    public static Message newDirRequest(int id, String path) {
        FileDirCreate create = FileDirCreate.newBuilder()
                .setId(id)
                .setPath(path)
                .build();
        return Message.newBuilder().setFileAction(FileAction.newBuilder().setCreate(create)).build();
    }

    /**
     * Removes a directory on the peer.
     *
     * <p>This two-argument form keeps the protobuf default {@code recursive = false}, i.e. the peer
     * only removes it when empty. Use {@link #removeDirRequest(int, String, boolean)} with
     * {@code true} to remove non-empty trees (what the reference client always sends).
     */
    public static Message removeDirRequest(int id, String path) {
        return removeDirRequest(id, path, false);
    }

    public static Message removeDirRequest(int id, String path, boolean recursive) {
        FileRemoveDir removeDir = FileRemoveDir.newBuilder()
                .setId(id)
                .setPath(path)
                .setRecursive(recursive)
                .build();
        return Message.newBuilder().setFileAction(FileAction.newBuilder().setRemoveDir(removeDir)).build();
    }

    /**
     * Removes one file on the peer.
     *
     * <p>{@code FileRemoveFile} also carries {@code file_num}, which is the index inside the running
     * transfer job rather than anything filesystem related; this form leaves it at 0.
     */
    public static Message removeFileRequest(int id, String path) {
        return removeFileRequest(id, path, 0);
    }

    public static Message removeFileRequest(int id, String path, int fileNum) {
        FileRemoveFile removeFile = FileRemoveFile.newBuilder()
                .setId(id)
                .setPath(path)
                .setFileNum(fileNum)
                .build();
        return Message.newBuilder().setFileAction(FileAction.newBuilder().setRemoveFile(removeFile)).build();
    }

    /** Renames the last path element of {@code path} to {@code newName} on the peer. */
    public static Message renameRequest(int id, String path, String newName) {
        FileRename rename = FileRename.newBuilder()
                .setId(id)
                .setPath(path)
                .setNewName(newName)
                .build();
        return Message.newBuilder().setFileAction(FileAction.newBuilder().setRename(rename)).build();
    }

    public static Message cancelRequest(int id) {
        FileTransferCancel cancel = FileTransferCancel.newBuilder().setId(id).build();
        return Message.newBuilder().setFileAction(FileAction.newBuilder().setCancel(cancel)).build();
    }

    /**
     * Renders the file fields of a message as one log line, or {@code null} when the message carries
     * no file action/response. File content is never printed: blocks only report their payload size.
     */
    public static String describe(Message message) {
        if (message == null) {
            return null;
        }
        if (message.hasFileAction()) {
            return describeAction(message.getFileAction());
        }
        if (message.hasFileResponse()) {
            return describeResponse(message.getFileResponse());
        }
        return null;
    }

    private static String describeAction(FileAction action) {
        switch (action.getUnionCase()) {
            case READ_DIR: {
                ReadDir readDir = action.getReadDir();
                return "FileAction{read_dir path=" + readDir.getPath()
                        + " include_hidden=" + readDir.getIncludeHidden() + "}";
            }
            case SEND: {
                FileTransferSendRequest send = action.getSend();
                return "FileAction{send id=" + send.getId()
                        + " path=" + send.getPath()
                        + " file_num=" + send.getFileNum()
                        + " include_hidden=" + send.getIncludeHidden()
                        + " file_type=" + send.getFileType() + "}";
            }
            case RECEIVE: {
                FileTransferReceiveRequest receive = action.getReceive();
                return "FileAction{receive id=" + receive.getId()
                        + " path=" + receive.getPath()
                        + " files=" + receive.getFilesList().size()
                        + " file_num=" + receive.getFileNum()
                        + " total_size=" + receive.getTotalSize() + "}";
            }
            case CREATE: {
                FileDirCreate create = action.getCreate();
                return "FileAction{create id=" + create.getId() + " path=" + create.getPath() + "}";
            }
            case REMOVE_DIR: {
                FileRemoveDir removeDir = action.getRemoveDir();
                return "FileAction{remove_dir id=" + removeDir.getId()
                        + " path=" + removeDir.getPath()
                        + " recursive=" + removeDir.getRecursive() + "}";
            }
            case REMOVE_FILE: {
                FileRemoveFile removeFile = action.getRemoveFile();
                return "FileAction{remove_file id=" + removeFile.getId()
                        + " path=" + removeFile.getPath()
                        + " file_num=" + removeFile.getFileNum() + "}";
            }
            case ALL_FILES: {
                ReadAllFiles allFiles = action.getAllFiles();
                return "FileAction{all_files id=" + allFiles.getId()
                        + " path=" + allFiles.getPath()
                        + " include_hidden=" + allFiles.getIncludeHidden() + "}";
            }
            case CANCEL:
                return "FileAction{cancel id=" + action.getCancel().getId() + "}";
            case SEND_CONFIRM:
                return describeSendConfirm(action.getSendConfirm());
            case RENAME: {
                FileRename rename = action.getRename();
                return "FileAction{rename id=" + rename.getId()
                        + " path=" + rename.getPath()
                        + " new_name=" + rename.getNewName() + "}";
            }
            case READ_EMPTY_DIRS: {
                ReadEmptyDirs readEmptyDirs = action.getReadEmptyDirs();
                return "FileAction{read_empty_dirs path=" + readEmptyDirs.getPath()
                        + " include_hidden=" + readEmptyDirs.getIncludeHidden() + "}";
            }
            default:
                return "FileAction{union_not_set}";
        }
    }

    private static String describeSendConfirm(FileTransferSendConfirmRequest confirm) {
        StringBuilder line = new StringBuilder("FileAction{send_confirm id=")
                .append(confirm.getId())
                .append(" file_num=").append(confirm.getFileNum());
        if (confirm.getUnionCase() == FileTransferSendConfirmRequest.UnionCase.SKIP) {
            line.append(" skip=").append(confirm.getSkip());
        } else if (confirm.getUnionCase() == FileTransferSendConfirmRequest.UnionCase.OFFSET_BLK) {
            line.append(" offset_blk=").append(confirm.getOffsetBlk());
        }
        return line.append('}').toString();
    }

    private static String describeResponse(FileResponse response) {
        switch (response.getUnionCase()) {
            case DIR: {
                FileDirectory dir = response.getDir();
                return "FileResponse{dir id=" + dir.getId()
                        + " path=" + dir.getPath()
                        + " entries=" + dir.getEntriesList().size() + "}";
            }
            case BLOCK: {
                FileTransferBlock block = response.getBlock();
                return "FileResponse{block id=" + block.getId()
                        + " file_num=" + block.getFileNum()
                        + " blk_id=" + block.getBlkId()
                        + " compressed=" + block.getCompressed()
                        + " data_len=" + block.getData().size() + "}";
            }
            case ERROR: {
                FileTransferError error = response.getError();
                return "FileResponse{error id=" + error.getId()
                        + " file_num=" + error.getFileNum()
                        + " error=" + error.getError() + "}";
            }
            case DONE: {
                FileTransferDone done = response.getDone();
                return "FileResponse{done id=" + done.getId()
                        + " file_num=" + done.getFileNum() + "}";
            }
            case DIGEST: {
                FileTransferDigest digest = response.getDigest();
                return "FileResponse{digest id=" + digest.getId()
                        + " file_num=" + digest.getFileNum()
                        + " file_size=" + digest.getFileSize()
                        + " last_modified=" + digest.getLastModified()
                        + " is_upload=" + digest.getIsUpload()
                        + " is_identical=" + digest.getIsIdentical()
                        + " is_resume=" + digest.getIsResume()
                        + " transferred_size=" + digest.getTransferredSize() + "}";
            }
            case EMPTY_DIRS: {
                ReadEmptyDirsResponse emptyDirs = response.getEmptyDirs();
                return "FileResponse{empty_dirs path=" + emptyDirs.getPath()
                        + " dirs=" + emptyDirs.getEmptyDirsList().size() + "}";
            }
            default:
                return "FileResponse{union_not_set}";
        }
    }
}
