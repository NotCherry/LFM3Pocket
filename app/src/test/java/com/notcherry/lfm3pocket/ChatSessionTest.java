package com.notcherry.lfm3pocket;

import org.junit.Test;
import java.util.*;
import java.util.concurrent.Executor;
import static org.junit.Assert.*;

public class ChatSessionTest {
    static final class Queue implements Executor,ChatSession.Dispatcher {
        final List<Runnable> tasks=new ArrayList<>();
        public void execute(Runnable task){tasks.add(task);}
        public void post(Runnable task,long delay){tasks.add(task);}
        void drain(){while(!tasks.isEmpty())tasks.remove(0).run();}
    }
    static class Engine implements InferenceEngine {
        int calls,answerCalls;String prompt,asrError,asrTranscript="The secret animal is a blue cat.";boolean stopped;final List<String> prompts=new ArrayList<>();
        final List<List<String>> mediaByCall=new ArrayList<>();
        /** When non-null, assistant generate emits this instead of the default Answer N token. */
        String assistantToken;
        public long loadModel(String m,String p){return 1;}
        public void unloadModel(long h){}
        public String generate(long h,String p,String media,int max,TokenSink sink){
            return generateWithMedia(h,p,media==null?new String[0]:new String[]{media},max,sink);
        }
        @Override public String generateWithMedia(long h,String p,String[] media,int max,TokenSink sink){
            calls++;prompt=p;prompts.add(p);
            mediaByCall.add(new ArrayList<>(Arrays.asList(media)));
            if(!stopped) {
                if(p.contains("Perform ASR.")) { if(asrTranscript!=null)sink.onToken(asrTranscript); }
                else if(assistantToken!=null) sink.onToken(assistantToken);
                else sink.onToken("Answer "+(++answerCalls));
            }
            return p.contains("Perform ASR.")?asrError:null;
        }
        long[] nativeStats;
        int consumeStatsCalls;
        public void stop(){stopped=true;}
        public void resetStop(){stopped=false;}
        @Override public long[] consumeGenerationStats(long handle) {
            consumeStatsCalls++;
            long[] snapshot=nativeStats;
            nativeStats=null;
            return snapshot;
        }
    }
    static final class Harness {
        final Queue worker=new Queue(),ui=new Queue();final Engine engine=new Engine();
        String text="",status="";boolean generating;
        ChatSnapshot snapshot=new ChatSnapshot(Collections.emptyList(),"",false);
        final ChatSession session=new ChatSession(engine,worker,ui,()->1,
                (endpoint,question)->{throw new AssertionError("Disabled search must not run");},
                turn->{},path->{},
                (t,s,g)->{text=t;status=s;generating=g;},
                snap->{snapshot=snap;});
        void complete(){worker.drain();ui.drain();}
        boolean send(String text){return session.submit(text,"","",false,"",32);}
    }

    @Test public void structuredSnapshotMatchesFlatTranscriptParity(){
        Harness h=new Harness();
        assertTrue(h.send("First question"));h.complete();
        assertEquals(FlatTranscript.format(h.snapshot),h.text);
        assertEquals(2,h.snapshot.turns.size());
        assertEquals("user",h.snapshot.turns.get(0).role);
        assertEquals("First question",h.snapshot.turns.get(0).text);
        assertFalse(h.snapshot.turns.get(0).streaming);
        assertEquals("assistant",h.snapshot.turns.get(1).role);
        assertEquals("Answer 1",h.snapshot.turns.get(1).text);
        assertFalse(h.snapshot.turns.get(1).streaming);
        assertFalse(h.snapshot.generating);
        assertEquals(h.session.snapshot(),h.snapshot);
    }

