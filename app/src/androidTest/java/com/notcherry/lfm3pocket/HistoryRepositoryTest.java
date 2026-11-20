package com.notcherry.lfm3pocket;

import android.content.Context;
import androidx.room.Room;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class HistoryRepositoryTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private HistoryDatabase db;
    @After public void close(){if(db!=null)db.close();}

    @Test public void completeTurnAtomicallyStoresConversationMessagesSpeechAndToolResult(){
        db=Room.inMemoryDatabaseBuilder(context,HistoryDatabase.class).build();
        HistoryRepository repository=new HistoryRepository(db);
        HistoryRepository.ToolExecution action=new HistoryRepository.ToolExecution(
                "request-1","set_alarm","{\"hour\":7,\"minute\":30}",false,"DECLINED","User declined",130);
        HistoryRepository.Turn turn=new HistoryRepository.Turn(
                "turn-1","conversation-1","Morning","Set an alarm","I can prepare that.",
                "set an alarm",HistoryRepository.Status.COMPLETED,100,action,"AVAILABLE");

        assertTrue(repository.completeTurn(turn));
        List<HistoryMessage> messages=db.history().messages("conversation-1");
        assertEquals(Arrays.asList("user","assistant"),Arrays.asList(messages.get(0).getRole(),messages.get(1).getRole()));
        assertEquals(Arrays.asList("Set an alarm","I can prepare that."),Arrays.asList(messages.get(0).getText(),messages.get(1).getText()));
        assertEquals("set an alarm",messages.get(0).getSpeechTranscript());
        assertEquals("AVAILABLE",messages.get(0).getSpeechTranscriptStatus());
        assertEquals(1,db.history().conversations().size());
        assertEquals(1,db.history().toolOutcomes("conversation-1").size());
        HistoryToolOutcome saved=db.history().toolOutcomes("conversation-1").get(0);
        assertEquals(messages.get(1).getId(),saved.getMessageId());
        assertEquals("DECLINED",saved.getStatus());
        assertFalse(saved.getSucceeded());
    }

    @Test public void unavailableSpeechTranscriptIsPersistedSeparatelyFromAssistantText(){
        db=Room.inMemoryDatabaseBuilder(context,HistoryDatabase.class).build();
        HistoryRepository repository=new HistoryRepository(db);
        HistoryRepository.Turn turn=new HistoryRepository.Turn(
                "turn-asr-unavailable","conversation-asr","Voice","Summarize this recording","Audio answer",
                null,HistoryRepository.Status.COMPLETED,100,null,"UNAVAILABLE");

        assertTrue(repository.completeTurn(turn));
        List<HistoryMessage> messages=db.history().messages("conversation-asr");
        assertNull(messages.get(0).getSpeechTranscript());
        assertEquals("UNAVAILABLE",messages.get(0).getSpeechTranscriptStatus());
        assertEquals("Audio answer",messages.get(1).getText());
        assertNull(messages.get(1).getSpeechTranscriptStatus());
    }

    @Test public void cancelledAndFailedTurnsKeepTerminalStateAndRepeatedCommitDoesNotDuplicate(){
        db=Room.inMemoryDatabaseBuilder(context,HistoryDatabase.class).build();
        HistoryRepository repository=new HistoryRepository(db);
        HistoryRepository.Turn cancelled=new HistoryRepository.Turn(
                "turn-cancelled","conversation-1","Conversation","Question","partial answer",
                null,HistoryRepository.Status.CANCELLED,200,null);
        HistoryRepository.Turn failed=new HistoryRepository.Turn(
                "turn-failed","conversation-1","Conversation","Next question","",
                null,HistoryRepository.Status.FAILED,300,null);

        assertTrue(repository.completeTurn(cancelled));
        assertFalse(repository.completeTurn(cancelled));
        assertTrue(repository.completeTurn(failed));
        List<HistoryMessage> messages=db.history().messages("conversation-1");
        assertEquals(4,messages.size());
        assertEquals("CANCELLED",messages.get(1).getTurnStatus());
        assertEquals("FAILED",messages.get(3).getTurnStatus());
        assertEquals(1,db.history().conversations().size());
    }

    @Test public void toolOutcomeConflictRollsBackWholeTurnAndAllowsCleanRetry(){
        db=Room.inMemoryDatabaseBuilder(context,HistoryDatabase.class).build();
        HistoryRepository repository=new HistoryRepository(db);
        HistoryRepository.ToolExecution firstAction=new HistoryRepository.ToolExecution(
                "request-unique","set_timer","{\"seconds\":30}",false,"DECLINED","Declined",100);
        HistoryRepository.Turn first=new HistoryRepository.Turn("turn-first","conversation-1","Test",
                "First","First answer",null,HistoryRepository.Status.COMPLETED,100,firstAction);
        assertTrue(repository.completeTurn(first));

        HistoryRepository.ToolExecution duplicateAction=new HistoryRepository.ToolExecution(
                "request-unique","set_alarm","{\"hour\":7,\"minute\":30}",false,"DECLINED","Declined",200);
        HistoryRepository.Turn second=new HistoryRepository.Turn("turn-second","conversation-1","Test",
                "Second","Second answer",null,HistoryRepository.Status.COMPLETED,200,duplicateAction);
        assertThrows(android.database.sqlite.SQLiteConstraintException.class,()->repository.completeTurn(second));
        assertEquals("A failed outcome insert must not leave half a turn",2,db.history().messages("conversation-1").size());
        assertEquals("A failed transaction must roll back its conversation timestamp",100,
                db.history().conversations().get(0).getUpdatedAt());
        assertEquals(1,db.history().toolOutcomes("conversation-1").size());

        HistoryRepository.ToolExecution retryAction=new HistoryRepository.ToolExecution(
                "request-retry","set_alarm","{\"hour\":7,\"minute\":30}",false,"DECLINED","Declined",200);
        HistoryRepository.Turn retry=new HistoryRepository.Turn("turn-second","conversation-1","Test",
                "Second","Second answer",null,HistoryRepository.Status.COMPLETED,200,retryAction);
        assertTrue(repository.completeTurn(retry));
        assertEquals(4,db.history().messages("conversation-1").size());
        assertEquals(2,db.history().toolOutcomes("conversation-1").size());
    }

    @Test public void standaloneToolOutcomeIsRecordedOnceInItsConversation(){
        db=Room.inMemoryDatabaseBuilder(context,HistoryDatabase.class).build();
        HistoryRepository repository=new HistoryRepository(db);
        HistoryRepository.ToolExecution action=new HistoryRepository.ToolExecution(
                "proposal-1","set_timer","{\"seconds\":30}",true,"PERFORMED","Timer created",100);

        assertTrue(repository.appendToolOutcome("conversation-1","Actions",action));
        assertFalse(repository.appendToolOutcome("conversation-1","Actions",action));
        List<HistoryMessage> messages=db.history().messages("conversation-1");
        assertEquals(1,messages.size());
        assertEquals("tool",messages.get(0).getRole());
        assertEquals("Timer created",messages.get(0).getText());
        assertEquals(1,db.history().toolOutcomes("conversation-1").size());
    }

    @Test public void beginTurnPersistsUserBeforeCompletionAndSurvivesInterruptMarking(){
        db=Room.inMemoryDatabaseBuilder(context,HistoryDatabase.class).build();
        HistoryRepository repository=new HistoryRepository(db);
        HistoryRepository.TurnStart start=new HistoryRepository.TurnStart(
                "turn-live","conversation-live","Vision","What is in this photo?",100,"/files/history-media/photo.img");
        assertTrue(repository.beginTurn(start));
        List<HistoryMessage> midway=db.history().messages("conversation-live");
        assertEquals(2,midway.size());
        assertEquals("user",midway.get(0).getRole());
        assertEquals("/files/history-media/photo.img",midway.get(0).getMediaRef());
        assertEquals("GENERATING",midway.get(1).getTurnStatus());
        assertEquals("",midway.get(1).getText());

        assertEquals(1,repository.markInterruptedGenerations(150));
        List<HistoryMessage> interrupted=db.history().messages("conversation-live");
        assertEquals("INTERRUPTED",interrupted.get(1).getTurnStatus());
        assertEquals(HistoryRepository.INTERRUPTED_PLACEHOLDER,interrupted.get(1).getText());
        assertEquals("user",interrupted.get(0).getRole());

        HistoryRepository.Turn finished=new HistoryRepository.Turn(
                "turn-live","conversation-live","Vision","What is in this photo?","A cat on a sofa",
                null,HistoryRepository.Status.COMPLETED,200,null,null,"/files/history-media/photo.img");
        assertTrue(repository.completeTurn(finished));
        List<HistoryMessage> done=db.history().messages("conversation-live");
        assertEquals(2,done.size());
        assertEquals("COMPLETED",done.get(1).getTurnStatus());
        assertEquals("A cat on a sofa",done.get(1).getText());
    }

    @Test public void recoveryDoesNotInterruptTurnBegunAfterCutoff(){
        db=Room.inMemoryDatabaseBuilder(context,HistoryDatabase.class).build();
        HistoryRepository repository=new HistoryRepository(db);
        HistoryRepository.TurnStart start=new HistoryRepository.TurnStart(
                "turn-new","conversation-new","Chat","Hello",2000,"");
        assertTrue(repository.beginTurn(start));
        // Recovery cutoff is at/before begin time must not mark this live turn.
        assertEquals(0,repository.markInterruptedGenerations(2000));
        List<HistoryMessage> after=db.history().messages("conversation-new");
        assertEquals("GENERATING",after.get(1).getTurnStatus());
        HistoryRepository.Turn finished=new HistoryRepository.Turn(
                "turn-new","conversation-new","Chat","Hello","Hi there",
                null,HistoryRepository.Status.COMPLETED,2100,null,null,null);
        assertTrue(repository.completeTurn(finished));
        List<HistoryMessage> done=db.history().messages("conversation-new");
        assertEquals("COMPLETED",done.get(1).getTurnStatus());
        assertEquals("Hi there",done.get(1).getText());
    }

    @Test public void conversationSummaryPersistsWithoutDroppingMessages() {
        db=Room.inMemoryDatabaseBuilder(context,HistoryDatabase.class).build();
        HistoryRepository repository=new HistoryRepository(db);
        repository.beginTurn(new HistoryRepository.TurnStart("turn-1","conversation-1","Voice","First",100,null));
        repository.completeTurn(new HistoryRepository.Turn("turn-1","conversation-1","Voice","First","Answer 1",null,
                HistoryRepository.Status.COMPLETED,120,null,null,null));
        repository.beginTurn(new HistoryRepository.TurnStart("turn-2","conversation-1","Voice","Second",130,null));
        repository.completeTurn(new HistoryRepository.Turn("turn-2","conversation-1","Voice","Second","Answer 2",null,
                HistoryRepository.Status.COMPLETED,140,null,null,null));
        assertTrue(repository.updateConversationSummary("conversation-1","Voice","Older turns summarized",2L,150L));
        HistoryRepository.ConversationSummary summary=repository.conversationSummary("conversation-1");
        assertNotNull(summary);
        assertEquals("Older turns summarized",summary.getSummary());
        assertEquals(2L,summary.getSummarizedMessageCount());
        assertEquals("Full transcript must remain in Room",4,repository.messages("conversation-1").size());
    }
}
