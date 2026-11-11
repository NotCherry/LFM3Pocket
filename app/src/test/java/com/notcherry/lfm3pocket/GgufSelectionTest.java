package com.notcherry.lfm3pocket;

import org.junit.Test;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class GgufSelectionTest {
    private File gguf(String architecture, String name, boolean projector) throws Exception {
        File file = Files.createTempFile("selection", ".gguf").toFile();
        Map<String,Object> metadata = new LinkedHashMap<>();
        metadata.put("general.architecture", architecture);
        metadata.put("general.name", name);
        metadata.put(projector ? "clip.vision.projection_dim" : architecture + ".embedding_length", 2048);
        if (projector) metadata.put("clip.vision.projector_type", "lfm3vl");
        try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
            out.writeBytes("GGUF"); u32(out,3); u64(out,1); u64(out, metadata.size());
            for (Map.Entry<String,Object> e : metadata.entrySet()) {
                str(out,e.getKey());
                if (e.getValue() instanceof String) { u32(out,8); str(out,(String)e.getValue()); }
                else { u32(out,4); u32(out,(Integer)e.getValue()); }
            }
            str(out,"test.weight"); u32(out,1); u64(out,1); u32(out,0); u64(out,0);
            while (out.getFilePointer()%32 != 0) out.write(0);
            out.writeInt(0);
        }
        return file;
    }
    static void u32(RandomAccessFile f, int n) throws Exception { f.writeInt(Integer.reverseBytes(n)); }
    static void u64(RandomAccessFile f, long n) throws Exception { f.writeLong(Long.reverseBytes(n)); }
    static void str(RandomAccessFile f, String s) throws Exception {
        byte[] b=s.getBytes(java.nio.charset.StandardCharsets.UTF_8); u64(f,b.length); f.write(b);
    }
    @Test public void matchingMetadataPairAndStandaloneTextAreAccepted() throws Exception {
        File model=gguf("lfm3","LFM3-Omni-2B-Q4_K_M",false);
        File projector=gguf("clip","mmproj-LFM3-Omni-2B-Q4_K_M",true);
        GgufSelection.validate(model,projector);
        GgufSelection.validate(gguf("lfm3","LFM3-1.7B",false),null);
    }
    @Test public void swappedRolesWrongFamilyAndMissingFilesAreRejected() throws Exception {
        File model=gguf("lfm3","LFM3-Omni-2B",false);
        File projector=gguf("clip","mmproj-LFM3-Omni-2B",true);
        reject(projector,model);
        reject(model,gguf("clip","mmproj-LFM3-VL-2B",true));
        reject(model,new File(model.getParentFile(),"missing.gguf"));
        reject(gguf("llama","Unrelated",false),null);
    }
    @Test public void truncatedHeadersAndUnboundedMetadataFailRecoverably() throws Exception {
        File file=gguf("lfm3","LFM3-Omni-2B",false);
        try(RandomAccessFile f=new RandomAccessFile(file,"rw")) { f.setLength(30); }
        reject(file,null);
        try(RandomAccessFile f=new RandomAccessFile(file,"rw")) { f.seek(16); u64(f,Long.MAX_VALUE); }
        reject(file,null);
    }
    @Test public void unprovenPairAndDimensionMismatchAreRejected() throws Exception {
        File model=gguf("lfm3","Unknown model",false);
        reject(model,gguf("clip","Unknown projector",true));
        File projector=gguf("clip","mmproj-LFM3-Omni-2B",true);
        byte[] b=Files.readAllBytes(projector.toPath());
        // Metadata uint32 value 2048 appears once in the fixture.
        for(int i=0;i<b.length-4;i++) if(b[i]==0 && b[i+1]==8 && b[i+2]==0 && b[i+3]==0) {
            b[i+1]=4; break;
        }
        Files.write(projector.toPath(),b);
        reject(gguf("lfm3","LFM3-Omni-2B",false),projector);
    }
    private void reject(File model,File projector) throws Exception {
        try { GgufSelection.validate(model,projector); fail("Invalid selection accepted"); }
        catch(IOException expected) { assertNotNull(expected.getMessage()); }
    }
}