    @Test public void thinkingTagsAreStrippedFromAnswerAndFlatTranscript(){
        Queue worker=new Queue(),ui=new Queue();
        Engine engine=new Engine();
        engine.assistantToken="<think>hidden plan</think>Visible answer";
        String[] flat={""};ChatSnapshot[] snap={null};
        ChatSession session=new ChatSession(engine,worker,ui,()->1,
                (endpoint,question)->Collections.emptyList(),turn->{},path->{},
                (text,status,running)->flat[0]=text,
                s->snap[0]=s);
        assertTrue(session.submit("Ask","","",false,"",32));
        worker.drain();ui.drain();
        assertEquals("Visible answer",snap[0].turns.get(1).text);
        assertEquals("hidden plan",snap[0].turns.get(1).reasoning);
        assertFalse(flat[0].contains("<think>"));
        assertFalse(flat[0].contains("hidden plan"));
        assertTrue(flat[0].contains("Visible answer"));
        assertEquals(FlatTranscript.format(snap[0]),flat[0]);
    }

    @Test public void streamingSnapshotExposesCleanedPartialAndCursorInFlat(){
        Queue worker=new Queue(),ui=new Queue();
        Engine engine=new Engine();
        engine.assistantToken="<think>plan</think>Partial";
        String[] flat={""};
        ChatSession session=new ChatSession(engine,worker,ui,()->1,
                (endpoint,question)->Collections.emptyList(),turn->{},path->{},
                (text,status,running)->flat[0]=text,
                snap->{});
        assertTrue(session.submit("Stream me","","",false,"",32));
        worker.drain();
        // Offline path posts progress emit, then token drain emit, then finish.
        assertTrue(ui.tasks.size()>=3);
        ui.tasks.remove(0).run(); // progress
        ui.tasks.remove(0).run(); // stream drain
        ChatSnapshot mid=session.snapshot();
        assertTrue(mid.generating);
        ChatSnapshot.Turn stream=mid.turns.get(mid.turns.size()-1);
        assertTrue(stream.streaming);
        assertEquals("Partial",stream.text);
        assertEquals("plan",stream.reasoning);
        assertTrue(flat[0].contains("LFM3\nPartial"));
        assertTrue(flat[0].contains("\u258C")); // ▌ streaming cursor
        assertFalse(flat[0].contains("<think>"));
        assertEquals(FlatTranscript.format(mid),flat[0]);
        ui.drain();
        assertFalse(session.snapshot().generating);
        assertFalse(session.snapshot().turns.get(1).streaming);
        assertEquals("Partial",session.snapshot().turns.get(1).text);
    }
    @Test public void twoTurnsRetainActiveContextAndAppendEachResponseExactlyOnce(){
        Harness h=new Harness();assertTrue(h.send("First question"));h.complete();
        assertTrue(h.send("Second question"));h.complete();
        assertTrue(h.engine.prompt.contains("First question"));assertTrue(h.engine.prompt.contains("Answer 1"));
        assertTrue(h.engine.prompt.contains("Second question"));
        assertEquals(1,h.text.split("Answer 1",-1).length-1);
        assertEquals(1,h.text.split("Answer 2",-1).length-1);assertFalse(h.generating);
    }
    @Test public void clearingBeforeUiDeliveryDiscardsEveryStaleCallback(){
        Harness h=new Harness();h.send("Discard me");h.worker.drain();
        h.session.clear();h.ui.drain();assertEquals("No conversation yet.\n",h.text);
        assertTrue(h.send("Keep me"));h.complete();
        assertFalse(h.text.contains("Discard me"));assertTrue(h.text.contains("Keep me"));
        assertFalse(h.engine.prompt.contains("Discard me"));
    }
    @Test public void stopBeforeQueuedGenerationSkipsDecodeAndAllowsRetry(){
        Harness h=new Harness();h.send("Cancel before start");h.session.stop();h.complete();
        assertEquals(0,h.engine.calls);assertFalse(h.generating);
        assertTrue(h.send("Retry"));h.complete();assertEquals(1,h.engine.calls);
        assertTrue(h.text.contains("Answer 1"));
    }
    @Test public void closedSessionRejectsNewTurnsAndQueuedWork(){
        Harness h=new Harness();h.send("Old activity");h.session.close();h.complete();
        assertFalse(h.send("New activity"));assertEquals(0,h.engine.calls);
    }
    @Test public void cancellingDuringExplicitSearchPreventsDecode(){
        Queue worker=new Queue(),ui=new Queue();Engine engine=new Engine();
        ChatSession[] owner=new ChatSession[1];
        owner[0]=new ChatSession(engine,worker,ui,()->1,(endpoint,question)->{
            owner[0].stop();return Collections.emptyList();
        },(text,status,running)->{});
        assertTrue(owner[0].submit("Question","","",true,"https://search.example/search",32));
        worker.drain();ui.drain();assertEquals(0,engine.calls);assertFalse(owner[0].isGenerating());
    }
    @Test public void webModeShowsSearchThenLocalGenerationAndRetainsModeOnCompletion(){
        Queue worker=new Queue(),ui=new Queue();Engine engine=new Engine();String[] status={""};
        ChatSession session=new ChatSession(engine,worker,ui,()->1,
                (endpoint,question)->Collections.emptyList(),
                (text,message,running)->status[0]=message);
        assertTrue(session.submit("Search this","","",true,
                "https://search.example/search",32));
        assertEquals("Searching web, then generating locally…",status[0]);
        worker.drain();
        ui.tasks.remove(0).run();
        assertEquals("Generating locally · web search unavailable…",status[0]);
        ui.drain();
        assertEquals("Response complete · web search + local model",status[0]);
    }
    @Test public void completedTurnIsPersistedOffUiBeforeCompletionIsReported(){
        Queue worker=new Queue(),ui=new Queue();Engine engine=new Engine();
        List<ChatSession.CompletedTurn> saved=new ArrayList<>();String[] status={""};
        ChatSession session=new ChatSession(engine,worker,ui,()->1,(endpoint,question)->Collections.emptyList(),
                saved::add,(text,message,running)->status[0]=message);
        assertTrue(session.submit("Persist this question","","",false,"",32));
        worker.drain();
        assertEquals(1,saved.size());
        assertEquals("Persist this question",saved.get(0).userText);
        assertEquals("Answer 1",saved.get(0).assistantText);
        assertEquals(ChatSession.TerminalState.COMPLETED,saved.get(0).status);
        assertEquals("Completion must wait until the repository write finishes",
                "Generating locally · offline…",status[0]);
        ui.drain();assertEquals("Response complete · local model · offline",status[0]);
    }

