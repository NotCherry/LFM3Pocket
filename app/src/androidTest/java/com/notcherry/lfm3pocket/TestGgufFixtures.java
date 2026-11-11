package com.notcherry.lfm3pocket;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Structurally valid metadata fixtures for preflight tests. Never loaded by native inference. */
final class TestGgufFixtures {
    static void model(File file) throws IOException { write(file,false,"LFM3-Omni-2B"); }
    static void write(File file,boolean projector,String name) throws IOException {
        try(RandomAccessFile out=new RandomAccessFile(file,"rw")) {
            out.setLength(0); out.writeBytes("GGUF"); i(out,3); l(out,1); l(out,projector?4:3);
            text(out,"general.architecture",projector?"clip":"lfm3");
            text(out,"general.name",name);
            string(out,projector?"clip.vision.projection_dim":"lfm3.embedding_length"); i(out,4); i(out,2048);
            if(projector) text(out,"clip.vision.projector_type","lfm3vl");
            string(out,"test.weight"); i(out,1); l(out,1); i(out,0); l(out,0);
            while(out.getFilePointer()%32!=0) out.write(0);
            out.writeInt(0); out.setLength(1024*1024);
        }
    }
    private static void text(RandomAccessFile out,String k,String v) throws IOException { string(out,k);i(out,8);string(out,v); }
    private static void string(RandomAccessFile out,String s) throws IOException { byte[] b=s.getBytes(StandardCharsets.UTF_8); l(out,b.length);out.write(b); }
    private static void i(RandomAccessFile out,int n) throws IOException { out.writeInt(Integer.reverseBytes(n)); }
    private static void l(RandomAccessFile out,long n) throws IOException { out.writeLong(Long.reverseBytes(n)); }
}
