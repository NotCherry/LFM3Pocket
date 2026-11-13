package com.notcherry.lfm3pocket;

import android.content.Context;
import java.io.File;
import java.io.IOException;

/** Deletes only app-owned, temporary photo, voice, and spoken attachments; never traverses arbitrary cache data. */
public final class HistoryMediaCleanup {
    private HistoryMediaCleanup() {}

    public static boolean deleteAttachment(Context context,String path) {
        if(path==null || path.isBlank())return true;
        try {
            File file=new File(path).getCanonicalFile();
            if(!isManaged(context,file))return false;
            return !file.exists() || file.delete();
        } catch(IOException e) { return false; }
    }

    /** Removes transient media left after inference, cancellation, or process death. */
    public static boolean deleteTemporaryAttachments(Context context) {
        boolean success=true;
        File cache=context.getCacheDir();
        File[] cached=cache.listFiles();
        if(cached!=null)for(File file:cached) {
            if(isPhoto(file) || isSpoken(file))success=delete(file)&&success;
        }
        File recordings=new File(cache,"recordings");
        File[] audio=recordings.listFiles();
        if(audio!=null)for(File file:audio) {
            if(file.isFile() && (file.getName().startsWith("voice-") || file.getName().endsWith(".partial")))
                success=delete(file)&&success;
        }
        if(recordings.isDirectory() && recordings.list()!=null && recordings.list().length==0)
            success=delete(recordings)&&success;
        File speech=new File(cache,"speech");
        File[] spoken=speech.listFiles();
        if(spoken!=null)for(File file:spoken) {
            if(isSpoken(file) || (file.isFile() && file.getName().endsWith(".partial")))
                success=delete(file)&&success;
        }
        if(speech.isDirectory() && speech.list()!=null && speech.list().length==0)
            success=delete(speech)&&success;
        return success;
    }

    private static boolean isManaged(Context context,File file)throws IOException {
        File cache=context.getCacheDir().getCanonicalFile();
        File parent=file.getParentFile();
        if(parent==null)return false;
        parent=parent.getCanonicalFile();
        if(parent.equals(cache))return isPhoto(file) || isSpoken(file);
        if(parent.equals(new File(cache,"speech").getCanonicalFile()))
            return isSpoken(file) || (file.isFile() && file.getName().endsWith(".partial"));
        return parent.equals(new File(cache,"recordings").getCanonicalFile())
                && file.isFile() && (file.getName().startsWith("voice-") || file.getName().endsWith(".partial"));
    }

    private static boolean isPhoto(File file) {
        return file.isFile() && (file.getName().startsWith("photo-") || file.getName().startsWith("camera-"));
    }

    private static boolean isSpoken(File file) {
        return file.isFile() && file.getName().startsWith("spoken-");
    }

    private static boolean delete(File file) { return !file.exists() || file.delete(); }
}