    @Test public void userTurnIsPersistedBeforeInferenceStarts(){
        Queue worker=new Queue(),ui=new Queue();Engine engine=new Engine();
        List<String> order=new ArrayList<>();
        List<ChatSession.StartedTurn> began=new ArrayList<>();
        List<ChatSession.CompletedTurn> saved=new ArrayList<>();
        ChatSession.Persistence persistence=new ChatSession.Persistence() {
            @Override public void begin(ChatSession.StartedTurn turn){
                began.add(turn);order.add("begin");
            }
            @Override public void save(ChatSession.CompletedTurn turn){saved.add(turn);order.add("save");}
        };
        Engine recording=new Engine() {
            @Override public String generateWithMedia(long h,String p,String[] media,int max,TokenSink sink){
                order.add("generate");
                return super.generateWithMedia(h,p,media,max,sink);
            }
        };
        ChatSession session=new ChatSession(recording,worker,ui,()->1,(endpoint,question)->Collections.emptyList(),
                persistence,(text,status,running)->{});
        assertTrue(session.submit("Save me first","","",false,"",32));
        assertTrue("Begin must not run on the submit/UI thread",began.isEmpty());
        assertEquals(0,recording.calls);
        worker.drain();ui.drain();
        assertEquals(1,began.size());
        assertEquals("Save me first",began.get(0).userText);
        assertEquals(Arrays.asList("begin","generate","save"),order);
        assertEquals(ChatSession.TerminalState.COMPLETED,saved.get(0).status);
    }

