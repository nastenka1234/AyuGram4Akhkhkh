package com.radolyn.ayugram.history;

import android.text.TextUtils;
import androidx.core.util.Pair;

import com.google.android.exoplayer2.util.Log;
import com.radolyn.ayugram.AyuConstants;
import com.radolyn.ayugram.AyuUtils;
import com.radolyn.ayugram.database.entities.AyuMessageBase;
import com.radolyn.ayugram.messages.AyuMessagesController;
import com.radolyn.ayugram.messages.AyuSavePreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;

/**
 * JojoGram: open replacement for the closed-source "proprietary" mapping code.
 * Converts TLRPC messages to database entities (save) and back (show in chat).
 */
public class AyuMessageUtils {

    // ------------------------------------------------------------------ message -> entity

    public static void map(AyuSavePreferences prefs, AyuMessageBase base) {
        base.userId = prefs.getUserId();
        base.dialogId = prefs.getDialogId();
        base.topicId = prefs.getTopicId();
        base.messageId = prefs.getMessageId();
        base.entityCreateDate = prefs.getRequestCatchTime();

        TLRPC.Message msg = prefs.getMessage();
        if (msg == null) {
            return;
        }

        base.groupedId = msg.grouped_id;
        base.peerId = msg.peer_id != null ? MessageObject.getPeerId(msg.peer_id) : prefs.getDialogId();
        base.fromId = msg.from_id != null ? MessageObject.getPeerId(msg.from_id) : 0;
        base.date = msg.date;
        base.flags = msg.flags;
        base.editDate = msg.edit_date;
        base.views = msg.views;

        if (msg.fwd_from != null) {
            base.fwdFlags = msg.fwd_from.flags;
            base.fwdFromId = msg.fwd_from.from_id != null ? MessageObject.getPeerId(msg.fwd_from.from_id) : 0;
            base.fwdName = msg.fwd_from.from_name;
            base.fwdDate = msg.fwd_from.date;
            base.fwdPostAuthor = msg.fwd_from.post_author;
        }

        if (msg.reply_to != null) {
            base.replyFlags = msg.reply_to.flags;
            base.replyMessageId = msg.reply_to.reply_to_msg_id;
            base.replyPeerId = msg.reply_to.reply_to_peer_id != null ? MessageObject.getPeerId(msg.reply_to.reply_to_peer_id) : 0;
            base.replyTopId = msg.reply_to.reply_to_top_id;
            base.replyForumTopic = msg.reply_to.forum_topic;
        }

        base.text = msg.message;
        base.textEntities = serializeEntities(msg.entities);
    }

    public static void mapMedia(AyuSavePreferences prefs, AyuMessageBase base, boolean saveFiles) {
        TLRPC.Message msg = prefs.getMessage();
        if (msg == null || msg.media == null) {
            return;
        }

        int account = prefs.getAccountId();

        try {
            if (msg.media instanceof TLRPC.TL_messageMediaPhoto && msg.media.photo != null) {
                base.documentType = AyuConstants.DOCUMENT_TYPE_PHOTO;
                String path = saveFile(account, msg, saveFiles);
                base.mediaPath = path;
                base.hqThumbPath = path;
            } else if (msg.media instanceof TLRPC.TL_messageMediaDocument && msg.media.document != null) {
                TLRPC.Document doc = msg.media.document;
                base.mimeType = doc.mime_type;
                base.documentType = MessageObject.isStickerDocument(doc) ? AyuConstants.DOCUMENT_TYPE_STICKER : AyuConstants.DOCUMENT_TYPE_FILE;

                SerializedData data = new SerializedData(doc.getObjectSize());
                doc.serializeToStream(data);
                base.documentSerialized = data.toByteArray();
                data.cleanup();

                base.mediaPath = saveFile(account, msg, saveFiles);
            }
        } catch (Exception e) {
            Log.e("AyuGram", "mapMedia failed", e);
        }
    }

    private static String saveFile(int account, TLRPC.Message msg, boolean copyToSaved) {
        File src = null;
        if (!TextUtils.isEmpty(msg.attachPath)) {
            File f = new File(msg.attachPath);
            if (f.exists()) {
                src = f;
            }
        }
        if (src == null) {
            File f = FileLoader.getInstance(account).getPathToMessage(msg);
            if (f != null && f.exists()) {
                src = f;
            }
        }
        if (src == null) {
            return null;
        }
        if (!copyToSaved) {
            return src.getAbsolutePath();
        }

        try {
            File dir = AyuMessagesController.attachmentsPath;
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File dst = new File(dir, AyuUtils.getFilename(msg, src));
            if (AndroidUtilities.copyFile(src, dst)) {
                return dst.getAbsolutePath();
            }
        } catch (Exception e) {
            Log.e("AyuGram", "failed to save attachment", e);
        }
        return src.getAbsolutePath();
    }

    // ------------------------------------------------------------------ entity -> message

