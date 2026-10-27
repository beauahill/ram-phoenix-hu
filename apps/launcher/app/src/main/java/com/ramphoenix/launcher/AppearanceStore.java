package com.ramphoenix.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.util.AtomicFile;
import com.ramphoenix.appearance.WallpaperScene;
import java.io.*;

/** Import into bounded private storage: a wallpaper remains usable after USB removal. */
final class AppearanceStore {
    private static final java.util.concurrent.atomic.AtomicInteger requests=new java.util.concurrent.atomic.AtomicInteger();
    static final long MAX_BYTES=32L*1024*1024;
    static File image(Context c) {return new File(c.getFilesDir(),"wallpaper.webp");}
    static int dim(Context c) {return Math.max(20,Math.min(80,c.getSharedPreferences("appearance",0).getInt("dim",38)));}
    static int softness(Context c) {return Math.max(0,Math.min(20,c.getSharedPreferences("appearance",0).getInt("softness",7)));}
    static void level(Context c,String key,int value) {
        if(!key.equals("dim") && !key.equals("softness"))throw new IllegalArgumentException("Appearance setting");
        int safe=key.equals("dim")?Math.max(20,Math.min(80,value)):Math.max(0,Math.min(20,value));
        c.getSharedPreferences("appearance",0).edit().putInt(key,safe).apply();
        changed(c);
    }
    static int beginImport() {return requests.incrementAndGet();}
    static void importImage(Context c,Uri uri,int request) throws IOException {
        File source=File.createTempFile("wallpaper-",".source",c.getCacheDir());Bitmap bitmap=null;
        try {
            try(InputStream input=c.getContentResolver().openInputStream(uri);OutputStream output=new FileOutputStream(source)) {
                if(input==null)throw new IOException("Image could not be opened");
                byte[] buffer=new byte[32768];long total=0;
                for(int count;(count=input.read(buffer))!=-1;) {
                    total+=count;if(total>MAX_BYTES)throw new IOException("Choose an image smaller than 32 MB");
                    output.write(buffer,0,count);
                }
            }
            bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(source),(decoder,info,src) -> {
                int w=info.getSize().getWidth(),h=info.getSize().getHeight();
                if(w<=0 || h<=0 || (long)w*h>100_000_000L)throw new IllegalArgumentException("Image dimensions are too large");
                double scale=Math.min(1,2048.0/Math.max(w,h));
                decoder.setTargetSize(Math.max(1,(int)(w*scale)),Math.max(1,(int)(h*scale)));
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
            synchronized(AppearanceStore.class) {
                if(request!=requests.get())throw new IOException("Import cancelled");
                AtomicFile destination=new AtomicFile(image(c));FileOutputStream out=destination.startWrite();
                try {
                    if(!bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY,92,out))throw new IOException("Image could not be saved");
                    destination.finishWrite(out);
                } catch(IOException|RuntimeException|OutOfMemoryError failure) {destination.failWrite(out);throw failure;}
            }
            changed(c);
        } finally {source.delete();if(bitmap!=null)bitmap.recycle();}
    }
    static synchronized void clear(Context c) {requests.incrementAndGet();new AtomicFile(image(c)).delete();changed(c);}
    private static void changed(Context c) {c.getContentResolver().notifyChange(WallpaperScene.ROOT,null);}
}
