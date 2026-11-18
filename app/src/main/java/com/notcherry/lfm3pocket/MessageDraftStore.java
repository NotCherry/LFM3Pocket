package com.notcherry.lfm3pocket;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Immutable in-memory drafts for voice message actions (not persisted). */
public final class MessageDraftStore {
    public static final class Draft {
        public final String id;
        public final String channel; // "sms" or "share"
        public final String to;
        public final String body;
        public final String sharePackage; // non-null for share

        public Draft(String id, String channel, String to, String body, String sharePackage) {
            this.id = id;
            this.channel = channel;
            this.to = to;
            this.body = body;
            this.sharePackage = sharePackage;
        }
    }

    private final Map<String, Draft> drafts = Collections.synchronizedMap(new LinkedHashMap<String, Draft>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Draft> eldest) {
            return size() > 64;
        }
    });

    public Draft create(String channel, String to, String body, String sharePackage) {
        String id = UUID.randomUUID().toString();
        Draft draft = new Draft(id, channel, to == null ? "" : to, body, sharePackage);
        drafts.put(id, draft);
        return draft;
    }

    public Draft get(String id) {
        if (id == null) return null;
        return drafts.get(id);
    }
}
