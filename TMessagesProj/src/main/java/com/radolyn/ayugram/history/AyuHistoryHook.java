package com.radolyn.ayugram.history;

import androidx.core.util.Pair;
import android.util.SparseArray;

import com.google.android.exoplayer2.util.Log;
import com.radolyn.ayugram.database.entities.DeletedMessageFull;
import com.radolyn.ayugram.messages.AyuMessagesController;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JojoGram: open replacement for the closed-source history hook.
 * Puts saved (deleted) messages back into the list that the chat is about to show.
 */
public class AyuHistoryHook {

    /** first = smaller id, second = bigger id */
    public static Pair<Integer, Integer> getMinAndMaxIds(ArrayList<MessageObject> messArr) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < messArr.size(); i++) {
            int id = messArr.get(i).getId();
            min = Math.min(min, id);
            max = Math.max(max, id);
        }
        return new Pair<>(min, max);
    }

    public static void doHook(int account, ArrayList<MessageObject> messArr, SparseArray<MessageObject>[] messagesDict,
                              int startId, int endId, long dialogId, int limit, long topicId, boolean secret) {
        try {
            long userId = UserConfig.getInstance(account).getClientUserId();
            int lo = Math.min(startId, endId);
            int hi = Math.max(startId, endId);

            List<DeletedMessageFull> saved = AyuMessagesController.getInstance().getMessages(userId, dialogId, topicId, lo, hi, limit);
            if (saved == null || saved.isEmpty()) {
                return;
            }

            boolean added = false;
            for (int i = 0; i < saved.size(); i++) {
                DeletedMessageFull full = saved.get(i);
                if (full == null || full.message == null) {
                    continue;
                }
                int id = full.message.messageId;
                if (messagesDict[0].indexOfKey(id) >= 0 || containsId(messArr, id)) {
                    continue;
                }

                TLRPC.TL_message msg = new TLRPC.TL_message();
                AyuMessageUtils.map(full.message, msg, account);
                AyuMessageUtils.mapMedia(full.message, msg);
                msg.ayuDeleted = true;

                MessageObject obj = new MessageObject(account, msg, true, true);
                messArr.add(obj);
                added = true;
            }

            if (added) {
                final boolean isSecret = secret;
                Collections.sort(messArr, (a, b) -> {
                    int c = Integer.compare(b.messageOwner.date, a.messageOwner.date);
                    if (c == 0) {
                        c = isSecret ? Integer.compare(a.getId(), b.getId()) : Integer.compare(b.getId(), a.getId());
                    }
                    return c;
                });
            }
        } catch (Exception e) {
            Log.e("AyuGram", "history hook failed", e);
        }
    }

    private static boolean containsId(ArrayList<MessageObject> list, int id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId() == id) {
                return true;
            }
        }
        return false;
    }
}
