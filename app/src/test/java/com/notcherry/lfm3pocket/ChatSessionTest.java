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
    static final class Engine implements InferenceEngine {
        int calls,answerCalls;String prompt,asrError,asrTranscript="The secret animal is a blue cat.";boolean stopped;final List<String> prompts=new ArrayList<>();
        final List<List<String>> mediaByCall=new ArrayList<>();
        public long loadModel(String m,String p){return 1;}
        public void unloadModel(long h){}
        public String generate(long h,String p,String media,int max,TokenSink sink){
            return generate(h,p,media==null?new String[0]:new String[]{media},max,sink);
        }
        @Override public String generate(long h,String p,String[] media,int max,TokenSink sink){
            calls++;prompt=p;prompts.add(p);
            mediaByCall.add(Arrays.asList(media.clone()));
            if(!stopped) {
                if(p.contains("Perform ASR.")) { if(asrTranscript!=null)sink.onToken(asrTranscript); }
                else sink.onToken("Answer "+(++answerCalls));
            }
            return p.contains("Perform ASR.")?asrError:null;
        }
        public void stop(){stopped=true;}
        public void resetStop(){stopped=false;}
    }
    static final class Harness {
        final Queue worker=new Queue(),ui=new Queue();final Engine engine=new Engine();
        String text="",status="";boolean generating;
        final ChatSession session=new ChatSession(engine,worker,ui,()->1,
                (endpoint,question)->{throw new AssertionError("Disabled search must not run");},
                (t,s,g)->{text=t;status=s;generating=g;});
        void complete(){worker.drain();ui.drain();}
        boolean send(String text){return session.submit(text,"","",false,"",32);}
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
    @Test public void temporaryMediaIsRemovedAfterInferenceFinishes(){
        Queue worker=new Queue(),ui=new Queue();Engine engine=new Engine();List<String> deleted=new ArrayList<>();
        ChatSession session=new ChatSession(engine,worker,ui,()->1,(endpoint,question)->Collections.emptyList(),
                turn->{},deleted::add,(text,message,running)->{});
        assertTrue(session.submit("Describe this","/private/cache/photo-1.img","photo",false,"",32));
        assertTrue(deleted.isEmpty());
        worker.drain();
        assertEquals(Collections.singletonList("/private/cache/photo-1.img"),deleted);
        assertEquals(1,engine.calls);
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

    @Test public void combinedVoiceAndImageTurnKeepsImageForFollowUpAndCleansAudio(){
        Queue worker=new Queue(),ui=new Queue();Engine engine=new Engine();List<String> deleted=new ArrayList<>();
        ChatSession session=new ChatSession(engine,worker,ui,()->1,(endpoint,question)->Collections.emptyList(),
                turn->{},deleted::add,(text,status,running)->{});
        List<ChatSession.Attachment> attachments=Arrays.asList(
                new ChatSession.Attachment("/private/image.jpg",ChatSession.AttachmentKind.PHOTO),
                new ChatSession.Attachment("/private/question.wav",ChatSession.AttachmentKind.VOICE_RECORDING));

        assertTrue(session.submit("What is in the picture?",attachments,false,"",64));
        worker.drain();ui.drain();

        assertEquals(Collections.singletonList("/private/question.wav"),engine.mediaByCall.get(0));
        assertEquals(Arrays.asList("/private/image.jpg","/private/question.wav"),engine.mediaByCall.get(1));
        assertEquals("Audio and image must each have a media marker in the same user message",2,
                occurrences(engine.prompts.get(1),"<__media__>"));
        assertEquals(Collections.singletonList("/private/question.wav"),deleted);

        assertTrue(session.submit("What color was it?",Collections.emptyList(),false,"",64));
        worker.drain();ui.drain();
        assertEquals(Collections.singletonList("/private/image.jpg"),engine.mediaByCall.get(2));
        assertEquals(1,occurrences(engine.prompts.get(2),"<__media__>"));
        assertTrue(engine.prompts.get(2).contains("What is in the picture?"));
        assertTrue(engine.prompts.get(2).contains("What color was it?"));
        assertEquals("Follow-up must keep the image but not reuse the spoken audio",1,deleted.size());

        session.clear();
        assertEquals(Arrays.asList("/private/question.wav","/private/image.jpg"),deleted);
    }

    private static int occurrences(String value,String token){
        return value.split(java.util.regex.Pattern.quote(token),-1).length-1;
    }
}
