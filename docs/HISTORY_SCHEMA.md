# Private conversation history schema

H01 defines the Room database contract for the later persistence and history UI work. It stores conversation and message text, optional speech transcripts, and structured tool outcomes in the app's private database directory. The schema does not store media paths, model prompts, or derived AI memory.

The HistoryDatabase is version 2 and exports its schema under app/schemas. Version 1 is a development schema baseline created for this implementation before tool outcomes were added; it is not a schema shipped to existing users. Migration 1→2 adds tool outcomes with a foreign key to their parent message and a unique request ID. The migration test creates version 1 rows, preserves their text, sequence, and speech transcript, migrates them, then inserts and reloads a structured outcome.

Messages are ordered by a per-conversation sequence number protected by a unique index. Conversation ordering is deterministic for equal timestamps. Tool outcomes cascade with their message, and conversation deletion cascades through both tables. Inserts use Room's abort behavior so duplicate IDs, duplicate message positions, replayed request IDs, and orphan rows fail visibly instead of replacing history.

H01 supplies the schema, DAO, private database builder, migration, and emulator coverage. The app does not yet read or write chat sessions through this database. H02 owns atomic conversation persistence; H03–H05 own history navigation, deletion, and end-to-end privacy behavior. No production database fallback or destructive migration is configured.
