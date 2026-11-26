package com.notcherry.lfm3pocket;

import android.content.Context;
import java.io.File;
import java.io.IOException;

/** Deletes only app-owned, temporary photo and voice attachments; never traverses arbitrary cache data. */
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
        return deleteTemporaryAttachments(context.getCacheDir());
    }

    static boolean deleteTemporaryAttachments(File cache) {
        boolean success=true;
        if (cache == null) return false;
        File[] cached=cache.listFiles();
        if(cached!=null)for(File file:cached) {
            if(isPhoto(file))success=delete(file)&&success;
        }
        success = deleteVoiceDir(new File(cache, "recordings")) && success;
        success = deleteVoiceDir(new File(cache, "voice-actions")) && success;
        success = deleteViewDir(new File(cache, "view")) && success;
        return success;
    }

    private static boolean deleteViewDir(File dir) {
        boolean success = true;
        File[] files = dir.listFiles();
        if (files != null) for (File file : files) {
            if (file.isFile()) success = delete(file) && success;
        }
        if (dir.isDirectory()) {
            String[] remaining = dir.list();
            if (remaining != null && remaining.length == 0) success = delete(dir) && success;
        }
        return success;
    }

    private static boolean deleteVoiceDir(File dir) {
        boolean success = true;
        File[] audio = dir.listFiles();
        if (audio != null) for (File file : audio) {
            if (file.isFile() && (file.getName().startsWith("voice-") || file.getName().endsWith(".partial")
                    || file.getName().endsWith(".wav"))) {
                success = delete(file) && success;
            }
        }
        if (dir.isDirectory()) {
            String[] remaining = dir.list();
            if (remaining != null && remaining.length == 0) success = delete(dir) && success;
        }
        return success;
    }

    private static boolean isManaged(Context context,File file)throws IOException {
        File cache=context.getCacheDir().getCanonicalFile();
        File parent=file.getParentFile();
        if(parent==null)return false;
        parent=parent.getCanonicalFile();
        if(parent.equals(cache))return isPhoto(file);
        return parent.equals(new File(cache,"recordings").getCanonicalFile())
                && file.isFile() && (file.getName().startsWith("voice-") || file.getName().endsWith(".partial"));
    }

    private static boolean isPhoto(File file) {
        if (!file.isFile()) return false;
        String name = file.getName();
        return name.startsWith("photo-") || name.startsWith("camera-")
                || name.startsWith("pdf-page-")
                || name.endsWith("-vision.jpg") || name.endsWith("-vision.jpg.partial");
    }

    private static boolean delete(File file) { return !file.exists() || file.delete(); }
}
