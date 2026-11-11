package com.notcherry.lfm3pocket;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.*;

/** Bounded preflight only. Native loading remains authoritative for tensors and runtime support. */
public final class GgufSelection {
    private static final long HEADER_LIMIT = 64L * 1024 * 1024;
    private static final Pattern FAMILY = Pattern.compile("(?i)lfm3[- _]?(omni|vl|audio)[- _]?(\\d+(?:\\.\\d+)?b)");
    private GgufSelection() {}

    public static void validate(File model, File projector) throws IOException {
        Map<String,Object> text = inspect(model);
        if (!"lfm3".equals(text.get("general.architecture")))
            throw new IOException("Select an LFM3 language model, not a projector or another architecture");
        if (projector == null) return; // Text-only use is explicit, with no modality claims.
        Map<String,Object> media = inspect(projector);
        if (!"clip".equals(media.get("general.architecture")))
            throw new IOException("Select a multimodal projector, not a language model");
        String identity = identity(text);
        if (identity.isEmpty() || !identity.equals(identity(media)))
            throw new IOException("Cannot verify matching model/projector family and size from GGUF metadata");
        boolean supported = "lfm3vl".equals(media.get("clip.vision.projector_type"))
                || "lfm3a".equals(media.get("clip.audio.projector_type"));
        if (!supported) throw new IOException("Projector is not an LFM3 input projector");
        Object embedding = text.get("lfm3.embedding_length");
        if (!(embedding instanceof Number) || ((Number)embedding).longValue() <= 0)
            throw new IOException("Missing model embedding dimension");
        for (String key : new String[]{"clip.vision.projection_dim", "clip.audio.projection_dim"}) {
            Object dimension = media.get(key);
            if (dimension instanceof Number && ((Number)dimension).longValue() != ((Number)embedding).longValue())
                throw new IOException("Projector output dimension does not match the language model");
        }
    }

    private static String identity(Map<String,Object> metadata) {
        String candidate = metadata.getOrDefault("general.basename", "") + "-"
                + metadata.getOrDefault("general.size_label", "") + " "
                + metadata.getOrDefault("general.name", "") + " "
                + metadata.getOrDefault("general.base_model.0.name", "");
        Matcher match = FAMILY.matcher(candidate);
        return match.find() ? (match.group(1) + match.group(2)).toLowerCase(Locale.ROOT) : "";
    }