    @Test public void missingPhotoFailsAfterBeginWithoutNativeInference(){
        Queue worker=new Queue(),ui=new Queue();Engine engine=new Engine();
        List<ChatSession.StartedTurn> began=new ArrayList<>();
        List<ChatSession.CompletedTurn> saved=new ArrayList<>();
        ChatSession.Persistence persistence=new ChatSession.Persistence() {
            @Override public void begin(ChatSession.StartedTurn turn){began.add(turn);}
            @Override public void save(ChatSession.CompletedTurn turn){saved.add(turn);}
        };
        ChatSession session=new ChatSession(engine,worker,ui,()->1,(endpoint,question)->Collections.emptyList(),
                persistence,(text,status,running)->{});
        assertTrue(session.submit("Save me first","/missing/photo.img","photo",false,"",32));
        worker.drain();ui.drain();
        assertEquals(1,began.size());
        assertEquals("/missing/photo.img",began.get(0).mediaRef);
        assertEquals("Unsafe/missing images must not reach native inference",0,engine.calls);
        assertEquals(1,saved.size());
        assertEquals(ChatSession.TerminalState.FAILED,saved.get(0).status);
        assertTrue(session.snapshot().turns.get(1).text.contains("not a readable image")
                || session.snapshot().turns.get(1).text.contains("Could not"));
    }

    @Test public void restoreAfterProcessDeathMarksInterruptedReplyForRetry() {
        Queue worker=new Queue(),ui=new Queue();Engine engine=new Engine();
        List<ChatSession.StartedTurn> began=new ArrayList<>();
        ChatSession.Persistence persistence=new ChatSession.Persistence() {
            @Override public void begin(ChatSession.StartedTurn turn){began.add(turn);}
            @Override public void save(ChatSession.CompletedTurn turn){}
        };
        ChatSession session=new ChatSession(engine,worker,ui,()->1,(endpoint,question)->Collections.emptyList(),
                persistence,(text,status,running)->{});
        session.restore(Arrays.asList(
                new ChatSession.RestoredMessage("user","What color is the sky?",null,"COMPLETED",null),
                new ChatSession.RestoredMessage("assistant","Interrupted — tap Retry to send again.","turn-1","INTERRUPTED",null)));
        ChatSnapshot snapshot=session.snapshot();
        assertEquals(2,snapshot.turns.size());
        assertTrue(snapshot.turns.get(1).interrupted);
        assertEquals("turn-1",snapshot.turns.get(1).turnId);
        assertTrue(session.snapshot().status.contains("interrupted") || session.snapshot().status.contains("Retry"));
        assertTrue(session.retryInterrupted("turn-1",64));
        assertTrue(began.isEmpty());
        worker.drain();ui.drain();
        assertEquals(1,began.size());
        assertEquals("What color is the sky?",began.get(0).userText);
        assertEquals(1,engine.calls);
        assertTrue(engine.mediaByCall.get(0).isEmpty());
        assertFalse(session.snapshot().turns.get(session.snapshot().turns.size()-1).interrupted);
    }
    @Test public void temporaryMediaIsRemovedAfterInferenceFinishes() throws Exception {
        Queue worker=new Queue(),ui=new Queue();Engine engine=new Engine();List<String> deleted=new ArrayList<>();
        java.io.File photo=java.io.File.createTempFile("photo-1",".img");
        photo.deleteOnExit();
        java.nio.file.Files.write(photo.toPath(),new byte[]{1,2,3,4});
        ChatSession session=new ChatSession(engine,worker,ui,()->1,(endpoint,question)->Collections.emptyList(),
                turn->{},deleted::add,(text,message,running)->{});
        assertTrue(session.submit("Describe this",photo.getAbsolutePath(),"photo",false,"",32));
        assertTrue(deleted.isEmpty());
        worker.drain();
        assertTrue("The current conversation retains its image for follow-up",deleted.isEmpty());
        assertEquals(1,engine.calls);
        session.clear();
        assertEquals(Collections.singletonList(photo.getAbsolutePath()),deleted);
    }