    public static void map(AyuMessageBase base, TLRPC.Message msg, int account) {
        msg.id = base.messageId;
        msg.dialog_id = base.dialogId;
        msg.date = base.date;
        msg.edit_date = base.editDate;
        msg.views = base.views;
        msg.grouped_id = base.groupedId;
        msg.message = base.text != null ? base.text : "";
        msg.flags = base.flags;
        msg.out = (base.flags & 2) != 0;

        msg.peer_id = makePeer(account, base.peerId != 0 ? base.peerId : base.dialogId);
        if (base.fromId != 0) {
            msg.from_id = makePeer(account, base.fromId);
            msg.flags |= TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
        }

        ArrayList<TLRPC.MessageEntity> entities = deserializeEntities(base.textEntities);
        if (!entities.isEmpty()) {
            msg.entities = entities;
            msg.flags |= 128;
        }

        if (base.fwdFromId != 0 || !TextUtils.isEmpty(base.fwdName)) {
            TLRPC.TL_messageFwdHeader fwd = new TLRPC.TL_messageFwdHeader();
            fwd.flags = base.fwdFlags;
            if (base.fwdFromId != 0) {
                fwd.from_id = makePeer(account, base.fwdFromId);
            }
            fwd.from_name = base.fwdName;
            fwd.date = base.fwdDate;
            fwd.post_author = base.fwdPostAuthor;
            msg.fwd_from = fwd;
            msg.flags |= TLRPC.MESSAGE_FLAG_FWD;
        }

        if (base.replyMessageId != 0 || base.replyTopId != 0) {
            TLRPC.TL_messageReplyHeader reply = new TLRPC.TL_messageReplyHeader();
            reply.flags = base.replyFlags;
            reply.reply_to_msg_id = base.replyMessageId;
            reply.reply_to_top_id = base.replyTopId;
            reply.forum_topic = base.replyForumTopic;
            if (base.replyPeerId != 0) {
                reply.reply_to_peer_id = makePeer(account, base.replyPeerId);
            }
            msg.reply_to = reply;
            msg.flags |= TLRPC.MESSAGE_FLAG_REPLY;
        }
    }

    public static void mapMedia(AyuMessageBase base, TLRPC.Message msg) {
        if (base.documentType == AyuConstants.DOCUMENT_TYPE_NONE && TextUtils.isEmpty(base.mediaPath)) {
            msg.media = new TLRPC.TL_messageMediaEmpty();
            return;
        }

        try {
            if (base.documentType == AyuConstants.DOCUMENT_TYPE_PHOTO) {
                File file = new File(base.mediaPath != null ? base.mediaPath : "");
                if (!file.exists()) {
                    msg.media = new TLRPC.TL_messageMediaEmpty();
                    return;
                }

                Pair<Integer, Integer> size = AyuUtils.extractImageSizeFromName(file.getName());
                if (size == null) {
                    size = AyuUtils.extractImageSizeFromFile(file.getAbsolutePath());
                }
                int w = size != null && size.first != null && size.first > 0 ? size.first : 800;
                int h = size != null && size.second != null && size.second > 0 ? size.second : 800;

                TLRPC.TL_photoSize photoSize = new TLRPC.TL_photoSize();
                photoSize.type = "x";
                photoSize.w = w;
                photoSize.h = h;
                photoSize.size = (int) file.length();
                photoSize.location = new TLRPC.TL_fileLocationToBeDeprecated();
                photoSize.location.volume_id = -Math.abs(base.messageId) - 1;
                photoSize.location.local_id = -Math.abs(base.messageId) - 1;

                TLRPC.TL_photo photo = new TLRPC.TL_photo();
                photo.date = base.date;
                photo.sizes = new ArrayList<>();
                photo.sizes.add(photoSize);
                photo.file_reference = new byte[0];

                TLRPC.TL_messageMediaPhoto media = new TLRPC.TL_messageMediaPhoto();
                media.photo = photo;
                media.flags |= 1;
                msg.media = media;
                msg.attachPath = file.getAbsolutePath();
                msg.flags |= 512;
            } else if (base.documentSerialized != null) {
                SerializedData data = new SerializedData(base.documentSerialized);
                TLRPC.Document doc = TLRPC.Document.TLdeserialize(data, data.readInt32(false), false);
                data.cleanup();
                if (doc != null) {
                    TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument();
                    media.document = doc;
                    media.flags |= 1;
                    msg.media = media;
                    if (!TextUtils.isEmpty(base.mediaPath) && new File(base.mediaPath).exists()) {
                        msg.attachPath = base.mediaPath;
                    }
                    msg.flags |= 512;
                }
            }
        } catch (Exception e) {
            Log.e("AyuGram", "mapMedia (restore) failed", e);
        }
    }

    // ------------------------------------------------------------------ helpers

    public static TLRPC.Peer makePeer(int account, long id) {
        if (id > 0) {
            TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
            peer.user_id = id;
            return peer;
        }
        long chatId = -id;
        TLRPC.Chat chat = MessagesController.getInstance(account).getChat(chatId);
        if (ChatObject.isChannel(chat)) {
            TLRPC.TL_peerChannel peer = new TLRPC.TL_peerChannel();
            peer.channel_id = chatId;
            return peer;
        }
        TLRPC.TL_peerChat peer = new TLRPC.TL_peerChat();
        peer.chat_id = chatId;
        return peer;
    }

    private static byte[] serializeEntities(ArrayList<TLRPC.MessageEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return null;
        }
        try {
            SerializedData data = new SerializedData();
            data.writeInt32(entities.size());
            for (int i = 0; i < entities.size(); i++) {
                entities.get(i).serializeToStream(data);
            }
            byte[] result = data.toByteArray();
            data.cleanup();
            return result;
        } catch (Exception e) {
            Log.e("AyuGram", "serializeEntities failed", e);
            return null;
        }
    }

    private static ArrayList<TLRPC.MessageEntity> deserializeEntities(byte[] bytes) {
        ArrayList<TLRPC.MessageEntity> result = new ArrayList<>();
        if (bytes == null) {
            return result;
        }
        try {
            SerializedData data = new SerializedData(bytes);
            int count = data.readInt32(false);
            for (int i = 0; i < count; i++) {
                TLRPC.MessageEntity entity = TLRPC.MessageEntity.TLdeserialize(data, data.readInt32(false), false);
                if (entity != null) {
                    result.add(entity);
                }
            }
            data.cleanup();
        } catch (Exception e) {
            Log.e("AyuGram", "deserializeEntities failed", e);
        }
        return result;
    }
}