    static Map<String,Object> inspect(File file) throws IOException {
        if (!file.isFile()) throw new IOException("Selected GGUF is missing or unreadable");
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (input.readInt() != 0x47475546) throw new IOException("Not a GGUF file");
            long version = u32(input);
            if (version != 2 && version != 3) throw new IOException("Unsupported GGUF version");
            long tensors = bounded(u64(input),100000,"tensor count");
            long entries = bounded(u64(input),100000,"metadata count");
            if (tensors == 0) throw new IOException("GGUF contains no model tensors");
            Map<String,Object> metadata = new HashMap<>();
            Set<String> keys = new HashSet<>();
            for (long index=0;index<entries;index++) {
                String key = string(input,1024);
                if (!keys.add(key)) throw new IOException("Duplicate GGUF metadata key");
                int type = (int)u32(input);
                boolean keep = key.equals("general.architecture") || key.equals("general.name")
                        || key.equals("general.basename") || key.equals("general.size_label")
                        || key.equals("general.base_model.0.name") || key.equals("general.alignment")
                        || key.equals("lfm3.embedding_length") || key.startsWith("clip.");
                Object value = value(input,type,keep);
                if (keep) metadata.put(key,value);
                headerBound(input);
            }
            long maxEnd = 0;
            long alignment = metadata.get("general.alignment") instanceof Number
                    ? ((Number)metadata.get("general.alignment")).longValue() : 32;
            if (alignment <= 0 || alignment > 4096 || (alignment & (alignment-1)) != 0)
                throw new IOException("Invalid GGUF tensor alignment");
            for (long index=0;index<tensors;index++) {
                string(input,1024);
                long dimensions = bounded(u32(input),4,"tensor dimensions");
                if (dimensions == 0) throw new IOException("Empty tensor shape");
                long elements = 1;
                for(long d=0;d<dimensions;d++) {
                    long n=u64(input);
                    if(n <= 0 || elements > Long.MAX_VALUE/n) throw new IOException("Invalid tensor shape");
                    elements *= n;
                }
                long type=u32(input);
                long offset=u64(input);
                if(offset < 0 || offset % alignment != 0) throw new IOException("Invalid tensor offset");
                // Check complete extents for common LFM3 quantizations; native validates all remaining types.
                int block=1, bytes;
                switch((int)type) {
                    case 0: bytes=4; break;
                    case 1: case 30: bytes=2; break;
                    case 2: block=32;bytes=18;break;
                    case 3: block=32;bytes=20;break;
                    case 6: block=32;bytes=22;break;
                    case 7: block=32;bytes=24;break;
                    case 8: block=32;bytes=34;break;
                    case 10: block=256;bytes=84;break;
                    case 11: block=256;bytes=110;break;
                    case 12: block=256;bytes=144;break;
                    case 13: block=256;bytes=176;break;
                    case 14: block=256;bytes=210;break;
                    default: bytes=1; elements=1; break;
                }
                if(elements % block != 0 || elements/block > (Long.MAX_VALUE-offset)/bytes)
                    throw new IOException("Invalid tensor extent");
                maxEnd=Math.max(maxEnd,offset+elements/block*bytes);
                headerBound(input);
            }
            long dataStart=(input.getFilePointer()+alignment-1)/alignment*alignment;
            if(dataStart > input.length() || maxEnd > input.length()-dataStart)
                throw new IOException("GGUF tensor data is truncated");
            return metadata;
        } catch(EOFException e) { throw new IOException("GGUF header is truncated",e); }
    }
    private static Object value(RandomAccessFile f,int type,boolean keep) throws IOException {
        switch(type) {
            case 0: return f.readUnsignedByte();
            case 1: return f.readByte();
            case 2: return Short.toUnsignedInt(Short.reverseBytes(f.readShort()));
            case 3: return Short.reverseBytes(f.readShort());
            case 4: return u32(f);
            case 5: return (int)u32(f);
            case 6: return Float.intBitsToFloat((int)u32(f));
            case 7:
                int b=f.readUnsignedByte(); if(b>1) throw new IOException("Invalid GGUF boolean"); return b==1;
            case 8:
                if(keep) return string(f,4096);
                skip(f,bounded(u64(f),HEADER_LIMIT,"string length")); return null;
            case 9:
                int elementType=(int)u32(f);
                if(elementType<0 || elementType>12 || elementType==9) throw new IOException("Invalid GGUF array type");
                long count=bounded(u64(f),1000000,"array length");
                for(long i=0;i<count;i++) { value(f,elementType,false); headerBound(f); }
                return null;
            case 10: case 11: return u64(f);
            case 12: return Double.longBitsToDouble(u64(f));
            default: throw new IOException("Unknown GGUF metadata type");
        }
    }
    private static String string(RandomAccessFile f,long limit) throws IOException {
        int n=(int)bounded(u64(f),limit,"string length");
        byte[] bytes=new byte[n]; f.readFully(bytes);
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString(); }
        catch(CharacterCodingException e) { throw new IOException("Invalid UTF-8 metadata",e); }
    }
    private static void skip(RandomAccessFile f,long n) throws IOException {
        if(n>f.length()-f.getFilePointer()) throw new EOFException();
        f.seek(f.getFilePointer()+n);
    }
    private static long bounded(long n,long limit,String what) throws IOException {
        if(n<0 || n>limit) throw new IOException("Invalid or excessive GGUF "+what); return n;
    }
    private static void headerBound(RandomAccessFile f) throws IOException {
        if(f.getFilePointer()>HEADER_LIMIT) throw new IOException("GGUF metadata exceeds safety limit");
    }
    private static long u32(RandomAccessFile f) throws IOException { return Integer.toUnsignedLong(Integer.reverseBytes(f.readInt())); }
    private static long u64(RandomAccessFile f) throws IOException { return Long.reverseBytes(f.readLong()); }
}
