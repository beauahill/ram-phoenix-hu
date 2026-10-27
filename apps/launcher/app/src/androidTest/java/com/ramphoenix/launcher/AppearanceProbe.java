package com.ramphoenix.launcher;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import com.ramphoenix.appearance.WallpaperScene;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;

/** Real Android decode/atomic-save checks; skips an existing personal wallpaper. */
final class AppearanceProbe {
    private final Instrumentation runner;
    AppearanceProbe(Instrumentation runner) {this.runner=runner;}
    void run() {
        Context context=runner.getTargetContext();Bundle result=new Bundle();int code=0;
        boolean touched=false;File bad=null,large=null;
        try {
            if(AppearanceStore.image(context).exists())throw new IllegalStateException("Personal wallpaper exists; preserve it and skip fixture import");
            File fixture=File.createTempFile("appearance-qa-",".png",context.getCacheDir());
            Bitmap image=Bitmap.createBitmap(1200,2400,Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(image);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setShader(new LinearGradient(0,0,1200,2400,new int[]{0xffe6d5b7,0xff8ebbc7,0xff243c60},null,Shader.TileMode.CLAMP));
            canvas.drawRect(0,0,1200,2400,paint);paint.setShader(null);paint.setColor(0x30ffffff);paint.setStrokeWidth(100);
            canvas.drawLine(-150,1900,1350,450,paint);
            try(OutputStream out=new FileOutputStream(fixture)) {if(!image.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("Fixture");}
            image.recycle();touched=true;
            AppearanceStore.importImage(context,Uri.fromFile(fixture),AppearanceStore.beginImport());
            BitmapFactory.Options size=new BitmapFactory.Options();size.inJustDecodeBounds=true;
            BitmapFactory.decodeFile(AppearanceStore.image(context).getPath(),size);
            if(size.outWidth<=0 || size.outHeight<=0 || Math.max(size.outWidth,size.outHeight)>2048)
                throw new AssertionError("Wallpaper was not bounded");
            byte[] original=hash(AppearanceStore.image(context));
            bad=File.createTempFile("appearance-invalid-",".bin",context.getCacheDir());
            Files.write(bad.toPath(),new byte[]{1,2,3,4});
            reject(context,bad);
            if(!Arrays.equals(original,hash(AppearanceStore.image(context))))throw new AssertionError("Invalid image replaced the wallpaper");
            large=File.createTempFile("appearance-oversize-",".bin",context.getCacheDir());
            try(RandomAccessFile file=new RandomAccessFile(large,"rw")) {file.setLength(AppearanceStore.MAX_BYTES+1);}
            reject(context,large);
            if(!Arrays.equals(original,hash(AppearanceStore.image(context))))throw new AssertionError("Oversize import replaced the wallpaper");
            try(ParcelFileDescriptor ignored=context.getContentResolver().openFileDescriptor(WallpaperScene.IMAGE,"rw")) {
                throw new AssertionError("Wallpaper provider allowed a write");
            } catch(FileNotFoundException expected) {}
            int cancelled=AppearanceStore.beginImport();AppearanceStore.clear(context);
            try {AppearanceStore.importImage(context,Uri.fromFile(fixture),cancelled);throw new AssertionError("Cancelled import restored an old selection");}
            catch(IOException expected) {}
            if(AppearanceStore.image(context).exists())throw new AssertionError("Default wallpaper was not restored");
            result.putString("fixture",fixture.getName());
            result.putString("result","Passed: bounded decode, invalid/oversize imports preserve prior image, read-only provider, cancelled import cannot undo reset");
            code=-1;
        } catch(Throwable failure) {result.putString("failure",failure.toString());}
        finally {
            if(touched)AppearanceStore.clear(context);
            if(bad!=null)bad.delete();if(large!=null)large.delete();
        }
        runner.finish(code,result);
    }
    private static byte[] hash(File file) throws Exception {return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()));}
    private static void reject(Context context,File file) throws Exception {
        try {AppearanceStore.importImage(context,Uri.fromFile(file),AppearanceStore.beginImport());}
        catch(IOException|IllegalArgumentException expected) {return;}
        throw new AssertionError("Invalid/oversize image accepted");
    }
}
