package com.notcherry.lfm3pocket;

import java.util.List;

/** Offline read-only contacts lookup gateway backed by Android ContactsContract in production. */
public interface ContactsGateway {
    final class ContactMatch {
        public final String displayName;
        public final String phone;
        public final String email;

        public ContactMatch(String displayName, String phone, String email) {
            this.displayName = displayName == null ? "" : displayName.trim();
            this.phone = phone == null ? "" : phone.trim();
            this.email = email == null ? "" : email.trim();
        }
    }

    List<ContactMatch> search(String query, int limit);
}