    @Test public void recordedVoiceStoresASRSeparatelyFromAssistantReply(){
        Queue worker=new Queue(),ui=new Queue();Engine engine=new Engine();
        List<ChatSession.CompletedTurn> saved=new ArrayList<>();
        ChatSession session=new ChatSession(engine,worker,ui,()->1,(endpoint,question)->Collections.emptyList(),
                saved::add,(text,message,running)->{});
        assertTrue(session.submit("What animal was mentioned?","/private/cache/voice.wav",
                "voice recording",false,"",64));

        worker.drain();ui.drain();

        assertEquals("A recorded voice turn must run a distinct ASR pass",2,engine.calls);
        assertTrue(engine.prompts.get(0).contains("Perform ASR."));
        assertTrue(engine.prompts.get(1).contains("What animal was mentioned?"));
        assertEquals("The secret animal is a blue cat.",saved.get(0).speechTranscript);
        assertEquals("Answer 1",saved.get(0).assistantText);
        assertNotEquals("ASR text must never be derived from assistant output",
                saved.get(0).speechTranscript,saved.get(0).assistantText);
        assertEquals("AVAILABLE",saved.get(0).speechTranscriptStatus);
    }

    @Test public void unavailableASRIsExplicitWhileAssistantInferenceContinues(){
        Queue worker=new Queue(),ui=new Queue();Engine engine=new Engine();
        engine.asrError="decoder unavailable";engine.asrTranscript=null;
        List<ChatSession.CompletedTurn> saved=new ArrayList<>();String[] status={""};
        ChatSession session=new ChatSession(engine,worker,ui,()->1,(endpoint,question)->Collections.emptyList(),
                saved::add,(text,message,running)->status[0]=message);

        assertTrue(session.submit("Summarize this recording","/private/cache/voice.wav",
                "voice recording",false,"",64));
        worker.drain();ui.drain();

        assertEquals(2,engine.calls);
        assertNull(saved.get(0).speechTranscript);
        assertEquals("UNAVAILABLE",saved.get(0).speechTranscriptStatus);
        assertEquals("Answer 1",saved.get(0).assistantText);
        assertTrue(status[0].contains("Response complete"));
    }

    @Test public void combinedVoiceAndImageTurnKeepsImageForFollowUpAndCleansAudio() throws Exception {
        Queue worker=new Queue(),ui=new Queue();Engine engine=new Engine();List<String> deleted=new ArrayList<>();
        java.io.File image=java.io.File.createTempFile("image",".jpg");
        java.io.File voice=java.io.File.createTempFile("question",".wav");
        image.deleteOnExit();voice.deleteOnExit();
        java.nio.file.Files.write(image.toPath(),new byte[]{1,2,3,4});
        java.nio.file.Files.write(voice.toPath(),new byte[]{1,2,3,4});
        ChatSession session=new ChatSession(engine,worker,ui,()->1,(endpoint,question)->Collections.emptyList(),
                turn->{},deleted::add,(text,status,running)->{});
        List<ChatSession.Attachment> attachments=Arrays.asList(
                new ChatSession.Attachment(image.getAbsolutePath(),ChatSession.AttachmentKind.PHOTO),
                new ChatSession.Attachment(voice.getAbsolutePath(),ChatSession.AttachmentKind.VOICE_RECORDING));

        assertTrue(session.submit("What is in the picture?",attachments,false,"",64));
        worker.drain();ui.drain();

        assertEquals(Collections.singletonList(voice.getAbsolutePath()),engine.mediaByCall.get(0));
        assertEquals("Successful ASR must not re-attach the WAV to the assistant turn",
                Collections.singletonList(image.getAbsolutePath()),engine.mediaByCall.get(1));
        assertEquals(1,occurrences(engine.prompts.get(1),"<__media__>"));
        assertEquals(Collections.singletonList(voice.getAbsolutePath()),deleted);

        assertTrue(session.submit("What color was it?",Collections.emptyList(),false,"",64));
        worker.drain();ui.drain();
        assertEquals(Collections.singletonList(image.getAbsolutePath()),engine.mediaByCall.get(2));
        assertEquals(1,occurrences(engine.prompts.get(2),"<__media__>"));
        assertTrue(engine.prompts.get(2).contains("What is in the picture?"));
        assertTrue(engine.prompts.get(2).contains("What color was it?"));
        assertEquals("Follow-up must keep the image but not reuse the spoken audio",1,deleted.size());

        session.clear();
        assertEquals(Arrays.asList(voice.getAbsolutePath(),image.getAbsolutePath()),deleted);
    }

