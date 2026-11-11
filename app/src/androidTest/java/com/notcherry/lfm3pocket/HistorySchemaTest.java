package com.notcherry.lfm3pocket;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import androidx.room.Room;
import androidx.room.testing.MigrationTestHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import java.util.*;
import static org.junit.Assert.*;

public final class HistorySchemaTest {
    private static final String DB="history-migration-test";
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    @Rule public final MigrationTestHelper migrations=new MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),HistoryDatabase.class.getCanonicalName(),
            new FrameworkSQLiteOpenHelperFactory());
    private HistoryDatabase db;
    @After public void close(){if(db!=null)db.close();context.deleteDatabase(DB);context.deleteDatabase("history-reopen-test");}
    private HistoryMessage message(String id,long sequence,String transcript){
        return new HistoryMessage(id,"conversation",sequence,"user","Wiadomość "+sequence,transcript,100-sequence);
    }
    private HistoryToolOutcome outcome(String id,String message,String request){
        return new HistoryToolOutcome(id,message,request,"set_alarm","{\"hour\":7,\"minute\":30}",false,"DECLINED","User declined",200);
    }
    @Test public void freshDatabaseOrdersMessagesAndEnforcesRelationsWithoutReplacement(){
        db=Room.inMemoryDatabaseBuilder(context,HistoryDatabase.class).build();HistoryDao dao=db.history();
        dao.insertConversation(new HistoryConversation("conversation","Test",10,20));
        dao.insertMessage(message("third",2,null));dao.insertMessage(message("first",0,"niebieski kot"));dao.insertMessage(message("second",1,null));
        List<HistoryMessage> messages=dao.messages("conversation");
        assertEquals(Arrays.asList("first","second","third"),Arrays.asList(messages.get(0).getId(),messages.get(1).getId(),messages.get(2).getId()));
        assertEquals("niebieski kot",messages.get(0).getSpeechTranscript());
        assertThrows(SQLiteConstraintException.class,()->dao.insertMessage(message("duplicate-position",0,null)));
        assertThrows(SQLiteConstraintException.class,()->dao.insertMessage(new HistoryMessage("orphan","missing",0,"assistant","x",null,100)));
        dao.insertToolOutcome(outcome("tool","second","request"));
        assertThrows(SQLiteConstraintException.class,()->dao.insertToolOutcome(outcome("replayed","third","request")));
        assertFalse(dao.toolOutcomes("conversation").get(0).getSucceeded());
        assertEquals(1,dao.deleteConversation("conversation"));assertTrue(dao.messages("conversation").isEmpty());
        try(Cursor count=db.getOpenHelper().getReadableDatabase().query("SELECT COUNT(*) FROM tool_outcomes")){assertTrue(count.moveToFirst());assertEquals(0,count.getInt(0));}
    }
    @Test public void migrationRetainsOrderedTextAndSpeechThenAcceptsStructuredOutcomes()throws Exception{
        SupportSQLiteDatabase old=migrations.createDatabase(DB,1);
        old.execSQL("INSERT INTO conversations VALUES ('conversation','Zażółć 🐈',10,20)");
        old.execSQL("INSERT INTO messages (id,conversationId,sequence,role,text,speechTranscript,createdAt) VALUES ('second','conversation',1,'assistant','Odpowiedź',NULL,5)");
        old.execSQL("INSERT INTO messages (id,conversationId,sequence,role,text,speechTranscript,createdAt) VALUES ('first','conversation',0,'user','Pytanie','niebieski kot',50)");old.close();
        migrations.runMigrationsAndValidate(DB,2,true,HistoryDatabase.MIGRATION_1_2).close();
        db=Room.databaseBuilder(context,HistoryDatabase.class,DB).addMigrations(HistoryDatabase.MIGRATION_1_2).build();
        assertEquals("Zażółć 🐈",db.history().conversations().get(0).getTitle());
        List<HistoryMessage> messages=db.history().messages("conversation");assertEquals("first",messages.get(0).getId());
        assertEquals("niebieski kot",messages.get(0).getSpeechTranscript());assertEquals("second",messages.get(1).getId());
        db.history().insertToolOutcome(outcome("tool","second","request"));assertEquals("DECLINED",db.history().toolOutcomes("conversation").get(0).getStatus());
    }
    @Test public void privateDatabaseReopenRetainsRecordsAndDeterministicConversationOrder(){
        String name="history-reopen-test";
        db=Room.databaseBuilder(context,HistoryDatabase.class,name).addMigrations(HistoryDatabase.MIGRATION_1_2).build();
        db.history().insertConversation(new HistoryConversation("b","Second",0,20));
        db.history().insertConversation(new HistoryConversation("a","First",0,20));db.close();
        db=Room.databaseBuilder(context,HistoryDatabase.class,name).addMigrations(HistoryDatabase.MIGRATION_1_2).build();
        assertEquals("a",db.history().conversations().get(0).getId());assertEquals("b",db.history().conversations().get(1).getId());
        assertTrue(context.getDatabasePath(name).getAbsolutePath().startsWith(context.getDataDir().getAbsolutePath()+"/"));
    }
}