    @Test public void successfulAsrAnswersFromTranscriptWithoutReattachingAudio(){
        Queue worker=new Queue(),ui=new Queue();Engine engine=new Engine();
        List<ChatSession.CompletedTurn> saved=new ArrayList<>();
        ChatSession session=new ChatSession(engine,worker,ui,()->1,(endpoint,question)->Collections.emptyList(),
                saved::add,(text,status,running)->{});
        assertTrue(session.submit("","/private/cache/voice.wav","voice recording",false,"",64));
        worker.drain();ui.drain();

        assertEquals(Collections.singletonList("/private/cache/voice.wav"),engine.mediaByCall.get(0));
        assertTrue(engine.mediaByCall.get(1).isEmpty());
        assertEquals(0,occurrences(engine.prompts.get(1),"<__media__>"));
        assertTrue("Assistant prompt must use the ASR transcript",
                engine.prompts.get(1).contains("The secret animal is a blue cat."));
        assertFalse(engine.prompts.get(1).contains(ChatSession.DEFAULT_VOICE_QUESTION));
        assertEquals("The secret animal is a blue cat.",saved.get(0).speechTranscript);
        assertEquals("Answer 1",saved.get(0).assistantText);
    }

    @Test public void unavailableAsrKeepsAudioAttachedForAssistantTurn(){
        Queue worker=new Queue(),ui=new Queue();Engine engine=new Engine();
        engine.asrError="decoder unavailable";engine.asrTranscript=null;
        ChatSession session=new ChatSession(engine,worker,ui,()->1,(endpoint,question)->Collections.emptyList(),
                turn->{},(text,status,running)->{});
        assertTrue(session.submit("","/private/cache/voice.wav","voice recording",false,"",64));
        worker.drain();ui.drain();

        assertEquals(Collections.singletonList("/private/cache/voice.wav"),engine.mediaByCall.get(0));
        assertEquals(Collections.singletonList("/private/cache/voice.wav"),engine.mediaByCall.get(1));
        assertEquals(1,occurrences(engine.prompts.get(1),"<__media__>"));
        assertTrue(engine.prompts.get(1).contains(ChatSession.DEFAULT_VOICE_QUESTION));
    }

    @Test public void asrPassDiscardsGenerationStatsBeforeAssistantReply(){
        Queue worker=new Queue(),ui=new Queue();
        Engine engine=new Engine();
        engine.nativeStats=new long[]{30L,4L,200L,8L,50L};
        List<ChatSession.CompletedTurn> saved=new ArrayList<>();
        ChatSession session=new ChatSession(engine,worker,ui,()->1,(endpoint,question)->Collections.emptyList(),
                turn->saved.add(turn),path->{},
                (text,status,running)->{});
        assertTrue(session.submit("","/private/cache/voice.wav","voice recording",false,"",64));
        worker.drain();ui.drain();
        assertTrue("ASR must discard native stats before the assistant reply",
                engine.consumeStatsCalls>=1);
    }

    @Test public void assistantTurnCollectsInferenceStatsForUiAndHistory(){
        Harness h=new Harness();
        h.engine.nativeStats=new long[]{50L,8L,400L,1L,120L};
        assertTrue(h.send("Speed?"));
        h.complete();
        InferenceStats stats=h.snapshot.turns.get(1).inferenceStats;
        assertNotNull(stats);
        assertEquals(1,stats.tokensGenerated);
        assertEquals(2.5,stats.decodeTokensPerSec,0.1);
        assertEquals(160.0,stats.prefillTokensPerSec,0.1);
    }

    private static int occurrences(String value,String token){
        return value.split(java.util.regex.Pattern.quote(token),-1).length-1;
    }
}
